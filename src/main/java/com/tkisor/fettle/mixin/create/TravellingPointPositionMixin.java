package com.tkisor.fettle.mixin.create;

import com.tkisor.fettle.config.HotFlags;
import com.tkisor.fettle.util.ServerTickClock;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Batch B3 / algorithmic helper - memoise {@code TravellingPoint#getPositionWithOffset}.
 *
 * <p>Train collision detection asks for the world position of every carriage of every train for
 * every train: O(T^2 * C) calls, each one doing a lerp plus a normal computation and allocating
 * several {@code Vec3}s - even though the answer only changes when the point moves. The cache is an
 * exact key comparison (graph, edge, position, offset, flip, upsideDown) plus a per-tick stamp, so a
 * value is only reused while every input is identical; the stamp bounds reuse to a single server
 * tick, after which the track graph is allowed to have changed.
 */
@Mixin(value = TravellingPoint.class, remap = false, priority = 1100)
public abstract class TravellingPointPositionMixin {

    @Unique private long fettle$posTick = Long.MIN_VALUE;
    @Unique private TrackGraph fettle$posGraph;
    @Unique private TrackEdge fettle$posEdge;
    @Unique private double fettle$posPosition = Double.NaN;
    @Unique private double fettle$posOffset = Double.NaN;
    @Unique private boolean fettle$posFlip;
    @Unique private boolean fettle$posUpsideDown;
    @Unique private Vec3 fettle$posResult;

    @Inject(method = "getPositionWithOffset", at = @At("HEAD"), cancellable = true, remap = false)
    private void fettle$cachedPosition(TrackGraph trackGraph, double offset, boolean flipUpsideDown,
                                       CallbackInfoReturnable<Vec3> cir) {
        if (!HotFlags.TRAVEL_POSITION_CACHE || fettle$posResult == null) {
            return;
        }
        TravellingPoint self = (TravellingPoint) (Object) this;
        if (fettle$posTick == ServerTickClock.tick()
                && fettle$posGraph == trackGraph
                && fettle$posEdge == self.edge
                && fettle$posPosition == self.position
                && fettle$posOffset == offset
                && fettle$posFlip == flipUpsideDown
                && fettle$posUpsideDown == self.upsideDown) {
            cir.setReturnValue(fettle$posResult);
        }
    }

    @Inject(method = "getPositionWithOffset", at = @At("RETURN"), remap = false)
    private void fettle$storePosition(TrackGraph trackGraph, double offset, boolean flipUpsideDown,
                                      CallbackInfoReturnable<Vec3> cir) {
        if (!HotFlags.TRAVEL_POSITION_CACHE) {
            return;
        }
        TravellingPoint self = (TravellingPoint) (Object) this;
        fettle$posTick = ServerTickClock.tick();
        fettle$posGraph = trackGraph;
        fettle$posEdge = self.edge;
        fettle$posPosition = self.position;
        fettle$posOffset = offset;
        fettle$posFlip = flipUpsideDown;
        fettle$posUpsideDown = self.upsideDown;
        fettle$posResult = cir.getReturnValue();
    }
}
