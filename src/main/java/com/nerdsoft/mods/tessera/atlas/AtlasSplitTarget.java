package com.nerdsoft.mods.tessera.atlas;

import com.nerdsoft.mods.tessera.compress.CompressionPipeline;
import net.minecraft.resources.ResourceLocation;

/**
 * The four independently-stitched, independently-owned static atlases that
 * replace the old same-atlas "bucket" hack -- one opaque/alpha pair per
 * {@link SourceAtlasFamily}. Each value here corresponds to a real
 * {@code net.minecraft.client.renderer.texture.TextureAtlas} instance with
 * its own GL texture ID, its own {@code SpriteLoader.stitch()} pass, and
 * therefore its own self-consistent UV space -- there is no cross-atlas
 * coordinate reuse anywhere in this design, which is what the previous
 * single-atlas-three-textures approach got wrong.
 *
 * <p>Only <em>static</em> (non-animated) sprites are split this way.
 * Animated sprites (anything with a {@code Ticker}) are deliberately left
 * on vanilla's own source atlas, sized down to just that subset -- see
 * {@link SpriteClassifier}.
 */
public enum AtlasSplitTarget {

    /**
     * Fully opaque sprites (every texel alpha == 255) plus punch-through
     * sprites (alpha is binary, never partial). Compressed as BC1, using
     * DXT1 punch-through mode for the latter -- both fit in the same 4bpp
     * format and the same physical atlas.
     */
    OPAQUE(
            ResourceLocation.fromNamespaceAndPath("tessera", "atlas/blocks_opaque"),
            CompressionPipeline.Target.BC1,
            true
    ),

    /**
     * Sprites with genuine partial-coverage alpha (anti-aliased edges,
     * soft glow, blended overlays). Compressed as BC7 to preserve that
     * fidelity -- BC1 punch-through cannot represent an in-between alpha
     * value.
     */
    ALPHA(
            ResourceLocation.fromNamespaceAndPath("tessera", "atlas/blocks_alpha"),
            CompressionPipeline.Target.BC7,
            true
    ),

    /**
     * {@code minecraft:textures/atlas/gui}'s opaque/punch-through sprites.
     * Consumed by the {@code GuiGraphics.blitSprite} rendering mixin, which
     * rebinds a routed sprite's blit onto this atlas with UVs rewritten
     * relative to its own packing -- the GUI-side counterpart to
     * {@code ModelWrapper}/{@code SectionGeometryHandler}/
     * {@code LevelRenderHandler} for blocks.
     */
    HUD_OPAQUE(
            ResourceLocation.fromNamespaceAndPath("tessera", "atlas/hud_opaque"),
            CompressionPipeline.Target.BC1,
            true
    ),

    /**
     * {@code minecraft:textures/atlas/gui}'s blended-alpha sprites. See
     * {@link #HUD_OPAQUE} for the rendering consumer both share.
     */
    HUD_ALPHA(
            ResourceLocation.fromNamespaceAndPath("tessera", "atlas/hud_alpha"),
            CompressionPipeline.Target.BC7,
            true
    );

    private final ResourceLocation atlasLocation;
    private final CompressionPipeline.Target compressionTarget;
    private final boolean eligible;

    AtlasSplitTarget(ResourceLocation atlasLocation, CompressionPipeline.Target compressionTarget, boolean eligible) {
        this.atlasLocation = atlasLocation;
        this.compressionTarget = compressionTarget;
        this.eligible = eligible;
    }

    public ResourceLocation atlasLocation() {
        return atlasLocation;
    }

    public CompressionPipeline.Target compressionTarget() {
        return compressionTarget;
    }

    /**
     * Whether a real, wired-up rendering consumer exists for this target.
     * {@link #OPAQUE}/{@link #ALPHA} are consumed by the block chunk-layer
     * render path ({@code SectionGeometryHandler}/{@code LevelRenderHandler});
     * {@link #HUD_OPAQUE}/{@link #HUD_ALPHA} are consumed by the GUI sprite
     * blit path ({@code GuiBlitSpriteMixin}).
     */
    public boolean eligible() {
        return eligible;
    }
}