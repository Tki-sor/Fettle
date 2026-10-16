package com.tkisor.fettle.bench;

import com.simibubi.create.content.kinetics.KineticNetwork;
import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.world.level.Level;

/** Shared lookup of the biggest populated kinetic network for a level. */
public final class KineticBenchHelper {

    private KineticBenchHelper() {
    }

    @SuppressWarnings("unchecked")
    public static KineticNetwork biggestNetwork(Level level) {
        try {
            Class<?> propagator = Class.forName("com.simibubi.create.content.kinetics.TorquePropagator");
            Field field = propagator.getDeclaredField("networks");
            field.setAccessible(true);
            Map<?, ?> byLevel = (Map<?, ?>) field.get(null);
            KineticNetwork best = null;
            for (Map.Entry<?, ?> entry : byLevel.entrySet()) {
                if (entry.getKey() != level) {
                    continue;
                }
                for (Object candidate : ((Map<?, ?>) entry.getValue()).values()) {
                    KineticNetwork network = (KineticNetwork) candidate;
                    if (network.members == null || network.members.isEmpty()) {
                        continue;
                    }
                    if (best == null || network.members.size() > best.members.size()) {
                        best = network;
                    }
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }
}
