package com.tkisor.fettle.mixin.observe;

import com.tkisor.fettle.observe.BeSleepController;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wake condition: capability queries.
 *
 * <p>{@code BlockCapability#getCapability} is the single resolution point every NeoForge capability
 * lookup goes through (level helpers, BlockCapabilityCache, machines asking a neighbour for an item or
 * energy handler). Somebody asking a block entity for its capability means it is being used, so the
 * instance is woken before the query is answered.
 */
@Mixin(value = BlockCapability.class, remap = false)
public abstract class BlockCapabilityMixin {

    @Inject(method = "getCapability", at = @At("HEAD"), remap = false)
    private void fettle$wakeOnCapabilityQuery(Level level, BlockPos pos, BlockState state, BlockEntity blockEntity,
                                              Object context, CallbackInfoReturnable<Object> cir) {
        if (blockEntity != null) {
            BeSleepController.wakeQueried(blockEntity);
        }
    }
}
