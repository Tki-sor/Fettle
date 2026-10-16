package com.tkisor.fettle.mixin.create;

import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.tkisor.fettle.bench.TickRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement probe: mechanical crafter tick time and call count. */
@Mixin(value = MechanicalCrafterBlockEntity.class, remap = false, priority = 900)
public abstract class MechanicalCrafterBenchMixin {

    @Unique
    private long fettle$benchStart;

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void fettle$benchTickStart(CallbackInfo ci) {
        if (TickRecorder.isSectionArmed(TickRecorder.SECTION_CRAFTER_TICK)) {
            fettle$benchStart = System.nanoTime();
            TickRecorder.countCall(TickRecorder.SECTION_CRAFTER_TICK);
        }
    }

    @Inject(method = "tick", at = @At("RETURN"), remap = false)
    private void fettle$benchTickEnd(CallbackInfo ci) {
        if (TickRecorder.isSectionArmed(TickRecorder.SECTION_CRAFTER_TICK)) {
            TickRecorder.addSectionNanos(TickRecorder.SECTION_CRAFTER_TICK, System.nanoTime() - fettle$benchStart);
        }
    }
}
