package com.tkisor.fettle.mixin.create;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.tkisor.fettle.bench.TickRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement probe: how much time factory panels spend in their own tick, and how often they tick. */
@Mixin(value = FactoryPanelBehaviour.class, remap = false, priority = 900)
public abstract class FactoryPanelBenchMixin {

    @Unique
    private long fettle$benchStart;

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void fettle$benchTickStart(CallbackInfo ci) {
        if (TickRecorder.isSectionArmed(TickRecorder.SECTION_PANEL_TICK)) {
            fettle$benchStart = System.nanoTime();
            TickRecorder.countCall(TickRecorder.SECTION_PANEL_TICK);
        }
    }

    @Inject(method = "tick", at = @At("RETURN"), remap = false)
    private void fettle$benchTickEnd(CallbackInfo ci) {
        if (TickRecorder.isSectionArmed(TickRecorder.SECTION_PANEL_TICK)) {
            TickRecorder.addSectionNanos(TickRecorder.SECTION_PANEL_TICK, System.nanoTime() - fettle$benchStart);
        }
    }
}
