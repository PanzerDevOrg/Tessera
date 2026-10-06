package com.panzer.mods.tessera.api;

import com.panzer.mods.tessera.cache.AtlasCache.CompressedFormat;
//? >=1.21.11 {
/*import net.minecraft.resources.Identifier;
*///?} else
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
 * On Minecraft 1.21 - 1.21.1 the first resource reload runs while mods are still
 * loading, and NeoForge's bus drops events posted before loading ends: the events
 * of that first reload's atlases may never arrive. Later reloads are not affected.
 */
@SuppressWarnings("unused")
public abstract class AtlasCompressEvent extends Event {

    //? >=1.21.11 {
    /*private final Identifier atlasLocation;
    *///?} else
    private final ResourceLocation atlasLocation;

    //? >=1.21.11 {
    /*public AtlasCompressEvent(Identifier atlasLocation) {
    *///?} else
    public AtlasCompressEvent(ResourceLocation atlasLocation) {
        this.atlasLocation = atlasLocation;
    }

    //? >=1.21.11 {
    /*public Identifier getAtlasLocation() {
    *///?} else
    public ResourceLocation getAtlasLocation() {
        return atlasLocation;
    }

    public static class Pre extends AtlasCompressEvent implements ICancellableEvent {

        private CompressedFormat targetFormat;

        //? >=1.21.11 {
        /*public Pre(Identifier atlasLocation, CompressedFormat defaultFormat) {
        *///?} else
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

        //? >=1.21.11 {
        /*public Post(Identifier atlasLocation, CompressedFormat appliedFormat, long vramBytesSaved, long residentBytes) {
        *///?} else
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
