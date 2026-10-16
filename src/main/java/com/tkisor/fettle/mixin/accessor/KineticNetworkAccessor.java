package com.tkisor.fettle.mixin.accessor;

import com.simibubi.create.content.kinetics.KineticNetwork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to the cached network totals, for the differential test. */
@Mixin(value = KineticNetwork.class, remap = false)
public interface KineticNetworkAccessor {

    @Accessor("currentStress")
    float fettle$currentStress();

    @Accessor("currentCapacity")
    float fettle$currentCapacity();
}
