package com.panzer.mods.tessera.compress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

/**
 * Reads {@code SpriteContents.AnimationState} (1.21.11+), where vanilla picks
 * and blends animation frames on the GPU and exposes neither: the frame shown,
 * the next one and the blend progress, exactly as {@code drawToAtlas} hands
 * them to its shaders. Method handles, because the classes involved are not
 * public. {@link #AVAILABLE} is false when they cannot be resolved; compressed
 * atlases with animations are then not compressed at all.
 */
final class AnimationReader {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/AnimationReader");
    private static final String SPRITE_CONTENTS = "net.minecraft.client.renderer.texture.SpriteContents$";

    static final boolean AVAILABLE;
    private static final MethodHandle FRAME;
    private static final MethodHandle SUB_FRAME;
    private static final MethodHandle INFO;
    private static final MethodHandle NEEDS_TO_DRAW;
    private static final MethodHandle FRAMES;
    private static final MethodHandle ROW_SIZE;
    private static final MethodHandle INTERPOLATE;
    private static final MethodHandle INDEX;
    private static final MethodHandle TIME;

    /**
     * The frame grid cells (column, row in the sprite's image) shown now and
     * next, and the blend between them (0 when the sprite does not interpolate).
     */
    record Frame(int column, int row, int nextColumn, int nextRow, float progress, boolean interpolated) {
    }

    static {
        MethodHandle[] h = new MethodHandle[9];
        boolean ok = false;
        try {
            ClassLoader loader = AnimationReader.class.getClassLoader();
            Class<?> state = Class.forName(SPRITE_CONTENTS + "AnimationState", false, loader);
            Class<?> info = Class.forName(SPRITE_CONTENTS + "AnimatedTexture", false, loader);
            Class<?> frame = Class.forName(SPRITE_CONTENTS + "FrameInfo", false, loader);
            h[0] = getter(state, "frame", int.class);
            h[1] = getter(state, "subFrame", int.class);
            h[2] = getter(state, "animationInfo", info);
            h[3] = MethodHandles.privateLookupIn(state, MethodHandles.lookup())
                    .findVirtual(state, "needsToDraw", MethodType.methodType(boolean.class))
                    .asType(MethodType.methodType(boolean.class, Object.class));
            h[4] = getter(info, "frames", List.class);
            h[5] = getter(info, "frameRowSize", int.class);
            h[6] = getter(info, "interpolateFrames", boolean.class);
            h[7] = getter(frame, "index", int.class);
            h[8] = getter(frame, "time", int.class);
            ok = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Tessera: cannot read sprite animation state ({}); atlases with animations stay uncompressed",
                    e.toString());
        }
        AVAILABLE = ok;
        FRAME = h[0];
        SUB_FRAME = h[1];
        INFO = h[2];
        NEEDS_TO_DRAW = h[3];
        FRAMES = h[4];
        ROW_SIZE = h[5];
        INTERPOLATE = h[6];
        INDEX = h[7];
        TIME = h[8];
    }

    private AnimationReader() {
    }

    private static MethodHandle getter(Class<?> owner, String field, Class<?> type) throws ReflectiveOperationException {
        Class<?> erased = type.isPrimitive() ? type : Object.class;
        return MethodHandles.privateLookupIn(owner, MethodHandles.lookup())
                .findGetter(owner, field, type)
                .asType(MethodType.methodType(erased, Object.class));
    }

    /** AnimationState.needsToDraw(): interpolating, or the frame index just changed. */
    static boolean needsToDraw(Object state) {
        try {
            return (boolean) NEEDS_TO_DRAW.invokeExact(state);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** What AnimationState.drawToAtlas would draw now. */
    static Frame read(Object state) {
        try {
            int frame = (int) FRAME.invokeExact(state);
            int subFrame = (int) SUB_FRAME.invokeExact(state);
            Object info = (Object) INFO.invokeExact(state);
            List<?> frames = (List<?>) (Object) FRAMES.invokeExact(info);
            int rowSize = (int) ROW_SIZE.invokeExact(info);
            boolean interpolate = (boolean) INTERPOLATE.invokeExact(info);
            Object current = frames.get(frame);
            int index = (int) INDEX.invokeExact(current);
            int time = (int) TIME.invokeExact(current);
            int next = (int) INDEX.invokeExact((Object) frames.get((frame + 1) % frames.size()));
            // drawToAtlas passes (int) (subFrame / time * 1000) to the shader, which divides by 1000.
            float progress = interpolate ? (int) ((float) subFrame / time * 1000.0F) / 1000.0F : 0.0F;
            return new Frame(index % rowSize, index / rowSize, next % rowSize, next / rowSize, progress, interpolate);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
