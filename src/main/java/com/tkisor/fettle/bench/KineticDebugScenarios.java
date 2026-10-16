package com.tkisor.fettle.bench;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.tkisor.fettle.alg.KineticCounters;
import com.tkisor.fettle.mixin.accessor.KineticNetworkAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;

/**
 * Differential test for the incremental kinetic stress bookkeeping.
 *
 * <p>Phases:
 * <ol>
 *   <li><b>stress only</b> - repeated {@code updateStressFor} calls with the cached sum compared against
 *       the exact full scan; must be identical;</li>
 *   <li><b>speed changed</b> - a member's speed is changed without a stress update, still within the
 *       same tick: the incremental sum cannot know about it, so this quantifies the size of the
 *       one-tick window;</li>
 *   <li><b>after reconcile</b> - {@code updateStress()} runs the exact scan and re-seeds the sum; must
 *       be identical again.</li>
 * </ol>
 */
public final class KineticDebugScenarios {

    private KineticDebugScenarios() {
    }

    public static String incrementalDifferential(ServerLevel level, int iterations) {
        KineticNetwork network = KineticBenchHelper.biggestNetwork(level);
        if (network == null) {
            return "kinetic incremental test: no populated network (place shafts + a motor first)";
        }
        KineticNetworkAccessor accessor = (KineticNetworkAccessor) network;
        List<KineticBlockEntity> members = new ArrayList<>(network.members.keySet());
        if (members.isEmpty()) {
            return "kinetic incremental test: network has no members";
        }
        KineticCounters.reset();

        float epsilon = 1.0e-3f;
        int stressChecks = 0;
        int stressMismatches = 0;
        int speedChecks = 0;
        int speedMismatches = 0;
        int rawSpeedChecks = 0;
        int rawSpeedMismatches = 0;
        int afterReconcileChecks = 0;
        int afterReconcileMismatches = 0;

        for (int round = 0; round < Math.max(1, iterations); round++) {
            for (int i = 0; i < members.size(); i += Math.max(1, members.size() / 8)) {
                KineticBlockEntity member = members.get(i);
                Float current = network.members.get(member);
                if (current == null) {
                    continue;
                }
                float next = current + (round % 2 == 0 ? 0.5f : -0.5f);
                network.updateStressFor(member, next);
                float exact = network.calculateStress();
                stressChecks++;
                if (Math.abs(exact - accessor.fettle$currentStress()) > epsilon) {
                    stressMismatches++;
                }
            }

            // phase 2a: speed changed through the real path (KineticBlockEntity#setSpeed)
            for (int i = 0; i < members.size(); i += Math.max(1, members.size() / 4)) {
                KineticBlockEntity member = members.get(i);
                float before = member.getTheoreticalSpeed();
                member.setSpeed(before + 1.0f);
                float exact = network.calculateStress();
                speedChecks++;
                if (Math.abs(exact - accessor.fettle$currentStress()) > epsilon) {
                    speedMismatches++;
                }
            }

            // phase 2b: a raw field write that bypasses even the setter
            for (int i = 0; i < members.size(); i += Math.max(1, members.size() / 8)) {
                KineticBlockEntity member = members.get(i);
                float before = member.getTheoreticalSpeed();
                try {
                    java.lang.reflect.Field speedField = KineticBlockEntity.class.getDeclaredField("speed");
                    speedField.setAccessible(true);
                    speedField.setFloat(member, before + 1.0f);
                } catch (Throwable t) {
                    continue;
                }
                rawSpeedChecks++;
                float exact = network.calculateStress();
                if (Math.abs(exact - accessor.fettle$currentStress()) > epsilon) {
                    rawSpeedMismatches++;
                }
            }

            // phase 3: reconcile
            network.updateStress();
            float exact = network.calculateStress();
            afterReconcileChecks++;
            if (Math.abs(exact - accessor.fettle$currentStress()) > epsilon) {
                afterReconcileMismatches++;
            }
        }

        return String.format(Locale.ROOT,
                "kinetic incremental: members=%d stress=[n=%d mismatches=%d] "
                        + "speedViaNetwork=[n=%d mismatches=%d] rawSpeedWrite=[n=%d mismatches=%d] "
                        + "afterReconcile=[n=%d mismatches=%d] %s",
                members.size(), stressChecks, stressMismatches, speedChecks, speedMismatches,
                rawSpeedChecks, rawSpeedMismatches, afterReconcileChecks, afterReconcileMismatches,
                KineticCounters.report());
    }
}
