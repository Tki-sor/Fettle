package com.tkisor.fettle.mixin;

import com.tkisor.fettle.Fettle;
import com.tkisor.fettle.config.FettleConfig;
import java.util.List;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.Set;

/**
 * Decides per-mixin whether Fettle should apply it.
 *
 * <p>Coexistence policy: Fettle never fights for a call site. Every check is expressed here so the
 * decision is visible in one place, and it can be reversed by config instead of by code.
 */
public class FettleMixinPlugin implements IMixinConfigPlugin {

    /** Mods that already optimize the same call sites; checked by id because Lithium has forks. */
    private static final List<String> RECIPE_COMPETITORS = List.of("fastsuite");

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean apply = decide(targetClassName, mixinClassName);
        if (FettleConfig.mixinLog()) {
            Fettle.LOGGER.info("[Fettle] mixin {} for {} -> {}", mixinClassName, targetClassName, apply);
        }
        return apply;
    }

    private static boolean decide(String targetClassName, String mixinClassName) {
        if (!FettleConfig.enabled()) {
            return false;
        }
        // Measurement and observation mixins are always applied and switch themselves off by reading a
        // config-backed flag inside the handler. That keeps every feature controlled by config
        // (/fettle feature ...) instead of by a JVM argument, at the cost of one volatile read.
        if (mixinClassName.endsWith("BenchMixin")
                || mixinClassName.endsWith("MinecraftServerTickMixin")
                || mixinClassName.contains(".observe.")
                || mixinClassName.endsWith("DebugMixin")) {
            return true;
        }
        if (mixinClassName.contains(".create.")) {
            // Absence of Create is already handled by requiredMods in neoforge.mods.toml.
            return FettleConfig.createEnabled();
        }
        if (mixinClassName.contains(".recipe.")) {
            if (!FettleConfig.recipeIndexEnabled()) {
                return false;
            }
            if (FettleConfig.yieldToFastSuite()) {
                for (String competitor : RECIPE_COMPETITORS) {
                    if (isLoaded(competitor)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isLoaded(String modId) {
        try {
            ModList list = ModList.get();
            return list != null && list.isLoaded(modId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }
}
