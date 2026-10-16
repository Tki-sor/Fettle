package com.tkisor.fettle.mixin.create;

import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Batch A8 - {@code BeltBlock#hasShaftTowards}.
 *
 * <p>Vanilla does {@code getBlockEntityOptional(world, pos).map(BeltBlockEntity::hasPulley).orElse(false)},
 * which builds an {@code Optional} (plus the mapping lambda) on a path that runs during shaft
 * connection checks. The check is equivalent to an {@code instanceof} test.
 */
@Mixin(value = BeltBlock.class, remap = false, priority = 1100)
public abstract class BeltBlockMixin {

    @Overwrite(remap = false)
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        if (face.getAxis() != ((BeltBlock) (Object) this).getRotationAxis(state)) {
            return false;
        }
        BlockEntity blockEntity = world.getBlockEntity(pos);
        return blockEntity instanceof BeltBlockEntity belt && belt.hasPulley();
    }
}
