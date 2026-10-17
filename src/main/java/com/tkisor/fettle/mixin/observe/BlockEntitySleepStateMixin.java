package com.tkisor.fettle.mixin.observe;

import com.tkisor.fettle.observe.BeSleepState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Per-instance sleeping state on every block entity, so the pilot is not limited to Create's
 * {@code SmartBlockEntity} subclasses: any mod's block entity can be observed and (if it proves idle)
 * slept.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntitySleepStateMixin implements BeSleepState {

    @Unique private int fettle$observedTicks;
    @Unique private int fettle$skippedTicks;
    @Unique private int fettle$probeAt = 1;
    @Unique private boolean fettle$sleeping;
    @Unique private boolean fettle$sawChange;

    @Override public int fettle$observedTicks() { return fettle$observedTicks; }

    @Override public void fettle$observedTicks(int value) { fettle$observedTicks = value; }

    @Override public int fettle$skippedTicks() { return fettle$skippedTicks; }

    @Override public void fettle$skippedTicks(int value) { fettle$skippedTicks = value; }

    @Override public int fettle$probeAt() { return fettle$probeAt; }

    @Override public void fettle$probeAt(int value) { fettle$probeAt = Math.max(1, value); }

    @Override public boolean fettle$sleeping() { return fettle$sleeping; }

    @Override public void fettle$sleeping(boolean value) { fettle$sleeping = value; }

    @Override public boolean fettle$sawChange() { return fettle$sawChange; }

    @Override public void fettle$sawChange(boolean value) { fettle$sawChange = value; }
}
