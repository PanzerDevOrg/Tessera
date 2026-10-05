package com.panzer.mods.tessera.selftest;

import com.panzer.mods.tessera.api.AtlasCompressEvent;
import com.panzer.mods.tessera.cache.AtlasCache;
import com.panzer.mods.tessera.compat.TesseraCompat;
import com.panzer.mods.tessera.compress.Bc7GpuSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client self-test ({@code -Dtessera.selftest=true}, Gradle task {@code selftest}):
 * once the first resource reload has been compressed, reads every compressed
 * atlas back through OpenGL and checks it against the sprites it was built from,
 * then quits. Runs headless in CI (Xvfb + Mesa).
 *
 * <ul>
 *   <li>every mip level of the texture holds the compressed format (a level left
 *       uncompressed makes the texture incomplete: black in game);</li>
 *   <li>level 0, decoded by the driver, matches the static sprites (PSNR; small
 *       icon atlases such as mob_effects land near 29-31 dB at the default quality,
 *       a broken or misplaced upload well under 20);</li>
 *   <li>animations keep running: after ticking the atlas, animated sprites show
 *       one of their frames, and some changed.</li>
 * </ul>
 * Writes {@code tessera-selftest.txt} in the game directory; its first line ends
 * with PASS or FAIL.
 */
public final class TesseraSelfTest {

    public static final boolean ENABLED = Boolean.getBoolean("tessera.selftest");

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/SelfTest");
    private static final int SETTLE_TICKS = 200, GIVE_UP_TICKS = 6000, ANIMATION_TICKS = 60;
    private static final double MIN_STATIC_PSNR = 28.0, MIN_FRAME_PSNR = 20.0;
    private static final int GL_COMPRESSED_RGBA_BPTC_UNORM = 0x8E8C;
    private static final int GL_COMPRESSED_RGB_S3TC_DXT1 = 0x83F0, GL_COMPRESSED_RGBA_S3TC_DXT1 = 0x83F1;

    private record Compressed(TextureAtlas atlas, AtlasCache.CompressedFormat format) {
    }

    private static final Map<String, Compressed> COMPRESSED = new LinkedHashMap<>();
    private static int ticks, lastCompressedTick = -1, animatedChanged;
    private static boolean done;

    private TesseraSelfTest() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(AtlasCompressEvent.Post.class, e -> {
            var location = e.getAtlasLocation();
            if (Minecraft.getInstance().getTextureManager().getTexture(location) instanceof TextureAtlas atlas) {
                COMPRESSED.put(location.toString(), new Compressed(atlas, e.getAppliedFormat()));
                lastCompressedTick = ticks;
            }
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> tick());
    }

    private static void tick() {
        if (done) {
            return;
        }
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        // The block atlas is the largest and usually the last one compressed.
        boolean blocksDone = COMPRESSED.keySet().stream().anyMatch(name -> name.endsWith("textures/atlas/blocks.png"));
        boolean settled = !overlayShown(mc) && blocksDone && ticks - lastCompressedTick >= SETTLE_TICKS;
        if (!settled && ticks < GIVE_UP_TICKS) {
            return;
        }
        done = true;
        List<String> lines = new ArrayList<>();
        boolean pass;
        try {
            pass = check(lines);
        } catch (Throwable t) {
            LOGGER.error("Tessera self-test crashed", t);
            lines.add("crashed: " + t);
            pass = false;
        }
        lines.add(0, "tessera selftest " + System.getProperty("tessera.selftest.version", "?") + ": " + (pass ? "PASS" : "FAIL"));
        lines.forEach(LOGGER::info);
        try {
            Files.write(mc.gameDirectory.toPath().resolve("tessera-selftest.txt"), lines);
        } catch (IOException e) {
            LOGGER.error("Tessera self-test: cannot write the report", e);
        }
        mc.stop();
    }

    private static boolean check(List<String> out) {
        if (!Bc7GpuSupport.isSupported()) {
            out.add("the GL driver has no BC7 (" + GL11.glGetString(GL11.GL_RENDERER) + ")");
            return false;
        }
        out.add("renderer " + GL11.glGetString(GL11.GL_RENDERER) + ", " + GL11.glGetString(GL11.GL_VERSION));
        if (COMPRESSED.isEmpty()) {
            out.add("no atlas was compressed within " + GIVE_UP_TICKS + " ticks");
            return false;
        }
        boolean ok = COMPRESSED.keySet().stream().anyMatch(name -> name.endsWith("textures/atlas/blocks.png"));
        if (!ok) {
            out.add("the block atlas was not compressed");
        }
        boolean anyAnimated = false;
        for (var entry : COMPRESSED.entrySet()) {
            ok &= checkAtlas(entry.getKey(), entry.getValue(), out);
            anyAnimated |= entry.getValue().atlas().getTextures().values().stream()
                    .anyMatch(sprite -> TesseraCompat.isAnimated(sprite.contents()));
        }
        // Some animations are slow (a GUI icon may hold a frame for seconds), but
        // across every compressed atlas some must have moved on.
        if (anyAnimated && animatedChanged == 0) {
            out.add("no animated sprite changed in any compressed atlas");
            ok = false;
        }
        return ok;
    }

    private static boolean checkAtlas(String name, Compressed c, List<String> out) {
        TextureAtlas atlas = c.atlas();
        int glId = TesseraCompat.glId(atlas);
        TesseraCompat.bindTexture(glId);
        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        int maxLevel = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL);
        boolean ok = true;

        // Every level the sampler may reach must exist, compressed, at the right size.
        int levels = 0;
        for (int level = 0; level <= Math.min(maxLevel, 16); level++) {
            int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT);
            if (w == 0 && h == 0 && level > 0 && (width >> level) == 0 && (height >> level) == 0) {
                break;
            }
            int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            boolean expected = c.format() == AtlasCache.CompressedFormat.BC1
                    ? format == GL_COMPRESSED_RGB_S3TC_DXT1 || format == GL_COMPRESSED_RGBA_S3TC_DXT1
                    : format == GL_COMPRESSED_RGBA_BPTC_UNORM;
            if (!expected || w != Math.max(1, width >> level) || h != Math.max(1, height >> level)) {
                out.add(String.format("  %s level %d: %dx%d format 0x%X (expected %s %dx%d)", name, level, w, h, format,
                        c.format(), Math.max(1, width >> level), Math.max(1, height >> level)));
                ok = false;
            }
            levels++;
        }

        ByteBuffer pixels = readLevel0(width, height);
        try {
            // Static sprites: the decoded atlas against the sprites' own pixels.
            List<TextureAtlasSprite> animated = new ArrayList<>();
            Error staticError = new Error();
            for (TextureAtlasSprite sprite : atlas.getTextures().values()) {
                if (TesseraCompat.isAnimated(sprite.contents())) {
                    animated.add(sprite);
                } else {
                    compare(pixels, width, sprite, 0, staticError);
                }
            }
            double staticPsnr = staticError.psnr();
            boolean staticOk = staticPsnr >= MIN_STATIC_PSNR;
            out.add(String.format("%s %s %dx%d, %d levels (max %d): static sprites PSNR %.1f dB over %d texels%s",
                    name, c.format(), width, height, levels, maxLevel, staticPsnr, staticError.count,
                    staticOk ? "" : " (below " + MIN_STATIC_PSNR + ")"));
            ok &= staticOk;

            if (!animated.isEmpty()) {
                ok &= checkAnimations(name, atlas, glId, width, height, pixels, animated, out);
            }
        } finally {
            MemoryUtil.memFree(pixels);
        }
        return ok;
    }

    /** Ticks the atlas (as the client does every tick) and checks the animated sprites. */
    private static boolean checkAnimations(String name, TextureAtlas atlas, int glId, int width, int height,
                                           ByteBuffer before, List<TextureAtlasSprite> animated, List<String> out) {
        for (int i = 0; i < ANIMATION_TICKS; i++) {
            atlas.tick();
        }
        TesseraCompat.bindTexture(glId);
        ByteBuffer after = readLevel0(width, height);
        try {
            int changed = 0, matching = 0;
            List<String> mismatched = new ArrayList<>();
            for (TextureAtlasSprite sprite : animated) {
                if (differs(before, after, width, sprite)) {
                    changed++;
                }
                double best = 0.0;
                for (int frame : TesseraCompat.uniqueFrames(sprite.contents())) {
                    Error e = new Error();
                    compare(after, width, sprite, frame, e);
                    best = Math.max(best, e.psnr());
                }
                if (best >= MIN_FRAME_PSNR) {
                    matching++;
                } else {
                    mismatched.add(String.format("%s %.1f dB (%dx%d at %d,%d, %d frames)", sprite.contents().name(), best,
                            sprite.contents().width(), sprite.contents().height(), sprite.getX(), sprite.getY(),
                            TesseraCompat.uniqueFrames(sprite.contents()).length));
                }
            }
            animatedChanged += changed;
            boolean ok = matching == animated.size();
            out.add(String.format("%s: %d animated sprites, %d changed after %d ticks, %d show one of their frames%s",
                    name, animated.size(), changed, ANIMATION_TICKS, matching, ok ? "" : " FAIL:"));
            mismatched.forEach(m -> out.add("    " + m));
            return ok;
        } finally {
            MemoryUtil.memFree(after);
        }
    }

    /** Level 0 of the bound texture as RGBA8, decoded by the driver. */
    private static ByteBuffer readLevel0(int width, int height) {
        ByteBuffer buffer = MemoryUtil.memAlloc(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
        return buffer;
    }

    /** Squared error of a sprite's region (without padding) against one of its frames; transparent texels skipped. */
    private static void compare(ByteBuffer atlas, int atlasWidth, TextureAtlasSprite sprite, int frame, Error error) {
        int padding = TesseraCompat.padding(sprite);
        int x0 = sprite.getX() + padding, y0 = sprite.getY() + padding;
        for (int y = 0; y < sprite.contents().height(); y++) {
            for (int x = 0; x < sprite.contents().width(); x++) {
                int expected = TesseraCompat.spriteAbgr(sprite, frame, x, y);
                if ((expected >>> 24) == 0) {
                    continue;
                }
                int actual = atlas.getInt(((y0 + y) * atlasWidth + x0 + x) * 4);
                for (int shift = 0; shift < 32; shift += 8) {
                    int d = ((expected >>> shift) & 0xFF) - ((actual >>> shift) & 0xFF);
                    error.sum += (long) d * d;
                }
                error.count++;
            }
        }
    }

    private static boolean differs(ByteBuffer a, ByteBuffer b, int atlasWidth, TextureAtlasSprite sprite) {
        int padding = TesseraCompat.padding(sprite);
        for (int y = 0; y < sprite.contents().height(); y++) {
            int row = ((sprite.getY() + padding + y) * atlasWidth + sprite.getX() + padding) * 4;
            for (int x = 0; x < sprite.contents().width(); x++) {
                if (a.getInt(row + x * 4) != b.getInt(row + x * 4)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether an overlay (the resource-loading screen) is up: {@code Minecraft.getOverlay()}
     * up to 26.1, {@code Minecraft.gui.overlay()} from 26.2. Looked up by name so one
     * jar checks both.
     */
    private static boolean overlayShown(Minecraft mc) {
        try {
            try {
                return Minecraft.class.getMethod("getOverlay").invoke(mc) != null;
            } catch (NoSuchMethodException e) {
                Object gui = Minecraft.class.getField("gui").get(mc);
                return gui.getClass().getMethod("overlay").invoke(gui) != null;
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Tessera self-test: no way to see the loading overlay", e);
        }
    }

    private static final class Error {
        long sum;
        long count;

        double psnr() {
            if (count == 0) {
                return Double.POSITIVE_INFINITY;
            }
            double mse = (double) sum / (count * 4);
            return mse == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 / mse);
        }
    }
}
