package com.panzer.mods.tessera.datagen;

import com.panzer.mods.tessera.datagen.lang.EnUsLanguageProvider;
import com.panzer.mods.tessera.datagen.lang.EsEsLanguageProvider;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.data.event.GatherDataEvent;

public final class DataGenerators {

    private DataGenerators() {
    }

    public static void register(IEventBus eventBus) {
        //? <1.21.10 {
        // From NeoForge 21.4 GatherDataEvent is abstract (Client/Server subclasses) and
        // listening to it throws; the 1.21.1 build also runs there. Datagen is dev-only.
        if (java.lang.reflect.Modifier.isAbstract(GatherDataEvent.class.getModifiers())) {
            return;
        }
        //?}
        eventBus.addListener(DataGenerators::gatherData);
    }

    //? >=1.21.10 {
    /*private static void gatherData(GatherDataEvent.Client event) {
        event.createProvider(EnUsLanguageProvider::new);
        event.createProvider(EsEsLanguageProvider::new);
    }
    *///?} else {
    private static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput packOutput = generator.getPackOutput();
        generator.addProvider(event.includeClient(), new EnUsLanguageProvider(packOutput));
        generator.addProvider(event.includeClient(), new EsEsLanguageProvider(packOutput));
    }
    //?}
}
