# Overwrite 候选：`PumpBlockEntity.distributePressureTo`

> 目标：`src/main/java/com/simibubi/create/content/fluids/pump/PumpBlockEntity.java`
> 关联 finding：[05-fluids.md](../05-fluids.md) F1（`:140-146` `Remove(0)` + `Pair<Integer, BlockPos>` 装箱）
> 公共约定见 [01-mixin-playbook.md](../01-mixin-playbook.md)

## 0. 事实核对

- `frontier` 是**局部变量**（`:140`）→ 只能整体重写，无法用 `@Redirect`/`@ModifyExpressionValue` 换掉 `ArrayList`。
- `frontier.remove(0)`（`:146`）每次 O(n) 移位；`frontier.add(...)` 在 `:197`。
- 元素类型是 `Pair<Integer, BlockPos>`（`:140`）→ 每次入队 `distance + 1` 都要 `Integer.valueOf` 装箱，出队 `entry.getFirst()` 再拆箱。
- 触发频次：本方法**不是每 tick**运行。调用链为
  `PumpBlockEntity.tick()`（`:77-82`，仅当 `sidesToUpdate` 某侧为 true）
  ← `updatePressureChange()`（`:99-110`，把两侧都置 true）
  ← `onSpeedChanged(previousSpeed)`（`:86-97`，**泵转速绝对值变化时**）。
  稳态下转速不变 → 不触发；因此单次成本要比的是「一次压力重算要花多少」，规模放大来自
  `mechanicalPumpRange`（默认 **16**，`CFluids.java:12`）内管道网络的大小：分支越多，`frontier` 越长，O(n²) 越明显。

## 1. 原方法完整源码（`PumpBlockEntity.java:119-228`）

```java
	protected void distributePressureTo(Direction side) {
		if (getSpeed() == 0)
			return;

		BlockFace start = new BlockFace(worldPosition, side);
		boolean pull = isPullingOnSide(isFront(side));
		Set<BlockFace> targets = new HashSet<>();
		Map<BlockPos, Pair<Integer, Map<Direction, Boolean>>> pipeGraph = new HashMap<>();

		if (!pull)
			FluidPropagator.resetAffectedFluidNetworks(level, worldPosition, side.getOpposite());

		if (!hasReachedValidEndpoint(level, start, pull)) {

			pipeGraph.computeIfAbsent(worldPosition, $ -> Pair.of(0, new IdentityHashMap<>()))
				.getSecond()
				.put(side, pull);
			pipeGraph.computeIfAbsent(start.getConnectedPos(), $ -> Pair.of(1, new IdentityHashMap<>()))
				.getSecond()
				.put(side.getOpposite(), !pull);

			List<Pair<Integer, BlockPos>> frontier = new ArrayList<>();
			Set<BlockPos> visited = new HashSet<>();
			int maxDistance = FluidPropagator.getPumpRange();
			frontier.add(Pair.of(1, start.getConnectedPos()));

			while (!frontier.isEmpty()) {
				Pair<Integer, BlockPos> entry = frontier.remove(0);
				int distance = entry.getFirst();
				BlockPos currentPos = entry.getSecond();

				if (!level.isLoaded(currentPos))
					continue;
				if (visited.contains(currentPos))
					continue;
				visited.add(currentPos);
				BlockState currentState = level.getBlockState(currentPos);
				FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, currentPos);
				if (pipe == null)
					continue;

				for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
					BlockFace blockFace = new BlockFace(currentPos, face);
					BlockPos connectedPos = blockFace.getConnectedPos();

					if (!level.isLoaded(connectedPos))
						continue;
					if (blockFace.isEquivalent(start))
						continue;
					if (hasReachedValidEndpoint(level, blockFace, pull)) {
						pipeGraph.computeIfAbsent(currentPos, $ -> Pair.of(distance, new IdentityHashMap<>()))
							.getSecond()
							.put(face, pull);
						targets.add(blockFace);
						continue;
					}

					FluidTransportBehaviour pipeBehaviour = FluidPropagator.getPipe(level, connectedPos);
					if (pipeBehaviour == null)
						continue;
					if (pipeBehaviour instanceof PumpFluidTransferBehaviour)
						continue;
					if (visited.contains(connectedPos))
						continue;
					if (distance + 1 >= maxDistance) {
						pipeGraph.computeIfAbsent(currentPos, $ -> Pair.of(distance, new IdentityHashMap<>()))
							.getSecond()
							.put(face, pull);
						targets.add(blockFace);
						continue;
					}

					pipeGraph.computeIfAbsent(currentPos, $ -> Pair.of(distance, new IdentityHashMap<>()))
						.getSecond()
						.put(face, pull);
					pipeGraph.computeIfAbsent(connectedPos, $ -> Pair.of(distance + 1, new IdentityHashMap<>()))
						.getSecond()
						.put(face.getOpposite(), !pull);
					frontier.add(Pair.of(distance + 1, connectedPos));
				}
			}
		}

		// DFS
		Map<Integer, Set<BlockFace>> validFaces = new HashMap<>();
		searchForEndpointRecursively(pipeGraph, targets, validFaces,
			new BlockFace(start.getPos(), start.getOppositeFace()), pull);

		float pressure = Math.abs(getSpeed());
		for (Set<BlockFace> set : validFaces.values()) {
			int parallelBranches = Math.max(1, set.size() - 1);
			for (BlockFace face : set) {
				BlockPos pipePos = face.getPos();
				Direction pipeSide = face.getFace();

				if (pipePos.equals(worldPosition))
					continue;

				boolean inbound = pipeGraph.get(pipePos)
					.getSecond()
					.get(pipeSide);
				FluidTransportBehaviour pipeBehaviour = FluidPropagator.getPipe(level, pipePos);
				if (pipeBehaviour == null)
					continue;

				pipeBehaviour.addPressure(pipeSide, inbound, pressure / parallelBranches);
			}
		}

	}
```

## 2. 改后完整可粘贴实现

**伴生接口**（`hasReachedValidEndpoint` 是 **private**，Java 不允许 `private abstract`，必须 `@Invoker`；
`Pair<Integer, BlockPos>` 换成 [FluidPropagator-BFS.md](FluidPropagator-BFS.md) §2 里那个 `CreateperfPosDist` record）：

```java
@Mixin(value = PumpBlockEntity.class, remap = false)
public interface PumpBlockEntityInvoker {
	@Invoker("hasReachedValidEndpoint")
	boolean createperf$hasReachedValidEndpoint(LevelAccessor world, BlockFace blockFace, boolean pull);
}
```

```java
package com.example.createperf.mixin.fluids;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.fluids.pump.PumpFluidTransferBehaviour;

import net.createmod.catnip.data.Pair;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(value = PumpBlockEntity.class, remap = false)
public abstract class PumpBlockEntityMixin {

	// isFront / searchForEndpointRecursively 都是 protected，修饰符一致即可 @Shadow（不需要 @Invoker）
	@Shadow
	protected abstract boolean isFront(Direction side);

	@Shadow
	protected abstract boolean searchForEndpointRecursively(
		Map<BlockPos, Pair<Integer, Map<Direction, Boolean>>> pipeGraph, Set<BlockFace> targets,
		Map<Integer, Set<BlockFace>> validFaces, BlockFace currentFace, boolean pull);

	/**
	 * @author createperf
	 * @reason ArrayList.remove(0) 每次 O(n) 移位（整体 O(n²)），且 Pair<Integer, BlockPos> 每次入队都要 Integer 装箱。
	 *         改为 ArrayDeque.pollFirst() + CreateperfPosDist(BlockPos, int)。
	 */
	@Overwrite(remap = false)
	protected void distributePressureTo(Direction side) {
		if (getSpeed() == 0)
			return;

		BlockFace start = new BlockFace(getBlockPos(), side);
		boolean pull = isPullingOnSide(isFront(side));
		Set<BlockFace> targets = new HashSet<>();
		Map<BlockPos, Pair<Integer, Map<Direction, Boolean>>> pipeGraph = new HashMap<>();
		PumpBlockEntityInvoker inv = (PumpBlockEntityInvoker) (Object) this;

		if (!pull)
			FluidPropagator.resetAffectedFluidNetworks(getLevel(), getBlockPos(), side.getOpposite());

		if (!inv.createperf$hasReachedValidEndpoint(getLevel(), start, pull)) {

			pipeGraph.computeIfAbsent(getBlockPos(), $ -> Pair.of(0, new IdentityHashMap<>()))
				.getSecond()
				.put(side, pull);
			pipeGraph.computeIfAbsent(start.getConnectedPos(), $ -> Pair.of(1, new IdentityHashMap<>()))
				.getSecond()
				.put(side.getOpposite(), !pull);

			ArrayDeque<CreateperfPosDist> frontier = new ArrayDeque<>();
			Set<BlockPos> visited = new HashSet<>();
			int maxDistance = FluidPropagator.getPumpRange();
			frontier.addLast(new CreateperfPosDist(start.getConnectedPos(), 1));

			while (!frontier.isEmpty()) {
				CreateperfPosDist entry = frontier.pollFirst();
				int distance = entry.distance();
				BlockPos currentPos = entry.pos();

				if (!getLevel().isLoaded(currentPos))
					continue;
				if (visited.contains(currentPos))
					continue;
				visited.add(currentPos);
				BlockState currentState = getLevel().getBlockState(currentPos);
				FluidTransportBehaviour pipe = FluidPropagator.getPipe(getLevel(), currentPos);
				if (pipe == null)
					continue;

				for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
					BlockFace blockFace = new BlockFace(currentPos, face);
					BlockPos connectedPos = blockFace.getConnectedPos();

					if (!getLevel().isLoaded(connectedPos))
						continue;
					if (blockFace.isEquivalent(start))
						continue;
					if (inv.createperf$hasReachedValidEndpoint(getLevel(), blockFace, pull)) {
						pipeGraph.computeIfAbsent(currentPos, $ -> Pair.of(distance, new IdentityHashMap<>()))
							.getSecond()
							.put(face, pull);
						targets.add(blockFace);
						continue;
					}

					FluidTransportBehaviour pipeBehaviour = FluidPropagator.getPipe(getLevel(), connectedPos);
					if (pipeBehaviour == null)
						continue;
					if (pipeBehaviour instanceof PumpFluidTransferBehaviour)
						continue;
					if (visited.contains(connectedPos))
						continue;
					if (distance + 1 >= maxDistance) {
						pipeGraph.computeIfAbsent(currentPos, $ -> Pair.of(distance, new IdentityHashMap<>()))
							.getSecond()
							.put(face, pull);
						targets.add(blockFace);
						continue;
					}

					pipeGraph.computeIfAbsent(currentPos, $ -> Pair.of(distance, new IdentityHashMap<>()))
						.getSecond()
						.put(face, pull);
					pipeGraph.computeIfAbsent(connectedPos, $ -> Pair.of(distance + 1, new IdentityHashMap<>()))
						.getSecond()
						.put(face.getOpposite(), !pull);
					frontier.addLast(new CreateperfPosDist(connectedPos, distance + 1));
				}
			}
		}

		// DFS
		Map<Integer, Set<BlockFace>> validFaces = new HashMap<>();
		searchForEndpointRecursively(pipeGraph, targets, validFaces,
			new BlockFace(start.getPos(), start.getOppositeFace()), pull);

		float pressure = Math.abs(getSpeed());
		for (Set<BlockFace> set : validFaces.values()) {
			int parallelBranches = Math.max(1, set.size() - 1);
			for (BlockFace face : set) {
				BlockPos pipePos = face.getPos();
				Direction pipeSide = face.getFace();

				if (pipePos.equals(getBlockPos()))
					continue;

				boolean inbound = pipeGraph.get(pipePos)
					.getSecond()
					.get(pipeSide);
				FluidTransportBehaviour pipeBehaviour = FluidPropagator.getPipe(getLevel(), pipePos);
				if (pipeBehaviour == null)
					continue;

				pipeBehaviour.addPressure(pipeSide, inbound, pressure / parallelBranches);
			}
		}
	}
}
```

## 3. 风险

| 项 | 评估 |
| --- | --- |
| 语义 | **中**。BFS 的 FIFO 顺序、`visited` 去重、`distance + 1 >= maxDistance` 截断、`blockFace.isEquivalent(start)` 跳过、`PumpFluidTransferBehaviour` 过滤 —— 全部逐行保留；`targets`/`validFaces` 的**迭代顺序**与改前一致。 |
| `worldPosition` → `getBlockPos()` | 二者在 1.21.1 中等价（`BlockEntity.getBlockPos()` 返回 `worldPosition`）。若你担心 NeoForge 对 `worldPosition` 的改写，可改用 `@Shadow protected BlockPos worldPosition;`（继承字段，Mixin 会解析并打印 warning）。 |
| `@Shadow` 可见性 | `isFront`、`searchForEndpointRecursively` 在目标里是 `protected` → `@Shadow protected abstract` 合法。**不要**对 `hasReachedValidEndpoint`（private）用 `@Shadow`。 |
| 递归调用 | `searchForEndpointRecursively` 是**递归**的（`:256` 自调用）。本 mixin **没有**覆盖它，所以递归仍然走原实现，只有入口从我们的方法发出——语义不变。 |
| `@Overwrite` 冲突 | 与 Create 自身 mixin 无重叠；第三方 mod 若也 `@Overwrite` 本方法会硬冲突（启动期报错）。 |
| 配置重读 | 原代码在循环**外**读一次 `FluidPropagator.getPumpRange()`；改后仍在循环外读一次，行为不变。 |
| 脆弱度 | 依赖 `PumpFluidTransferBehaviour` 类型名、`hasReachedValidEndpoint` 私有方法名与描述符、`isFront`/`searchForEndpointRecursively` 的签名；上游改名 → 启动期报错（`defaultRequire = 1`）。 |

## 4. 回归测试点

1. **泵压反转**：`sidesToUpdate` 两侧先后触发，确认推/拉两种 `pull` 下压力施加方向正确（`pipeGraph` 里 `put(face, pull)` 与 `put(face.getOpposite(), !pull)` 的成对写入不能被写反）。
2. **分叉支路计数**：`parallelBranches = max(1, set.size() - 1)` 决定每根管道分到的压力；用「一泵 → T 形三通 → 两个储罐」验证两侧压力值相等（除以 2）。
3. **距离截断**：`mechanicalPumpRange` 设为 1、16、64 三档，确认达到 `distance + 1 >= maxDistance` 后压力只施加到端点，不再向后传播。
4. **泵之间的管道**：两个泵正对（`hasReachedValidEndpoint` 命中 PumpBlock 分支）时，确认 `isPullingOnSide(isFront(...)) != pull` 的判定与改前一致。
5. **`resetAffectedFluidNetworks` 被触发**：`pull == false` 时先调用 `resetAffectedFluidNetworks(getLevel(), getBlockPos(), side.getOpposite())`，参数顺序/方向不能变（见 FluidPropagator 文档的回归点）。
6. **性能对照**：一支 300+ 格、带多个 T 形分支的管道网络，反复用离合器切换泵转速触发压力重算；
   `/spark profiler` 对比 `System.arraycopy`（来自 `ArrayList.remove`）与 `java.lang.Integer`（装箱）在火焰图中的样本数。

