package com.tkisor.fettle.mixin.create;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MEASUREMENT SCAFFOLD - not a shipping optimisation.
 *
 * <p>{@code FactoryPanelBlockEntity#lazyTick} deletes a panel block whose {@code activePanels()} is
 * zero, so a scripted ({@code /setblock}) gauge removes itself within seconds and B8 could never be
 * measured in a scripted world. With {@code -Dfettle.debug.panels=true} that self-removal is skipped
 * so a benchmark scene can hold thousands of ticking panels.
 *
 * <p>Gated in {@code FettleMixinPlugin} by that system property, so it is inert everywhere else.
 */
@Mixin(value = FactoryPanelBlockEntity.class, remap = false, priority = 900)
public abstract class FactoryPanelDebugMixin {

    @Inject(method = "lazyTick", at = @At("HEAD"), cancellable = true, remap = false)
    private void fettle$keepPanelAlive(CallbackInfo ci) {
        if (com.tkisor.fettle.config.HotFlags.DEBUG_PANEL_KEEP_ALIVE) {
            ci.cancel();
        }
    }
}
