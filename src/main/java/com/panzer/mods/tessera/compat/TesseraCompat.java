package com.panzer.mods.tessera.compat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.IdentityHashMap;
import java.util.concurrent.Executor;

//? >=1.21.10 {
/*import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
*///?} else
import com.mojang.blaze3d.systems.RenderSystem;
//? >=1.21.11 {
/*import net.minecraft.util.Util;
*///?} else
import net.minecraft.Util;

/**
 * The Minecraft API differences between the versions Tessera is built for, in
 * one place (Stonecutter comments pick the branch per version).
 *
 * <ul>
 *   <li>1.21.1: GL textures by id, sprites and animation frames uploaded from
 *       the CPU.</li>
 *   <li>1.21.10: {@code GpuTexture}s; still CPU uploads.</li>
 *   <li>1.21.11+: the atlas is a render target. Sprites (with a padding ring)
 *       and animation frames are drawn into it by GPU passes.</li>
 * </ul>
 */
public final class TesseraCompat {

    /**
     * Whether the whole compressed mip chain must be uploaded or none of it.
     * From 1.21.10 binding a texture view resets {@code GL_TEXTURE_MAX_LEVEL}
     * to the full chain, so a level left in RGBA would make the texture
     * incomplete (sampled as black).
     */
    public static final boolean FULL_CHAIN_REQUIRED =
            //? >=1.21.10 {
            /*true;
            *///?} else
            false;

    /** Animation frames are drawn into the atlas by GPU passes (TextureAtlas.uploadAnimationFrames). */
    public static final boolean GPU_ANIMATION =
            //? >=1.21.11 {
            /*true;
            *///?} else
            false;

    private TesseraCompat() {
    }

    /** OpenGL name of a texture. */
    public static int glId(AbstractTexture texture) {
        //? >=1.21.10 {
        /*Object gpu = texture.getTexture();
        // NeoForge's GPU validation layer (on in dev runs) wraps every texture:
        // ValidationGpuTexture.getRealTexture() is the GL one underneath.
        for (int depth = 0; !(gpu instanceof GlTexture) && depth < 4; depth++) {
            try {
                gpu = gpu.getClass().getMethod("getRealTexture").invoke(gpu);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("not an OpenGL texture: " + gpu.getClass().getName(), e);
            }
        }
        return ((GlTexture) gpu).glId();
        *///?} else
        return texture.getId();
    }

    /** Binds a texture to GL_TEXTURE_2D through the game's state cache. */
    public static void bindTexture(int glId) {
        //? >=1.21.10 {
        /*GlStateManager._bindTexture(glId);
        *///?} else
        RenderSystem.bindTexture(glId);
    }

    /** A pixel as ABGR (R in the low byte: R, G, B, A when written little-endian). */
    public static int abgr(NativeImage image, int x, int y) {
        //? >=1.21.10 {
        /*return argbToAbgr(image.getPixel(x, y));
        *///?} else
        return image.getPixelRGBA(x, y);
    }

    /** A sprite pixel of an animation frame, as ABGR. */
    public static int spriteAbgr(TextureAtlasSprite sprite, int frame, int x, int y) {
        // Despite the name, getPixelRGBA returns ARGB from 1.21.2 on.
        //? >=1.21.10 {
        /*try {
            return argbToAbgr((int) SPRITE_PIXEL.invokeExact(sprite, frame, x, y));
        } catch (Throwable t) {
            throw new IllegalStateException("Tessera: reading a sprite pixel failed", t);
        }
        *///?} else
        return sprite.getPixelRGBA(frame, x, y);
    }

    //? >=1.21.10 {
    /*// getPixelRGBA(frame, x, y) (ARGB despite the name), renamed getPixelARGB in 26.3.
    private static final java.lang.invoke.MethodHandle SPRITE_PIXEL = spritePixel();

    private static java.lang.invoke.MethodHandle spritePixel() {
        var type = java.lang.invoke.MethodType.methodType(int.class, int.class, int.class, int.class);
        for (String name : new String[]{"getPixelARGB", "getPixelRGBA"}) {
            try {
                return java.lang.invoke.MethodHandles.publicLookup().findVirtual(TextureAtlasSprite.class, name, type);
            } catch (ReflectiveOperationException ignored) {
                // the other name
            }
        }
        throw new IllegalStateException("Tessera: no TextureAtlasSprite pixel getter");
    }
    *///?}

    public static int argbToAbgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb >>> 16) & 0xFF) | ((argb & 0xFF) << 16);
    }

    /** More than one distinct animation frame. */
    public static boolean isAnimated(SpriteContents contents) {
        //? >=1.21.11 {
        /*return contents.isAnimated();
        *///?} else
        return contents.getUniqueFrames().limit(2).count() > 1;
    }

    /** Indices of a sprite's distinct animation frames (an IntStream up to 1.21.10, an IntList after). */
    public static int[] uniqueFrames(SpriteContents contents) {
        Object frames = contents.getUniqueFrames();
        if (frames instanceof java.util.stream.IntStream stream) {
            return stream.toArray();
        }
        return ((it.unimi.dsi.fastutil.ints.IntCollection) frames).toIntArray();
    }

    /**
     * Width of the ring around a sprite's pixels in the atlas (1.21.11+):
     * the sprite occupies {@code (x, y, width + 2p, height + 2p)} and the ring
     * repeats its edge pixels.
     */
    public static int padding(TextureAtlasSprite sprite) {
        //? >=1.21.11 {
        /*return ((com.panzer.mods.tessera.mixin.TextureAtlasSpriteAccessor) sprite).tessera$padding();
        *///?} else
        return 0;
    }

    /**
     * 1.21.11+: each animation state of the atlas with its sprite. TextureAtlas.upload
     * creates one state per animated sprite, in sprite order. Null when they do not
     * pair up (then animations cannot be drawn on a compressed atlas); empty before 1.21.11.
     */
    public static IdentityHashMap<Object, TextureAtlasSprite> animatedSprites(TextureAtlas atlas) {
        IdentityHashMap<Object, TextureAtlasSprite> result = new IdentityHashMap<>();
        //? >=1.21.11 {
        /*var accessor = (com.panzer.mods.tessera.mixin.TextureAtlasAccessor) atlas;
        var states = accessor.tessera$animationStates();
        int k = 0;
        for (TextureAtlasSprite sprite : accessor.tessera$sprites()) {
            if (sprite.contents().isAnimated()) {
                if (k >= states.size()) {
                    return null;
                }
                result.put(states.get(k++), sprite);
            }
        }
        if (k != states.size()) {
            return null;
        }
        *///?}
        return result;
    }

    /** {@code tessera:<path>} (ResourceLocation up to 1.21.10, Identifier from 1.21.11). */
    //? >=1.21.11 {
    /*public static net.minecraft.resources.Identifier id(String path) {
        return net.minecraft.resources.Identifier.fromNamespaceAndPath(com.panzer.mods.tessera.Tessera.MOD_ID, path);
    }
    *///?} else {
    public static net.minecraft.resources.ResourceLocation id(String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.panzer.mods.tessera.Tessera.MOD_ID, path);
    }
    //?}

    /** Whether a class exists in the running game/loader (APIs that come and go between versions). */
    public static boolean hasClass(String name) {
        try {
            Class.forName(name, false, TesseraCompat.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    public static Executor backgroundExecutor() {
        return Util.backgroundExecutor();
    }
}
