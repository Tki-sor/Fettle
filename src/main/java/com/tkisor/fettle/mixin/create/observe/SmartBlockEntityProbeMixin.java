package com.tkisor.fettle.mixin.create.observe;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.tkisor.fettle.config.HotFlags;
import com.tkisor.fettle.observe.BehaviourProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts client syncs per Create block entity class.
 *
 * <p>Tick counting and the sleeping decision live in the ticker-level hook
 * ({@code BlockEntityTickerMixin}), which covers every mod's block entities.
 */
@Mixin(value = SmartBlockEntity.class, remap = false)
public abstract class SmartBlockEntityProbeMixin {

    @Inject(method = "sendData", at = @At("HEAD"), remap = false)
    private void fettle$observeSend(CallbackInfo ci) {
        if (HotFlags.OBSERVE) {
            BehaviourProbe.sentData(this.getClass());
        }
    }
}
