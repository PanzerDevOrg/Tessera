package com.panzer.mods.tessera.config;

import java.util.HashSet;
import java.util.Set;

/** Overrides that a rules file may set; nothing fills them yet (no reload listener). */
public final class RulesManager {

    // Atlas locations excluded from compression.
    public static final Set<String> BLACKLISTED_ATLASES = new HashSet<>();

    public static Integer forcedVramBudgetMb;

    private RulesManager() {
    }
}
