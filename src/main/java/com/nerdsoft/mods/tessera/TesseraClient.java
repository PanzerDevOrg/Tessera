package com.nerdsoft.mods.tessera;

import com.nerdsoft.mods.tessera.atlas.SplitAtlasManager;

/**
 * Client-only static holder for singletons.
 */
public final class TesseraClient {

    public static final SplitAtlasManager SPLIT_ATLAS_MANAGER = new SplitAtlasManager();

    private TesseraClient() {
    }
}