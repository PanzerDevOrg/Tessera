package com.nerdsoft.mods.tessera.api;

import com.nerdsoft.mods.tessera.cache.AtlasCache.CompressedFormat;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Fired around {@code AtlasCompressionDriver#upload}, once per atlas per
 * reload that reaches the upload step.
 *
 * <p>Only {@link Post} is currently posted (see
 * {@code AtlasCompressionDriver#upload}), fired once compression has
 * already succeeded and been uploaded to GL -- it is purely informational.
 * {@link Pre}, despite being cancellable and exposing a mutable target
 * format, is not currently posted anywhere in the pipeline: letting a
 * listener override {@link Pre#setTargetFormat} would need to flow that
 * choice back through quality-preset resolution, GL internal-format
 * selection, and (for BC1) the GPU-compute-vs-CPU path split, none of
 * which currently accept a format different from the one
 * {@link com.nerdsoft.mods.tessera.atlas.AtlasSplitTarget} fixed for that
 * bucket. Listening for {@link Pre} compiles but will never fire until
 * that wiring exists.
 */
@SuppressWarnings("unused")
public abstract class AtlasCompressEvent extends Event {

    private final ResourceLocation atlasLocation;

    public AtlasCompressEvent(ResourceLocation atlasLocation) {
        this.atlasLocation = atlasLocation;
    }

    public ResourceLocation getAtlasLocation() {
        return atlasLocation;
    }

    public static class Pre extends AtlasCompressEvent implements ICancellableEvent {

        private CompressedFormat targetFormat;

        public Pre(ResourceLocation atlasLocation, CompressedFormat defaultFormat) {
            super(atlasLocation);
            this.targetFormat = defaultFormat;
        }

        public CompressedFormat getTargetFormat() {
            return targetFormat;
        }

        public void setTargetFormat(CompressedFormat targetFormat) {
            this.targetFormat = targetFormat;
        }
    }

    public static class Post extends AtlasCompressEvent {

        private final CompressedFormat appliedFormat;
        private final long vramBytesSaved;
        private final long residentBytes;

        public Post(ResourceLocation atlasLocation, CompressedFormat appliedFormat, long vramBytesSaved, long residentBytes) {
            super(atlasLocation);
            this.appliedFormat = appliedFormat;
            this.vramBytesSaved = vramBytesSaved;
            this.residentBytes = residentBytes;
        }

        public CompressedFormat getAppliedFormat() {
            return appliedFormat;
        }

        public long getVramBytesSaved() {
            return vramBytesSaved;
        }

        public long getResidentBytes() {
            return residentBytes;
        }
    }
}