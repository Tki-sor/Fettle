package com.tkisor.fettle.mixin.observe;

import com.tkisor.fettle.config.HotFlags;
import com.tkisor.fettle.observe.BeSleepController;
import com.tkisor.fettle.observe.BehaviourProbe;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observation only: counts {@code setChanged()} per block entity class. */
@Mixin(BlockEntity.class)
public abstract class BlockEntityChangedMixin {

    @Inject(method = "setChanged", at = @At("HEAD"))
    private void fettle$observeChanged(CallbackInfo ci) {
        if (HotFlags.OBSERVE) {
            BehaviourProbe.changed(this.getClass());
        }
        BeSleepController.noteChanged((BlockEntity) (Object) this);
    }
}
