# Overwrite 候选：`FluidPropagator.propagateChangedPipe` + `FluidPropagator.resetAffectedFluidNetworks`

> 目标：`src/main/java/com/simibubi/create/content/fluids/FluidPropagator.java`
> 关联 finding：[05-fluids.md](../05-fluids.md) F1（另两处 `Remove(0)` 出队）
> 公共约定见 [01-mixin-playbook.md](../01-mixin-playbook.md)

## 0. 为什么这是「性价比最高」的两个重写

两个方法都是 **`public static`**、**只用局部变量**（`frontier` / `visited` / `discoveredPumps` 都在方法内 new），
因此 `@Overwrite` **不需要任何 `@Shadow`/`@Accessor`**，是本领域风险最低的重写。
唯一的外部依赖是 `PipeConnection.flow`（包私有）→ 复用 [FluidNetwork-tick.md](FluidNetwork-tick.md) §2.2 定义的 `PipeConnectionAccessor`。

触发频次（已核对）：
- `propagateChangedPipe`：由 `PumpBlockEntity.updatePressureChange()`（`PumpBlockEntity.java:99-110`，即**泵转速变化时**）与
  `FluidPropagator` 自身调用；**不是**每 tick。
- `resetAffectedFluidNetworks`：由 `PumpBlockEntity.distributePressureTo`（`:129`，泵转为「推」且转速变化时）与
  `PipeConnection.manageFlows`（`:132`，流向被覆盖时）调用。

## 1. 原方法完整源码（`FluidPropagator.java:49-135`）

```java
	public static void propagateChangedPipe(LevelAccessor world, BlockPos pipePos, BlockState pipeState) {
		List<Pair<Integer, BlockPos>> frontier = new ArrayList<>();
		Set<BlockPos> visited = new HashSet<>();
		Set<Pair<PumpBlockEntity, Direction>> discoveredPumps = new HashSet<>();

		frontier.add(Pair.of(0, pipePos));

		// Visit all connected pumps to update their network
		while (!frontier.isEmpty()) {
			Pair<Integer, BlockPos> pair = frontier.remove(0);
			BlockPos currentPos = pair.getSecond();
			if (visited.contains(currentPos))
				continue;
			visited.add(currentPos);
			BlockState currentState = currentPos.equals(pipePos) ? pipeState : world.getBlockState(currentPos);
			FluidTransportBehaviour pipe = getPipe(world, currentPos);
			if (pipe == null)
				continue;
			pipe.wipePressure();

			for (Direction direction : getPipeConnections(currentState, pipe)) {
				BlockPos target = currentPos.relative(direction);
				if (world instanceof Level l && !l.isLoaded(target))
					continue;

				BlockEntity blockEntity = world.getBlockEntity(target);
				BlockState targetState = world.getBlockState(target);
				if (blockEntity instanceof PumpBlockEntity) {
					if (!(targetState.getBlock() instanceof PumpBlock) || targetState.getValue(PumpBlock.FACING)
						.getAxis() != direction.getAxis())
						continue;
					discoveredPumps.add(Pair.of((PumpBlockEntity) blockEntity, direction.getOpposite()));
					continue;
				}
				if (visited.contains(target))
					continue;
				FluidTransportBehaviour targetPipe = getPipe(world, target);
				if (targetPipe == null)
					continue;
				Integer distance = pair.getFirst();
				if (distance >= getPumpRange() && !targetPipe.hasAnyPressure())
					continue;
				if (targetPipe.canHaveFlowToward(targetState, direction.getOpposite()))
					frontier.add(Pair.of(distance + 1, target));
			}
		}

		discoveredPumps.forEach(pair -> pair.getFirst()
			.updatePipesOnSide(pair.getSecond()));
	}

	public static void resetAffectedFluidNetworks(Level world, BlockPos start, Direction side) {
		List<BlockPos> frontier = new ArrayList<>();
		Set<BlockPos> visited = new HashSet<>();
		frontier.add(start);

		while (!frontier.isEmpty()) {
			BlockPos pos = frontier.remove(0);
			if (visited.contains(pos))
				continue;
			visited.add(pos);
			FluidTransportBehaviour pipe = getPipe(world, pos);
			if (pipe == null)
				continue;

			for (Direction d : Iterate.directions) {
				if (pos.equals(start) && d != side)
					continue;
				BlockPos target = pos.relative(d);
				if (visited.contains(target))
					continue;

				PipeConnection connection = pipe.getConnection(d);
				if (connection == null)
					continue;
				if (!connection.hasFlow())
					continue;

				Flow flow = connection.flow.get();
				if (!flow.inbound)
					continue;

				connection.resetNetwork();
				frontier.add(target);
			}
		}
	}
```

## 2. 改后完整可粘贴实现

**辅助类型**（新建一个普通文件，不是 mixin）：

```java
package com.example.createperf.mixin.fluids;

import net.minecraft.core.BlockPos;

/** 替代 Pair<Integer, BlockPos>，去掉 int 的自动装箱。 */
public record CreateperfPosDist(BlockPos pos, int distance) {
}
```

**Overwrite 主体**：

```java
package com.example.createperf.mixin.fluids;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.PipeConnection.Flow;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;

import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.data.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(value = FluidPropagator.class, remap = false)
public abstract class FluidPropagatorMixin {

	/**
	 * @author createperf
	 * @reason ArrayList.remove(0) 每次 O(n) 移位；改为 ArrayDeque.pollFirst，并用 CreateperfPosDist 去掉 Integer 装箱。
	 */
	@Overwrite(remap = false)
	public static void propagateChangedPipe(LevelAccessor world, BlockPos pipePos, BlockState pipeState) {
		ArrayDeque<CreateperfPosDist> frontier = new ArrayDeque<>();
		Set<BlockPos> visited = new HashSet<>();
		Set<Pair<PumpBlockEntity, Direction>> discoveredPumps = new HashSet<>();

		frontier.addLast(new CreateperfPosDist(pipePos, 0));

		// Visit all connected pumps to update their network
		while (!frontier.isEmpty()) {
			CreateperfPosDist entry = frontier.pollFirst();
			BlockPos currentPos = entry.pos();
			if (visited.contains(currentPos))
				continue;
			visited.add(currentPos);
			BlockState currentState = currentPos.equals(pipePos) ? pipeState : world.getBlockState(currentPos);
			FluidTransportBehaviour pipe = FluidPropagator.getPipe(world, currentPos);
			if (pipe == null)
				continue;
			pipe.wipePressure();

			for (Direction direction : FluidPropagator.getPipeConnections(currentState, pipe)) {
				BlockPos target = currentPos.relative(direction);
				if (world instanceof Level l && !l.isLoaded(target))
					continue;

				BlockEntity blockEntity = world.getBlockEntity(target);
				BlockState targetState = world.getBlockState(target);
				if (blockEntity instanceof PumpBlockEntity) {
					if (!(targetState.getBlock() instanceof PumpBlock) || targetState.getValue(PumpBlock.FACING)
						.getAxis() != direction.getAxis())
						continue;
					discoveredPumps.add(Pair.of((PumpBlockEntity) blockEntity, direction.getOpposite()));
					continue;
				}
				if (visited.contains(target))
					continue;
				FluidTransportBehaviour targetPipe = FluidPropagator.getPipe(world, target);
				if (targetPipe == null)
					continue;
				int distance = entry.distance();
				if (distance >= FluidPropagator.getPumpRange() && !targetPipe.hasAnyPressure())
					continue;
				if (targetPipe.canHaveFlowToward(targetState, direction.getOpposite()))
					frontier.addLast(new CreateperfPosDist(target, distance + 1));
			}
		}

		discoveredPumps.forEach(pair -> pair.getFirst()
			.updatePipesOnSide(pair.getSecond()));
	}

	/**
	 * @author createperf
	 * @reason 同上：List.remove(0) -> ArrayDeque.pollFirst()。
	 */
	@Overwrite(remap = false)
	public static void resetAffectedFluidNetworks(Level world, BlockPos start, Direction side) {
		ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
		Set<BlockPos> visited = new HashSet<>();
		frontier.addLast(start);

		while (!frontier.isEmpty()) {
			BlockPos pos = frontier.pollFirst();
			if (visited.contains(pos))
				continue;
			visited.add(pos);
			FluidTransportBehaviour pipe = FluidPropagator.getPipe(world, pos);
			if (pipe == null)
				continue;

			for (Direction d : Iterate.directions) {
				if (pos.equals(start) && d != side)
					continue;
				BlockPos target = pos.relative(d);
				if (visited.contains(target))
					continue;

				PipeConnection connection = pipe.getConnection(d);
				if (connection == null)
					continue;
				if (!connection.hasFlow())
					continue;

				Flow flow = ((PipeConnectionAccessor) connection).createperf$getFlow().get();
				if (!flow.inbound)
					continue;

				connection.resetNetwork();
				frontier.addLast(target);
			}
		}
	}
}
```

> 说明：`List` 版本的 `remove(0)` 与 `ArrayDeque.pollFirst()` 都是 FIFO，遍历顺序逐字节等价，因此
> `visited` 的填充顺序、`discoveredPumps` 的迭代顺序都不变（`Pair`/`BlockPos` 在 `HashSet` 中的顺序本来就与插入顺序无关）。

## 3. 风险

| 项 | 评估 |
| --- | --- |
| 语义 | **低**。两个方法都是纯 BFS，无外部状态，FIFO 顺序不变。 |
| `@Overwrite` 冲突 | 与 Create 自身 mixin 无重叠（Create 只注入原版类）。若第三方 mod 也 `@Overwrite` 这两个方法，会硬冲突（启动期报错），这比静默失效好。 |
| `@Shadow` | **不需要**（全部局部变量）。唯一的外部成员访问是 `PipeConnection.flow` → 需要 `PipeConnectionAccessor`（见 FluidNetwork-tick.md §2.2）。 |
| 脆弱度 | 依赖 `FluidPropagator.getPipeConnections/getPipe/getPumpRange` 与 `PumpBlockEntity.updatePipesOnSide` 的公开签名；上游改名 → 启动期报错。`PumpBlock.FACING` 也直接引用。 |
| 装箱 | `Pair<Integer, BlockPos>` → `CreateperfPosDist` 记录类，`int distance` 不再装箱；`Pair<PumpBlockEntity, Direction>` 保留（只在发现泵时分配，量级 = 泵数量）。 |

## 4. 回归测试点

1. **泵 + 长管道压力传播**：`mechanicalPumpRange` 默认 16；用 30 格直管 + 末端泵，确认压力能传到、旋转方向改变后压力反转。
2. **多泵网络**：一条管线上 3 个泵（相互反向/同向各测一次），确认 `discoveredPumps` 里去重后的泵都收到 `updatePipesOnSide`。
3. **距离边界**：管道长度恰好 = `mechanicalPumpRange`（16）与 16+1，确认 `distance >= getPumpRange() && !hasAnyPressure()` 的截断点与改前一致（`getPumpRange()` 在循环内每次重新读取配置，改动后仍每次读）。
4. **`resetAffectedFluidNetworks` 的方向限制**：`pos.equals(start) && d != side` —— 从起始管道只在指定方向展开，不能被放宽。
5. **`flow.inbound` 过滤**：只有 inbound 连接才 `resetNetwork()` + 继续展开；反向流不展开。
6. **性能对照**：造一条 200+ 格、带分支的管道网络，反复改变泵转速触发压力重算，用 `/spark profiler` 对比
   `ArrayList` 移位（`System.arraycopy`）在火焰图中的占比。

