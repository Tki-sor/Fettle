package com.tkisor.fettle.mixin.observe;

import com.tkisor.fettle.observe.BeSleepController;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wake condition: player interaction.
 *
 * <p>Right-clicking a block is how a player opens a container, cycles a gauge or clicks a lever, so the
 * target block entity must be awake before the interaction is handled.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class PlayerInteractionMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void fettle$wakeOnUse(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
                                  BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        BeSleepController.wakeAt(level, hit.getBlockPos());
    }
}
