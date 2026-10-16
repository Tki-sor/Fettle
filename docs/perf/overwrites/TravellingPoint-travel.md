# @Overwrite 详解：`TravellingPoint.travel`

> 关联 finding：`03-contraptions-trains.md` 第 2 条（High）与第 7 条（Medium）
> 目标：`com.simibubi.create.content.trains.entity.TravellingPoint`
> 本文件给的是**完整可粘贴**的替换实现。

## 1. 原方法完整源码（Create 6.0.10，行号已逐行核对）

文件：`src/main/java/com/simibubi/create/content/trains/entity/TravellingPoint.java`

### 1.1 三个转发重载（第 184–199 行）

```java
	public double travel(TrackGraph graph, double distance, ITrackSelector trackSelector) {
		return travel(graph, distance, trackSelector, ignoreEdgePoints());
	}

	public double travel(TrackGraph graph, double distance, ITrackSelector trackSelector,
		IEdgePointListener signalListener) {
		return travel(graph, distance, trackSelector, signalListener, ignoreTurns());
	}

	public double travel(TrackGraph graph, double distance, ITrackSelector trackSelector,
		IEdgePointListener signalListener, ITurnListener turnListener) {
		return travel(graph, distance, trackSelector, signalListener, turnListener, ignorePortals());
	}
```

### 1.2 真正的实现（第 198–354 行，6 参数版）

```java
	public double travel(TrackGraph graph, double distance, ITrackSelector trackSelector,
		IEdgePointListener signalListener, ITurnListener turnListener, IPortalListener portalListener) {
		blocked = false;
		if (edge == null)
			return 0;
		double edgeLength = edge.getLength();
		if (Mth.equal(distance, 0))
			return 0;

		double prevPos = position;
		double traveled = distance;
		double currentT = edgeLength == 0 ? 0 : position / edgeLength;
		double incrementT = edge.incrementT(currentT, distance);
		position = incrementT * edgeLength;

		// FIXME: using incrementT like this becomes inaccurate at medium-long distances
		// travelling points would travel only 50m instead of 100m due to the low
		// incrementT at their starting position (e.g. bezier turn)
		// In an ideal scenario the amount added to position would iterate the traversed
		// edges for context first

		// A workaround was added in TrackEdge::incrementT

		List<Entry<TrackNode, TrackEdge>> validTargets = new ArrayList<>();

		boolean forward = distance > 0;
		double collectedDistance = forward ? -prevPos : -edgeLength + prevPos;

		Double blockedLocation =
			edgeTraversedFrom(graph, forward, signalListener, turnListener, prevPos, collectedDistance);
		if (blockedLocation != null) {
			position = blockedLocation;
			traveled = position - prevPos;
			return traveled;
		}

		if (forward) {
			// Moving forward
			while (position > edgeLength) {
				validTargets.clear();

				for (Entry<TrackNode, TrackEdge> entry : graph.getConnectionsFrom(node2)
					.entrySet()) {
					TrackNode newNode = entry.getKey();
					if (newNode == node1)
						continue;

					TrackEdge newEdge = entry.getValue();
					if (!edge.canTravelTo(newEdge))
						continue;

					validTargets.add(entry);
				}

				if (validTargets.isEmpty()) {
					traveled -= position - edgeLength;
					position = edgeLength;
					blocked = true;
					break;
				}

				Entry<TrackNode, TrackEdge> entry = validTargets.size() == 1 ? validTargets.get(0)
					: trackSelector.apply(graph, Pair.of(true, validTargets));

				if (entry.getValue()
					.getLength() == 0 && portalListener.test(
						Couple.create(node2.getLocation(), entry.getKey()
							.getLocation()))) {
					traveled -= position - edgeLength;
					position = edgeLength;
					blocked = true;
					break;
				}

				node1 = node2;
				node2 = entry.getKey();
				edge = entry.getValue();
				position -= edgeLength;

				collectedDistance += edgeLength;
				if (edge.isTurn())
					turnListener.accept(collectedDistance, edge);

				blockedLocation = edgeTraversedFrom(graph, forward, signalListener, turnListener, 0, collectedDistance);

				if (blockedLocation != null) {
					traveled -= position;
					position = blockedLocation;
					traveled += position;
					break;
				}

				prevPos = 0;
				edgeLength = edge.getLength();
			}

		} else {
			// Moving backwards
			while (position < 0) {
				validTargets.clear();

				for (Entry<TrackNode, TrackEdge> entry : graph.getConnectionsFrom(node1)
					.entrySet()) {
					TrackNode newNode = entry.getKey();
					if (newNode == node2)
						continue;
					if (!graph.getConnectionsFrom(newNode)
						.get(node1)
						.canTravelTo(edge))
						continue;

					validTargets.add(entry);
				}

				if (validTargets.isEmpty()) {
					traveled -= position;
					position = 0;
					blocked = true;
					break;
				}

				Entry<TrackNode, TrackEdge> entry = validTargets.size() == 1 ? validTargets.get(0)
					: trackSelector.apply(graph, Pair.of(false, validTargets));

				if (entry.getValue()
					.getLength() == 0 && portalListener.test(
						Couple.create(entry.getKey()
							.getLocation(), node1.getLocation()))) {
					traveled -= position;
					position = 0;
					blocked = true;
					break;
				}

				node2 = node1;
				node1 = entry.getKey();
				edge = graph.getConnectionsFrom(node1)
					.get(node2);
				collectedDistance += edgeLength;
				edgeLength = edge.getLength();
				position += edgeLength;

				blockedLocation =
					edgeTraversedFrom(graph, forward, signalListener, turnListener, edgeLength, collectedDistance);

				if (blockedLocation != null) {
					traveled -= position;
					position = blockedLocation;
					traveled += position;
					break;
				}
			}

		}

		return traveled;
	}
```

### 1.3 关联的 `edgeTraversedFrom`（第 356–381 行）

```java
	protected Double edgeTraversedFrom(TrackGraph graph, boolean forward, IEdgePointListener edgePointListener,
		ITurnListener turnListener, double prevPos, double totalDistance) {
		if (edge.isTurn())
			turnListener.accept(Math.max(0, totalDistance), edge);

		double from = forward ? prevPos : position;
		double to = forward ? position : prevPos;

		EdgeData edgeData = edge.getEdgeData();
		List<TrackEdgePoint> edgePoints = edgeData.getPoints();

		double length = edge.getLength();
		for (int i = 0; i < edgePoints.size(); i++) {
			int index = forward ? i : edgePoints.size() - i - 1;
			TrackEdgePoint nextBoundary = edgePoints.get(index);
			double locationOn = nextBoundary.getLocationOn(edge);
			double distance = forward ? locationOn : length - locationOn;
			if (forward ? (locationOn < from || locationOn >= to) : (locationOn <= from || locationOn > to))
				continue;
			Couple<TrackNode> nodes = Couple.create(node1, node2);
			if (edgePointListener.test(totalDistance + distance, Pair.of(nextBoundary, forward ? nodes : nodes.swap())))
				return locationOn;
		}

		return null;
	}
```

## 2. 为什么值得 @Overwrite

1. `validTargets` 是**方法内唯一的大对象**，每个 `travel` 调用一次 `new ArrayList`。调用密度（已核对）：
   - `Carriage.travel` 的 2 转向架 × 2 轮 = **每车厢每 tick 最多 4 次**（`Carriage.java:172-217`，`point.travel` 在 `:203`）
   - `CarriageSyncData.approach`：客户端每轮点一次（`CarriageSyncData.java:278`）
   - `Train.java:423 → Carriage.travel`，所以放大系数是「车厢数 × 4」。
2. `edgeTraversedFrom` 的 `Couple.create(node1, node2)` 在**每次调用**至少构造 1 个 `Couple`；循环内每个「扫到的边点」再 `Pair.of(...)` 1 个（`Couple` 会被 `forward ? nodes : nodes.swap()` 复用，`swap()` 只在 backward 时再分配 1 个 `Couple`）。
   - 注意清单第 7 条说「含被 `:373` 范围判断直接 continue 的点」**不准确**：`Couple.create` 在 `:375`，位于 `:373` 的 `continue` **之后**，所以被范围判断跳过的点**不**分配 `Couple`/`Pair`。实际分配是「每个真正被扫过的边点」。
3. 三个扫描**都不改变 `node1`/`node2`**（`blockedLocation` 只改 `position`），所以「当前边的节点对」可以在方法内**算一次、复用**。

## 3. 改后完整方法体（可粘贴）

三个转发重载保持原样。下面只替换 6 参数实现，并额外把**每实例可复用的列表**放进 mixin 的 `@Unique` 字段、把**当前边节点对**在方法内缓存。

```java
	// ---- 新增 @Unique 字段（放在 TravellingPointMixin 里）----
	// 作用域：per-TravellingPoint 实例（服务端每车厢每轮点一个；客户端 CarriageSyncData 各持 4 个）。
	// 失效时机：无缓存语义，每次 travel 进来先 clear()，只在方法内使用。
	// 并发：travel 只从服务端 Train.tick / 客户端 CarriageSyncData.approach 调用（均为对应侧主线程）。
	@Unique private final List<Entry<TrackNode, TrackEdge>> createperf$validTargets = new ArrayList<>();

	@Overwrite
	public double travel(TrackGraph graph, double distance, ITrackSelector trackSelector,
		IEdgePointListener signalListener, ITurnListener turnListener, IPortalListener portalListener) {
		blocked = false;
		if (edge == null)
			return 0;
		double edgeLength = edge.getLength();
		if (Mth.equal(distance, 0))
			return 0;

		double prevPos = position;
		double traveled = distance;
		double currentT = edgeLength == 0 ? 0 : position / edgeLength;
		double incrementT = edge.incrementT(currentT, distance);
		position = incrementT * edgeLength;

		// 原实现：每次调用 new ArrayList<>()；这里复用 per-instance 列表。
		// 该列表从不逃逸出 travel（只作为 trackSelector.apply 的参数传入），因此可安全复用。
		List<Entry<TrackNode, TrackEdge>> validTargets = createperf$validTargets;
		validTargets.clear();

		boolean forward = distance > 0;
		double collectedDistance = forward ? -prevPos : -edgeLength + prevPos;

		// 三次 edgeTraversedFrom 调用期间 node1/node2 不变（只改 position），
		// 所以当前边的节点对只构造一次。edgeTraversedFrom 内部行为逐字不变。
		boolean forwardEdge = node1 == edge.node1 && node2 == edge.node2;
		Couple<TrackNode> currentNodes = Couple.create(node1, node2);
		Couple<TrackNode> passedNodes = forwardEdge ? currentNodes : Couple.create(edge.node1, edge.node2);

		Double blockedLocation = edgeTraversedFromCached(graph, forward, signalListener, turnListener, prevPos,
			collectedDistance, passedNodes);
		if (blockedLocation != null) {
			position = blockedLocation;
			traveled = position - prevPos;
			return traveled;
		}

		if (forward) {
			// Moving forward
			while (position > edgeLength) {
				validTargets.clear();

				for (Entry<TrackNode, TrackEdge> entry : graph.getConnectionsFrom(node2)
					.entrySet()) {
					TrackNode newNode = entry.getKey();
					if (newNode == node1)
						continue;

					TrackEdge newEdge = entry.getValue();
					if (!edge.canTravelTo(newEdge))
						continue;

					validTargets.add(entry);
				}

				if (validTargets.isEmpty()) {
					traveled -= position - edgeLength;
					position = edgeLength;
					blocked = true;
					break;
				}

				Entry<TrackNode, TrackEdge> entry = validTargets.size() == 1 ? validTargets.get(0)
					: trackSelector.apply(graph, Pair.of(true, validTargets));

				if (entry.getValue()
					.getLength() == 0 && portalListener.test(
						Couple.create(node2.getLocation(), entry.getKey()
							.getLocation()))) {
					traveled -= position - edgeLength;
					position = edgeLength;
					blocked = true;
					break;
				}

				node1 = node2;
				node2 = entry.getKey();
				edge = entry.getValue();
				position -= edgeLength;

				collectedDistance += edgeLength;
				if (edge.isTurn())
					turnListener.accept(collectedDistance, edge);

				blockedLocation = edgeTraversedFromCached(graph, forward, signalListener, turnListener, 0,
					collectedDistance, Couple.create(edge.node1, edge.node2));

				if (blockedLocation != null) {
					traveled -= position;
					position = blockedLocation;
					traveled += position;
					break;
				}

				prevPos = 0;
				edgeLength = edge.getLength();
			}

		} else {
			// Moving backwards
			while (position < 0) {
				validTargets.clear();

				for (Entry<TrackNode, TrackEdge> entry : graph.getConnectionsFrom(node1)
					.entrySet()) {
					TrackNode newNode = entry.getKey();
					if (newNode == node2)
						continue;
					Map<TrackNode, TrackEdge> back = graph.getConnectionsFrom(newNode);
					if (back == null || !back.get(node1)
						.canTravelTo(edge))
						continue;

					validTargets.add(entry);
				}

				if (validTargets.isEmpty()) {
					traveled -= position;
					position = 0;
					blocked = true;
					break;
				}

				Entry<TrackNode, TrackEdge> entry = validTargets.size() == 1 ? validTargets.get(0)
					: trackSelector.apply(graph, Pair.of(false, validTargets));

				if (entry.getValue()
					.getLength() == 0 && portalListener.test(
						Couple.create(entry.getKey()
							.getLocation(), node1.getLocation()))) {
					traveled -= position;
					position = 0;
					blocked = true;
					break;
				}

				node2 = node1;
				node1 = entry.getKey();
				Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(node1);
				edge = connections == null ? null : connections.get(node2);
				if (edge == null) {
					// 原实现此处会 NPE；为保持「不改变存档行为」的选择，这里也不静默吞掉：
					// 详见第 5 节「行为差异」。
					throw new IllegalStateException("TravellingPoint lost its edge while moving backwards");
				}
				collectedDistance += edgeLength;
				edgeLength = edge.getLength();
				position += edgeLength;

				blockedLocation = edgeTraversedFromCached(graph, forward, signalListener, turnListener, edgeLength,
					collectedDistance, Couple.create(edge.node1, edge.node2));

				if (blockedLocation != null) {
					traveled -= position;
					position = blockedLocation;
					traveled += position;
					break;
				}
			}

		}

		return traveled;
	}

	// 与 edgeTraversedFrom 逐字等价，只是把「当前边的节点对」由调用方传入。
	// 注意：node1/node2 在三次调用间不变，调用方传入的 passedNodes 因此与该方法内部
	// Couple.create(node1, node2) 的值完全一致。
	@Unique
	private Double edgeTraversedFromCached(TrackGraph graph, boolean forward, IEdgePointListener edgePointListener,
		ITurnListener turnListener, double prevPos, double totalDistance, Couple<TrackNode> passedNodes) {
		if (edge.isTurn())
			turnListener.accept(Math.max(0, totalDistance), edge);

		double from = forward ? prevPos : position;
		double to = forward ? position : prevPos;

		EdgeData edgeData = edge.getEdgeData();
		List<TrackEdgePoint> edgePoints = edgeData.getPoints();

		double length = edge.getLength();
		for (int i = 0; i < edgePoints.size(); i++) {
			int index = forward ? i : edgePoints.size() - i - 1;
			TrackEdgePoint nextBoundary = edgePoints.get(index);
			double locationOn = nextBoundary.getLocationOn(edge);
			double distance = forward ? locationOn : length - locationOn;
			if (forward ? (locationOn < from || locationOn >= to) : (locationOn <= from || locationOn > to))
				continue;
			if (edgePointListener.test(totalDistance + distance,
				Pair.of(nextBoundary, forward ? passedNodes : passedNodes.swap())))
				return locationOn;
		}

		return null;
	}
```

### 需要的 import

```java
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.entity.TravellingPoint.IEdgePointListener;
import com.simibubi.create.content.trains.entity.TravellingPoint.IPortalListener;
import com.simibubi.create.content.trains.entity.TravellingPoint.ITrackSelector;
import com.simibubi.create.content.trains.entity.TravellingPoint.ITurnListener;
import com.simibubi.create.content.trains.graph.EdgeData;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;

import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.Pair;
import net.minecraft.util.Mth;
```

## 4. Mixin 类骨架

```java
@Mixin(value = TravellingPoint.class, remap = false)
public abstract class TravellingPointMixin {

	@Unique private final List<Entry<TrackNode, TrackEdge>> createperf$validTargets = new ArrayList<>();

	@Overwrite
	public double travel(TrackGraph graph, double distance, ITrackSelector trackSelector,
		IEdgePointListener signalListener, ITurnListener turnListener, IPortalListener portalListener) {
		/* 见第 3 节 */
	}

	@Unique
	private Double edgeTraversedFromCached(/* ... */) { /* 见第 3 节 */ }
}
```

描述符：`(Lcom/simibubi/create/content/trains/graph/TrackGraph;DLcom/simibubi/create/content/trains/entity/TravellingPoint$ITrackSelector;Lcom/simibubi/create/content/trains/entity/TravellingPoint$IEdgePointListener;Lcom/simibubi/create/content/trains/entity/TravellingPoint$ITurnListener;Lcom/simibubi/create/content/trains/entity/TravellingPoint$IPortalListener;)D`

## 5. 风险

| 风险 | 说明 |
| --- | --- |
| **列表复用前提** | `validTargets` 会被传给 `trackSelector.apply(graph, Pair.of(..., validTargets))`。Create 自带的所有 selector（`random()`、`follow()`、`steer()`、`Train.navigation.control`）都只在调用内遍历、不保留引用（已核对 `TravellingPoint.java:83-182`）。**但第三方 mod 的 ITrackSelector 可能保留该引用** —— 这是硬前提，必须在第 6 节测试里验证。 |
| **行为差异（backward 分支）** | 原实现在 `graph.getConnectionsFrom(node1)` 返回的 map 为 null 时会 NPE（`getConnectionsFrom` 在节点不存在时返回 `null` —— miss 分支返回的是空 map，只有 `node == null` 才返回 null）。上面的实现里我**显式抛出 IllegalStateException**：与原 NPE 同属崩溃，不算静默行为漂移。若不想引入异常差异，可直接保留原语句 `edge = graph.getConnectionsFrom(node1).get(node2);`（也会 NPE）。 |
| **`node1 == edge.node1` 对偶判断** | 这是为了与 `edgeTraversedFrom` 原语义完全一致（它读字段 `node1`/`node2`，而不是 `edge.node1/edge.node2`）。存在节点对反向的边（`edge.node1 == node2`）时，这个判断会走 `else` 分支重建 `Couple`，退化成每次多 1 个 `Couple` —— 但**结果与原来完全一致**。 |
| **`@Unique` 方法名冲突** | `edgeTraversedFromCached` 是 `@Unique` 私有方法，不会与目标类成员冲突。 |
| **客户端`approach` 路径** | `CarriageSyncData.approach` 也在客户端主线程调用 `travel`，同一实例不会被两侧同时用（客户端只跑客户端实例）。 |
| **上游重构** | 方法签名变动 → 启动报错。 |
| **与 Create 自带 mixin** | 无重叠。 |

**降级方案（如果担心 Overwrite 面太大）**：只做两件事 ——
1. 不重写 `travel`，改用 `@Inject(method = "travel(...)D", at = @At("HEAD"), cancellable = true)` 在 `distance == 0` 时直接 `cir.setReturnValue(0)`；
2. 对 `edgeTraversedFrom` 单独做 `@Overwrite`（方法更短、语义更窄），只做「把 `Couple.create(node1,node2)` 提到范围判断之后」这一步（**注意：原代码本来就在 range check 之后，这一步只能省掉「同一方法内多个 edge point 的重复 Couple」**，所以降级方案 2 收益极小）。
   → 结论：**收益主要来自 `validTargets` 复用，所以 Overwrite 是合理选择。**

## 6. 回归测试点

1. **基本行驶**：单节车厢列车在直线轨道上跑 100 格，F3 记录位置每 5 tick 打点，改动前后轨迹应完全一致。
2. **岔口选向**：`validTargets.size() > 1` 时走 `trackSelector`（`Carriage.travel` 里是 `follow` 或 `navigation::control`），在 5 个岔口反复通过 20 次，确认选向与改动前相同（用 `/spark` 无关，直接看列车到达的站点）。
3. **死胡同**：把轨道造到头（`validTargets.isEmpty()`），确认列车**停在轨道末端并 blocked**，不会越界。
4. **倒车**：让列车以负速度行进（backward 分支），确认位置与 `edge` 更新正常、不崩。
5. **传送门轨道段**：造一段长度为 0 的传送门轨道（`entry.getValue().getLength() == 0 && portalListener.test(...)`），确认列车在传送门处理正确。
6. **信号/观察者**：在轨道上放信号机与观察者，让列车通过，确认信号占用/释放与改动前一致（`edgeTraversedFrom` 的 listener 调用顺序与次数不能变）。
7. **第三方 selector 兼容**（关键）：如果整合包里装有会替换 `ITrackSelector` 的 mod（比如某些寻路/列车 mod），单独跑一次；若出现轨道选择错乱，说明该 selector 保留了 `validTargets` 引用 → 必须回退本方案。

## 7. 收益

**估计**：每次 `travel` 调用省 1 个 `ArrayList`（初始容量 10）；每次 `edgeTraversedFrom` 调用省 1 个 `Couple`（首次调用）到 2 个（每次扫描多个边点时仍会 `Pair.of`）。
量级：**每 tick 车厢数 × 4 次 ArrayList**，加上每 tick 边点扫描次数个 `Couple`。**估计值，需 JFR/Spark `--alloc` 实测**。
