package com.nerdsoft.mods.tessera.atlas;

import net.minecraft.client.Minecraft;

import java.util.function.IntSupplier;

/**
 * Which vanilla source atlas a static sprite was pulled from before being
 * routed into Tessera's split atlases. Each family accumulates and
 * classifies independently (see {@link SplitAtlasManager}) and maps to its
 * own opaque/alpha {@link AtlasSplitTarget} pair -- a block-atlas sprite and
 * a GUI-atlas sprite must never share a physical texture, since their
 * render-side consumers (chunk section geometry vs. {@code GuiGraphics}
 * sprite blits) bind and rewrite UVs completely independently of each
 * other.
 *
 * <p>Each family also owns its own {@link #maxMipLevel()}: the depth
 * Tessera's own {@code SpriteLoader.stitch()} call for that family's
 * atlases requests. This MUST match the {@code maxMipLevel} vanilla's own
 * source atlas for that family requests, since both stitches share the
 * same underlying {@code SpriteContents} objects and a
 * {@code SpriteContents}' per-sprite {@code byMipLevel} array is populated
 * once, shared, to whatever the deepest level any stitch call against it
 * asked for -- not atlas-specific. {@code BLOCKS} tracks the live
 * block-mipmap option (vanilla's {@code minecraft:textures/atlas/blocks}
 * stitches at that same depth); {@code GUI} is fixed at 0 (vanilla's
 * {@code minecraft:textures/atlas/gui} never requests mips).
 */
public enum SourceAtlasFamily {

    BLOCKS(AtlasSplitTarget.OPAQUE, AtlasSplitTarget.ALPHA,
            () -> Minecraft.getInstance().options.mipmapLevels().get()),
    GUI(AtlasSplitTarget.HUD_OPAQUE, AtlasSplitTarget.HUD_ALPHA, () -> 0);

    private final AtlasSplitTarget opaqueTarget;
    private final AtlasSplitTarget alphaTarget;
    private final IntSupplier maxMipLevel;

    SourceAtlasFamily(AtlasSplitTarget opaqueTarget, AtlasSplitTarget alphaTarget, IntSupplier maxMipLevel) {
        this.opaqueTarget = opaqueTarget;
        this.alphaTarget = alphaTarget;
        this.maxMipLevel = maxMipLevel;
    }

    public AtlasSplitTarget opaqueTarget() {
        return opaqueTarget;
    }

    public AtlasSplitTarget alphaTarget() {
        return alphaTarget;
    }

    /**
     * The {@code maxMipLevel} Tessera's own stitch for this family's
     * atlases must request -- see this enum's class doc for why this has
     * to track vanilla's own source atlas depth per family rather than
     * being one shared value.
     */
    public int maxMipLevel() {
        return maxMipLevel.getAsInt();
    }
}