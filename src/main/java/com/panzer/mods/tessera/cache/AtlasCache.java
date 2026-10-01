package com.panzer.mods.tessera.cache;

import com.panzer.mods.tessera.util.TesseraHasher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

public final class AtlasCache {

    private static final byte[] MAGIC = {'T', 'S', 'R', '2'};
    private static final int HEADER_BYTES = MAGIC.length + Integer.BYTES * 5;
    private final Path cacheDirectory;

    public AtlasCache(Path cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    public String hashHex(ByteBuffer rgba8Direct, int length) {
        ByteBuffer slice = rgba8Direct.duplicate();
        slice.limit(slice.position() + length);
        return TesseraHasher.hashContent(slice);
    }

    /**
     * Reads a cached compressed atlas, scoped to a specific {@link CompressedFormat}.
     *
     * <p>The cache key is content-hash-based (see {@link #hashHex}), which only
     * covers pixel data -- it says nothing about which GL format the caller
     * wants that content encoded to. The same RGBA8 pixels can legitimately
     * be requested as BC1 for one atlas bucket and BC7 for another (e.g. a
     * sprite whose opaque/alpha classification differs between mod-added
     * variants sharing identical pixels), so {@code format} is threaded
     * through both the read and write paths and is checked against what's
     * on disk before a hit is returned -- a format mismatch is treated as a
     * cache miss, never as a silently-wrong-format hit.
     */
    public Optional<CachedAtlas> read(String hashHex, CompressedFormat format) throws IOException {
        Path file = fileFor(hashHex, format);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.size() < HEADER_BYTES) {
                return Optional.empty();
            }

            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            while (header.hasRemaining()) {
                if (channel.read(header) < 0) {
                    // Truncated file, shorter than its own declared header
                    // size -- treat as a miss rather than reading a
                    // partially zero-filled header.
                    return Optional.empty();
                }
            }
            header.flip();

            byte[] magic = new byte[MAGIC.length];
            header.get(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                return Optional.empty();
            }

            int formatOrdinal = header.getInt();
            if (formatOrdinal != format.ordinal()) {
                // Same content hash, different requested format (or a stale
                // cache file from before format-tagging existed) -- treat as a
                // miss rather than returning bytes in the wrong GL format.
                return Optional.empty();
            }

            int width = header.getInt();
            int height = header.getInt();
            int qualityPreset = header.getInt();
            int payloadLength = header.getInt();

            // Corrupt header guard: negative/oversized lengths would otherwise throw
            // IllegalArgumentException from allocateDirect, or allocate garbage sizes.
            if (payloadLength <= 0 || width <= 0 || height <= 0
                    || channel.size() < (long) HEADER_BYTES + payloadLength) {
                return Optional.empty();
            }

            ByteBuffer payload = ByteBuffer.allocateDirect(payloadLength).order(ByteOrder.LITTLE_ENDIAN);
            while (payload.hasRemaining()) {
                if (channel.read(payload) < 0) {
                    // Truncated mid-read (e.g. concurrent external
                    // modification of the cache file) -- treat as a miss
                    // rather than spinning forever or returning a
                    // partially-filled buffer.
                    return Optional.empty();
                }
            }
            payload.flip();

            touch(file); // refresh LRU position for prune()
            return Optional.of(new CachedAtlas(width, height, qualityPreset, payload));
        }
    }

    // False Positive
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void write(String hashHex, CompressedFormat format, int width, int height, int qualityPreset, ByteBuffer compressedBlocks) throws IOException {
        Files.createDirectories(cacheDirectory);

        int payloadLength = compressedBlocks.remaining();
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.put(MAGIC);
        header.putInt(format.ordinal());
        header.putInt(width);
        header.putInt(height);
        header.putInt(qualityPreset);
        header.putInt(payloadLength);
        header.flip();

        // Gathering write: header + payload straight from the (direct) block buffer,
        // instead of first copying the whole payload into a heap staging buffer.
        ByteBuffer[] parts = {header, compressedBlocks.duplicate()};
        long total = (long) HEADER_BYTES + payloadLength;

        Path tempFile = Files.createTempFile(cacheDirectory, hashHex, ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(tempFile,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                long written = 0;
                while (written < total) {
                    written += channel.write(parts);
                }
            }
            Files.move(tempFile, fileFor(hashHex, format), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }

    /** Default on-disk cap; override with {@code -Dtessera.cache.maxMB=N}. */
    private static final long DEFAULT_MAX_BYTES = 512L * 1024 * 1024;
    private static final long STALE_TMP_MILLIS = 60L * 60 * 1000;

    public static long configuredMaxBytes() {
        return Long.getLong("tessera.cache.maxMB", DEFAULT_MAX_BYTES / (1024 * 1024)) * 1024 * 1024;
    }

    /**
     * Least-recently-used eviction down to {@code maxBytes} (hits refresh mtime in
     * {@link #touch}), plus removal of orphaned {@code .tmp} files left by crashed
     * writes. Best-effort: I/O errors on individual files are ignored.
     */
    public void prune(long maxBytes) {
        if (!Files.isDirectory(cacheDirectory)) {
            return;
        }
        record Entry(Path path, long size, long mtime) {
        }
        java.util.List<Entry> entries = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        long total = 0L;
        try (java.util.stream.Stream<Path> files = Files.list(cacheDirectory)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                try {
                    long mtime = Files.getLastModifiedTime(p).toMillis();
                    if (name.endsWith(".tmp")) {
                        if (now - mtime > STALE_TMP_MILLIS) {
                            Files.deleteIfExists(p);
                        }
                        continue;
                    }
                    if (!name.endsWith(CompressedFormat.BC1.extension) && !name.endsWith(CompressedFormat.BC7.extension)) {
                        continue;
                    }
                    long size = Files.size(p);
                    entries.add(new Entry(p, size, mtime));
                    total += size;
                } catch (IOException ignored) {
                    // Concurrently removed or unreadable: skip.
                }
            }
        } catch (IOException ignored) {
            return;
        }
        if (total <= maxBytes) {
            return;
        }
        entries.sort(java.util.Comparator.comparingLong(Entry::mtime));
        for (Entry e : entries) {
            if (total <= maxBytes) {
                break;
            }
            try {
                Files.deleteIfExists(e.path());
                total -= e.size();
            } catch (IOException ignored) {
                // Locked on Windows, etc.: try the next one.
            }
        }
    }

    private static void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // LRU ordering is best-effort.
        }
    }

    private Path fileFor(String hashHex, CompressedFormat format) {
        return cacheDirectory.resolve(hashHex + format.fileExtension());
    }

    /**
     * Which GPU block-compression format a cached blob holds. Determines
     * both the on-disk file extension (so BC1/BC7 caches for the same
     * content hash never collide on the filesystem either) and the
     * in-header discriminator checked by {@link #read}.
     */
    public enum CompressedFormat {
        BC1(".bc1"),
        BC7(".bc7");

        private final String extension;

        CompressedFormat(String extension) {
            this.extension = extension;
        }

        public String fileExtension() {
            return extension;
        }
    }

    public record CachedAtlas(int width, int height, int qualityPreset, ByteBuffer compressedBlocks) {
    }
}
