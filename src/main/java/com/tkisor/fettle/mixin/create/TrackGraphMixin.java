package com.tkisor.fettle.mixin.create;

import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.tkisor.fettle.config.FettleConfig;
import java.util.Collections;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Batch A1 - {@code TrackGraph#getConnectionsFrom}.
 *
 * <p>Vanilla allocates a fresh {@code HashMap} on every lookup miss:
 * {@code connectionsByNode.getOrDefault(node, new HashMap<>())}. Train/carriage logic calls this
 * several times per tick per node, so the miss path is pure garbage.
 *
 * <p>All 12 call sites only read the returned map ({@code get}, {@code containsKey}, iteration,
 * {@code .values()}); none of them mutates it, so handing out the stored instance - or a shared
 * immutable empty map for the miss case - is behaviourally identical.
 */
@Mixin(value = TrackGraph.class, remap = false, priority = 1100)
public abstract class TrackGraphMixin {

    @Shadow
    Map<TrackNode, Map<TrackNode, TrackEdge>> connectionsByNode;

    @Inject(method = "getConnectionsFrom", at = @At("HEAD"), cancellable = true, remap = false)
    private void fettle$avoidEmptyMapAllocation(TrackNode node,
                                                CallbackInfoReturnable<Map<TrackNode, TrackEdge>> cir) {
        if (!FettleConfig.createTrackGraphConnections()) {
            return;
        }
        if (node == null) {
            cir.setReturnValue(null);
            return;
        }
        Map<TrackNode, TrackEdge> connections = connectionsByNode.get(node);
        cir.setReturnValue(connections == null ? Collections.emptyMap() : connections);
    }
}
