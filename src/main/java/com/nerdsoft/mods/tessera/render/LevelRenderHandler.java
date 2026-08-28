package com.nerdsoft.mods.tessera.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nerdsoft.mods.tessera.Tessera;
import com.nerdsoft.mods.tessera.TesseraClient;
import com.nerdsoft.mods.tessera.atlas.AtlasSplitTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draw-side counterpart to {@link SectionGeometryHandler}: once per
 * frame, at {@code RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS} and
 * {@code AFTER_CUTOUT_MIPPED_BLOCKS}, binds the corresponding Tessera
 * atlas and draws every stored section's accumulated geometry for that
 * target in one pass -- this is the two-extra-binds-per-frame (not
 * per-section) design confirmed with the person before implementation
 * began.
 */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = Tessera.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class LevelRenderHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/LevelRenderHandler");
    private static volatile int tessera$sharedQuadIndexBuffer = -1;
    private static volatile int tessera$sharedQuadIndexBufferCapacity = 0;
    private static volatile int tessera$vao = -1;
    private static volatile boolean tessera$loggedOnce = false;

    private LevelRenderHandler() {
    }

    @SuppressWarnings("resource")
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        AtlasSplitTarget target;
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            target = AtlasSplitTarget.OPAQUE;
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS) {
            target = AtlasSplitTarget.ALPHA;
        } else {
            return;
        }

        // Render-thread-only: frees GL buffers superseded by a recompile
        // or freed by a chunk unload since last frame (see
        // SectionGeometryStore's own doc). Runs on both stages this
        // handler subscribes to; draining an already-empty queue on the
        // second call each frame is a cheap no-op.
        SectionGeometryStore.drainPendingGpuBufferDeletions();

        if (!TesseraClient.SPLIT_ATLAS_MANAGER.hasContent(target)) {
            return;
        }

        int storedSections = SectionGeometryStore.getSectionCount(target);
        if (storedSections == 0) {
            return;
        }

        var atlas = TesseraClient.SPLIT_ATLAS_MANAGER.atlasFor(target);
        int textureId = atlas.getId();

        Camera camera = event.getCamera();
        double camX = camera.getPosition().x();
        double camY = camera.getPosition().y();
        double camZ = camera.getPosition().z();

        RenderSystem.setShader(target == AtlasSplitTarget.OPAQUE
                ? GameRenderer::getRendertypeSolidShader
                : GameRenderer::getRendertypeCutoutMippedShader);
        RenderSystem.setShaderTexture(0, textureId);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();

        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) {
            RenderSystem.setupShaderLights(shader);
        }

        int[] drawnSections = {0};
        SectionGeometryStore.forEachSection(target, (sectionOrigin, geometry) ->
                tessera$drawSection(sectionOrigin, geometry, camX, camY, camZ, shader, drawnSections));

        RenderSystem.disableBlend();

        // This handler runs twice per rendered frame (once per stage this
        // class subscribes to) -- logging every occurrence once this path
        // is actually drawing geometry would flood the log at 60-120+
        // lines/second. Match ModelWrapper's own first-hit-only pattern:
        // confirm this path is live once, then go quiet.
        if (!tessera$loggedOnce && drawnSections[0] > 0) {
            LOGGER.info("[Tessera-Debug] Drew {} sections for Tessera atlas {}. (Further logs muted)",
                    drawnSections[0], target);
            tessera$loggedOnce = true;
        }
    }

    /**
     * Draws one section's geometry, reusing a persistent GL buffer across
     * frames via {@link SectionGeometryStore.GpuBufferCache}.
     * {@code glBufferData} only runs on a cache miss, i.e. when the
     * section has genuinely recompiled since the last draw.
     */
    @SuppressWarnings("unused")
    private static void tessera$drawSection(
            BlockPos sectionOrigin, SectionGeometryStore.CompiledSectionGeometry geometry,
            double camX, double camY, double camZ, ShaderInstance shader, int[] drawnSections
    ) {
        if (tessera$vao < 0) {
            tessera$vao = GL30.glGenVertexArrays();
        }
        GL30.glBindVertexArray(tessera$vao);

        int vbo = SectionGeometryStore.GpuBufferCache.get(geometry).orElseGet(() -> {
            int newBuffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, newBuffer);
            // STATIC_DRAW: this buffer persists until the section
            // recompiles and a new CompiledSectionGeometry replaces it.
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, geometry.vertexData(), GL15.GL_STATIC_DRAW);
            SectionGeometryStore.GpuBufferCache.put(geometry, newBuffer);
            return newBuffer;
        });

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        int stride = 32;

        // DefaultVertexFormat.BLOCK, 32 bytes/vertex: Position (3xfloat),
        // Color (4xubyte), UV0 (2xfloat), UV2/Lightmap (2xshort), Normal
        // (3xbyte + 1 pad byte). No overlay attribute in this format.
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0L);

        // Color: 4 packed unsigned bytes (RGBA), normalized to [0,1] in-shader.
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, stride, 12L);

        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, stride, 16L);

        // UV2 (lightmap coords): 2 packed shorts.
        GL20.glEnableVertexAttribArray(3);
        GL20.glVertexAttribPointer(3, 2, GL11.GL_SHORT, false, stride, 24L);

        // Normal: 3 packed signed bytes (X,Y,Z), normalized -- followed by
        // 1 unread padding byte per DefaultVertexFormat.BLOCK's own
        // skip(1); count must be 3, not 4, or the pad byte is read as a
        // 4th component and corrupts the normal vector.
        GL20.glEnableVertexAttribArray(4);
        GL20.glVertexAttribPointer(4, 3, GL11.GL_BYTE, true, stride, 28L);

        if (shader != null && shader.CHUNK_OFFSET != null) {
            shader.CHUNK_OFFSET.set(
                    (float) (sectionOrigin.getX() - camX),
                    (float) (sectionOrigin.getY() - camY),
                    (float) (sectionOrigin.getZ() - camZ));
            shader.apply();
        }

        // GL_QUADS isn't valid in core GL profiles; each quad is expanded
        // to 2 triangles (6 indices: 0,1,2 / 2,3,0) via a shared, grown-
        // as-needed index buffer.
        int indexBuffer = tessera$ensureQuadIndexBuffer(geometry.quadCount());
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        GL11.glDrawElements(GL11.GL_TRIANGLES, geometry.quadCount() * 6, GL11.GL_UNSIGNED_INT, 0L);

        GL20.glDisableVertexAttribArray(0);
        GL20.glDisableVertexAttribArray(1);
        GL20.glDisableVertexAttribArray(2);
        GL20.glDisableVertexAttribArray(3);
        GL20.glDisableVertexAttribArray(4);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);

        drawnSections[0]++;
    }

    /**
     * Lazily builds (and grows, never shrinks) one shared quad-expansion
     * index buffer reused across every section drawn this frame and
     * across frames -- avoids rebuilding an index buffer per section per
     * frame, since the 0,1,2,2,3,0 pattern is identical for every quad
     * regardless of which section or atlas it belongs to; only the total
     * quad count varies, and only the largest section's count actually
     * needs to be covered since {@code glDrawElements} only reads as many
     * indices as requested.
     */
    private static synchronized int tessera$ensureQuadIndexBuffer(int requiredQuadCount) {
        if (tessera$sharedQuadIndexBuffer >= 0 && tessera$sharedQuadIndexBufferCapacity >= requiredQuadCount) {
            return tessera$sharedQuadIndexBuffer;
        }

        int newCapacity = Math.max(requiredQuadCount, tessera$sharedQuadIndexBufferCapacity * 2);
        java.nio.IntBuffer indices = java.nio.ByteBuffer.allocateDirect(newCapacity * 6 * Integer.BYTES)
                .order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
        for (int q = 0; q < newCapacity; q++) {
            int base = q * 4;
            indices.put(base).put(base + 1).put(base + 2);
            indices.put(base + 2).put(base + 3).put(base);
        }
        indices.rewind();

        if (tessera$sharedQuadIndexBuffer < 0) {
            tessera$sharedQuadIndexBuffer = GL15.glGenBuffers();
        }
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, tessera$sharedQuadIndexBuffer);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        tessera$sharedQuadIndexBufferCapacity = newCapacity;
        return tessera$sharedQuadIndexBuffer;
    }
}