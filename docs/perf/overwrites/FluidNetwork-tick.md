# Overwrite 候选：`FluidNetwork.tick`

> 目标：`src/main/java/com/simibubi/create/content/fluids/FluidNetwork.java`
> 关联 finding：[05-fluids.md](../05-fluids.md) F5
> 公共约定见 [01-mixin-playbook.md](../01-mixin-playbook.md)

## 0. 事实核对（与原始 finding 的差异）

逐行核对 `FluidNetwork.java:65-268` 后确认：

| 原始 finding 的说法 | 实际情况 |
| --- | --- |
| `new IdentityHashMap<>`（:185）「simulate+execute 两遍各一次」 | **错**。`:185` 在 `for (boolean simulate : Iterate.trueAndFalse)`（`:187`）**之外** → 每 tick **1 次**；`getOrDefault` 的 `0` 装箱在 `:247`（每次 attempt 一次） |
| `new ArrayList<>(targets)`（:219） | **对**。在 simulate 循环**之内**，最多 2 次/tick；但 `:214` 的 `if (transfer.isEmpty()) return;` 会短路，所以是「最多 2 次」 |
| `:116` 每边一个 `BlockFace` | **对**。`:113` 的 `for (Direction side : Iterate.directions)` 在 `for (int cycle = 0; cycle < CYCLES_PER_TICK; cycle++)`（`:71`，CYCLES_PER_TICK = 16）之内 → 每 tick 最多 `16 × frontier.size() × 6` 次 `new BlockFace` |

结论：本方法的最大分配项是 `:116` 的 `BlockFace`（每 tick 可达数百到数千个），其次是 `:219` 与 `:185`。

## 1. 原方法完整源码（`FluidNetwork.java:65-268`）

```java
	public void tick() {
		if (pauseBeforePropagation > 0) {
			pauseBeforePropagation--;
			return;
		}

		for (int cycle = 0; cycle < CYCLES_PER_TICK; cycle++) {
			boolean shouldContinue = false;
			for (Iterator<BlockFace> iterator = queued.iterator(); iterator.hasNext();) {
				BlockFace blockFace = iterator.next();
				if (!isPresent(blockFace))
					continue;
				PipeConnection pipeConnection = get(blockFace);
				if (pipeConnection != null) {
					if (blockFace.equals(start))
						transferSpeed = (int) Math.max(1, pipeConnection.pressure.get(true) / 2f);
					frontier.add(Pair.of(blockFace, pipeConnection));
				}
				iterator.remove();
			}

			for (Iterator<Pair<BlockFace, PipeConnection>> iterator = frontier.iterator(); iterator.hasNext();) {
				Pair<BlockFace, PipeConnection> pair = iterator.next();
				BlockFace blockFace = pair.getFirst();
				PipeConnection pipeConnection = pair.getSecond();

				if (!pipeConnection.hasFlow())
					continue;

				Flow flow = pipeConnection.flow.get();
				if (!fluid.isEmpty() && !FluidStack.isSameFluidSameComponents(flow.fluid, fluid)) {
					iterator.remove();
					continue;
				}
				if (!flow.inbound) {
					if (pipeConnection.comparePressure() >= 0)
						iterator.remove();
					continue;
				}
				if (!flow.complete)
					continue;

				if (fluid.isEmpty())
					fluid = flow.fluid;

				boolean canRemove = true;
				for (Direction side : Iterate.directions) {
					if (side == blockFace.getFace())
						continue;
					BlockFace adjacentLocation = new BlockFace(blockFace.getPos(), side);
					PipeConnection adjacent = get(adjacentLocation);
					if (adjacent == null)
						continue;
					if (!adjacent.hasFlow()) {
						// Branch could potentially still appear
						if (adjacent.hasPressure() && adjacent.pressure.getSecond() > 0)
							canRemove = false;
						continue;
					}
					Flow outFlow = adjacent.flow.get();
					if (outFlow.inbound) {
						if (adjacent.comparePressure() > 0)
							canRemove = false;
						continue;
					}
					if (!outFlow.complete) {
						canRemove = false;
						continue;
					}

					// Give pipe end a chance to init connections
					if (!adjacent.source.isPresent() && !adjacent.determineSource(world, blockFace.getPos())) {
						canRemove = false;
						continue;
					}

					if (adjacent.source.isPresent() && adjacent.source.get()
						.isEndpoint()) {
						targets.add(Pair.of(adjacentLocation, adjacent.source.get()));
						continue;
					}

					if (visited.add(adjacentLocation.getConnectedPos())) {
						queued.add(adjacentLocation.getOpposite());
						shouldContinue = true;
					}
				}
				if (canRemove)
					iterator.remove();
			}
			if (!shouldContinue)
				break;
		}

		if (source == null)
			source = sourceSupplier.get();
		if (source == null)
			return;

		keepPortableFluidInterfaceEngaged();

		if (targets.isEmpty())
			return;
		for (Pair<BlockFace, FlowSource> pair : targets) {
			if (pair.getSecond() != null && world.getGameTime() % 40 != 0)
				continue;
			PipeConnection pipeConnection = get(pair.getFirst());
			if (pipeConnection == null)
				continue;
			pipeConnection.source.ifPresent(fs -> {
				if (fs.isEndpoint())
					pair.setSecond(fs);
			});
		}

		int flowSpeed = transferSpeed;
		Map<IFluidHandler, Integer> accumulatedFill = new IdentityHashMap<>();

		for (boolean simulate : Iterate.trueAndFalse) {
			FluidAction action = simulate ? FluidAction.SIMULATE : FluidAction.EXECUTE;

			if (source == null)
				return;
			IFluidHandler sourceCap = source.getCapability();
			if (sourceCap == null)
				return;

			FluidStack transfer = FluidStack.EMPTY;
			for (int i = 0; i < sourceCap.getTanks(); i++) {
				FluidStack contained = sourceCap.getFluidInTank(i);
				if (contained.isEmpty())
					continue;
				if (!FluidStack.isSameFluidSameComponents(contained, fluid))
					continue;
				FluidStack toExtract = FluidHelper.copyStackWithAmount(contained, flowSpeed);
				transfer = sourceCap.drain(toExtract, action);
				break;
			}

			if (transfer.isEmpty()) {
				FluidStack genericExtract = sourceCap.drain(flowSpeed, action);
				if (!genericExtract.isEmpty() && FluidStack.isSameFluidSameComponents(genericExtract, fluid))
					transfer = genericExtract;
			}

			if (transfer.isEmpty())
				return;
			if (simulate)
				flowSpeed = transfer.getAmount();

			List<Pair<BlockFace, FlowSource>> availableOutputs = new ArrayList<>(targets);
			while (!availableOutputs.isEmpty() && transfer.getAmount() > 0) {
				int dividedTransfer = transfer.getAmount() / availableOutputs.size();
				int remainder = transfer.getAmount() % availableOutputs.size();

				for (Iterator<Pair<BlockFace, FlowSource>> iterator = availableOutputs.iterator(); iterator.hasNext();) {
					Pair<BlockFace, FlowSource> pair = iterator.next();
					int toTransfer = dividedTransfer;
					if (remainder > 0) {
						toTransfer++;
						remainder--;
					}

					if (transfer.isEmpty())
						break;
					@Nullable ICapabilityProvider<IFluidHandler> targetHandlerProvider = pair.getSecond().provideHandler();
					if (targetHandlerProvider == null) {
						iterator.remove();
						continue;
					}
					IFluidHandler targetHandler = targetHandlerProvider.getCapability();
					if (targetHandler == null) {
						iterator.remove();
						continue;
					}

					int simulatedTransfer = toTransfer;
					if (simulate)
						simulatedTransfer += accumulatedFill.getOrDefault(targetHandler, 0);

					FluidStack divided = transfer.copy();
					divided.setAmount(simulatedTransfer);
					int fill = targetHandler.fill(divided, action);

					if (simulate) {
						accumulatedFill.put(targetHandler, Integer.valueOf(fill));
						fill -= simulatedTransfer - toTransfer;
					}

					transfer.setAmount(transfer.getAmount() - fill);
					if (fill < simulatedTransfer)
						iterator.remove();
				}

			}

			flowSpeed -= transfer.getAmount();
			transfer = FluidStack.EMPTY;
		}
	}
```

> 注：源码中 `:86`、`:161`、`:270-282` 是注释掉的 debug 代码，上面已省略（不影响语义）。

## 2. 改后实现

### 2.1 轻量方案：`@Redirect` 打在 `NEW` 上（不重写方法）

**先说清一个关键区别**（手册 §3.1 的陷阱不适用于这里）：手册警告「`@Redirect` 无法消除参数表达式里的分配」，
指的是 `getOrDefault(key, new HashMap<>())` 这种 **`new` 作为实参**的情形——实参在调用前已被求值。
而本方法里 `new IdentityHashMap<>()` / `new ArrayList<>(targets)` 是**赋值语句右侧的独立表达式**，
`@Redirect(at = @At("NEW", target = "..."))` 会**整体替换 NEW + INVOKESPECIAL 两条指令**，
分配被真正消除。此时 handler 必须自己完成构造（拿到的参数就是原构造器的参数）。

```java
package com.example.createperf.mixin.fluids;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.simibubi.create.content.fluids.FlowSource;
import com.simibubi.create.content.fluids.FluidNetwork;

import net.createmod.catnip.data.Pair;
import net.createmod.catnip.math.BlockFace;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

@Mixin(value = FluidNetwork.class, remap = false)
public abstract class FluidNetworkMixin {

	@Unique
	private final IdentityHashMap<IFluidHandler, Integer> createperf$fillScratch = new IdentityHashMap<>();

	@Unique
	private final ArrayList<Pair<BlockFace, FlowSource>> createperf$outputScratch = new ArrayList<>();

	/** FluidNetwork.java:185 —— 每 tick 一次的累计填充表。 */
	@Redirect(method = "tick()V", at = @At(value = "NEW", target = "java/util/IdentityHashMap"), remap = false)
	private IdentityHashMap<IFluidHandler, Integer> createperf$reuseFillMap() {
		createperf$fillScratch.clear(); // 必须清空：原语义是「每 tick 一个空 map」
		return createperf$fillScratch;
	}

	/** FluidNetwork.java:219 —— simulate / execute 各一次的可用输出副本。 */
	@Redirect(method = "tick()V", at = @At(value = "NEW", target = "java/util/ArrayList"), remap = false)
	private ArrayList<Pair<BlockFace, FlowSource>> createperf$reuseOutputList(
		Collection<Pair<BlockFace, FlowSource>> source) {
		createperf$outputScratch.clear();
		createperf$outputScratch.addAll(source); // 原构造器 ArrayList(Collection) 已被替换，语义要自己补上
		return createperf$outputScratch;
	}
}
```

**重入风险（必须知道）**：`tick()` 的 EXECUTE 分支会调用第三方 `IFluidHandler.fill`。
若某个第三方实现回头触发**同一个** `FluidNetwork` 实例的 `tick()`，内层会把 `createperf$fillScratch` 清空，
导致外层 simulate 阶段累计的填充量丢失（静默少输送）。Create 自身的 `SmartFluidTank` / `FlowSource.*` 不会重入；
若你不接受这个理论风险，用 §2.2 的 `@Overwrite`（内置重入守卫）或加下面这个守卫：

```java
	@Unique private boolean createperf$active;
	@Unique private boolean createperf$reentrant;

	@Inject(method = "tick()V", at = @At("HEAD"), remap = false)
	private void createperf$enter(CallbackInfo ci) {
		createperf$reentrant = createperf$active;
		createperf$active = true;
	}

	// tick() 有 5 处 return，@At("RETURN") 会在每一处 return 之前注入
	@Inject(method = "tick()V", at = @At("RETURN"), remap = false)
	private void createperf$exit(CallbackInfo ci) {
		if (!createperf$reentrant)
			createperf$active = false;
	}
	// 然后在两个 @Redirect handler 里：if (createperf$reentrant) return new IdentityHashMap<>(); 等
```

### 2.2 完整方案：`@Overwrite`（同时消除 `:116` 的 `BlockFace` 分配）

`:116` 的 `new BlockFace(blockFace.getPos(), side)` 每 tick 可达数百到数千次，是本方法最大的分配项。
它只在两处逃逸（`:145` 进 `targets`、`:150` 经 `getOpposite()` 进 `queued`），
所以把「查表」改成不构造 `BlockFace` 的路径、只在真正需要保存时构造，就能把这一步的分配降到接近 0。
这需要重写整个方法（`BlockFace` 不可复用，`@Redirect` 无法安全替换）。

**伴生接口**（`PipeConnection.pressure/flow/source` 与 `FluidNetwork.get/getFluidTransfer` 都是包私有/私有，
跨包 javac 层面不可访问，必须走 `@Accessor`/`@Invoker`）：

```java
@Mixin(value = PipeConnection.class, remap = false)
public interface PipeConnectionAccessor {
	@Accessor("pressure") Couple<Float> createperf$getPressure();
	@Accessor("flow") Optional<PipeConnection.Flow> createperf$getFlow();
	@Accessor("source") Optional<FlowSource> createperf$getSource();
}

@Mixin(value = FluidNetwork.class, remap = false)
public interface FluidNetworkInvoker {
	// get / isPresent / keepPortableFluidInterfaceEngaged 在目标里是 private，
	// Java 不允许 "private abstract"，所以不能 @Shadow，必须用 @Invoker。
	@Invoker("get") PipeConnection createperf$invokeGet(BlockFace location);
	@Invoker("isPresent") boolean createperf$invokeIsPresent(BlockFace location);
	@Invoker("keepPortableFluidInterfaceEngaged") void createperf$invokeKeepAlive();
	@Invoker("getFluidTransfer") FluidTransportBehaviour createperf$getFluidTransfer(BlockPos pos);
}
```

```java
package com.example.createperf.mixin.fluids;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.simibubi.create.content.fluids.FlowSource;
import com.simibubi.create.content.fluids.FluidNetwork;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.PipeConnection.Flow;
import com.simibubi.create.foundation.ICapabilityProvider;
import com.simibubi.create.foundation.fluid.FluidHelper;

import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.data.Pair;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

@Mixin(value = FluidNetwork.class, remap = false)
public abstract class FluidNetworkOverwriteMixin {

	/** FluidNetwork.CYCLES_PER_TICK（:35，private static final，编译期常量，无法 @Shadow）。 */
	@Unique
	private static final int CREATEPERF_CYCLES_PER_TICK = 16;

	@Unique
	private final IdentityHashMap<IFluidHandler, Integer> createperf$fillScratch = new IdentityHashMap<>();

	@Unique
	private final ArrayList<Pair<BlockFace, FlowSource>> createperf$outputScratch = new ArrayList<>();

	/** 重入守卫：第三方 IFluidHandler 若回头再进同一个 FluidNetwork.tick()，内层改用临时对象。 */
	@Unique
	private boolean createperf$active;

	// 目标字段（FluidNetwork 中均为包私有；@Shadow 保持完全相同的修饰符）
	@Shadow Level world;
	@Shadow BlockFace start;
	@Shadow Supplier<@Nullable ICapabilityProvider<IFluidHandler>> sourceSupplier;
	@Shadow @Nullable ICapabilityProvider<IFluidHandler> source;
	@Shadow int transferSpeed;
	@Shadow int pauseBeforePropagation;
	@Shadow List<BlockFace> queued;
	@Shadow Set<Pair<BlockFace, PipeConnection>> frontier;
	@Shadow Set<BlockPos> visited;
	@Shadow FluidStack fluid;
	@Shadow List<Pair<BlockFace, FlowSource>> targets;

	/**
	 * @author createperf
	 * @reason 消除 :116 每边一次的 BlockFace 分配（改用 getFluidTransfer + getConnection 查表），
	 *         复用 :185 的 IdentityHashMap 与 :219 的 ArrayList，去掉 getOrDefault 的 Integer 装箱。
	 */
	@Overwrite(remap = false)
	public void tick() {
		FluidNetworkInvoker inv = (FluidNetworkInvoker) (Object) this;
		boolean reentrant = createperf$active;
		if (!reentrant)
			createperf$active = true;
		try {
			if (pauseBeforePropagation > 0) {
				pauseBeforePropagation--;
				return;
			}

			for (int cycle = 0; cycle < CREATEPERF_CYCLES_PER_TICK; cycle++) {
				boolean shouldContinue = false;
				for (Iterator<BlockFace> iterator = queued.iterator(); iterator.hasNext();) {
					BlockFace blockFace = iterator.next();
					if (!inv.createperf$invokeIsPresent(blockFace))
						continue;
					PipeConnection pipeConnection = inv.createperf$invokeGet(blockFace);
					if (pipeConnection != null) {
						if (blockFace.equals(start))
							transferSpeed = (int) Math.max(1,
								((PipeConnectionAccessor) pipeConnection).createperf$getPressure().get(true) / 2f);
						frontier.add(Pair.of(blockFace, pipeConnection));
					}
					iterator.remove();
				}

				for (Iterator<Pair<BlockFace, PipeConnection>> iterator = frontier.iterator(); iterator.hasNext();) {
					Pair<BlockFace, PipeConnection> pair = iterator.next();
					BlockFace blockFace = pair.getFirst();
					PipeConnection pipeConnection = pair.getSecond();

					if (!pipeConnection.hasFlow())
						continue;

					Flow flow = ((PipeConnectionAccessor) pipeConnection).createperf$getFlow().get();
					if (!fluid.isEmpty() && !FluidStack.isSameFluidSameComponents(flow.fluid, fluid)) {
						iterator.remove();
						continue;
					}
					if (!flow.inbound) {
						if (pipeConnection.comparePressure() >= 0)
							iterator.remove();
						continue;
					}
					if (!flow.complete)
						continue;

					if (fluid.isEmpty())
						fluid = flow.fluid;

					boolean canRemove = true;
					for (Direction side : Iterate.directions) {
						if (side == blockFace.getFace())
							continue;
						// 原 :116 在此 new BlockFace；查表只需 connected pos，省掉这次分配
						BlockPos adjacentPos = blockFace.getPos().relative(side);
						FluidTransportBehaviour adjacentBehaviour = inv.createperf$getFluidTransfer(adjacentPos);
						PipeConnection adjacent = adjacentBehaviour == null ? null
							: adjacentBehaviour.getConnection(side);
						if (adjacent == null)
							continue;
						PipeConnectionAccessor adjacentAccess = (PipeConnectionAccessor) adjacent;
						if (!adjacent.hasFlow()) {
							// Branch could potentially still appear
							if (adjacent.hasPressure() && adjacentAccess.createperf$getPressure().getSecond() > 0)
								canRemove = false;
							continue;
						}
						Flow outFlow = adjacentAccess.createperf$getFlow().get();
						if (outFlow.inbound) {
							if (adjacent.comparePressure() > 0)
								canRemove = false;
							continue;
						}
						if (!outFlow.complete) {
							canRemove = false;
							continue;
						}

						// Give pipe end a chance to init connections
						if (!adjacentAccess.createperf$getSource().isPresent()
							&& !adjacent.determineSource(world, blockFace.getPos())) {
							canRemove = false;
							continue;
						}

						// 注意：determineSource 可能刚刚写入 source，必须重新读取（原 :143 也是重新读）
						Optional<FlowSource> adjacentSource = adjacentAccess.createperf$getSource();
						if (adjacentSource.isPresent() && adjacentSource.get().isEndpoint()) {
							targets.add(Pair.of(new BlockFace(adjacentPos, side), adjacentSource.get()));
							continue;
						}

						if (visited.add(adjacentPos)) {
							queued.add(new BlockFace(adjacentPos, side.getOpposite()));
							shouldContinue = true;
						}
					}
					if (canRemove)
						iterator.remove();
				}
				if (!shouldContinue)
					break;
			}

			if (source == null)
				source = sourceSupplier.get();
			if (source == null)
				return;

			inv.createperf$invokeKeepAlive();

			if (targets.isEmpty())
				return;
			for (Pair<BlockFace, FlowSource> pair : targets) {
				if (pair.getSecond() != null && world.getGameTime() % 40 != 0)
					continue;
				PipeConnection pipeConnection = inv.createperf$invokeGet(pair.getFirst());
				if (pipeConnection == null)
					continue;
				((PipeConnectionAccessor) pipeConnection).createperf$getSource().ifPresent(fs -> {
					if (fs.isEndpoint())
						pair.setSecond(fs);
				});
			}

			int flowSpeed = transferSpeed;
			Map<IFluidHandler, Integer> accumulatedFill = reentrant ? new IdentityHashMap<>() : createperf$fillScratch;
			if (!reentrant)
				createperf$fillScratch.clear();

			for (boolean simulate : Iterate.trueAndFalse) {
				FluidAction action = simulate ? FluidAction.SIMULATE : FluidAction.EXECUTE;

				if (source == null)
					return;
				IFluidHandler sourceCap = source.getCapability();
				if (sourceCap == null)
					return;

				FluidStack transfer = FluidStack.EMPTY;
				for (int i = 0; i < sourceCap.getTanks(); i++) {
					FluidStack contained = sourceCap.getFluidInTank(i);
					if (contained.isEmpty())
						continue;
					if (!FluidStack.isSameFluidSameComponents(contained, fluid))
						continue;
					FluidStack toExtract = FluidHelper.copyStackWithAmount(contained, flowSpeed);
					transfer = sourceCap.drain(toExtract, action);
					break;
				}

				if (transfer.isEmpty()) {
					FluidStack genericExtract = sourceCap.drain(flowSpeed, action);
					if (!genericExtract.isEmpty() && FluidStack.isSameFluidSameComponents(genericExtract, fluid))
						transfer = genericExtract;
				}

				if (transfer.isEmpty())
					return;
				if (simulate)
					flowSpeed = transfer.getAmount();

				List<Pair<BlockFace, FlowSource>> availableOutputs = reentrant ? new ArrayList<>(targets)
					: createperf$outputScratch;
				if (!reentrant) {
					createperf$outputScratch.clear();
					createperf$outputScratch.addAll(targets);
				}

				while (!availableOutputs.isEmpty() && transfer.getAmount() > 0) {
					int dividedTransfer = transfer.getAmount() / availableOutputs.size();
					int remainder = transfer.getAmount() % availableOutputs.size();

					for (Iterator<Pair<BlockFace, FlowSource>> iterator = availableOutputs.iterator(); iterator
						.hasNext();) {
						Pair<BlockFace, FlowSource> pair = iterator.next();
						int toTransfer = dividedTransfer;
						if (remainder > 0) {
							toTransfer++;
							remainder--;
						}

						if (transfer.isEmpty())
							break;
						@Nullable ICapabilityProvider<IFluidHandler> targetHandlerProvider =
							pair.getSecond().provideHandler();
						if (targetHandlerProvider == null) {
							iterator.remove();
							continue;
						}
						IFluidHandler targetHandler = targetHandlerProvider.getCapability();
						if (targetHandler == null) {
							iterator.remove();
							continue;
						}

						int simulatedTransfer = toTransfer;
						if (simulate) {
							Integer previous = accumulatedFill.get(targetHandler); // 代替 getOrDefault(h, 0) 的装箱
							simulatedTransfer += previous == null ? 0 : previous;
						}

						FluidStack divided = transfer.copy();
						divided.setAmount(simulatedTransfer);
						int fill = targetHandler.fill(divided, action);

						if (simulate) {
							accumulatedFill.put(targetHandler, fill);
							fill -= simulatedTransfer - toTransfer;
						}

						transfer.setAmount(transfer.getAmount() - fill);
						if (fill < simulatedTransfer)
							iterator.remove();
					}

				}

				flowSpeed -= transfer.getAmount();
				transfer = FluidStack.EMPTY;
			}
		} finally {
			if (!reentrant) {
				createperf$active = false;
				// 释放 scratch 里对 IFluidHandler 的强引用，避免跨 tick 持有（含所有提前 return 路径）
				createperf$fillScratch.clear();
				createperf$outputScratch.clear();
			}
		}
	}
}
```

## 3. 风险

| 风险 | 2.1（@Redirect on NEW，推荐起步） | 2.2（@Overwrite） |
| --- | --- | --- |
| 语义 | 低-中：唯一理论风险是第三方 `IFluidHandler` 重入同一 network（见 §2.1 的守卫） | 中：必须逐行等价迁移。已特别注意 `determineSource` **会写入** `source`，所以 `:143` 处必须重新读 `source`（本文档代码已按此实现，容易写错） |
| 分配收益 | 每 tick/network 省 1 个 `IdentityHashMap`（含 32 槽 Object[]）+ 最多 2 个 `ArrayList`；约 0.3~0.5 KB | 额外省掉 `:116` 每边一次的 `BlockFace`（16 × frontier × 6 次/tick），这是 F5 的主要收益 |
| 状态 | 2 个 `@Unique` 容器 + 1 个 guard 布尔 | 同上 |
| 失效时机 | scratch 在原语义的每次构造点 `clear()`，无跨 tick 语义 | 无缓存失效问题（纯 scratch，用完即清） |
| 泄漏 | `@Unique` 容器会持有 `IFluidHandler` 强引用 → 必须在 `finally` 清空（本文档代码已做） | 同（已做） |
| 并发 | 服务端主线程 + 客户端（`isVirtual()` contraption）；per-instance，不跨线程 | 同 |
| 脆弱度 | `@At(value="NEW", target="java/util/IdentityHashMap")`：上游换容器类型 → 启动期报错（`defaultRequire = 1`）。若你的 Mixin 版本不接受 internal name，写成 `target = "java.util.IdentityHashMap"` 也可 | 依赖字段名 `world/start/sourceSupplier/source/transferSpeed/pauseBeforePropagation/queued/frontier/visited/fluid/targets` 与私有方法名 `get/isPresent/keepPortableFluidInterfaceEngaged/getFluidTransfer`；任一改名 → 启动期报错 |

**注意**：`@Redirect` 打在同一 `NEW` 上的两个 mod 会互斥（手册 §1），`@WrapOperation` 才是共存友好的选择；
但 `@WrapOperation` 拿到的已是构造完成的实例，无法省掉分配（手册 §3.1/§3.2），所以这里 `@Redirect` 是**唯一**能省分配的手段。

**注意（两处共用）**：`@Shadow` 字段必须与目标**修饰符完全一致**（这些字段在 `FluidNetwork` 中是包私有，不要写 `private`/`protected`）。
私有**方法**不能声明为 `private abstract`（Java 非法），因此一律走 `@Invoker`。

## 4. 回归测试点

1. **单源多目标**：一个水泵 → 分叉到 2/6 个储罐，确认**总输送量**与改前一致（simulate 阶段的 `accumulatedFill` 累计逻辑最容易改错）。
2. **压力反转**：泵反转，确认流向反转、`PipeConnection.flipFlowsIfPressureReversed` 分支正常。
3. **两点之间长管道**（> 40 格）+ `world.getGameTime() % 40` 的分支：端点每 40 tick 重新解析一次，不能变成每 tick 或永不解析。
4. **流体接口（Portable Fluid Interface）**：`keepPortableFluidInterfaceEngaged` 必须仍被调用（本文档把它换成了 `@Invoker`），
   接口在 contraption 运动时不能提前断开。
5. **跨区块**：输运管道跨 3 个区块，途中卸载一个区块再加载，`frontier`/`visited`/`queued` 的收敛行为一致（不能死循环、不能丢目标）。
6. **两个不同流体碰撞**：`FluidStack.isSameFluidSameComponents` 分支触发 `FluidReactions.handlePipeFlowCollision`（在 `PipeConnection` 侧），行为不变。
7. **性能对照**：`/spark profiler --timeout 60`；预期 `catnip.math.BlockFace` 的分配样本大幅下降，且
   `java.util.IdentityHashMap`/`java.util.ArrayList` 的分配样本消失。用 JFR `ObjectAllocationSample` 交叉验证。



