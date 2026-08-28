package com.nerdsoft.mods.tessera.render;

import com.nerdsoft.mods.tessera.Tessera;
import com.nerdsoft.mods.tessera.TesseraClient;
import com.nerdsoft.mods.tessera.atlas.AtlasSplitTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("removal")
@EventBusSubscriber(modid = Tessera.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class SectionGeometryHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/SectionGeometryHandler");
    private static final int SECTION_SIZE = 16;
    private static final List<RenderType> SUPPRESSIBLE_LAYERS =
            List.of(RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout());

    private SectionGeometryHandler() {
    }

    @SubscribeEvent
    public static void onAddSectionGeometry(AddSectionGeometryEvent event) {
        BlockPos sectionOrigin = event.getSectionOrigin();
        Level level = event.getLevel();

        // Level/BlockState reads are safe inside the renderer callback below
        // despite the event's non-thread-safe-structures warning -- that
        // warning concerns client-side caches, not Level's own block data,
        // which is designed for worker-thread access.
        // Must not call context.getOrCreateChunkBuffer(...) for any vanilla
        // RenderType: doing so claims that layer as active for this section
        // in vanilla's chunk-compile output, and nothing here ever writes
        // to it -- Tessera-routed quads only ever go into vertexIntsByTarget.
        event.addRenderer(context -> tessera$buildSectionGeometry(sectionOrigin, level));
    }

    /**
     * Runs on the compile thread (not the main thread) -- see this class's
     * own doc and the event's documented contract. Builds vertex data for
     * every Tessera-routed quad in this section, one
     * {@code CompiledSectionGeometry} per {@link AtlasSplitTarget} that
     * had at least one quad, and stores both via
     * {@link SectionGeometryStore#putSection}.
     */
    private static void tessera$buildSectionGeometry(BlockPos sectionOrigin, Level level) {
        Map<AtlasSplitTarget, List<Integer>> vertexIntsByTarget = new EnumMap<>(AtlasSplitTarget.class);
        Map<AtlasSplitTarget, Integer> quadCountByTarget = new EnumMap<>(AtlasSplitTarget.class);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        RandomSource random = RandomSource.create();

        for (int x = 0; x < SECTION_SIZE; x++) {
            for (int y = 0; y < SECTION_SIZE; y++) {
                for (int z = 0; z < SECTION_SIZE; z++) {
                    cursor.set(sectionOrigin.getX() + x, sectionOrigin.getY() + y, sectionOrigin.getZ() + z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }

                    BakedModel model = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);
                    // getBlockModel(state) returns whatever ModelBakingHandler
                    // registered for this state -- always a ModelWrapper
                    // instance after baking (see that class's own doc).
                    // Calling getQuads directly on it would recurse into
                    // ModelWrapper's own suppression override and receive
                    // the ALREADY-FILTERED quad list, i.e. this method
                    // would never see the Tessera-routed quads it exists to
                    // re-add -- every Tessera-split block would compile to
                    // zero replacement geometry here (invisible), while
                    // ModelWrapper still correctly suppressed it from
                    // vanilla's own chunk buffer. tessera$unwrap() returns
                    // the untouched original model instead, so the query
                    // below sees every quad, suppressed or not.
                    BakedModel unwrapped = model instanceof ModelWrapper wrapper ? wrapper.tessera$unwrap() : model;
                    long seed = state.getSeed(cursor);
                    // x, y, z are already the section-relative offset (0..15).
                    tessera$collectQuadsForBlock(unwrapped, state, seed, x, y, z, random, vertexIntsByTarget, quadCountByTarget);
                }
            }
        }

        int totalTesseraQuads = 0;
        // Block quads can only ever route to the BLOCKS family's two
        // targets (see SourceAtlasFamily/SpriteRoutingMixin) -- HUD_OPAQUE/
        // HUD_ALPHA never appear as keys in vertexIntsByTarget/
        // quadCountByTarget, but the loop is scoped explicitly rather than
        // relying on that being true only by construction.
        for (AtlasSplitTarget target : List.of(AtlasSplitTarget.OPAQUE, AtlasSplitTarget.ALPHA)) {
            List<Integer> ints = vertexIntsByTarget.get(target);
            int quadCount = quadCountByTarget.getOrDefault(target, 0);
            if (ints == null || quadCount == 0) {
                SectionGeometryStore.removeSectionTarget(sectionOrigin, target);
                continue;
            }

            ByteBuffer vertexData = ByteBuffer.allocateDirect(ints.size() * Integer.BYTES).order(ByteOrder.nativeOrder());
            for (int packed : ints) {
                vertexData.putInt(packed);
            }
            vertexData.rewind();

            SectionGeometryStore.putSection(sectionOrigin, target,
                    new SectionGeometryStore.CompiledSectionGeometry(vertexData, quadCount));
            totalTesseraQuads += quadCount;
        }

        if (totalTesseraQuads > 0) {
            LOGGER.info("[Tessera-Debug] Section {}: compiled {} Tessera-routed quads.", sectionOrigin, totalTesseraQuads);
        }
    }

    /**
     * For one block position: gets its model's quads per suppressible
     * layer (mirroring {@link ModelWrapper}'s layer set), querying the
     * unwrapped model directly since this method is the intended
     * consumer of the quads {@link ModelWrapper} suppresses from vanilla.
     *
     * <p>{@code random} is reseeded with {@code seed} immediately before
     * every single {@code getQuads} call, exactly matching
     * {@code ModelBlockRenderer#tesselateBlock}/{@code tesselateWithoutAO}'s
     * own per-direction {@code setSeed(seed)} contract. Models with more
     * than one weighted/multipart variant (e.g. {@code WeightedBakedModel})
     * consume {@code RandomSource} state inside {@code getQuads} to pick
     * which variant to return; without reseeding every call, each
     * direction/layer call here would advance the shared generator further
     * and resolve a different variant than the one vanilla's own,
     * correctly-reseeded compile pass selected and {@link ModelWrapper}
     * suppressed -- leaving that block/layer with no geometry from either
     * side.
     *
     * @param model the UNWRAPPED original model -- callers must resolve
     *              through {@link ModelWrapper#tessera$unwrap()} first
     *              (see {@link #tessera$buildSectionGeometry}); calling
     *              this with the wrapped instance would silently recurse
     *              into {@link ModelWrapper}'s own suppression and yield
     *              an already-filtered list
     * @param relX  section-relative block offset (0..15), not world position
     */
    private static void tessera$collectQuadsForBlock(
            BakedModel model, BlockState state, long seed, int relX, int relY, int relZ, RandomSource random,
            Map<AtlasSplitTarget, List<Integer>> vertexIntsByTarget, Map<AtlasSplitTarget, Integer> quadCountByTarget
    ) {
        for (RenderType layer : SUPPRESSIBLE_LAYERS) {
            for (Direction side : Direction.values()) {
                random.setSeed(seed);
                List<BakedQuad> quads = model.getQuads(state, side, random, ModelData.EMPTY, layer);
                tessera$collectQuads(quads, relX, relY, relZ, vertexIntsByTarget, quadCountByTarget);
            }
            random.setSeed(seed);
            List<BakedQuad> unculled = model.getQuads(state, null, random, ModelData.EMPTY, layer);
            tessera$collectQuads(unculled, relX, relY, relZ, vertexIntsByTarget, quadCountByTarget);
        }
    }

    @SuppressWarnings("resource")
    private static void tessera$collectQuads(
            List<BakedQuad> quads, int relX, int relY, int relZ,
            Map<AtlasSplitTarget, List<Integer>> vertexIntsByTarget, Map<AtlasSplitTarget, Integer> quadCountByTarget
    ) {
        for (BakedQuad quad : quads) {
            ResourceLocation spriteName = quad.getSprite().contents().name();
            AtlasSplitTarget target = TesseraClient.SPLIT_ATLAS_MANAGER.routingFor(spriteName);
            if (target == null) {
                continue;
            }

            var atlas = TesseraClient.SPLIT_ATLAS_MANAGER.atlasFor(target);
            if (atlas == null) {
                continue;
            }

            TextureAtlasSprite tesseraSprite = atlas.getSprite(spriteName);
            if (tesseraSprite == null) {
                continue;
            }

            List<Integer> ints = vertexIntsByTarget.computeIfAbsent(target, t -> new ArrayList<>());
            tessera$bakeVertices(quad, relX, relY, relZ, tesseraSprite, ints);
            quadCountByTarget.merge(target, 1, Integer::sum);
        }
    }

    /**
     * Packs one quad's 4 vertices into {@code DefaultVertexFormat.BLOCK}
     * layout (position, color, UV0, UV2/lightmap, normal -- 8 ints/vertex),
     * copying {@code BakedQuad.getVertices()} through as raw float bits
     * with the section-relative position added to X/Y/Z.
     */
    private static void tessera$bakeVertices(BakedQuad quad, int relX, int relY, int relZ, TextureAtlasSprite tesseraSprite, List<Integer> outInts) {
        int[] vertexData = quad.getVertices();
        int intsPerVertex = vertexData.length / 4;

        TextureAtlasSprite sourceSprite = quad.getSprite();
        float sourceU0 = sourceSprite.getU0();
        float sourceU1 = sourceSprite.getU1();
        float sourceV0 = sourceSprite.getV0();
        float sourceV1 = sourceSprite.getV1();
        float sourceUSpan = sourceU1 - sourceU0;
        float sourceVSpan = sourceV1 - sourceV0;

        float destU0 = tesseraSprite.getU0();
        float destU1 = tesseraSprite.getU1();
        float destV0 = tesseraSprite.getV0();
        float destV1 = tesseraSprite.getV1();
        float destUSpan = destU1 - destU0;
        float destVSpan = destV1 - destV0;

        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * intsPerVertex;
            float x = Float.intBitsToFloat(vertexData[base]) + relX;
            float y = Float.intBitsToFloat(vertexData[base + 1]) + relY;
            float z = Float.intBitsToFloat(vertexData[base + 2]) + relZ;

            outInts.add(Float.floatToRawIntBits(x));
            outInts.add(Float.floatToRawIntBits(y));
            outInts.add(Float.floatToRawIntBits(z));
            outInts.add(vertexData[base + 3]);

            float rawU = Float.intBitsToFloat(vertexData[base + 4]);
            float rawV = Float.intBitsToFloat(vertexData[base + 5]);
            float localU = sourceUSpan == 0f ? 0f : (rawU - sourceU0) / sourceUSpan;
            float localV = sourceVSpan == 0f ? 0f : (rawV - sourceV0) / sourceVSpan;
            outInts.add(Float.floatToRawIntBits(destU0 + localU * destUSpan));
            outInts.add(Float.floatToRawIntBits(destV0 + localV * destVSpan));

            outInts.add(vertexData[base + 6]);
            outInts.add(vertexData[base + 7]);
        }
    }
}