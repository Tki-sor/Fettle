package com.tkisor.fettle.alg;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.tkisor.fettle.util.ServerTickClock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Per-tick snapshot of every train's carriage bounding box, used to cut the candidate set of
 * {@code Train#findCollidingTrain} from "every train" down to "trains whose box is within reach".
 *
 * <p>Exactness argument: vanilla abandons a train as soon as it finds one carriage whose <em>both</em>
 * endpoints are farther than {@code maxAssemblyLength} from the query point. Therefore a train that
 * vanilla actually tests always has at least one carriage endpoint inside that radius - which is
 * exactly the condition this filter keeps. A train is only ever dropped when vanilla would have
 * dropped it too.
 *
 * <p>Snapshot policy (option B): the boxes are built once per server tick and reused for every query
 * in that tick. If another train moves into range later in the same tick, its stale (far) box can
 * drop it for the remainder of that tick; the next tick rebuilds. This window is what the
 * differential test measures.
 */
public final class TrainCollisionIndex {

    /** Snapshot entry: bounding box plus the movement signature the box was computed from. */
    private static final class Entry {
        final double[] box;
        final Object leadingEdge;
        final double leadingPosition;
        final Object trailingEdge;
        final double trailingPosition;

        Entry(double[] box, Object leadingEdge, double leadingPosition, Object trailingEdge, double trailingPosition) {
            this.box = box;
            this.leadingEdge = leadingEdge;
            this.leadingPosition = leadingPosition;
            this.trailingEdge = trailingEdge;
            this.trailingPosition = trailingPosition;
        }
    }

    private static long builtTick = Long.MIN_VALUE;
    private static Level builtLevel;
    private static final Map<Train, Entry> BOXES = new IdentityHashMap<>();
    private static long keptMoved;

    private static long builds;
    private static long indexedTrains;
    private static long keptTrains;
    private static long droppedTrains;
    private static long queries;

    private TrainCollisionIndex() {
    }

    /** Debug/test hook: forces the next query to rebuild the snapshot. */
    public static void invalidate() {
        BOXES.clear();
        builtTick = Long.MIN_VALUE;
        builtLevel = null;
    }

    public static void resetCounters() {
        builds = 0;
        indexedTrains = 0;
        keptTrains = 0;
        droppedTrains = 0;
        keptMoved = 0;
        queries = 0;
    }

    /** Test hook: switches the moved-train verification through the normal runtime-override path. */
    public static void setVerifyMoved(boolean value) {
        com.tkisor.fettle.config.FettleConfig.setBoolOverride("trainCollisionIndexVerifyMoved", value);
    }

    public static String counters() {
        return String.format(java.util.Locale.ROOT,
                "builds=%d indexed=%d queries=%d kept=%d keptMoved=%d dropped=%d",
                builds, indexedTrains, queries, keptTrains, keptMoved, droppedTrains);
    }

    /** Rebuilds the snapshot if it is missing or belongs to an earlier tick. */
    public static void ensureBuilt(Level level) {
        long tick = ServerTickClock.tick();
        if (level == builtLevel && tick == builtTick && !BOXES.isEmpty()) {
            return;
        }
        BOXES.clear();
        builtLevel = level;
        builtTick = tick;
        builds++;
        if (level == null) {
            return;
        }
        var railway = Create.RAILWAYS.sided(level);
        if (railway == null || railway.trains == null) {
            return;
        }
        for (Train train : railway.trains.values()) {
            double[] box = null;
            Object leadingEdge = null;
            Object trailingEdge = null;
            double leadingPosition = 0;
            double trailingPosition = 0;
            for (Carriage carriage : train.carriages) {
                var leading = carriage.getLeadingPoint();
                var trailing = carriage.getTrailingPoint();
                if (leading.edge == null || trailing.edge == null) {
                    continue;
                }
                box = grow(box, leading.getPosition(train.graph));
                box = grow(box, trailing.getPosition(train.graph));
                if (leadingEdge == null) {
                    leadingEdge = leading.edge;
                    leadingPosition = leading.position;
                    trailingEdge = trailing.edge;
                    trailingPosition = trailing.position;
                }
            }
            if (box != null) {
                BOXES.put(train, new Entry(box, leadingEdge, leadingPosition, trailingEdge, trailingPosition));
                indexedTrains++;
            }
        }
    }

    /**
     * @return the trains worth handing to vanilla's loop, in the original iteration order.
     *         Trains without a box are kept, so a train that appears mid-tick is never dropped.
     */
    public static Collection<Train> filter(Map<?, Train> source, Train self, Vec3 start, double maxAssemblyLength) {
        Collection<Train> all = source.values();
        queries++;
        if (start == null) {
            return all;
        }
        double maxDistanceSqr = Math.pow(maxAssemblyLength, 2.0);
        List<Train> kept = new ArrayList<>(Math.min(all.size(), 32));
        for (Train train : all) {
            if (train == self) {
                kept.add(train);
                continue;
            }
            Entry entry = BOXES.get(train);
            if (entry == null || boxDistanceSqr(entry.box, start) <= maxDistanceSqr) {
                kept.add(train);
                keptTrains++;
                continue;
            }
            if (com.tkisor.fettle.config.HotFlags.TRAIN_COLLISION_VERIFY_MOVED && movedSinceSnapshot(train, entry)) {
                // The snapshot says "out of reach" but the train moved after it was taken: keep it so
                // the decision stays exactly vanilla's.
                kept.add(train);
                keptMoved++;
                continue;
            }
            droppedTrains++;
        }
        return kept;
    }

    /** Cheap guard (a few field reads) that catches a train moving after the snapshot was taken. */
    private static boolean movedSinceSnapshot(Train train, Entry entry) {
        for (Carriage carriage : train.carriages) {
            var leading = carriage.getLeadingPoint();
            if (leading.edge != entry.leadingEdge || leading.position != entry.leadingPosition) {
                return true;
            }
            var trailing = carriage.getTrailingPoint();
            if (trailing.edge != entry.trailingEdge || trailing.position != entry.trailingPosition) {
                return true;
            }
        }
        return false;
    }

    private static double[] grow(double[] box, Vec3 point) {
        if (box == null) {
            return new double[]{point.x, point.y, point.z, point.x, point.y, point.z};
        }
        box[0] = Math.min(box[0], point.x);
        box[1] = Math.min(box[1], point.y);
        box[2] = Math.min(box[2], point.z);
        box[3] = Math.max(box[3], point.x);
        box[4] = Math.max(box[4], point.y);
        box[5] = Math.max(box[5], point.z);
        return box;
    }

    private static double boxDistanceSqr(double[] box, Vec3 point) {
        double dx = Math.max(0.0, Math.max(box[0] - point.x, point.x - box[3]));
        double dy = Math.max(0.0, Math.max(box[1] - point.y, point.y - box[4]));
        double dz = Math.max(0.0, Math.max(box[2] - point.z, point.z - box[5]));
        return dx * dx + dy * dy + dz * dz;
    }
}
