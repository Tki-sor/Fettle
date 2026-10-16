package com.tkisor.fettle.mixin.observe;

import com.tkisor.fettle.config.HotFlags;
import com.tkisor.fettle.observe.BeSleepController;
import com.tkisor.fettle.observe.BehaviourProbe;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The tick dispatch point every block entity in the game goes through - including third-party mods.
 *
 * <p>Hooking the bound ticker of a chunk (rather than a base class such as Create's
 * {@code SmartBlockEntity}) means the sleeping pilot can cover any mod's block entity, because the set
 * of ticking block entities does not care which mod registered the ticker. Counting also happens here,
 * so the observation layer sees every class.
 *
 * <p>Both actions are config-driven: with observation off this costs one volatile read, and the sleep
 * pilot does nothing unless it was explicitly enabled for a class.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BlockEntityTickerMixin {

    @Shadow
    private BlockEntity blockEntity;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void fettle$observeAndMaybeSkip(CallbackInfo ci) {
        BlockEntity entity = blockEntity;
        if (entity == null) {
            return;
        }
        if (HotFlags.OBSERVE) {
            BehaviourProbe.tick(entity.getClass());
        }
        if (!BeSleepController.shouldTick(entity)) {
            ci.cancel();
        }
    }
}
