package com.tkisor.fettle.bench;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.tkisor.fettle.alg.TrainCollisionIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Differential test for the train collision candidate filter.
 *
 * <p>Builds real {@code Train} objects on a synthetic straight track, registers them in the level's
 * railway for the duration of the test, and calls the real {@code Train#findCollidingTrain} twice per
 * scenario: with the index disabled (reference = vanilla) and enabled. Any difference in the returned
 * train is a mismatch.
 *
 * <p>Three phases:
 * <ol>
 *   <li><b>fresh</b> - snapshot rebuilt immediately before the indexed call (first query of a tick);</li>
 *   <li><b>stale / raw snapshot</b> - a train moves into range after the snapshot was taken, with the
 *       moved-train guard disabled: this is the raw option-B policy;</li>
 *   <li><b>stale / guarded</b> - the same scenario with the guard enabled.</li>
 * </ol>
 */
public final class TrainDebugScenarios {

    private TrainDebugScenarios() {
    }

    private static final class Rig {
        Train train;
        TravellingPoint leading;
        TravellingPoint trailing;
    }

    private static final double[] PROBE_DISTANCES = {4, 12, 40, 100, 127, 129, 200, 400};

    public static String differentialCollisionTest(ServerLevel level, int iterations) {
        var railway = Create.RAILWAYS.sided(level);
        if (railway == null || railway.trains == null) {
            return "no railway for this level";
        }
        Map<UUID, Train> registry = railway.trains;

        TrackGraph graph = new TrackGraph();
        TrackNodeLocation locationA = new TrackNodeLocation(0, 0, 0).in(Level.OVERWORLD);
        TrackNodeLocation locationB = new TrackNodeLocation(4000, 0, 0).in(Level.OVERWORLD);
        TrackNode nodeA = new TrackNode(locationA, 1, Vec3.ZERO);
        TrackNode nodeB = new TrackNode(locationB, 2, Vec3.ZERO);
        TrackEdge edge = new TrackEdge(nodeA, nodeB, null, null);

        int fleetSize = 6;
        List<Rig> fleet = new ArrayList<>();
        List<UUID> created = new ArrayList<>();
        try {
            for (int i = 0; i < fleetSize; i++) {
                Rig rig = buildTrain(graph, nodeA, nodeB, edge, 1000.0 + i * 200.0, 8.0);
                fleet.add(rig);
                created.add(rig.train.id);
                registry.put(rig.train.id, rig.train);
            }
            Train offline = buildTrain(graph, nodeA, nodeB, edge, 0.0, 8.0).train;
            Vec3 start = new Vec3(10.0, 0.0, 0.0);
            Vec3 end = new Vec3(30.0, 0.0, 0.0);

            int rounds = Math.max(1, iterations);
            int[] fresh = freshProbe(offline, level, start, end, fleet, rounds);

            TrainCollisionIndex.setVerifyMoved(false);
            int[] raw = staleProbe(offline, level, start, end, fleet, rounds);
            TrainCollisionIndex.setVerifyMoved(true);
            int[] guarded = staleProbe(offline, level, start, end, fleet, rounds);

            return String.format(Locale.ROOT,
                    "collisionDifferential: fleet=%d fresh=[n=%d collisions=%d mismatches=%d] "
                            + "stale_raw=[n=%d collisions=%d mismatches=%d missed=%d] "
                            + "stale_guarded=[n=%d collisions=%d mismatches=%d missed=%d] index=%s",
                    fleet.size(), fresh[0], fresh[1], fresh[2],
                    raw[0], raw[1], raw[2], raw[3],
                    guarded[0], guarded[1], guarded[2], guarded[3],
                    TrainCollisionIndex.counters());
        } finally {
            for (UUID id : created) {
                registry.remove(id);
            }
            TrainCollisionIndex.setVerifyMoved(true);
            TrainCollisionIndex.invalidate();
        }
    }

    /** @return {scenarios, referenceCollisions, mismatches} */
    private static int[] freshProbe(Train offline, ServerLevel level, Vec3 start, Vec3 end,
                                    List<Rig> fleet, int rounds) {
        int scenarios = 0;
        int collisions = 0;
        int mismatches = 0;
        for (int round = 0; round < rounds; round++) {
            for (double distance : PROBE_DISTANCES) {
                for (int i = 0; i < fleet.size(); i++) {
                    moveRig(fleet.get(i), distance + i * 300.0);
                }
                TrainCollisionIndex.invalidate();
                Train reference = callReference(offline, level, start, end);
                TrainCollisionIndex.invalidate();
                Train indexed = callIndexed(offline, level, start, end);
                scenarios++;
                if (reference != null) {
                    collisions++;
                }
                if (!Objects.equals(reference, indexed)) {
                    mismatches++;
                }
            }
        }
        return new int[]{scenarios, collisions, mismatches};
    }

    /** @return {scenarios, referenceCollisions, mismatches, missedCollisions} */
    private static int[] staleProbe(Train offline, ServerLevel level, Vec3 start, Vec3 end,
                                    List<Rig> fleet, int rounds) {
        int scenarios = 0;
        int collisions = 0;
        int mismatches = 0;
        int missed = 0;
        Rig rig = fleet.get(0);
        for (int round = 0; round < rounds; round++) {
            for (double distance : PROBE_DISTANCES) {
                // 1) train far away: the snapshot records a box that is out of reach
                moveRig(rig, 500.0);
                for (int other = 1; other < fleet.size(); other++) {
                    moveRig(fleet.get(other), 900.0 + other * 200.0);
                }
                TrainCollisionIndex.invalidate();
                callIndexed(offline, level, start, end);
                // 2) same tick: the train moves into range, then both paths are asked again
                moveRig(rig, distance);
                Train reference = callReference(offline, level, start, end);
                Train indexed = callIndexed(offline, level, start, end);
                scenarios++;
                if (reference != null) {
                    collisions++;
                }
                if (!Objects.equals(reference, indexed)) {
                    mismatches++;
                    if (reference != null && indexed == null) {
                        missed++;
                    }
                }
            }
        }
        return new int[]{scenarios, collisions, mismatches, missed};
    }

    private static Train callReference(Train train, ServerLevel level, Vec3 start, Vec3 end) {
        return call(train, level, start, end, false);
    }

    private static Train callIndexed(Train train, ServerLevel level, Vec3 start, Vec3 end) {
        return call(train, level, start, end, true);
    }

    private static Train call(Train train, ServerLevel level, Vec3 start, Vec3 end, boolean indexed) {
        boolean previous = com.tkisor.fettle.config.HotFlags.TRAIN_COLLISION_INDEX;
        com.tkisor.fettle.config.HotFlags.TRAIN_COLLISION_INDEX = indexed;
        try {
            var pair = train.findCollidingTrain(level, start, end, Level.OVERWORLD);
            return pair == null ? null : pair.getFirst();
        } finally {
            com.tkisor.fettle.config.HotFlags.TRAIN_COLLISION_INDEX = previous;
        }
    }

    private static void moveRig(Rig rig, double x) {
        rig.leading.position = x;
        rig.trailing.position = x + 8.0;
    }

    private static Rig buildTrain(TrackGraph graph, TrackNode nodeA, TrackNode nodeB, TrackEdge edge,
                                  double x, double length) {
        TravellingPoint leading = new TravellingPoint(nodeA, nodeB, edge, x, false);
        TravellingPoint trailing = new TravellingPoint(nodeA, nodeB, edge, x + length, false);
        CarriageBogey bogeyA = new CarriageBogey(AllBlocks.SMALL_BOGEY.get(), false, new CompoundTag(),
                leading, leading);
        CarriageBogey bogeyB = new CarriageBogey(AllBlocks.SMALL_BOGEY.get(), false, new CompoundTag(),
                trailing, trailing);
        Carriage carriage = new Carriage(bogeyA, bogeyB, (int) length);
        Train train = new Train(UUID.randomUUID(), UUID.randomUUID(), graph, List.of(carriage),
                List.of((int) length), false, 0);
        Rig rig = new Rig();
        rig.train = train;
        rig.leading = leading;
        rig.trailing = trailing;
        return rig;
    }
}
