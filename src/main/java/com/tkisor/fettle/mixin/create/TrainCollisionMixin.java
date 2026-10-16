package com.tkisor.fettle.mixin.create;

import com.simibubi.create.infrastructure.config.AllConfigs;
import com.simibubi.create.content.trains.entity.Train;
import com.tkisor.fettle.alg.TrainCollisionIndex;
import com.tkisor.fettle.config.HotFlags;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Batch B3 - shrinks the candidate set of {@code Train#findCollidingTrain}.
 *
 * <p>The method loops over {@code Create.RAILWAYS.sided(level).trains.values()} and tests every
 * carriage of every train. The loop body itself is untouched: the only change is that the collection
 * it iterates is pre-filtered to the trains that can possibly be within reach, using a per-tick
 * bounding box snapshot (see {@link TrainCollisionIndex}).
 */
@Mixin(value = Train.class, remap = false)
public abstract class TrainCollisionMixin {

    @Unique
    private Vec3 fettle$collisionStart;

    @Unique
    private Level fettle$collisionLevel;

    @Inject(method = "findCollidingTrain", at = @At("HEAD"), remap = false)
    private void fettle$captureQuery(Level level, Vec3 start, Vec3 end, ResourceKey<Level> dimension,
                                     CallbackInfoReturnable<?> cir) {
        fettle$collisionLevel = level;
        fettle$collisionStart = start;
        if (HotFlags.TRAIN_COLLISION_INDEX) {
            TrainCollisionIndex.ensureBuilt(level);
        }
    }

    @Redirect(
            method = "findCollidingTrain",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;values()Ljava/util/Collection;"),
            remap = false
    )
    private Collection<Train> fettle$candidateTrains(Map<UUID, Train> trains) {
        if (!HotFlags.TRAIN_COLLISION_INDEX) {
            return trains.values();
        }
        return TrainCollisionIndex.filter(trains, (Train) (Object) this, fettle$collisionStart,
                AllConfigs.server().trains.maxAssemblyLength.get());
    }
}
