package com.tkisor.fettle;

import com.tkisor.fettle.config.FettleConfig;
import com.tkisor.fettle.recipe.RecipeParityCheck;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Runs the correctness gate once the world is up (config option or {@code -Dfettle.selftest=true}). */
@EventBusSubscriber(modid = Fettle.MOD_ID)
public final class FettleSelfTest {

    private FettleSelfTest() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!FettleConfig.selfTestOnStartup()) {
            return;
        }
        RecipeParityCheck.run(event.getServer());
    }
}
