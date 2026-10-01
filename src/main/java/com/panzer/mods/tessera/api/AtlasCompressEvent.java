package com.panzer.mods.tessera.api;

import com.panzer.mods.tessera.cache.AtlasCache.CompressedFormat;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Fired on {@code NeoForge.EVENT_BUS}, on the render thread, around the
 * in-place compression of each atlas after vanilla uploads it.
 *
 * <ul>
 *   <li>{@link Pre}: before compression starts. Cancel it to keep this atlas
 *       uncompressed, or change {@link Pre#setTargetFormat the format}. BC1 has
 *       no alpha channel, so choose it only for atlases without transparency.</li>
 *   <li>{@link Post}: after the compressed chain has been uploaded, with the
 *       VRAM it saved and now occupies.</li>
 * </ul>
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
