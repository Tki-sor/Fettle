package com.tkisor.fettle.mixin.create;

import com.google.common.collect.ImmutableMap;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.MountedStorageManager;
import com.tkisor.fettle.config.HotFlags;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Batch ① - {@code MountedStorageManager#tick}.
 *
 * <p>Vanilla allocates two {@code HashMap}s on every tick that is not inside the sync cooldown (the
 * cooldown is 8 ticks), then fills them from {@code syncedItems} / {@code syncedFluids}; both maps can
 * only be non-empty when those immutable maps are non-empty. When a contraption has no synced item or
 * fluid storage - the common case for trains made of plain blocks - the two allocations are pure waste,
 * and the vanilla path is otherwise a no-op, so returning early is behaviour-identical.
 *
 * <p>Note the maps must not be pooled and reused: when they are non-empty they end up inside a
 * {@code MountedStorageSyncPacket} that is handed to the network layer.
 */
@Mixin(value = MountedStorageManager.class, remap = false, priority = 1100)
public abstract class MountedStorageFastPathMixin {

    @Shadow
    private ImmutableMap<BlockPos, ?> syncedItems;

    @Shadow
    private ImmutableMap<BlockPos, ?> syncedFluids;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private void fettle$skipEmptySync(AbstractContraptionEntity entity, CallbackInfo ci) {
        if (HotFlags.MOUNTED_STORAGE_FAST_PATH && syncedItems.isEmpty() && syncedFluids.isEmpty()) {
            ci.cancel();
        }
    }
}
