package com.tkisor.fettle.mixin.observe;

import com.tkisor.fettle.observe.BeSleepController;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wake condition: block / neighbour updates.
 *
 * <p>A block change is the most common reason a sleeping block entity suddenly has work to do (a
 * neighbour appeared, a redstone signal changed, a container was placed next to it). Fettle keeps a
 * position index of the instances that are actually sleeping and consults it here, so the cost is a
 * single empty-map check while the pilot is off, or a handful of hash lookups while it is on.
 */
@Mixin(Level.class)
public abstract class LevelBlockUpdateMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("HEAD"))
    private void fettle$wakeOnBlockChange(BlockPos pos, BlockState state, int flags,
                                          CallbackInfoReturnable<Boolean> cir) {
        BeSleepController.wakeAround((Level) (Object) this, pos);
    }
}
