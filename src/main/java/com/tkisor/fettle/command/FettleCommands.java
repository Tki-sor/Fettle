package com.tkisor.fettle.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.tkisor.fettle.Fettle;
import com.tkisor.fettle.bench.TickRecorder;
import com.tkisor.fettle.config.FettleConfig;
import com.tkisor.fettle.recipe.RecipeParityCheck;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Server-side helper commands.
 *
 * <pre>
 * /fettle bench start &lt;tag&gt;   arm the per-tick recorder
 * /fettle bench stop          write fettle-ticks-&lt;tag&gt;.csv
 * /fettle bench status
 * /fettle selftest            run the recipe parity check now
 * </pre>
 */
@EventBusSubscriber(modid = Fettle.MOD_ID)
public final class FettleCommands {

    private FettleCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fettle")
                .then(Commands.literal("bench")
                        .then(Commands.literal("start")
                                .then(Commands.argument("tag", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String message = TickRecorder.start(StringArgumentType.getString(ctx, "tag"));
                                            return reply(ctx.getSource(), message);
                                        })))
                        .then(Commands.literal("stop")
                                .executes(ctx -> reply(ctx.getSource(), TickRecorder.stop())))
                        .then(Commands.literal("status")
                                .executes(ctx -> reply(ctx.getSource(), TickRecorder.status()))))
                .then(Commands.literal("feature")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "name");
                                            String value = StringArgumentType.getString(ctx, "value");
                                            try {
                                                int number = Integer.parseInt(value);
                                                return reply(ctx.getSource(), FettleConfig.setIntOverride(name, number));
                                            } catch (NumberFormatException e) {
                                                return reply(ctx.getSource(),
                                                        FettleConfig.setBoolOverride(name, Boolean.parseBoolean(value)));
                                            }
                                        })))
                        .executes(ctx -> reply(ctx.getSource(), FettleConfig.overrideState())))
                .then(Commands.literal("micro")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("iterations", IntegerArgumentType.integer(1_000, 20_000_000))
                                        .executes(ctx -> reply(ctx.getSource(), runMicro(
                                                StringArgumentType.getString(ctx, "name"),
                                                ctx.getSource().getLevel(),
                                                IntegerArgumentType.getInteger(ctx, "iterations")))))))
                .then(Commands.literal("idle")
                        .then(Commands.literal("report").executes(ctx -> reply(ctx.getSource(), idleReport())))
                        .then(Commands.literal("candidates")
                                .then(Commands.argument("minTicks", IntegerArgumentType.integer(1))
                                        .executes(ctx -> reply(ctx.getSource(), idleCandidates(
                                                IntegerArgumentType.getInteger(ctx, "minTicks"))))))
                        .then(Commands.literal("sleeptest").executes(ctx -> reply(ctx.getSource(), sleepReport())))
                        .then(Commands.literal("dump").executes(ctx -> {
                            try {
                                return reply(ctx.getSource(), (String) Class.forName("com.tkisor.fettle.observe.BehaviourProbe")
                                        .getMethod("dump").invoke(null));
                            } catch (Throwable t) {
                                return reply(ctx.getSource(), "idle dump failed: " + t);
                            }
                        }))
                        .then(Commands.literal("wake").executes(ctx -> {
                            try {
                                Class.forName("com.tkisor.fettle.observe.BeSleepController")
                                        .getMethod("suspend").invoke(null);
                                return reply(ctx.getSource(), "BE sleeping suspended; every instance will tick again");
                            } catch (Throwable t) {
                                return reply(ctx.getSource(), "wake failed: " + t);
                            }
                        }))
                        .then(Commands.literal("reset").executes(ctx -> {
                            try {
                                Class.forName("com.tkisor.fettle.observe.BehaviourProbe")
                                        .getMethod("reset").invoke(null);
                                return reply(ctx.getSource(), "observation counters reset");
                            } catch (Throwable t) {
                                return reply(ctx.getSource(), "idle reset failed: " + t);
                            }
                        })))
                .then(Commands.literal("debug")
                        .then(Commands.literal("trains")
                                .executes(ctx -> reply(ctx.getSource(), runCollisionTest(ctx.getSource().getLevel(), 1)))
                                .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 100))
                                        .executes(ctx -> reply(ctx.getSource(), runCollisionTest(
                                                ctx.getSource().getLevel(),
                                                IntegerArgumentType.getInteger(ctx, "iterations"))))))
                        .then(Commands.literal("kinetics")
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 2000))
                                        .executes(ctx -> reply(ctx.getSource(), placeShafts(
                                                ctx.getSource().getLevel(),
                                                BlockPos.containing(ctx.getSource().getPosition()),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                        .then(Commands.literal("kinetictest")
                                .executes(ctx -> reply(ctx.getSource(), runKineticTest(ctx.getSource().getLevel(), 1)))
                                .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 200))
                                        .executes(ctx -> reply(ctx.getSource(), runKineticTest(
                                                ctx.getSource().getLevel(),
                                                IntegerArgumentType.getInteger(ctx, "iterations"))))))
                        .then(Commands.literal("cap")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> reply(ctx.getSource(), queryCapability(
                                                                ctx.getSource().getLevel(),
                                                                new BlockPos(
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                                        IntegerArgumentType.getInteger(ctx, "z")))))))))
                        .then(Commands.literal("hoppers")
                                .then(Commands.argument("side", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> reply(ctx.getSource(), placeHoppers(
                                                ctx.getSource().getLevel(),
                                                net.minecraft.core.BlockPos.containing(
                                                        ctx.getSource().getPosition()),
                                                IntegerArgumentType.getInteger(ctx, "side"), 2)))))
                        .then(Commands.literal("sleep")
                                .then(Commands.argument("class", StringArgumentType.word())
                                        .executes(ctx -> reply(ctx.getSource(), startSleep(
                                                StringArgumentType.getString(ctx, "class"), 1200, 20)))
                                        .then(Commands.argument("observe", IntegerArgumentType.integer(1, 100000))
                                                .then(Commands.argument("maxSkip", IntegerArgumentType.integer(1, 1000))
                                                        .executes(ctx -> reply(ctx.getSource(), startSleep(
                                                                StringArgumentType.getString(ctx, "class"),
                                                                IntegerArgumentType.getInteger(ctx, "observe"),
                                                                IntegerArgumentType.getInteger(ctx, "maxSkip"))))))))
                        .then(Commands.literal("pipenet")
                                .then(Commands.argument("radius", IntegerArgumentType.integer(2, 24))
                                        .executes(ctx -> reply(ctx.getSource(), createDebug(
                                                "placePipeNetwork", ctx.getSource().getLevel(),
                                                BlockPos.containing(ctx.getSource().getPosition()),
                                                IntegerArgumentType.getInteger(ctx, "radius"))))))
                        .then(Commands.literal("fluidpropagate")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 200))
                                                                .executes(ctx -> reply(ctx.getSource(), createDebug(
                                                                        "fluidPropagateBench", ctx.getSource().getLevel(),
                                                                        new BlockPos(IntegerArgumentType.getInteger(ctx, "x"),
                                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                                IntegerArgumentType.getInteger(ctx, "z")),
                                                                        IntegerArgumentType.getInteger(ctx, "iterations")))))))))
                        .then(Commands.literal("fluidsearch")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 500))
                                                                .executes(ctx -> reply(ctx.getSource(), fluidSearchBench(
                                                                        ctx.getSource().getLevel(),
                                                                        new BlockPos(IntegerArgumentType.getInteger(ctx, "x"),
                                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                                IntegerArgumentType.getInteger(ctx, "z")),
                                                                        IntegerArgumentType.getInteger(ctx, "iterations")))))))))
                        .then(Commands.literal("fluidcheck")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> reply(ctx.getSource(), fluidCheck(
                                                                ctx.getSource().getLevel(),
                                                                new BlockPos(IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                                        IntegerArgumentType.getInteger(ctx, "z")))))))))
                        .then(Commands.literal("fluidscene")
                                .then(Commands.argument("radius", IntegerArgumentType.integer(2, 24))
                                        .executes(ctx -> reply(ctx.getSource(), buildFluidScene(
                                                ctx.getSource().getLevel(),
                                                BlockPos.containing(ctx.getSource().getPosition()),
                                                IntegerArgumentType.getInteger(ctx, "radius"))))))
                        .then(Commands.literal("tp")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> reply(ctx.getSource(), teleportPlayer(
                                                                ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                IntegerArgumentType.getInteger(ctx, "z"))))))))
                        .then(Commands.literal("panelwire")
                                .then(Commands.argument("side", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("spacing", IntegerArgumentType.integer(2, 16))
                                                .executes(ctx -> reply(ctx.getSource(), wirePanelsAlive(
                                                        ctx.getSource().getLevel(),
                                                        BlockPos.containing(ctx.getSource().getPosition()),
                                                        IntegerArgumentType.getInteger(ctx, "side"),
                                                        IntegerArgumentType.getInteger(ctx, "spacing")))))))
                        .then(Commands.literal("panelpack")
                                .then(Commands.argument("side", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("spacing", IntegerArgumentType.integer(2, 16))
                                                .executes(ctx -> reply(ctx.getSource(), placePanelPackagers(
                                                        ctx.getSource().getLevel(),
                                                        BlockPos.containing(ctx.getSource().getPosition()),
                                                        IntegerArgumentType.getInteger(ctx, "side"),
                                                        IntegerArgumentType.getInteger(ctx, "spacing")))))))
                        .then(Commands.literal("panels")
                                .then(Commands.argument("side", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("spacing", IntegerArgumentType.integer(2, 16))
                                                .executes(ctx -> reply(ctx.getSource(), placePanels(
                                                        ctx.getSource().getLevel(),
                                                        BlockPos.containing(ctx.getSource().getPosition()),
                                                        IntegerArgumentType.getInteger(ctx, "side"),
                                                        IntegerArgumentType.getInteger(ctx, "spacing"))))))))
                .then(Commands.literal("selftest")
                        .executes(ctx -> {
                            RecipeParityCheck.run(ctx.getSource().getServer());
                            return reply(ctx.getSource(), "recipe parity self-test finished (see log)");
                        }))
                .then(Commands.literal("status")
                        .executes(ctx -> reply(ctx.getSource(), "enabled=" + FettleConfig.enabled()
                                + " create=" + FettleConfig.createEnabled()
                                + " panelInterval=" + FettleConfig.createFactoryPanelInterval()))));
    }

    /** Loaded reflectively so this class does not link against Create when Create is absent. */
    private static String runMicro(String name, net.minecraft.server.level.ServerLevel level, int iterations) {
        try {
            Class<?> benchmarks = Class.forName("com.tkisor.fettle.bench.CreateMicroBenchmarks");
            return (String) benchmarks.getMethod("run", String.class,
                    net.minecraft.server.level.ServerLevel.class, int.class)
                    .invoke(null, name, level, iterations);
        } catch (ClassNotFoundException e) {
            return "micro benchmark '" + name + "' needs Create (not installed)";
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return "micro benchmark failed: " + cause;
        }
    }

    @SuppressWarnings("unchecked")
    private static String idleCandidates(int minTicks) {
        try {
            Class<?> probe = Class.forName("com.tkisor.fettle.observe.BehaviourProbe");
            java.util.List<String> lines = (java.util.List<String>) probe.getMethod("candidates", int.class)
                    .invoke(null, minTicks);
            return "sleep candidates (ticks>=" + minTicks + ", changed=0, sendData=0): " + lines.size()
                    + "\n  " + String.join("\n  ", lines);
        } catch (Throwable t) {
            return "idle candidates failed: " + t;
        }
    }

    @SuppressWarnings("unchecked")
    private static String idleReport() {
        try {
            Class<?> probe = Class.forName("com.tkisor.fettle.observe.BehaviourProbe");
            java.util.List<String> lines = (java.util.List<String>) probe.getMethod("report", int.class)
                    .invoke(null, 15);
            int classes = (Integer) probe.getMethod("size").invoke(null);
            int idle = (Integer) probe.getMethod("idleClasses").invoke(null);
            return "observed classes=" + classes + " never-changed=" + idle + "\n  "
                    + String.join("\n  ", lines);
        } catch (Throwable t) {
            return "idle report failed: " + t;
        }
    }

    /** Asks for the item-handler capability at a position: exercises the capability wake condition. */
    private static String queryCapability(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        try {
            net.minecraft.world.level.block.entity.BlockEntity entity = level.getBlockEntity(pos);
            Object handler = net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK
                    .getCapability(level, pos, level.getBlockState(pos), entity, null);
            return "capability query at " + pos.toShortString() + " on "
                    + (entity == null ? "no block entity" : entity.getClass().getSimpleName())
                    + " -> " + (handler == null ? "null" : handler.getClass().getSimpleName());
        } catch (Throwable t) {
            return "capability query failed: " + t;
        }
    }

    private static String placeHoppers(net.minecraft.server.level.ServerLevel level,
                                       net.minecraft.core.BlockPos origin, int side, int spacing) {
        try {
            Class<?> scenarios = Class.forName("com.tkisor.fettle.bench.VanillaScenarios");
            return (String) scenarios.getMethod("placeHoppers",
                            net.minecraft.server.level.ServerLevel.class, net.minecraft.core.BlockPos.class,
                            int.class, int.class)
                    .invoke(null, level, origin, side, spacing);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return "debug hoppers failed: " + cause;
        }
    }

    private static String startSleep(String className, int observeTicks, int maxSkipTicks) {
        try {
            Class<?> controller = Class.forName("com.tkisor.fettle.observe.BeSleepController");
            controller.getMethod("enableDebug", String.class, int.class, int.class)
                    .invoke(null, className, observeTicks, maxSkipTicks);
            return "BE sleeping enabled for " + className + " (observe=" + observeTicks
                    + ", maxSkip=" + maxSkipTicks + ")";
        } catch (Throwable t) {
            return "sleep start failed: " + t;
        }
    }

    private static String sleepReport() {
        try {
            Class<?> controller = Class.forName("com.tkisor.fettle.observe.BeSleepController");
            return (String) controller.getMethod("report").invoke(null);
        } catch (Throwable t) {
            return "sleep report failed: " + t;
        }
    }

    private static String runKineticTest(net.minecraft.server.level.ServerLevel level, int iterations) {
        try {
            Class<?> scenarios = Class.forName("com.tkisor.fettle.bench.KineticDebugScenarios");
            return (String) scenarios.getMethod("incrementalDifferential",
                            net.minecraft.server.level.ServerLevel.class, int.class)
                    .invoke(null, level, iterations);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return "debug kinetictest failed: " + cause;
        }
    }

    private static String placeShafts(net.minecraft.server.level.ServerLevel level, BlockPos origin, int count) {
        try {
            Class<?> scenarios = Class.forName("com.tkisor.fettle.bench.CreateDebugScenarios");
            return (String) scenarios.getMethod("placeShaftLine",
                            net.minecraft.server.level.ServerLevel.class, BlockPos.class, int.class, int.class)
                    .invoke(null, level, origin, count, 0);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return "debug kinetics failed: " + cause;
        }
    }

    private static String runCollisionTest(net.minecraft.server.level.ServerLevel level, int iterations) {
        try {
            Class<?> scenarios = Class.forName("com.tkisor.fettle.bench.TrainDebugScenarios");
            return (String) scenarios.getMethod("differentialCollisionTest",
                            net.minecraft.server.level.ServerLevel.class, int.class)
                    .invoke(null, level, iterations);
        } catch (ClassNotFoundException e) {
            return "debug trains needs Create (not installed)";
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return "debug trains failed: " + cause;
        }
    }

    /** Debug helper: move the executing player. Works without cheats, unlike the vanilla command. */
    private static String teleportPlayer(CommandSourceStack source, int x, int y, int z) {
        net.minecraft.server.level.ServerPlayer player = source.getPlayer();
        if (player == null) {
            return "debug tp needs a player";
        }
        player.teleportTo(source.getLevel(), x + 0.5, y, z + 0.5, player.getYRot(), player.getXRot());
        return "teleported to " + x + " " + y + " " + z;
    }

    private static String buildFluidScene(net.minecraft.server.level.ServerLevel level, BlockPos origin, int radius) {
        try {
            Class<?> scenarios = Class.forName("com.tkisor.fettle.bench.CreateDebugScenarios");
            return (String) scenarios.getMethod("buildFluidScene",
                            net.minecraft.server.level.ServerLevel.class, BlockPos.class, int.class)
                    .invoke(null, level, origin, radius);
        } catch (ClassNotFoundException e) {
            return "debug fluidscene needs Create (not installed)";
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return "debug fluidscene failed: " + cause;
        }
    }

    /** Generic bridge to a CreateDebugScenarios method (ServerLevel, BlockPos, int) -> String. */
    private static String createDebug(String method, net.minecraft.server.level.ServerLevel level, BlockPos pos, int arg) {
        try {
            Class<?> scenarios = Class.forName("com.tkisor.fettle.bench.CreateDebugScenarios");
            return (String) scenarios.getMethod(method, net.minecraft.server.level.ServerLevel.class, BlockPos.class, int.class)
                    .invoke(null, level, pos, arg);
        } catch (ClassNotFoundException e) {
            return "debug " + method + " needs Create (not installed)";
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return "debug " + method + " failed: " + cause;
        }
    }

    private static String fluidSearchBench(net.minecraft.server.level.ServerLevel level, BlockPos pos, int iterations) {
        try {
            Class<?> scenarios = Class.forName("com.tkisor.fettle.bench.CreateDebugScenarios");
            return (String) scenarios.getMethod("fluidSearchBench",
                            net.minecraft.server.level.ServerLevel.class, BlockPos.class, int.class)
                    .invoke(null, level, pos, iterations);
        } catch (ClassNotFoundException e) {
            return "debug fluidsearch needs Create (not installed)";
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return "debug fluidsearch failed: " + cause;
        }
    }

    private static String fluidCheck(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        try {
            Class<?> scenarios = Class.forName("com.tkisor.fettle.bench.CreateDebugScenarios");
            return (String) scenarios.getMethod("fluidCheck",
                            net.minecraft.server.level.ServerLevel.class, BlockPos.class)
                    .invoke(null, level, pos);
        } catch (ClassNotFoundException e) {
            return "debug fluidcheck needs Create (not installed)";
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return "debug fluidcheck failed: " + cause;
        }
    }

    private static String wirePanelsAlive(net.minecraft.server.level.ServerLevel level, BlockPos origin,
                                         int side, int spacing) {
        try {
            Class<?> scenarios = Class.forName("com.tkisor.fettle.bench.CreateDebugScenarios");
            return (String) scenarios.getMethod("wirePanelsAlive",
                            net.minecraft.server.level.ServerLevel.class, BlockPos.class, int.class, int.class)
                    .invoke(null, level, origin, side, spacing);
        } catch (ClassNotFoundException e) {
            return "debug panelwire needs Create (not installed)";
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return "debug panelwire failed: " + cause;
        }
    }

    private static String placePanelPackagers(net.minecraft.server.level.ServerLevel level, BlockPos origin,
                                             int side, int spacing) {
        try {
            Class<?> scenarios = Class.forName("com.tkisor.fettle.bench.CreateDebugScenarios");
            return (String) scenarios.getMethod("placePanelPackagers",
                            net.minecraft.server.level.ServerLevel.class, BlockPos.class, int.class, int.class)
                    .invoke(null, level, origin, side, spacing);
        } catch (ClassNotFoundException e) {
            return "debug panelpack needs Create (not installed)";
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return "debug panelpack failed: " + cause;
        }
    }

    private static String placePanels(net.minecraft.server.level.ServerLevel level, BlockPos origin,
                                      int side, int spacing) {
        try {
            Class<?> scenarios = Class.forName("com.tkisor.fettle.bench.CreateDebugScenarios");
            return (String) scenarios.getMethod("placePanels",
                            net.minecraft.server.level.ServerLevel.class, BlockPos.class, int.class, int.class)
                    .invoke(null, level, origin, side, spacing);
        } catch (ClassNotFoundException e) {
            return "debug panels needs Create (not installed)";
        } catch (Throwable t) {
            return "debug panels failed: " + t;
        }
    }

    private static int reply(CommandSourceStack source, String message) {
        Fettle.LOGGER.info("[Fettle] {}", message);
        source.sendSuccess(() -> Component.literal("[Fettle] " + message), false);
        return 1;
    }
}
