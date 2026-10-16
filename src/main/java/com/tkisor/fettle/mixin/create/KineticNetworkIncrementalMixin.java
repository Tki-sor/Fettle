package com.tkisor.fettle.mixin.create;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.tkisor.fettle.alg.KineticCounters;
import com.tkisor.fettle.config.HotFlags;
import com.tkisor.fettle.util.ServerTickClock;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Batch B5 - incremental stress/capacity bookkeeping for a kinetic network.
 *
 * <p>Vanilla recomputes the whole sum (and re-validates every member) whenever a single member's
 * stress or capacity changes. This keeps a running sum instead: a single member change is O(1), and
 * the exact full scan is performed once per server tick (reconciliation) so the sum can never drift
 * beyond one tick. {@code updateStress()} / {@code updateCapacity()} / {@code updateNetwork()} always
 * run the vanilla full scan, and the running sum is re-seeded from the result.
 */
@Mixin(value = KineticNetwork.class, remap = false)
public abstract class KineticNetworkIncrementalMixin implements com.tkisor.fettle.alg.KineticNetworkAdjust {

    @Shadow private float currentCapacity;
    @Shadow private float currentStress;
    @Shadow public Map<KineticBlockEntity, Float> members;

    @Unique private float fettle$stressSum;
    @Unique private float fettle$capacitySum;
    @Unique private long fettle$lastReconcileTick = Long.MIN_VALUE;

    @Override
    public void fettle$adjustStress(float delta) {
        fettle$stressSum += delta;
        KineticCounters.speedAdjust();
    }

    @Override
    public void fettle$adjustCapacity(float delta) {
        fettle$capacitySum += delta;
        KineticCounters.speedAdjust();
    }

    @Inject(method = {"updateStress", "updateCapacity", "updateNetwork"}, at = @At("RETURN"), remap = false)
    private void fettle$reseed(CallbackInfo ci) {
        fettle$stressSum = currentStress;
        fettle$capacitySum = currentCapacity;
        fettle$lastReconcileTick = ServerTickClock.tick();
    }

    @Overwrite(remap = false)
    public void updateStressFor(KineticBlockEntity be, float stress) {
        if (!HotFlags.KINETIC_INCREMENTAL_STRESS) {
            members.put(be, stress);
            ((KineticNetwork) (Object) this).updateStress();
            return;
        }
        Float previous = members.get(be);
        if (previous != null) {
            fettle$stressSum -= previous * Math.abs(be.getTheoreticalSpeed());
        } else {
            // first time we see this member: fall back to the exact scan
            members.put(be, stress);
            ((KineticNetwork) (Object) this).updateStress();
            return;
        }
        members.put(be, stress);
        fettle$stressSum += stress * Math.abs(be.getTheoreticalSpeed());
        KineticCounters.incrementalUpdate();

        if (fettle$lastReconcileTick != ServerTickClock.tick()) {
            // reconciliation: one exact scan per tick, which also prunes stale members
            KineticCounters.reconcile();
            ((KineticNetwork) (Object) this).updateStress();
            return;
        }
        float newStress = fettle$stressSum;
        if (currentStress != newStress) {
            currentStress = newStress;
            KineticCounters.sync();
            ((KineticNetwork) (Object) this).sync();
        }
    }
}
