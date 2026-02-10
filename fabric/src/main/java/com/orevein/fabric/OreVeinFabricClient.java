package com.orevein.fabric;

import com.orevein.OreVeinClient;
import net.fabricmc.api.ClientModInitializer;

public class OreVeinFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        OreVeinClient.initClient();
    }
}
