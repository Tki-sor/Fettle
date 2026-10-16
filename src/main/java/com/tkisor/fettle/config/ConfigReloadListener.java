package com.tkisor.fettle.config;

import com.tkisor.fettle.Fettle;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;

/** Keeps {@link HotFlags} in sync with the config file. */
@EventBusSubscriber(modid = Fettle.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ConfigReloadListener {

    private ConfigReloadListener() {
    }

    @SubscribeEvent
    public static void onConfigLoad(ModConfigEvent.Loading event) {
        HotFlags.refresh();
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        HotFlags.refresh();
    }
}
