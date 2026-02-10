package com.orevein.neoforge;

import com.orevein.OreVeinClient;
import com.orevein.OreVeinMod;
import dev.architectury.platform.Platform;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@Mod(OreVeinMod.MOD_ID)
public class OreVeinNeoForge {
    public OreVeinNeoForge(IEventBus modBus) {
        OreVeinMod.init();

        if (Platform.getEnv() == Dist.CLIENT) {
            modBus.addListener(this::onClientSetup);
        }
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        OreVeinClient.initClient();
    }
}
