package com.tkisor.fettle.mixin.create;

import com.simibubi.create.content.logistics.box.PackageItem;
import net.createmod.catnip.data.Glob;
import com.tkisor.fettle.config.FettleConfig;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Batch A15 - {@code PackageItem#matchAddress(String, String)}.
 *
 * <p>Vanilla ends with {@code address.matches(Glob.toRegexPattern(boxAddress, "")) ||
 * boxAddress.matches(Glob.toRegexPattern(address, ""))}. {@code String#matches} compiles the pattern
 * on every call, and chain conveyors call this per package per movement - so a busy logistics setup
 * recompiles the same handful of glob patterns hundreds of times per tick.
 *
 * <p>Semantics are kept byte-for-byte: the early returns are reproduced exactly and the final step is
 * still {@code Pattern.matcher(...).matches()}, only the compiled {@code Pattern} is cached. The cache
 * is bounded and keyed by the glob string, which is deterministic.
 */
@Mixin(value = PackageItem.class, remap = false, priority = 1100)
public abstract class PackageItemMixin {

    @Unique
    private static final int FETTLE_MAX_CACHED_PATTERNS = 256;

    @Unique
    private static final Map<String, Pattern> fettle$patternCache = new ConcurrentHashMap<>();

    @Inject(method = "matchAddress(Ljava/lang/String;Ljava/lang/String;)Z", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void fettle$cachedMatchAddress(String boxAddress, String address,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (!com.tkisor.fettle.config.HotFlags.PACKAGE_ADDRESS) {
            return;
        }
        if (address.isBlank()) {
            cir.setReturnValue(boxAddress.isBlank());
            return;
        }
        if (address.equals("*") || boxAddress.equals("*")) {
            cir.setReturnValue(true);
            return;
        }
        if (address.equals(boxAddress)) {
            cir.setReturnValue(true);
            return;
        }
        cir.setReturnValue(fettle$pattern(address).matcher(boxAddress).matches()
                || fettle$pattern(boxAddress).matcher(address).matches());
    }

    @Unique
    private static Pattern fettle$pattern(String glob) {
        Pattern cached = fettle$patternCache.get(glob);
        if (cached != null) {
            return cached;
        }
        Pattern compiled = Pattern.compile(Glob.toRegexPattern(glob, ""));
        if (fettle$patternCache.size() >= FETTLE_MAX_CACHED_PATTERNS) {
            fettle$patternCache.clear();
        }
        fettle$patternCache.put(glob, compiled);
        return compiled;
    }
}
