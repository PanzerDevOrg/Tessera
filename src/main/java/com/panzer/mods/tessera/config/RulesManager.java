package com.panzer.mods.tessera.config;

import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class RulesManager implements PreparableReloadListener {

    // Atlas locations excluded from compression, populated from the rules file.
    public static final Set<String> BLACKLISTED_ATLASES = new HashSet<>();

    public static Integer forcedVramBudgetMb;

    public RulesManager() {
    }

    @Override
    @NotNull
    public CompletableFuture<Void> reload(PreparationBarrier preparationBarrier, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profilerFiller, @NotNull ProfilerFiller profilerFiller1, @NotNull Executor executor, @NotNull Executor executor1) {
        return CompletableFuture.runAsync(() -> {
        }, executor1).thenCompose(preparationBarrier::wait);
    }
}
