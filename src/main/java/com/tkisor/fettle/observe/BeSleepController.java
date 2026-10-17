package com.tkisor.fettle.observe;

import com.tkisor.fettle.Fettle;
import com.tkisor.fettle.config.HotFlags;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import java.util.HashMap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * BE-sleeping pilot, config driven (see docs/09 for the authorisation boundary).
 *
 * <p>Contract enforced here:
 * <ul>
 *   <li><b>default off, empty whitelist</b> - nothing sleeps unless {@code beSleeping.enabled = true} and
 *       {@code beSleeping.classes} names the class (simple or fully qualified name);</li>
 *   <li><b>observe first</b> - instances only sleep after {@code observeTicks} consecutive ticks without a
 *       {@code setChanged}; any change restarts the observation;</li>
 *   <li><b>heartbeat</b> - a sleeping instance is still ticked once every {@code maxSkipTicks} (staying
 *       asleep), so it can never be lost and a heartbeat that changes something wakes it;</li>
 *   <li><b>wake on change</b> - {@code setChanged} wakes the instance immediately;</li>
 *   <li><b>auto-blacklist</b> - a class that keeps changing (observation resets) or keeps being woken is
 *       dropped from the whitelist for the rest of the run; an exception on the skip path disables the
 *       pilot entirely;</li>
 *   <li><b>one-click revert</b> - {@link #suspend()} stops all skipping and wakes every instance.</li>
 * </ul>
 */
public final class BeSleepController {

    /** Per class: [0] ticks, [1] skips, [2] heartbeats, [3] wakes, [4] observation resets. */
    private static final Map<String, long[]> CLASS_STATS = new ConcurrentHashMap<>();
    private static final Set<String> BLACKLIST = ConcurrentHashMap.newKeySet();
    private static final AtomicLong blacklistEvents = new AtomicLong();

    /**
     * Position index of the instances that are currently sleeping, so that a block change or a
     * capability query only costs a hash lookup (and nothing at all while the map is empty).
     */
    private static final Map<Level, Map<Long, BlockEntity>> SLEEPING = new ConcurrentHashMap<>();

    private static final AtomicLong wakesByChange = new AtomicLong();
    private static final AtomicLong wakesByBlockUpdate = new AtomicLong();
    private static final AtomicLong wakesByCapability = new AtomicLong();
    private static final AtomicLong wakesByInteraction = new AtomicLong();
    private static final AtomicLong wakesByProximity = new AtomicLong();

    private static final Map<Class<?>, Boolean> TARGET_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> MOD_CLASSES = new ConcurrentHashMap<>();
    private static volatile String[] cachedPatterns;

    private static volatile boolean suspended;
    private static volatile boolean disabledByFailure;

    /** Runtime override used by {@code /fettle debug sleep}; empty when unused. */
    private static volatile String debugTarget = "";
    private static volatile int debugObserve = -1;
    private static volatile int debugMaxSkip = -1;

    private BeSleepController() {
    }

    public static boolean enabled() {
        if (disabledByFailure || suspended) {
            return false;
        }
        if (!debugTarget.isEmpty()) {
            return true;
        }
        return HotFlags.BE_SLEEPING_ENABLED && HotFlags.BE_SLEEPING_CLASSES.length > 0;
    }

    public static void enableDebug(String simpleClassName, int observe, int maxSkip) {
        debugTarget = simpleClassName == null ? "" : simpleClassName;
        debugObserve = Math.max(1, observe);
        debugMaxSkip = Math.max(1, maxSkip);
        suspended = false;
        disabledByFailure = false;
        CLASS_STATS.clear();
        wakeAll();
    }

    /** {@code /fettle idle wake}: stop skipping and wake every instance. */
    public static void suspend() {
        suspended = true;
        debugTarget = "";
        wakeAll();
    }

    /** Wakes every registered sleeping instance and forgets the position index. */
    public static void wakeAll() {
        for (Map.Entry<Level, Map<Long, BlockEntity>> levelEntry : SLEEPING.entrySet()) {
            for (BlockEntity entity : levelEntry.getValue().values()) {
                if (entity instanceof BeSleepState state) {
                    state.fettle$sleeping(false);
                    state.fettle$skippedTicks(0);
                    state.fettle$observedTicks(0);
                }
            }
        }
        SLEEPING.clear();
    }

    /** Wake condition: a block changed at this position or next to it. */
    public static void wakeAround(Level level, BlockPos pos) {
        Map<Long, BlockEntity> sleeping = level == null ? null : SLEEPING.get(level);
        if (sleeping == null || sleeping.isEmpty()) {
            return;
        }
        wakeAt(level, sleeping, pos);
        for (Direction direction : Direction.values()) {
            wakeAt(level, sleeping, pos.relative(direction));
        }
    }

    private static void wakeAt(Level level, Map<Long, BlockEntity> sleeping, BlockPos pos) {
        BlockEntity entity = sleeping.get(pos.asLong());
        if (entity != null && wake(entity)) {
            wakesByBlockUpdate.incrementAndGet();
        }
    }

    /** Wake condition: a player interacted with the block at this position. */
    public static void wakeAt(Level level, BlockPos pos) {
        Map<Long, BlockEntity> sleeping = level == null ? null : SLEEPING.get(level);
        if (sleeping == null || sleeping.isEmpty()) {
            return;
        }
        BlockEntity entity = sleeping.get(pos.asLong());
        if (entity != null && wake(entity)) {
            wakesByInteraction.incrementAndGet();
        }
    }

    /**
     * Wake condition: a player entered the chunk of a sleeping instance. Runs every 20 ticks near the
     * start of the server tick, so walking into a base wakes what the player is about to use.
     */
    public static void wakeNearPlayers() {
        if (SLEEPING.isEmpty()) {
            return;
        }
        List<BlockEntity> candidates = new ArrayList<>();
        for (Map.Entry<Level, Map<Long, BlockEntity>> levelEntry : SLEEPING.entrySet()) {
            if (!(levelEntry.getKey() instanceof ServerLevel serverLevel)) {
                continue;
            }
            for (ServerPlayer player : serverLevel.players()) {
                ChunkPos chunk = player.chunkPosition();
                for (Map.Entry<Long, BlockEntity> entry : levelEntry.getValue().entrySet()) {
                    BlockPos pos = BlockPos.of(entry.getKey());
                    if ((pos.getX() >> 4) == chunk.x && (pos.getZ() >> 4) == chunk.z) {
                        candidates.add(entry.getValue());
                    }
                }
            }
        }
        for (BlockEntity entity : candidates) {
            if (wake(entity)) {
                wakesByProximity.incrementAndGet();
            }
        }
    }

    /** Wake condition: something asked this block entity for a capability. */
    public static void wakeQueried(BlockEntity entity) {
        if (entity instanceof BeSleepState state && state.fettle$sleeping() && wake(entity)) {
            wakesByCapability.incrementAndGet();
        }
    }

    private static boolean wake(BlockEntity entity) {
        if (!(entity instanceof BeSleepState state) || !state.fettle$sleeping()) {
            return false;
        }
        state.fettle$sleeping(false);
        state.fettle$skippedTicks(0);
        state.fettle$observedTicks(0);
        unregister(entity);
        return true;
    }

    private static void register(BlockEntity entity) {
        Level level = entity.getLevel();
        if (level == null) {
            return;
        }
        SLEEPING.computeIfAbsent(level, $ -> new ConcurrentHashMap<>())
                .put(entity.getBlockPos().asLong(), entity);
    }

    private static void unregister(BlockEntity entity) {
        Level level = entity.getLevel();
        if (level == null) {
            return;
        }
        Map<Long, BlockEntity> sleeping = SLEEPING.get(level);
        if (sleeping != null) {
            sleeping.remove(entity.getBlockPos().asLong());
            if (sleeping.isEmpty()) {
                SLEEPING.remove(level);
            }
        }
    }

    public static String report() {
        StringBuilder builder = new StringBuilder();
        builder.append("beSleeping enabled=").append(enabled())
                .append(" suspended=").append(suspended)
                .append(" failed=").append(disabledByFailure)
                .append(" whitelist=").append(java.util.Arrays.toString(HotFlags.BE_SLEEPING_CLASSES))
                .append(" debug=").append(debugTarget.isEmpty() ? "-" : debugTarget)
                .append(" observeTicks=").append(observeTicks())
                .append(" maxSkipTicks=").append(maxSkipTicks())
                .append(" blacklistAfter=").append(HotFlags.BE_SLEEPING_BLACKLIST_WAKES)
                .append(" blacklisted=").append(BLACKLIST)
                .append(" events=").append(blacklistEvents.get())
                .append(" sleepRegistered=").append(sleepingInstances())
                .append(" wakes(change/block/capability/interact/proximity)=")
                .append(wakesByChange.get()).append('/').append(wakesByBlockUpdate.get()).append('/')
                .append(wakesByCapability.get()).append('/').append(wakesByInteraction.get()).append('/')
                .append(wakesByProximity.get());
        for (Map.Entry<String, long[]> entry : CLASS_STATS.entrySet()) {
            long[] value = entry.getValue();
            builder.append(String.format(Locale.ROOT,
                    "\n  %s ticks=%d skipped=%d heartbeats=%d wakes=%d observationResets=%d",
                    entry.getKey(), value[0], value[1], value[2], value[3], value[4]));
        }
        return builder.toString();
    }

    private static int observeTicks() {
        return debugObserve > 0 ? debugObserve : HotFlags.BE_SLEEPING_OBSERVE_TICKS;
    }

    private static int maxSkipTicks() {
        return debugMaxSkip > 0 ? debugMaxSkip : HotFlags.BE_SLEEPING_MAX_SKIP_TICKS;
    }

    private static String className(BlockEntity be) {
        return be.getClass().getSimpleName();
    }

    /** @return true when this instance's class is on the whitelist (and not blacklisted). */
    /** Number of instances currently registered as sleeping (across all levels). */
    private static int sleepingInstances() {
        int total = 0;
        for (Map<Long, BlockEntity> perLevel : SLEEPING.values()) {
            total += perLevel.size();
        }
        return total;
    }

    public static boolean targeted(BlockEntity be) {
        String simple = className(be);
        if (BLACKLIST.contains(simple)) {
            return false;
        }
        if (!debugTarget.isEmpty()) {
            return simple.equals(debugTarget);
        }
        if (HotFlags.BE_SLEEPING_CLASSES.length == 0) {
            return autoAdopted(be);
        }
        return targetCache(be.getClass()) || autoAdopted(be);
    }

    /**
     * C7: adopt a class from the observation layer instead of the hand-written whitelist. A class
     * qualifies only after {@code autoWhitelistMinTicks} observed ticks with zero setChanged and zero
     * sendData calls, and only when {@code beSleeping.autoWhitelist} is on; the existing auto-blacklist
     * and wake conditions still apply afterwards.
     */
    private static boolean autoAdopted(BlockEntity be) {
        if (!HotFlags.BE_SLEEPING_AUTO_WHITELIST) {
            return false;
        }
        if (!BehaviourProbe.isIdleClass(be.getClass(), HotFlags.BE_SLEEPING_AUTO_MIN_TICKS)) {
            return false;
        }
        if (AUTO_ADOPTED.add(be.getClass())) {
            Fettle.LOGGER.info("[Fettle] BE sleeping: {} adopted from observation (>= {} ticks, no setChanged, no sendData)",
                    be.getClass().getName(), HotFlags.BE_SLEEPING_AUTO_MIN_TICKS);
        }
        return true;
    }

    /**
     * Whitelist entries accept: a simple name, a fully qualified name, a wildcard pattern
     * ({@code *HopperBlockEntity}, {@code net.minecraft.world.level.block.entity.*}), a mod id with a
     * class pattern ({@code create:FactoryPanel*}) or a whole mod ({@code create:*}).
     *
     * <p>Results are cached per class and invalidated when the configured array changes, because this
     * runs for every ticked block entity.
     */
    private static boolean targetCache(Class<?> type) {
        String[] patterns = HotFlags.BE_SLEEPING_CLASSES;
        if (patterns != cachedPatterns) {
            cachedPatterns = patterns;
            TARGET_CACHE.clear();
        }
        Boolean cached = TARGET_CACHE.get(type);
        if (cached != null) {
            return cached;
        }
        boolean result = match(patterns, type);
        TARGET_CACHE.put(type, result);
        return result;
    }

    private static boolean match(String[] patterns, Class<?> type) {
        String simple = type.getSimpleName();
        String qualified = type.getName();
        String pkg = type.getPackage() == null ? "" : type.getPackage().getName();
        for (String pattern : patterns) {
            if (pattern.equals(simple) || pattern.equals(qualified)) {
                return true;
            }
            int colon = pattern.indexOf(':');
            if (colon > 0) {
                String modId = pattern.substring(0, colon);
                String rest = pattern.substring(colon + 1);
                if (modClasses(modId).contains(qualified) || packageLooksLike(qualified, pkg, modId)) {
                    if (rest.isEmpty() || "*".equals(rest) || wildcard(rest, simple)
                            || wildcard(rest, qualified)) {
                        return true;
                    }
                }
                continue;
            }
            if (pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0) {
                if (wildcard(pattern, simple) || wildcard(pattern, qualified) || wildcard(pattern, pkg)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Fallback when the mod's class list cannot be read: package name carries the mod id. */
    private static boolean packageLooksLike(String qualified, String pkg, String modId) {
        return pkg.startsWith(modId + ".") || pkg.contains("." + modId + ".") || pkg.endsWith("." + modId)
                || qualified.contains("." + modId + ".");
    }

    private static boolean wildcard(String pattern, String candidate) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '.', '(', ')', '[', ']', '+', '^', '$', '|', '\\' -> regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        return candidate.matches(regex.toString());
    }

    /** Class names of a mod, resolved once per mod id through FML's scan result (best effort). */
    private static Set<String> modClasses(String modId) {
        return MOD_CLASSES.computeIfAbsent(modId, id -> {
            Set<String> names = new java.util.HashSet<>();
            try {
                net.neoforged.fml.ModList list = net.neoforged.fml.ModList.get();
                Object info = list == null ? null : list.getModFileById(id);
                if (info == null) {
                    return names;
                }
                Object file = info.getClass().getMethod("getFile").invoke(info);
                Object scan = file.getClass().getMethod("getScanResult").invoke(file);
                Object classes = scan.getClass().getMethod("getClasses").invoke(scan);
                if (classes instanceof java.util.Collection<?> collection) {
                    for (Object entry : collection) {
                        String name = entry instanceof String text ? text : null;
                        if (name == null && entry != null) {
                            try {
                                name = (String) entry.getClass().getMethod("getName").invoke(entry);
                            } catch (Throwable ignored) {
                                name = null;
                            }
                        }
                        if (name != null) {
                            names.add(name.replace('/', '.'));
                        }
                    }
                }
            } catch (Throwable ignored) {
                // best effort only: the package fallback and wildcard patterns still work
            }
            return names;
        });
    }

    /**
     * Heartbeat self-check (the answer to "what if the tick does time-based work without ever calling
     * setChanged?"): once per heartbeat, compare the block entity's serialised state with the one from the
     * previous heartbeat. A class whose state keeps moving while its tick is being skipped is doing
     * per-tick work - a furnace advances its cook progress exactly like that - so it is blacklisted and
     * woken instead of being silently slowed to one tick in twenty.
     *
     * <p>One sample per class per heartbeat keeps the cost negligible (no per-instance NBT).
     */
    private static void heartbeatCheck(BlockEntity be) {
        if (be.getLevel() == null) {
            return;
        }
        String name = className(be);
        try {
            net.minecraft.nbt.CompoundTag tag = be.saveWithoutMetadata(be.getLevel().registryAccess());
            int hash = tag.toString().hashCode();
            Integer previous = HEARTBEAT_STATE.put(name, hash);
            if (previous != null && previous != hash && BLACKLIST.add(name)) {
                HEARTBEAT_STATE.remove(name);
                Fettle.LOGGER.info("[Fettle] BE sleeping: {} blacklisted - its state moved across heartbeats without calling setChanged (time-based work)",
                        name);
            }
        } catch (Throwable ignored) {
            // best effort: never let the safety check break the tick path
        }
    }

    /**
     * Observation-phase counterpart of {@link #heartbeatCheck}: sampled once per class per observed tick.
     * Detecting per-tick work here is what makes the pilot lossless for classes like the furnace - they
     * are rejected while they are still being ticked normally, instead of being slept first and caught
     * by the heartbeat check afterwards.
     */
    private static void observeCheck(BlockEntity be) {
        if (be.getLevel() == null) {
            return;
        }
        String name = className(be);
        try {
            net.minecraft.nbt.CompoundTag tag = be.saveWithoutMetadata(be.getLevel().registryAccess());
            int hash = tag.toString().hashCode();
            Integer previous = OBSERVE_STATE.put(name, hash);
            if (previous != null && previous != hash && BLACKLIST.add(name)) {
                OBSERVE_STATE.remove(name);
                Fettle.LOGGER.info("[Fettle] BE sleeping: {} blacklisted - its state moves while it is still being ticked (per-tick work, never slept, no tick lost)",
                        name);
            }
        } catch (Throwable ignored) {
            // best effort: never let the safety check break the tick path
        }
    }

    /** Last serialised-state hash per class during observation. */
    private static final Map<String, Integer> OBSERVE_STATE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Last serialised-state hash per class, sampled once per heartbeat. */
    private static final Map<String, Integer> HEARTBEAT_STATE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Classes adopted by {@link #autoAdopted(BlockEntity)}, logged once each. */
    private static final java.util.Set<Class<?>> AUTO_ADOPTED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static long[] stats(BlockEntity be) {
        return CLASS_STATS.computeIfAbsent(className(be), $ -> new long[5]);
    }

    /**
     * @return true when the vanilla tick should run, false to skip this tick.
     *         Never throws: any failure disables the pilot (auto-blacklist).
     */
    public static boolean shouldTick(BlockEntity be) {
        if (!enabled() || !(be instanceof BeSleepState state)) {
            return true;
        }
        // Server-side optimisation: never touch client-side block entity ticks.
        if (be.getLevel() != null && be.getLevel().isClientSide()) {
            return true;
        }
        try {
            if (!targeted(be)) {
                wake(be);
                return true;
            }
            long[] stat = stats(be);
            if (state.fettle$sleeping()) {
                int skipped = state.fettle$skippedTicks() + 1;
                if (skipped >= maxSkipTicks()) {
                    // heartbeat: run exactly one tick, then keep sleeping
                    state.fettle$skippedTicks(0);
                    stat[2]++;
                    stat[0]++;
                    if (HotFlags.BE_SLEEPING_HEARTBEAT_CHECK) {
                        heartbeatCheck(be);
                    }
                    return true;
                }
                state.fettle$skippedTicks(skipped);
                stat[1]++;
                return false;
            }

            int observed = state.fettle$observedTicks() + 1;
            state.fettle$observedTicks(observed);
            if (HotFlags.BE_SLEEPING_HEARTBEAT_CHECK) {
                // Zero-loss guard: while we are still ticking the instance normally, watch its serialised
                // state. A class that moves state on its own (a furnace advancing burn/cook progress) is
                // therefore rejected BEFORE it is ever put to sleep, so no tick is lost at all.
                observeCheck(be);
            }
            if (state.fettle$sawChange()) {
                state.fettle$sawChange(false);
                state.fettle$observedTicks(0);
                stat[4]++;
                blacklistIfNotIdle(be, stat);
                stat[0]++;
                return true;
            }
            if (observed >= observeTicks()) {
                state.fettle$sleeping(true);
                state.fettle$skippedTicks(0);
                register(be);
                stat[1]++;
                return false;
            }
            stat[0]++;
            return true;
        } catch (Throwable t) {
            disabledByFailure = true;
            blacklistEvents.incrementAndGet();
            Fettle.LOGGER.warn("[Fettle] BE sleeping disabled after an error on the skip path", t);
            return true;
        }
    }

    /** Called from {@code BlockEntity#setChanged}: anything observable wakes the instance. */
    public static void noteChanged(BlockEntity be) {
        if (!enabled() || !(be instanceof BeSleepState state)) {
            return;
        }
        state.fettle$sawChange(true);
        if (state.fettle$sleeping() && wake(be)) {
            wakesByChange.incrementAndGet();
            long[] stat = stats(be);
            stat[3]++;
            if (stat[3] >= HotFlags.BE_SLEEPING_BLACKLIST_WAKES) {
                blacklist(be, "woken " + stat[3] + " times");
            }
        }
    }

    private static void blacklistIfNotIdle(BlockEntity be, long[] stat) {
        if (stat[1] == 0 && stat[4] >= HotFlags.BE_SLEEPING_BLACKLIST_WAKES) {
            blacklist(be, "never stayed idle (" + stat[4] + " observation resets)");
        }
    }

    private static void blacklist(BlockEntity be, String reason) {
        String name = className(be);
        if (BLACKLIST.add(name)) {
            blacklistEvents.incrementAndGet();
            Fettle.LOGGER.info("[Fettle] BE sleeping: {} removed from the whitelist for this run ({})",
                    name, reason);
        }
    }
}
