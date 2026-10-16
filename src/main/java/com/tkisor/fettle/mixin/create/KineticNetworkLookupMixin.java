package com.tkisor.fettle.mixin.create;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.tkisor.fettle.alg.LevelBlockEntityCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Batch B4 - removes the dominant cost of {@code KineticNetwork#calculateStress} /
 * {@code calculateCapacity}.
 *
 * <p>Both methods sum over every member and validate each entry with
 * {@code be.getLevel().getBlockEntity(be.getBlockPos()) != be}. The arithmetic is cheap; the chunk and
 * section lookups are not, and the same positions are re-validated for every update within a tick.
 * The redirect routes those lookups through a per-tick memo, so the comparison still sees exactly the
 * same value while the lookups collapse to (at most) one per member per tick.
 */
@Mixin(value = KineticNetwork.class, remap = false)
public abstract class KineticNetworkLookupMixin {

    @Redirect(
            method = {"calculateStress", "calculateCapacity"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"
            ),
            remap = false
    )
    private BlockEntity fettle$cachedLookup(Level level, BlockPos pos) {
        return LevelBlockEntityCache.get(level, pos);
    }
}
