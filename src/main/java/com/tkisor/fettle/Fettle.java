package com.tkisor.fettle;

import com.mojang.logging.LogUtils;
import com.tkisor.fettle.config.FettleConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Fettle - cross-mod shared optimizations.
 *
 * <p>Phase 1 (this version) ships the recipe index layer: recipe lookups are pre-filtered with a
 * per-input index, while the final decision is still made by vanilla {@code Recipe#matches}.
 */
@Mod(Fettle.MOD_ID)
public class Fettle {

    public static final String MOD_ID = "fettle";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Fettle(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, FettleConfig.SPEC);
        LOGGER.info("[Fettle] loaded; master switch = {}, recipe index = {}",
                FettleConfig.enabled(), FettleConfig.recipeIndexEnabled());
    }
}
