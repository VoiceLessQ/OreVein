package com.orevein.fabric;

import com.orevein.OreVeinMod;
import net.fabricmc.api.ModInitializer;

public class OreVeinFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        OreVeinMod.init();
    }
}
