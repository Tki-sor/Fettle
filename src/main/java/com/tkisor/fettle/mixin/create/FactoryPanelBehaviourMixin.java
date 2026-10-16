package com.tkisor.fettle.mixin.create;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.tkisor.fettle.config.FettleConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Batch B8 - factory panel tick throttling.
 *
 * <p>Server-side {@code FactoryPanelBehaviour#tick()} runs {@code tickStorageMonitor()} and
 * {@code tickRequests()} on <em>every</em> tick, and both aggregate over the whole logistics network
 * (unloaded links, level in storage, promised amounts, connection walk). With many panels this is one
 * of the most repeated per-tick scans in a big factory.
 *
 * <p>Behaviour relaxation (explicitly approved): the two server-side calls now run every
 * {@code factoryPanelCheckInterval} ticks instead of every tick. The default is <b>1</b>, i.e. this
 * optimisation is opt-in and currently unproven: a scaffolding scene measured -72% of the panel
 * section, but a naturally active client scene showed no difference (docs/fettle/19, section 19.10).
 * The requests
 * themselves are untouched, so throughput is unchanged; only the observation latency grows, by at
 * most the configured interval. Each panel uses its identity hash as a phase offset, so the work is
 * spread across ticks rather than landing on the same tick for every panel.
 *
 * <p>The client-side call (ordinal 0, only for virtual panels) is deliberately left alone so the
 * on-screen panel keeps updating smoothly.
 */
@Mixin(value = FactoryPanelBehaviour.class, remap = false, priority = 1100)
public abstract class FactoryPanelBehaviourMixin {

    @Unique
    private int fettle$tickCounter;

    @Unique
    private boolean fettle$runServerAggregation = true;

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void fettle$decideThisTick(CallbackInfo ci) {
        int interval = com.tkisor.fettle.config.HotFlags.PANEL_INTERVAL;
        if (interval <= 1) {
            fettle$runServerAggregation = true;
            return;
        }
        int phase = Math.floorMod(System.identityHashCode(this), interval);
        fettle$runServerAggregation = Math.floorMod(fettle$tickCounter++, interval) == phase;
    }

    /** ordinal 1 = the server-side call in {@code tick()}; ordinal 0 is the virtual-panel client path. */
    @Inject(
            method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lcom/simibubi/create/content/logistics/factoryBoard/FactoryPanelBehaviour;tickStorageMonitor()V",
                    ordinal = 1),
            cancellable = true,
            remap = false
    )
    private void fettle$gateStorageMonitor(CallbackInfo ci) {
        if (!fettle$runServerAggregation) {
            ci.cancel();
        }
    }

    @Inject(
            method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lcom/simibubi/create/content/logistics/factoryBoard/FactoryPanelBehaviour;tickRequests()V"),
            cancellable = true,
            remap = false
    )
    private void fettle$gateRequests(CallbackInfo ci) {
        if (!fettle$runServerAggregation) {
            ci.cancel();
        }
    }
}
