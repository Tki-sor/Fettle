# @Overwrite 详解：`CarriageSyncData.getDistanceTo`

> 关联 finding：`03-contraptions-trains.md` 第 8 条（Medium）
> 目标：`com.simibubi.create.content.trains.entity.CarriageSyncData`
> 本文件给的是**完整可粘贴**的替换实现。

## 1. 原方法完整源码（Create 6.0.10，行号已逐行核对）

文件：`src/main/java/com/simibubi/create/content/trains/entity/CarriageSyncData.java`，第 299–369 行。

```java
	// 注意：这是 private 方法。@Overwrite 可以命中 private 方法（Mixin 通过 ASM 直接改写字节码，
	// 不依赖反射访问权限），但对应的 handler 方法名必须与描述符一致，见第 4 节。
	public float getDistanceTo(TrackGraph graph, TravellingPoint current, TravellingPoint target, float maxDistance,
		boolean forward) {
		if (maxDistance == -1)
			maxDistance = 32;

		Set<TrackEdge> visited = new HashSet<>();
		Map<TrackEdge, Pair<Boolean, TrackEdge>> reachedVia = new IdentityHashMap<>();
		PriorityQueue<Pair<Double, Pair<Couple<TrackNode>, TrackEdge>>> frontier =
			new PriorityQueue<>((p1, p2) -> Double.compare(p1.getFirst(), p2.getFirst()));

		TrackNode initialNode1 = forward ? current.node1 : current.node2;
		TrackNode initialNode2 = forward ? current.node2 : current.node1;

		Map<TrackNode, TrackEdge> connectionsFromInitial = graph.getConnectionsFrom(initialNode1);
		if (connectionsFromInitial == null)
			return -1;

		TrackEdge initialEdge = connectionsFromInitial.get(initialNode2);
		if (initialEdge == null)
			return -1; // graph changed

		TrackNode targetNode1 = forward ? target.node1 : target.node2;
		TrackNode targetNode2 = forward ? target.node2 : target.node1;
		TrackEdge targetEdge = graph.getConnectionsFrom(targetNode1)
			.get(targetNode2);

		double distanceToNode2 = forward ? initialEdge.getLength() - current.position : current.position;

		frontier.add(Pair.of(distanceToNode2, Pair.of(Couple.create(initialNode1, initialNode2), initialEdge)));

		while (!frontier.isEmpty()) {
			Pair<Double, Pair<Couple<TrackNode>, TrackEdge>> poll = frontier.poll();
			double distance = poll.getFirst();

			Pair<Couple<TrackNode>, TrackEdge> currentEntry = poll.getSecond();
			TrackNode node2 = currentEntry.getFirst()
				.getSecond();
			TrackEdge edge = currentEntry.getSecond();

			if (edge == targetEdge)
				return (float) (distance - (forward ? edge.getLength() - target.position : target.position));

			if (distance > maxDistance)
				continue;

			List<Entry<TrackNode, TrackEdge>> validTargets = new ArrayList<>();
			Map<TrackNode, TrackEdge> connectionsFrom = graph.getConnectionsFrom(node2);
			for (Entry<TrackNode, TrackEdge> entry : connectionsFrom.entrySet()) {
				TrackEdge newEdge = entry.getValue();
				Vec3 currentDirection = edge.getDirection(false);
				Vec3 newDirection = newEdge.getDirection(true);
				if (currentDirection.dot(newDirection) < 7 / 8f)
					continue;
				if (!visited.add(entry.getValue()))
					continue;
				validTargets.add(entry);
			}

			if (validTargets.isEmpty())
				continue;

			for (Entry<TrackNode, TrackEdge> entry : validTargets) {
				TrackNode newNode = entry.getKey();
				TrackEdge newEdge = entry.getValue();
				reachedVia.put(newEdge, Pair.of(validTargets.size() > 1, edge));
				frontier.add(Pair.of(newEdge.getLength() + distance, Pair.of(Couple.create(node2, newNode), newEdge)));
			}
		}

		return -1;
	}
```

## 2. 为什么值得 @Overwrite（而不是 Injection）

1. **三次容器构造在方法体内、每次调用都执行**：
   `new HashSet<>()`、`new IdentityHashMap<>()`、`new PriorityQueue<>(comparator)`（comparator 是 lambda → 每次调用一个额外对象）。
   按手册 §3 的判据，「分配是方法体中的独立语句」，可以用 `@ModifyExpressionValue` 逐个替换成共享实例 —— 但那样要 4 个注入点、而且
   共享实例必须保证**跨调用清空**，一旦漏清就是静默错误。整方法重写反而更安全。
2. **`reachedVia` 是纯 write-only 的死存储**：全仓库只有 `put`，没有任何 `get`（已 grep 确认），删掉不影响语义。
3. **`validTargets` 只是「过滤后的 entry 列表」**：过滤条件本身不依赖列表内容，可以边过滤边入队，省掉一个 ArrayList 和一趟遍历。
4. **热点规模**：客户端 `CarriageSyncData.apply` 每个轮点在 `for (boolean forward : Iterate.trueAndFalse)` 里调用**两次**
   （`CarriageSyncData.java:200-206`），每次调用都跑一遍 Dijkstra，`maxDistance` 第一轮是 -1 → 32 格。

## 3. 改后完整方法体（可粘贴）

```java
	// ---- 新增 @Unique 字段（放在 CarriageSyncDataMixin 里）----
	// 作用域：per-CarriageSyncData 实例（客户端每个车厢一个）。
	// 失效时机：每次调用开头 clear()，不跨调用保留语义。
	// 并发：仅客户端主线程（apply/approach 均由 CarriageContraptionEntity 的实体 tick 驱动）。
	@Unique private final Set<TrackEdge> createperf$visited = new HashSet<>(64);
	@Unique private final PriorityQueue<Pair<Double, Pair<Couple<TrackNode>, TrackEdge>>> createperf$frontier =
		new PriorityQueue<>(64, (p1, p2) -> Double.compare(p1.getFirst(), p2.getFirst()));

	@Overwrite
	public float getDistanceTo(TrackGraph graph, TravellingPoint current, TravellingPoint target, float maxDistance,
		boolean forward) {
		if (maxDistance == -1)
			maxDistance = 32;

		// 复用容器，避免每次调用 new HashSet / new PriorityQueue / comparator lambda
		Set<TrackEdge> visited = createperf$visited;
		visited.clear();
		PriorityQueue<Pair<Double, Pair<Couple<TrackNode>, TrackEdge>>> frontier = createperf$frontier;
		frontier.clear();

		TrackNode initialNode1 = forward ? current.node1 : current.node2;
		TrackNode initialNode2 = forward ? current.node2 : current.node1;

		Map<TrackNode, TrackEdge> connectionsFromInitial = graph.getConnectionsFrom(initialNode1);
		if (connectionsFromInitial == null)
			return -1;

		TrackEdge initialEdge = connectionsFromInitial.get(initialNode2);
		if (initialEdge == null)
			return -1; // graph changed

		TrackNode targetNode1 = forward ? target.node1 : target.node2;
		TrackNode targetNode2 = forward ? target.node2 : target.node1;
		Map<TrackNode, TrackEdge> connectionsFromTarget = graph.getConnectionsFrom(targetNode1);
		TrackEdge targetEdge = connectionsFromTarget == null ? null : connectionsFromTarget.get(targetNode2);

		double distanceToNode2 = forward ? initialEdge.getLength() - current.position : current.position;
		frontier.add(Pair.of(distanceToNode2, Pair.of(Couple.create(initialNode1, initialNode2), initialEdge)));

		while (!frontier.isEmpty()) {
			Pair<Double, Pair<Couple<TrackNode>, TrackEdge>> poll = frontier.poll();
			double distance = poll.getFirst();

			Pair<Couple<TrackNode>, TrackEdge> currentEntry = poll.getSecond();
			TrackNode node2 = currentEntry.getFirst()
				.getSecond();
			TrackEdge edge = currentEntry.getSecond();

			if (edge == targetEdge)
				return (float) (distance - (forward ? edge.getLength() - target.position : target.position));

			if (distance > maxDistance)
				continue;

			Map<TrackNode, TrackEdge> connectionsFrom = graph.getConnectionsFrom(node2);
			if (connectionsFrom == null)
				continue;

			// 原实现先收集 validTargets 再遍历；这里直接入队，省掉 ArrayList 与一趟遍历。
			// 入队顺序与原实现一致（HashMap 迭代顺序不变），因此 PriorityQueue 的 tie-break 行为不变。
			for (Entry<TrackNode, TrackEdge> entry : connectionsFrom.entrySet()) {
				TrackEdge newEdge = entry.getValue();
				Vec3 currentDirection = edge.getDirection(false);
				Vec3 newDirection = newEdge.getDirection(true);
				if (currentDirection.dot(newDirection) < 7 / 8f)
					continue;
				if (!visited.add(entry.getValue()))
					continue;
				// 原代码的 reachedVia 是 write-only 死存储，已删除
				frontier.add(Pair.of(newEdge.getLength() + distance,
					Pair.of(Couple.create(node2, entry.getKey()), newEdge)));
			}
		}

		return -1;
	}
```

### 需要的 import

```java
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import com.simibubi.create.content.trains.entity.CarriageSyncData;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;

import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.Pair;
import net.minecraft.world.phys.Vec3;
```

## 4. Mixin 类骨架

```java
@Mixin(value = CarriageSyncData.class, remap = false)
public abstract class CarriageSyncDataMixin {

	@Unique private final Set<TrackEdge> createperf$visited = new HashSet<>(64);
	@Unique private final PriorityQueue<Pair<Double, Pair<Couple<TrackNode>, TrackEdge>>> createperf$frontier =
		new PriorityQueue<>(64, (p1, p2) -> Double.compare(p1.getFirst(), p2.getFirst()));

	@Overwrite
	public float getDistanceTo(TrackGraph graph, TravellingPoint current, TravellingPoint target, float maxDistance,
		boolean forward) {
		/* 见第 3 节 */
	}
}
```

`@Overwrite` 的 handler **必须是 public**（Mixin 要求 overwrite 方法与目标方法同可见性；目标方法是 public，
所以这里 public）。参数表必须与目标完全一致：`(Lcom/simibubi/create/content/trains/graph/TrackGraph;Lcom/simibubi/create/content/trains/entity/TravellingPoint;Lcom/simibubi/create/content/trains/entity/TravellingPoint;FZ)F`。

## 5. 风险

| 风险 | 说明 |
| --- | --- |
| 上游重构 | 方法签名一变（哪怕只是参数名）Overwrite 直接失配；`required:true` + `defaultRequire:1` 会让它**启动报错**而不是静默失效。这是期望行为。 |
| 语义变更点 1 | 原代码 `graph.getConnectionsFrom(targetNode1).get(...)` 在节点不存在时会 NPE；新代码返回 null 后由 `edge == targetEdge` 判断，**把 NPE 变成「找不到路径」**。这是行为差异 —— 但原路径是崩溃，新路径是退化为没找到；如要严格一致，去掉 `connectionsFromTarget == null` 判空即可。 |
| 语义变更点 2 | 删除了 `reachedVia`。已 grep 确认全仓库无读取点。 |
| 语义变更点 3 | 删除了 `validTargets.isEmpty() → continue` 这一分支：原代码在无有效后继时直接进入下一轮 poll，新代码的 for 循环自然空转，效果相同。 |
| 容器复用 | 如果将来 `getDistanceTo` 被改成可重入（在自身内部再调一次），复用容器会串数据。当前调用图是「apply 的 for 循环里串行调用两次」，安全。 |
| 与其它 mixin 冲突 | 目标方法是 `private` 且不在 Create 自己的 mixin 列表（create.mixins.json 只注入原版类）里，冲突面极小。若第三方 mod 也 Overwrite 它，只有一个生效。 |

## 6. 回归测试点

1. **客户端车厢平滑**：进存档，让一列 ≥3 节车厢的列车在直线 + 弯道 + 道岔上跑满 60 秒；观察车厢是否有抖动/瞬移（`getDistanceTo` 只影响客户端插值方向与距离）。
2. **道岔选向**：在同一轨道分叉口连续让列车双向通过 10 次，确认客户端车厢跟随的是服务端同一分支（原实现按 `7/8` 点积过滤，改动不能影响该过滤）。
3. **维度/传送门切换**：列车穿越下界传送门轨道段，确认车厢实体在切换维度时不会错位（`apply` 里 `edge == null → continue` 的分支必须仍可达）。
4. **找不到路径的退化路径**：人为在服务端把一段轨道拆掉（客户端仍持有旧 `wheelLocations`），确认 `foundDistance == -1` 时的快照回退分支仍被触发（车厢不抖动）。
5. **无异常**：开 `-Dmixin.debug.verbose=true`，确认没有 `ClassCastException` / `NullPointerException` 来自 `carriage_data` 同步路径。

## 7. 收益

**估计**：每次 `apply` 的 `getDistanceTo` 调用少 3 个容器 + 1 个 comparator lambda（每次）＋ 1 个 ArrayList（每次）＋ 每步 1 个 `Pair`（`reachedVia` 的 value）。
规模放大是「每节车厢每轮点每方向一次调用」；实际收益需要 Spark `--alloc` 对照实测。
