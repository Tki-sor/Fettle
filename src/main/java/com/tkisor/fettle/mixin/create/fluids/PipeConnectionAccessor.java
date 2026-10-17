package com.tkisor.fettle.mixin.create.fluids;

import com.simibubi.create.content.fluids.PipeConnection;
import java.util.Optional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The packet-private flow field, needed by the rewritten FluidPropagator BFS. */
@Mixin(value = PipeConnection.class, remap = false)
public interface PipeConnectionAccessor {

    @Accessor("flow")
    Optional<PipeConnection.Flow> fettle$flow();
}
