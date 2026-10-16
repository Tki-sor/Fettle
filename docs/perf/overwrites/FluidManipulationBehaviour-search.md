# Overwrite 候选：`FluidManipulationBehaviour.search`

> 目标：`src/main/java/com/simibubi/create/content/fluids/transfer/FluidManipulationBehaviour.java`
> 关联 finding：[05-fluids.md](../05-fluids.md) F1
> 公共约定见 [01-mixin-playbook.md](../01-mixin-playbook.md)

## 0. 为什么必须整体重写

`frontier` 是方法**参数**，由调用方 `FluidDrainingBehaviour` 传入（`FluidDrainingBehaviour.java:278`、`:310`），
且调用方把 `!frontier.isEmpty()` 当作「搜索是否结束」的判据（`FluidDrainingBehaviour.java:63`、`:243`、`:301`、`:359`）。
所以**不能**改字段类型、也不能把队列私有化；必须把「剩余元素」按原顺序写回 `List`。
O(1) 出队（`ArrayDeque.pollFirst()`）与 O(n) 的 `ArrayList.remove(0)` 都是 FIFO，顺序语义一致。

## 1. 原方法完整源码（`FluidManipulationBehaviour.java:149-205`，逐行核对）

```java
	protected Fluid search(Fluid fluid, List<BlockPosEntry> frontier, Set<BlockPos> visited,
						   BiConsumer<BlockPos, Integer> add, boolean searchDownward) throws ChunkNotLoadedException {
		Level world = getWorld();
		int maxBlocks = maxBlocks();
		int maxRange = maxRange();
		int maxRangeSq = maxRange * maxRange;
		int i;

		for (i = 0; i < searchedPerTick && !frontier.isEmpty()
			&& (visited.size() <= maxBlocks || !canDrainInfinitely(fluid)); i++) {
			BlockPosEntry entry = frontier.remove(0);
			BlockPos currentPos = entry.pos;
			if (visited.contains(currentPos))
				continue;
			visited.add(currentPos);

			if (!world.isLoaded(currentPos))
				throw new ChunkNotLoadedException();

			FluidState fluidState = world.getFluidState(currentPos);
			if (fluidState.isEmpty())
				continue;

			Fluid currentFluid = FluidHelper.convertToStill(fluidState.getType());
			if (fluid == null)
				fluid = currentFluid;
			if (!currentFluid.isSame(fluid))
				continue;

			add.accept(currentPos, entry.distance);

			for (Direction side : Iterate.directions) {
				if (!searchDownward && side == Direction.DOWN)
					continue;

				BlockPos offsetPos = currentPos.relative(side);
				if (!world.isLoaded(offsetPos))
					throw new ChunkNotLoadedException();
				if (visited.contains(offsetPos))
					continue;
				if (offsetPos.distSqr(rootPos) > maxRangeSq)
					continue;

				FluidState nextFluidState = world.getFluidState(offsetPos);
				if (nextFluidState.isEmpty())
					continue;
				Fluid nextFluid = nextFluidState.getType();
				if (nextFluid == FluidHelper.convertToFlowing(nextFluid) && side == Direction.UP
					&& !VecHelper.onSameAxis(rootPos, offsetPos, Axis.Y))
					continue;

				frontier.add(new BlockPosEntry(offsetPos, entry.distance + 1));
			}
		}

		return fluid;
	}
```

相关常量与字段：

```java
	static final int searchedPerTick = 1024;   // FluidManipulationBehaviour.java:54
	List<BlockPosEntry> frontier;              // :56（被本方法参数遮蔽）
	BlockPos rootPos;                          // :49（包私有）
	protected int maxBlocks();                 // :95
	protected int maxRange();                  // :91
	protected boolean canDrainInfinitely(Fluid fluid);  // :220
	// getWorld() 继承自 BlockEntityBehaviour，public（BlockEntityBehaviour.java:97）
```

## 2. 改后完整可粘贴实现

### 2.1 推荐形式：`@Inject(HEAD, cancellable = true)`（手册 §2 优先级 3，优于 `@Overwrite`）

```java
package com.example.createperf.mixin.fluids;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.fluids.transfer.FluidManipulationBehaviour;
import com.simibubi.create.foundation.fluid.FluidHelper;

import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

@Mixin(value = FluidManipulationBehaviour.class, remap = false)
public abstract class FluidManipulationBehaviourMixin {

	/** FluidManipulationBehaviour.searchedPerTick（:54，static final 编译期常量，无法 @Shadow，故硬编码）。 */
	@Unique
	private static final int CREATEPERF_SEARCHED_PER_TICK = 1024;

	@Unique
	private final ArrayDeque<FluidManipulationBehaviour.BlockPosEntry> createperf$queue = new ArrayDeque<>();

	@Shadow
	BlockPos rootPos;

	@Shadow
	protected abstract Level getWorld();

	@Shadow
	protected abstract int maxBlocks();

	@Shadow
	protected abstract int maxRange();

	@Shadow
	protected abstract boolean canDrainInfinitely(Fluid fluid);

	@Inject(method = "search(Lnet/minecraft/world/level/material/Fluid;Ljava/util/List;Ljava/util/Set;Ljava/util/function/BiConsumer;Z)Lnet/minecraft/world/level/material/Fluid;",
			at = @At("HEAD"), cancellable = true, remap = false)
	private void createperf$fastSearch(Fluid fluid, List<FluidManipulationBehaviour.BlockPosEntry> frontier,
									   Set<BlockPos> visited, BiConsumer<BlockPos, Integer> add,
									   boolean searchDownward, CallbackInfo ci)
		throws FluidManipulationBehaviour.ChunkNotLoadedException {

		Level world = getWorld();
		int maxBlocks = maxBlocks();
		int maxRange = maxRange();
		int maxRangeSq = maxRange * maxRange;

		ArrayDeque<FluidManipulationBehaviour.BlockPosEntry> queue = createperf$queue;
		queue.clear();
		if (!frontier.isEmpty()) {
			queue.addAll(frontier);
			frontier.clear();
		}

		try {
			for (int i = 0; i < CREATEPERF_SEARCHED_PER_TICK && !queue.isEmpty()
				&& (visited.size() <= maxBlocks || !canDrainInfinitely(fluid)); i++) {

				FluidManipulationBehaviour.BlockPosEntry entry = queue.pollFirst();
				BlockPos currentPos = entry.pos();
				if (visited.contains(currentPos))
					continue;
				visited.add(currentPos);

				if (!world.isLoaded(currentPos))
					throw new FluidManipulationBehaviour.ChunkNotLoadedException();

				FluidState fluidState = world.getFluidState(currentPos);
				if (fluidState.isEmpty())
					continue;

				Fluid currentFluid = FluidHelper.convertToStill(fluidState.getType());
				if (fluid == null)
					fluid = currentFluid;
				if (!currentFluid.isSame(fluid))
					continue;

				add.accept(currentPos, entry.distance());

				for (Direction side : Iterate.directions) {
					if (!searchDownward && side == Direction.DOWN)
						continue;

					BlockPos offsetPos = currentPos.relative(side);
					if (!world.isLoaded(offsetPos))
						throw new FluidManipulationBehaviour.ChunkNotLoadedException();
					if (visited.contains(offsetPos))
						continue;
					if (offsetPos.distSqr(rootPos) > maxRangeSq)
						continue;

					FluidState nextFluidState = world.getFluidState(offsetPos);
					if (nextFluidState.isEmpty())
						continue;
					Fluid nextFluid = nextFluidState.getType();
					if (nextFluid == FluidHelper.convertToFlowing(nextFluid) && side == Direction.UP
						&& !VecHelper.onSameAxis(rootPos, offsetPos, Axis.Y))
						continue;

					queue.addLast(new FluidManipulationBehaviour.BlockPosEntry(offsetPos, entry.distance() + 1));
				}
			}
		} finally {
			// 关键：无论正常结束还是抛 ChunkNotLoadedException，剩余元素都必须按原顺序回到调用方的 List，
			// 否则 FluidDrainingBehaviour 的 `!frontier.isEmpty()` 判据与断点续搜语义全部破坏。
			if (!queue.isEmpty()) {
				frontier.addAll(queue);
				queue.clear();
			}
		}

		ci.setReturnValue(fluid);
	}
}
```

### 2.2 备选形式：`@Overwrite`

只需把上面的方法签名改为下面的形式（方法体与 2.1 中 `try { ... } finally { ... }` 之内的代码完全相同，去掉 `ci` 参数与 `@Inject`），最后 `return fluid;`：

```java
	@Overwrite(remap = false)
	protected Fluid search(Fluid fluid, List<FluidManipulationBehaviour.BlockPosEntry> frontier,
						   Set<BlockPos> visited, BiConsumer<BlockPos, Integer> add, boolean searchDownward)
		throws FluidManipulationBehaviour.ChunkNotLoadedException {
		// ... 同 2.1
	}
```

## 3. `@Overwrite` / `@Inject` 风险

| 风险 | 说明 |
| --- | --- |
| 语义风险 | **高**。`frontier` 是跨 tick 的搜索状态，「剩余元素按序写回」是硬约束；漏写或顺序错乱会让软管滑轮**漏搜 / 重复搜**。必须用 `finally` 覆盖 `ChunkNotLoadedException` 的两条抛出路径（`:166`、`:186`）。 |
| 常量耦合 | `searchedPerTick` 是 `static final` 编译期常量，无法 `@Shadow`，改后硬编码 `1024`。上游把它改成配置项时，本 mixin 会**静默**沿用 1024。 |
| `@Shadow` 解析 | `maxBlocks/maxRange/canDrainInfinitely` 声明在目标类内，`@Shadow` 稳定；`getWorld()` 声明在父类 `BlockEntityBehaviour`，Mixin 会在层级中解析并打印一条 warning，属预期。若你的 Mixin 版本拒绝解析父类成员，改成 `@Shadow` 目标类的 `blockEntity` 字段再调用 `getLevel()`。 |
| 可见性 | `rootPos` 在目标中是**包私有**，`@Shadow` 声明不要写成 `protected/public`。 |
| 并发 | `@Unique` 的 `ArrayDeque` 是 per-instance 字段，与原 `frontier` 同生命周期（服务端主线程独占；`isVirtual()` 的 contraption 场景也在主线程）。**不要**改成 `static`。 |
| 与 Create 自身 mixin 冲突 | `src/main/resources/create.mixins.json` 只注入原版/第三方类，未注入任何 Create content 类，无冲突。 |

## 4. 回归测试点

1. **软管滑轮抽干水池**：30×30×5 的水池 + 软管滑轮 + 泵，抽干后源方块全部消失、无残留。
2. **跨区块中断续搜**：水池横跨 4 个区块，抽干中途 `/forceload` 卸载其中一个 → 应抛 `ChunkNotLoadedException`；重新加载后继续抽干，**不能**出现「已抽过的位置被重复计算」或「漏掉一片水」。
3. **无限模式**：`hosePulleyBlockThreshold` 设小（如 64）+ `bottomlessFluidMode` 允许水 → 达到阈值后水不再减少、软管持续抽取。
4. **`hosePulleyRange` 取 1 与 128 两个极端**：范围限制（`offsetPos.distSqr(rootPos) > maxRangeSq`）行为与改前一致。
5. **向上流动的水**：`nextFluid == convertToFlowing(nextFluid) && side == UP && !onSameAxis(...)` 这条分支在抽干与灌注两个方向都要走到。
6. **性能对照**：同一存档同一场景，开/关 mixin 各跑 3 次 `/spark profiler --timeout 60`，取中位数；预期火焰图中 `ArrayList` 的 `remove`/`System.arraycopy` 路径显著下降。


