package com.tkisor.fettle.mixin.bench;

import com.tkisor.fettle.bench.TickRecorder;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measures the full server tick while the benchmark recorder is armed (two static checks otherwise). */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerTickMixin {

    @Unique
    private long fettle$tickStart;

    @Unique
    private int fettle$proximityTimer;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void fettle$benchTickStart(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        com.tkisor.fettle.util.ServerTickClock.advance();
        com.tkisor.fettle.observe.BehaviourProbe.maybeDump();
        if ((++fettle$proximityTimer % 20) == 0) {
            com.tkisor.fettle.observe.BeSleepController.wakeNearPlayers();
        }
        if (TickRecorder.isArmed()) {
            fettle$tickStart = System.nanoTime();
        }
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void fettle$benchTickEnd(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (TickRecorder.isArmed()) {
            TickRecorder.record(System.nanoTime() - fettle$tickStart);
        }
    }
}
