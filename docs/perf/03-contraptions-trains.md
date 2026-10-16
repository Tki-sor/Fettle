# Contraptions / Trains 性能问题

> 覆盖范围：`com.simibubi.create.content.trains.*`（含 `entity`/`graph`/`signal`）、`com.simibubi.create.content.contraptions.*`（含 `actors.roller`/`actors.harvester`/`minecart.capability`）
> 调查依据：Create 6.0.10 / MC 1.21.1 源码逐行核对；行号以本仓库为准（有偏差的在条目内注明）
> 公共约定见 [01-mixin-playbook.md](01-mixin-playbook.md)；整体重写方案见 [overwrites/](overwrites/)


## 摘要

- 轨道图与列车实体这一层最贵的是**「命中路径也分配」**：`TrackGraph.getConnectionsFrom`（`:377`）每个命中调用都丢一个 `HashMap`，而它每 tick 被 `TravellingPoint.travel` / `CarriageSyncData` / `TrackEdge.canTravelTo` 调用数百到数千次（High）。
- 第二贵的是**每车厢每 tick 的固定分配**：`TravellingPoint.travel` 的 `new ArrayList`（`:221`，每车厢每 tick 最多 4 次）、`edgeTraversedFrom` 的 `Couple`/`Pair`（`:375-376`）、`MountedStorageManager.tick` 的两个 `HashMap`（`:196-197`）、`Train.findCollidingTrain` 最内层的 `diff.normalize()`（`:700`，O(T×C×2) 次）。
- 碰撞检测里 `ContraptionCollider.getPotentiallyCollidedShapes`（`:582-608`）用**实体盒宽高比**膨胀扫描盒：1×20×1 的竖直装置会扫到**数千格**，而其中真正的装置方块不到 20 个（High）。
- 需要**更正**两条调查结论：① `ContraptionCollider` 的降级分支**不是死代码**（`CarriageContraption` 在传送门中会返回 `null`，见第 4 条）；② `CarriageContraptionEntity` 的 `TRACK_GRAPH` 写入**不会**每 tick 强制同步脏标记（`Optional.equals` 是值相等，见第 9 条），只是小对象分配。
- 优先级建议：第 1、2、3、4 条先做（收益密度最高、注入点明确）；第 6、8、10 条按 [overwrites/](overwrites/) 的方案做；第 5、7、9、11–15 条属低频或小对象，可作顺手项。
- 7 条整体重写候选已单独成文：`TrackGraph-getConnectionsFrom`、`TravellingPoint-travel`、`CarriageSyncData-getDistanceTo`、`ContraptionCollider-getPotentiallyCollidedShapes`、`Train-findCollidingTrain`、`MountedStorageManager-tick`、`CarriageContraptionEntity-tickContraption`。

## 发现

### 1. TrackGraph.getConnectionsFrom 命中路径也构造 HashMap — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/trains/graph/TrackGraph.java:374-378（关键行 :377）` |
| 调用频次 | 每 tick、每节点一次。服务端链路：Train.tick（Train.java:423）→ Carriage.travel（Carriage.java:163-217 的 2 转向架 × 2 轮循环，:203）→ TravellingPoint.travel 的 :239/:299/:304/:334；TrackEdge.canTravelTo（TrackEdge.java:121/125/138/142）也是高频使用者。客户端：CarriageSyncData.apply:187、getDistanceTo:312/322/345。 |
| 复杂度 / 成本 | O(1) 时间，但每次命中分配 1 个 HashMap（默认容量 16，首 put 前不建 table）。全仓库共 43 处调用点（已 grep）；每车厢每 tick 至少 4 次 travel，每次 travel 至少 1~3 次本调用，叠加 canTravelTo 后为每车厢每 tick 数十次。 |
| 注入方式 | `@Inject(at = @At("HEAD"), cancellable = true)` 短路（推荐）；`@Overwrite` 次选 |
| 目标成员 | `com.simibubi.create.content.trains.graph.TrackGraph#getConnectionsFrom(Lcom/simibubi/create/content/trains/graph/TrackNode;)Ljava/util/Map;` |
| 需新增状态 | 无缓存，仅消除分配；需要一个 `@Unique static final` 共享空 map 哨兵，以及 `@Accessor` 读私有字段 `connectionsByNode` |
| 风险 | 低（单点、无状态、无生命周期）；唯一语义差异是 miss 时返回「共享空 map」而非「新空 map」 |
| 脆弱度 | 低。只依赖方法的一行实现与私有字段名 `connectionsByNode`；字段名变更会启动报错（`defaultRequire:1`） |
| 置信度 | 确定（字段声明、构造器初始化、全部调用点均已核对） |

**改前**

```java
	public Map<TrackNode, TrackEdge> getConnectionsFrom(TrackNode node) {
		if (node == null)
			return null;
		return connectionsByNode.getOrDefault(node, new HashMap<>());
	}
```

字段（同文件，已核对）：

```java
	Map<TrackNode, Map<TrackNode, TrackEdge>> connectionsByNode;   // :52 包私有，非 private
	connectionsByNode = new IdentityHashMap<>();                    // :70 构造器
	connectionsByNode.computeIfAbsent(node1, n -> new IdentityHashMap<>());  // :457 说明它会被写入
```

**为什么慢**

`getOrDefault(node, new HashMap<>())` 的第二个实参是**方法调用参数**，Java 规定它在调用前求值 —— 命中也构造。手册 §3.1/§3.2 已明确：`@Redirect`/`@ModifyExpressionValue` 在这里**无法消除分配**（参数已构造 / 会插入 checkcast）。

规模放大：`Train.tick`（`Train.java:324` 的车厢循环）→ `Carriage.travel` 的 `for (boolean firstBogey : Iterate.trueAndFalse)` × `for (boolean firstWheel : Iterate.trueAndFalse)`（`:163`、`:172`）→ 每车厢最多 4 次 `TravellingPoint.travel`。一次 `travel` 在岔口密集区会走多轮 `while (position > edgeLength)`，每轮 1 次 `getConnectionsFrom(node2)`，每个候选邻居再 1 次 `getConnectionsFrom(newNode)`（`:304`），`canTravelTo` 内部另有 2~4 次。

**改后**

```java
	// 哨兵：保持「非 null + 空 + HashMap 类型」的原有契约，消除命中路径的分配。
	// 禁止写入；Create 自身全部 43 处调用点都只读（已逐处核对）。
	private static final Map<TrackNode, TrackEdge> EMPTY_CONNECTIONS = new HashMap<>(0);

	public Map<TrackNode, TrackEdge> getConnectionsFrom(TrackNode node) {
		if (node == null)
			return null;
		Map<TrackNode, TrackEdge> connections = connectionsByNode.get(node);
		return connections != null ? connections : EMPTY_CONNECTIONS;
	}
```

**Mixin 骨架**

```java
package com.example.createperf.mixin.trains;

import java.util.HashMap;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;

@Mixin(value = TrackGraph.class, remap = false)
public abstract class TrackGraphMixin {

	// 用 @Accessor 而不是 @Shadow：connectionsByNode 在 :457 被 computeIfAbsent 写入，
	// 是否 final 需以实际反编译为准；@Accessor 两种情形都安全。
	@Accessor("connectionsByNode")
	protected abstract Map<TrackNode, Map<TrackNode, TrackEdge>> createperf$getConnectionsByNode();

	@Unique
	private static final Map<TrackNode, TrackEdge> createperf$EMPTY = new HashMap<>(0);

	@Inject(
		method = "getConnectionsFrom(Lcom/simibubi/create/content/trains/graph/TrackNode;)Ljava/util/Map;",
		at = @At("HEAD"), cancellable = true)
	private void createperf$fastGetConnectionsFrom(TrackNode node,
		CallbackInfoReturnable<Map<TrackNode, TrackEdge>> cir) {
		if (node == null) {
			cir.setReturnValue(null);
			return;
		}
		Map<TrackNode, TrackEdge> hit = createperf$getConnectionsByNode().get(node);
		cir.setReturnValue(hit != null ? hit : createperf$EMPTY);
	}
}
```
完整 `@Overwrite` 版与更细的风险分析见 [overwrites/TrackGraph-getConnectionsFrom.md](overwrites/TrackGraph-getConnectionsFrom.md)。

**验证方法**

- 探针：在 mixin 里加 `@Unique private static final AtomicLong createperf$calls` 与 `createperf$allocations`（只在 `hit == null` 时自增后者）；每 100 tick 输出一次。混入后 `allocations` 应恒为 0。
- 分配量：JFR `jdk.ObjectAllocationSample` 过滤 `java.util.HashMap`，对比开关 mixin 两次；或 Spark `/spark profiler start --alloc`。
- 端到端：同一存档（20+ 岔口、10 列车）跑 `/spark tps` + `/spark profiler --timeout 60`，前后各 3 次取中位数。
- 反向验证：临时把哨兵换成「`put` 即抛异常」的 `Map`（**仅测试**），跑 2 分钟；不抛异常即证明无调用点写入返回值。

**收益估计**：每次命中调用省 1 个 `HashMap`；量级为每 tick 数百到数千个对象（随车厢数/岔口数线性放大）。**这是静态推理的量级估计，不是实测值。**
**回归测试点**：① 岔口选向不变（同一路线跑 10 次到达站点一致）；② 死胡同端点节点不崩 —— 覆盖 `TrackGraphBounds:27-28` 直接 `.values()` 的路径；③ 拆/铺轨道触发 `removeNode`/`connectNodes` 后图结构正确；④ 跨维度轨道迁移（`TravellingPoint.migrateTo`）正常；⑤ 信号传播（`SignalPropagator`，第二大使用者）不错组。

---

### 2. TravellingPoint.travel 每车厢每 tick 分配候选列表与节点对 — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/trains/entity/TravellingPoint.java:198-354（:221 的 new ArrayList）；关联 :356-381 的 edgeTraversedFrom（:375-376）` |
| 调用频次 | 服务端：Train.tick:423 → Carriage.travel 的 2 转向架 × 2 轮循环 → 每车厢每 tick 最多 4 次 point.travel。客户端：CarriageSyncData.approach:278（每个非零 pointDistanceSnapshot 的轮点一次）。 |
| 复杂度 / 成本 | 每次调用 1 个 ArrayList（默认容量 10）；edgeTraversedFrom 每个通过区间判断的边点 1 个 Couple + 1 个 Pair。 |
| 注入方式 | `@Overwrite`（推荐）；`@Inject(HEAD, cancellable)` 只能覆盖 `distance == 0` 的早退 |
| 目标成员 | `com.simibubi.create.content.trains.entity.TravellingPoint#travel(Lcom/simibubi/create/content/trains/graph/TrackGraph;DLcom/simibubi/create/content/trains/entity/TravellingPoint$ITrackSelector;Lcom/simibubi/create/content/trains/entity/TravellingPoint$IEdgePointListener;Lcom/simibubi/create/content/trains/entity/TravellingPoint$ITurnListener;Lcom/simibubi/create/content/trains/entity/TravellingPoint$IPortalListener;)D` |
| 需新增状态 | `@Unique private final List<Entry<TrackNode,TrackEdge>> createperf$validTargets`：per-instance；每次调用开头 `clear()`；仅在对应侧主线程使用 |
| 风险 | 中（`@Overwrite` 体量 + 「列表复用」依赖 `ITrackSelector` 不保留引用） |
| 脆弱度 | 中。依赖「edgeTraversedFrom 只改 position、不改 node1/node2」这一事实；上游若改则本优化失效（但不会出错） |
| 置信度 | 高概率（分配点已逐行核对；列表复用安全性依赖第三方 ITrackSelector，需实测） |

**改前**

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

		List<Entry<TrackNode, TrackEdge>> validTargets = new ArrayList<>();   // :221 每次调用都分配

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
				// ...（后半段与 backward 分支请见 overwrites 文档，逐字不改）
			}
		}
		return traveled;
	}
```

`edgeTraversedFrom` 的关键行（`:368-377`）：

```java
		for (int i = 0; i < edgePoints.size(); i++) {
			int index = forward ? i : edgePoints.size() - i - 1;
			TrackEdgePoint nextBoundary = edgePoints.get(index);
			double locationOn = nextBoundary.getLocationOn(edge);
			double distance = forward ? locationOn : length - locationOn;
			if (forward ? (locationOn < from || locationOn >= to) : (locationOn <= from || locationOn > to))
				continue;                                        // 范围外直接跳过（在分配之前）
			Couple<TrackNode> nodes = Couple.create(node1, node2);   // :375 在循环体内
			if (edgePointListener.test(totalDistance + distance, Pair.of(nextBoundary, forward ? nodes : nodes.swap())))
				return locationOn;                               // :376 每个进入区间的点 1 个 Pair
		}
```

**为什么慢**

1. `validTargets` 是方法里唯一的大对象（`:221`，位于所有早退之后），每车厢每 tick 最多 4 次；10 节车厢即约 40 个 `ArrayList`/tick = 800 个/秒（估计）。
2. `edgeTraversedFrom` 的 `Couple.create(node1, node2)` 位于 **for 循环体内**（逐行核对确认），因此**每个通过区间的边点**都会新建一个 `Couple`；backward 时 `nodes.swap()` 再多一个。
   **更正清单第 7 条**：「含被 `:373` 范围判断直接 continue 的点」不成立 —— 分配在 `continue` **之后**。

**改后**

完整可粘贴方法体见 [overwrites/TravellingPoint-travel.md](overwrites/TravellingPoint-travel.md)。核心三点：

```java
	// 1) 复用 per-instance 列表
	@Unique private final List<Entry<TrackNode, TrackEdge>> createperf$validTargets = new ArrayList<>();
	List<Entry<TrackNode, TrackEdge>> validTargets = createperf$validTargets;
	validTargets.clear();

	// 2) 当前边的节点对在方法内算一次（三次 edgeTraversedFrom 之间 node1/node2 不变）
	Couple<TrackNode> passedNodes = (node1 == edge.node1 && node2 == edge.node2)
		? Couple.create(node1, node2) : Couple.create(edge.node1, edge.node2);

	// 3) 把 edgeTraversedFrom 复制为 @Unique 方法并接受 passedNodes（行为逐字不变）
```

**Mixin 骨架**

```java
@Mixin(value = TravellingPoint.class, remap = false)
public abstract class TravellingPointMixin {

	@Unique private final List<Entry<TrackNode, TrackEdge>> createperf$validTargets = new ArrayList<>();

	@Overwrite
	public double travel(TrackGraph graph, double distance, ITrackSelector trackSelector,
		IEdgePointListener signalListener, ITurnListener turnListener, IPortalListener portalListener) {
		/* 完整方法体见 overwrites/TravellingPoint-travel.md 第 3 节 */
	}

	@Unique
	private Double edgeTraversedFromCached(TrackGraph graph, boolean forward, IEdgePointListener edgePointListener,
		ITurnListener turnListener, double prevPos, double totalDistance, Couple<TrackNode> passedNodes) {
		/* 与 TravellingPoint.edgeTraversedFrom 逐字等价，只是节点对由调用方传入 */
	}
}
```

**验证方法**

- 探针：`@Unique private static final AtomicLong createperf$travelCalls`，在 `clear()` 处统计调用次数与列表容量；JFR 过滤 `java.util.ArrayList`。
- **兼容性验证（最重要）**：整合包全量 mod 跑 30 分钟，打日志记录 `trackSelector.getClass()`；若存在第三方 `ITrackSelector`，需单独确认它不缓存传入的 `validTargets`。
- 端到端：10 节车厢列车在环线跑 10 分钟，`/spark profiler --timeout 60`，对比 young gen GC 频率。

**收益估计**：每 tick 省 `车厢数 × 4` 个 `ArrayList`，以及边点扫描次数个 `Couple`/`Pair`（估计：10 节车厢约 40 个 ArrayList/tick + 若干 Couple）。**估计值，需 JFR 实测。**
**回归测试点**：① 直线轨迹逐 tick 一致（F3 坐标对比）；② 岔口选向一致；③ 死胡同 blocked 行为一致；④ 倒车（backward 分支）不崩；⑤ 0 长度传送门轨道段行为一致；⑥ 信号机/观察者的 listener 调用顺序与次数不变；⑦ 整合包环境（第三方 `ITrackSelector`）。

---

### 3. MountedStorageManager.tick 无条件分配两个 HashMap — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/contraptions/MountedStorageManager.java:190-216（关键行 :196-197）` |
| 调用频次 | 每个 contraption 每 tick 一次，客户端也执行。链路：AbstractContraptionEntity.tick:368 → :386 contraption.tickStorage(this) → Contraption.tickStorage:1502-1504 → MountedStorageManager.tick。syncCooldown 只挡 8 tick，且仅在真发过包时设置。 |
| 复杂度 / 成本 | 每次进入分配路径固定 2 个 HashMap；稳态约每 9 tick 一次。客户端 sendToClientsTrackingEntity 是 no-op，这整段客户端 forEach + 分配是纯浪费。 |
| 注入方式 | `@Overwrite`（推荐，懒构造）；或 `@Inject(HEAD, cancellable)` 只做「客户端跳过」（行为变更，需声明） |
| 目标成员 | `com.simibubi.create.content.contraptions.MountedStorageManager#tick(Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;)V` |
| 需新增状态 | 无（懒构造版）；map 复用版需 2 个 `@Unique` map |
| 风险 | 低（懒构造版逐字保持语义）；中（客户端跳过版是行为变更） |
| 脆弱度 | 低。方法只有 27 行，且 syncedItems/syncedFluids 是私有 ImmutableMap 字段 |
| 置信度 | 确定（方法体与调用链已核对） |

**改前**

```java
	public void tick(AbstractContraptionEntity entity) {
		if (this.syncCooldown > 0) {
			this.syncCooldown--;
			return;
		}

		Map<BlockPos, MountedItemStorage> items = new HashMap<>();      // 无条件
		Map<BlockPos, MountedFluidStorage> fluids = new HashMap<>();    // 无条件
		this.syncedItems.forEach((pos, storage) -> {
			if (storage.isDirty()) {
				items.put(pos, (MountedItemStorage) storage);
				storage.markClean();
			}
		});
		this.syncedFluids.forEach((pos, storage) -> {
			if (storage.isDirty()) {
				fluids.put(pos, (MountedFluidStorage) storage);
				storage.markClean();
			}
		});

		if (!items.isEmpty() || !fluids.isEmpty()) {
			MountedStorageSyncPacket packet = new MountedStorageSyncPacket(entity.getId(), items, fluids);
			CatnipServices.NETWORK.sendToClientsTrackingEntity(entity, packet);
			this.syncCooldown = 8;
		}
	}
```

**为什么慢**

- `syncCooldown` 只在「发了包」时被设为 8 → 「有 storage 但都没动」的装置每 9 tick 就要为两个空 map 付账。
- 放大：车厢 contraption 数 = 车厢数，10 节车厢在客户端每 tick 进入 10 次。
- 客户端不存在发包可能，所以客户端的分配永远不会被 `syncCooldown = 8` 补偿，只是每 8 tick 白做一次全 map 遍历。

**改后**

```java
	public void tick(AbstractContraptionEntity entity) {
		if (this.syncCooldown > 0) {
			this.syncCooldown--;
			return;
		}

		// 懒构造：无脏 storage 时（绝大多数 tick）不再 new 两个 HashMap。
		Map<BlockPos, MountedItemStorage> items = null;
		Map<BlockPos, MountedFluidStorage> fluids = null;

		for (Map.Entry<BlockPos, SyncedMountedStorage> e : this.syncedItems.entrySet()) {
			SyncedMountedStorage storage = e.getValue();
			if (!storage.isDirty())
				continue;
			if (items == null)
				items = new HashMap<>();
			items.put(e.getKey(), (MountedItemStorage) storage);
			storage.markClean();
		}
		for (Map.Entry<BlockPos, SyncedMountedStorage> e : this.syncedFluids.entrySet()) {
			SyncedMountedStorage storage = e.getValue();
			if (!storage.isDirty())
				continue;
			if (fluids == null)
				fluids = new HashMap<>();
			fluids.put(e.getKey(), (MountedFluidStorage) storage);
			storage.markClean();
		}

		if (items == null && fluids == null)
			return;

		MountedStorageSyncPacket packet = new MountedStorageSyncPacket(entity.getId(),
			items == null ? Collections.emptyMap() : items,
			fluids == null ? Collections.emptyMap() : fluids);
		CatnipServices.NETWORK.sendToClientsTrackingEntity(entity, packet);
		this.syncCooldown = 8;
	}
```

> 必须用 `for` 而不是 `forEach`：Java 的 lambda 不能给外层局部变量重新赋值，「懒构造 + lambda」在语言层面不可行。

**Mixin 骨架**

```java
@Mixin(value = MountedStorageManager.class, remap = false)
public abstract class MountedStorageManagerMixin {

	@Overwrite
	public void tick(AbstractContraptionEntity entity) {
		/* 见上「改后」；目标方法是 public，@Overwrite handler 也必须是 public */
	}
}
```

只做「客户端跳过」（收益更大，但属行为变更）：

```java
	@Inject(method = "tick(Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;)V",
		at = @At("HEAD"), cancellable = true)
	private void createperf$skipOnClient(AbstractContraptionEntity entity, CallbackInfo ci) {
		// sendToClientsTrackingEntity 在客户端是 no-op，客户端这条路径只产生垃圾。
		// 副作用：客户端 syncCooldown 不再递减（它只用于节流发包，客户端不用）→ 行为变更，需声明
		if (entity.level().isClientSide)
			ci.cancel();
	}
```

**验证方法**

- 探针：在 `tick` HEAD 处用 `@Unique static AtomicLong` 记录「进入次数」与「实际发包次数」；混入后 `HashMap` 分配事件应只出现在「有脏 storage」时。
- 端到端：客户端 F3 观察一列装满箱子但不操作的装置列车，对比 JFR `ObjectAllocationSample` 中 `HashMap` 样本数。

**收益估计**：稳态每 contraption 每 9 tick 省 2 个 `HashMap`；10 节车厢列车约每秒数十个对象；客户端跳过版还能省掉每 8 tick 一次的全 map 遍历（估计值）。
**回归测试点**：① 移动装置上箱子内容变化必须能同步到客户端；② 一装置 4 箱 + 2 罐同时操作，变化不丢；③ `syncCooldown = 8` 节流仍生效；④ 列车车厢内箱子同步正常；⑤ 电梯（`ElevatorContraption.tickStorage` 在 super 之后做事）名称列表同步不受影响；⑥ 空装置跑 5 分钟无异常。

---

### 4. ContraptionCollider.getPotentiallyCollidedShapes 按宽高比膨胀扫描盒 — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/contraptions/ContraptionCollider.java:582-608（关键行 :585-588 膨胀、:593 betweenClosed）；调用点 :156-164` |
| 调用频次 | collideEntities 内每个满足 canCollideWith 的实体调用一次（:100-102 先圈实体）；collideEntities 随 contraption 实体每 tick 调用。放大系数 = 装置附近实体数。 |
| 复杂度 / 成本 | O(扫描盒体积)。1×20×1 装置 + 玩家盒（高≈1.8、宽≈0.6）→ verticalFactor≈3、horizontalFactor≈3 → 扫描盒约 4×24×4 → 数千次迭代 + 每次 2 次 BlockPos.hashCode（containsKey + get）。 |
| 注入方式 | `@Overwrite`（推荐）；或 `@WrapOperation` 拦 :163 的调用 |
| 目标成员 | `com.simibubi.create.content.contraptions.ContraptionCollider#getPotentiallyCollidedShapes(Lnet/minecraft/world/level/Level;Lcom/simibubi/create/content/contraptions/Contraption;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/phys/shapes/Shapes$DoubleLineConsumer;)V`（private static） |
| 需新增状态 | 无 |
| 风险 | 中（收集范围/顺序变化，碰撞结果需实测确认一致） |
| 脆弱度 | 低。方法签名与 contraption.blocks 字段稳定；上游重写碰撞检测则注入直接失配报错 |
| 置信度 | 高概率（膨胀公式与遍历已核对；碰撞结果是否逐位一致需实测） |

**改前**

```java
	private static void getPotentiallyCollidedShapes(Level world, Contraption contraption, AABB localBB, DoubleLineConsumer out) {
		double height = localBB.getYsize();
		double width = localBB.getXsize();
		double horizontalFactor = (height > width && width != 0) ? height / width : 1;
		double verticalFactor = (width > height && height != 0) ? width / height : 1;
		AABB blockScanBB = localBB.inflate(0.5f);
		blockScanBB = blockScanBB.inflate(horizontalFactor, verticalFactor, horizontalFactor);

		BlockPos min = BlockPos.containing(blockScanBB.minX, blockScanBB.minY, blockScanBB.minZ);
		BlockPos max = BlockPos.containing(blockScanBB.maxX, blockScanBB.maxY, blockScanBB.maxZ);

		for (BlockPos p : BlockPos.betweenClosed(min, max)) {
			if (contraption.blocks.containsKey(p) && !contraption.isHiddenInPortal(p)) {
				StructureBlockInfo info = contraption.getBlocks().get(p);

				BlockState blockState = info.state();
				BlockPos pos = info.pos();

				VoxelShape collisionShape = blockState.getCollisionShape(world, p)
						.move(pos.getX(), pos.getY(), pos.getZ());

				if (!collisionShape.isEmpty()) {
					collisionShape.forAllBoxes(out);
				}
			}
		}
	}
```

**为什么慢**

- 扫描盒由**被检测实体的包围盒宽高比**决定，而非 contraption 形状 → 装置越细长，扫描体积越大而命中率越低。1×20×1 装置扫数千格、命中约 20 格，命中率 <1%。
- 每次迭代 2 次哈希（`containsKey` + `get`），`Contraption.blocks` 是普通 `Map`（非 `IdentityHashMap`），`BlockPos.hashCode` 还要遍历坐标分量。
- 触发条件是 `getSimplifiedEntityColliders()` 返回 `null`。**更正清单第 4 条**：普通 `Contraption` 的 `getSimplifiedEntityColliders`（`Contraption.java:1497-1500`）确实永不返回 null；但 `CarriageContraption`（`src/main/java/com/simibubi/create/content/trains/entity/CarriageContraption.java:252-258`）重写了它，在 `!notInPortal()`（正在传送门中）时**返回 null** → 对「传送门里的列车车厢」，这个降级分支**会被真正执行**，所以**不是死代码**，只是触发条件窄。

**改后**

```java
	private static void getPotentiallyCollidedShapes(Level world, Contraption contraption, AABB localBB, DoubleLineConsumer out) {
		Map<BlockPos, StructureBlockInfo> blocks = contraption.blocks;
		if (blocks.isEmpty())
			return;

		// 原实现按实体盒宽高比 inflate 出立方体扫描盒（1×20×1 装置会扫数千格）。
		// 改为「遍历 contraption 方块 + 局部盒相交测试」：O(装置方块数)。
		// 用 localBB（而非膨胀后的 blockScanBB）→ 范围更紧：可能多收集，绝不会漏。
		double minX = localBB.minX, minY = localBB.minY, minZ = localBB.minZ;
		double maxX = localBB.maxX, maxY = localBB.maxY, maxZ = localBB.maxZ;

		for (Map.Entry<BlockPos, StructureBlockInfo> entry : blocks.entrySet()) {
			BlockPos localPos = entry.getKey();

			// 手工内联的 AABB 相交测试，避免每个方块 new AABB
			if (localPos.getX() + 1 <= minX || localPos.getX() >= maxX)
				continue;
			if (localPos.getY() + 1 <= minY || localPos.getY() >= maxY)
				continue;
			if (localPos.getZ() + 1 <= minZ || localPos.getZ() >= maxZ)
				continue;

			if (contraption.isHiddenInPortal(localPos))
				continue;

			StructureBlockInfo info = entry.getValue();
			VoxelShape collisionShape = info.state().getCollisionShape(world, localPos)
				.move(info.pos().getX(), info.pos().getY(), info.pos().getZ());

			if (!collisionShape.isEmpty()) {
				collisionShape.forAllBoxes(out);
			}
		}
	}
```

**Mixin 骨架**

```java
@Mixin(value = ContraptionCollider.class, remap = false)
public abstract class ContraptionColliderMixin {

	@Overwrite
	private static void getPotentiallyCollidedShapes(Level world, Contraption contraption, AABB localBB,
		DoubleLineConsumer out) {
		/* 见上「改后」。目标方法是 private static，handler 也必须是 private static */
	}
}
```

> 建议加规模保护：`if (blocks.size() > 4096) { /* 走原扫描逻辑 */ }`，避免「装置极大 + 扫描区域很小」时反而变慢。

**验证方法**

- 正确性（最重要）：自动化三个场景 —— ① 玩家贴 1×20×1 柱状装置行走；② 装置自下而上顶玩家；③ 装置中间留门洞玩家穿过。每 5 tick 记录玩家坐标，混入前后逐点比对（允许极小浮点差异，>1e-6 需人工确认）。
- 性能：Spark `/spark profiler start --timeout 60`，在「1×20×1 装置 + 玩家贴近」场景下对比 `getPotentiallyCollidedShapes` 相关样本占比。
- 时间（而非分配）：`betweenClosed` 复用同一个可变 `BlockPos`，所以主成本是**迭代次数 × 双哈希时间**，用 profiler 采样而非分配计数。

**收益估计**：单次调用从「数千格迭代 + 数千次双哈希」降到「装置方块数（约 20）× 3 次整数比较 + 约 20 次哈希」；量级为**每次调用少上千次哈希查找**（估计值，需实测）。
**回归测试点**：① 竖直细长装置不穿模、不卡住玩家；② 传送门中的列车车厢（触发 `CarriageContraption` 返回 null）碰撞正常；③ 宽平板装置从下方顶人（`maxUpStep` 路径）正常；④ 装置门洞可穿过；⑤ 多实体（5 盔甲架 + 2 玩家）无退化；⑥ A/B 开关对比玩家位置轨迹。

---

### 5. AbstractContraptionEntity.tick 的 removeIf 每 tick 分配 Iterator — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/contraptions/AbstractContraptionEntity.java:374-376（字段 :86 public final Map<Entity, MutableInt>，初始化 :105）` |
| 调用频次 | 每个 contraption 实体每 tick 一次（tick() 开头，:368） |
| 复杂度 / 成本 | entrySet().removeIf(...) 每次执行至少 1 个 IdentityHashMap 迭代器对象；map 为空时也分配。计数自增走 MutableInt.incrementAndGet()（可变对象，无装箱）。 |
| 注入方式 | `@Overwrite` `tick()`；若不想复制 36 行则建议放弃（收益仅 1 个 Iterator/tick） |
| 目标成员 | `com.simibubi.create.content.contraptions.AbstractContraptionEntity#tick()V` |
| 需新增状态 | 无 |
| 风险 | 低（替换标准集合 API 调用，语义等价） |
| 脆弱度 | 低。依赖 collidingEntities 的类型（已核对 :86 为 public final Map<Entity, MutableInt>） |
| 置信度 | 确定 |

**改前**

```java
	@Override
	public void tick() {
		if (contraption == null) {
			discard();
			return;
		}

		collidingEntities.entrySet()
			.removeIf(e -> e.getValue()
				.incrementAndGet() > 3);

		xo = getX();
		yo = getY();
		zo = getZ();
		prevPosInvalid = false;
		// ... 后续（contraptionInitialize / tickStorage / tickContraption / super.tick / 乘客修正）逐字不变
	}
```

**为什么慢**

`Map.entrySet().removeIf(Predicate)` 走 `Collection.removeIf` 默认实现 → 必然 `iterator()`。`IdentityHashMap` 的迭代器是独立对象，且 **map 为空时也会创建**。装置与实体发生碰撞的 tick 极少，所以绝大多数 tick 都是「空 map + 分配一个 Iterator」。10 个 contraption 实体 = 每秒 200 个 Iterator。

**改后**

```java
		if (!collidingEntities.isEmpty()) {
			Iterator<Entry<Entity, MutableInt>> iterator = collidingEntities.entrySet()
				.iterator();
			while (iterator.hasNext()) {
				if (iterator.next()
					.getValue()
					.incrementAndGet() > 3)
					iterator.remove();
			}
		}
```

**Mixin 骨架**

```java
@Mixin(value = AbstractContraptionEntity.class, remap = false)
public abstract class AbstractContraptionEntityMixin {

	@Shadow @Final public Map<Entity, MutableInt> collidingEntities;

	@Overwrite
	public void tick() {
		/* 需完整复制 tick() 的 36 行；仅把 removeIf 换成带 isEmpty 守卫的迭代器循环。
		   若不想复制，建议放弃本条 —— 收益仅每 tick 1 个 Iterator。 */
	}
}
```

**验证方法**

- 探针：`@Unique static AtomicLong` 统计「空 map tick 比例」，预期 >95%。
- 分配：JFR 过滤 `java.util.IdentityHashMap` 内部迭代器类，混入前后对比。

**收益估计**：每 tick 每装置 1 个 Iterator；10 装置约 200 个/秒（估计值）。
**回归测试点**：① 玩家站在/被推时「3 tick 后移除」行为不变；② 反复接触同一实体的计数器语义不变；③ `:142` `collidingEntities.put(collidingEntity, new MutableInt())` 路径不受影响。

---

### 6. Train.findCollidingTrain 每 tick 双重循环 + 最内层 normalize — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/trains/entity/Train.java:655-730（关键行 :656-657、:700、:716）` |
| 调用频次 | 每 tick 每列车一次：Train.tick:289 → collideWithOtherTrains:623（在 :436 只对 index == 0 的车厢调用）→ :639 findCollidingTrain。另有 TrainRelocator.java:227（装配时）。 |
| 复杂度 / 成本 | O(T × C × 2)。内层每次构造 1 个 Vec3 diff2 + 1 个 Vec3 normedDiff2 + 1 个 Vec3 normedDiff（循环不变量被重复构造）；每 tick 一次 Math.pow 与配置读取。 |
| 注入方式 | `@Overwrite`（推荐）；`@ModifyExpressionValue` 无法消除 `Math.pow`/`normalize` |
| 目标成员 | `com.simibubi.create.content.trains.entity.Train#findCollidingTrain(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/resources/ResourceKey;)Lnet/createmod/catnip/data/Pair;` |
| 需新增状态 | 无 |
| 风险 | 低（三处替换都是把同一值从内层提到外层） |
| 脆弱度 | 高。方法含带标签的 continue Trains；Overwrite 时必须保留原 for 结构，否则易写错 |
| 置信度 | 确定（diff 在整个方法内不变，normedDiff 只依赖 diff） |

**改前（关键行）**

```java
	public Pair<Train, Vec3> findCollidingTrain(Level level, Vec3 start, Vec3 end, ResourceKey<Level> dimension) {
		Vec3 diff = end.subtract(start);                                                          // :656
		double maxDistanceSqr = Math.pow(AllConfigs.server().trains.maxAssemblyLength.get(), 2.0); // :657

		Trains: for (Train train : Create.RAILWAYS.sided(level).trains.values()) {
			...
			for (Carriage otherCarriage : train.carriages) {
				for (boolean betweenBits : Iterate.trueAndFalse) {
					...
					Vec3 start2 = otherLeading.getPosition(train.graph);
					Vec3 end2 = otherTrailing.getPosition(train.graph);
					if (Math.min(start2.distanceToSqr(start), end2.distanceToSqr(start)) > maxDistanceSqr)
						continue Trains;
					...
					Vec3 diff2 = end2.subtract(start2);
					Vec3 normedDiff = diff.normalize();                          // :700 循环不变量，重复构造
					Vec3 normedDiff2 = diff2.normalize();
					double[] intersect = VecHelper.intersect(start, start2, normedDiff, normedDiff2, Axis.Y);
					...
					if (intersect[0] > diff.length())                            // :716 重复 .length()
						continue;
					...
```

**为什么慢**

- `normedDiff` 依赖的 `diff` 是整方法不变量，却在最内层构造 → `O(T×C×2)` 次 `Vec3` 分配 + `1/sqrt`；而且它**先于** `intersect` 计算，即便紧接着 `continue` 也白算。
- `diff.length()`（`:716`）同样重复计算（又一次 `sqrt`）。
- 规模：T=10、C=5 时每 tick 约 100 次多余 `normalize()`；T=20、C=8 时约 320 次。
- `Math.pow(x, 2.0)` 是 libm 调用；配置项是 `ConfigInt`（`CTrains.java:9`，默认 128），整数平方用乘法即可。

**改后（最小改动版，保留 `continue Trains`）**

```java
	public Pair<Train, Vec3> findCollidingTrain(Level level, Vec3 start, Vec3 end, ResourceKey<Level> dimension) {
		Vec3 diff = end.subtract(start);
		double diffLength = diff.length();                          // ① 提到循环外
		Vec3 normedDiff = diff.normalize();                         // ① 提到循环外（原在最内层）
		double maxAssemblyLength = AllConfigs.server().trains.maxAssemblyLength.get();
		double maxDistanceSqr = maxAssemblyLength * maxAssemblyLength;   // ② 去 Math.pow
		// ③ 不要缓存到静态字段：用户改配置必须立即生效（手册 §7 反模式）

		Trains: for (Train train : Create.RAILWAYS.sided(level).trains.values()) {
			/* ... 与原实现逐字相同 ... */
					Vec3 diff2 = end2.subtract(start2);
					Vec3 normedDiff2 = diff2.normalize();           // ③ 只算 diff2 的
					double[] intersect = VecHelper.intersect(start, start2, normedDiff, normedDiff2, Axis.Y);
					/* ... */
					if (intersect[0] > diffLength)                  // ③ 用缓存长度
						continue;
					if (intersect[1] > diff2.length())
						continue;
					/* ... */
		}
		return null;
	}
```

**Mixin 骨架**

```java
@Mixin(value = Train.class, remap = false)
public abstract class TrainMixin {

	@Overwrite
	public Pair<Train, Vec3> findCollidingTrain(Level level, Vec3 start, Vec3 end, ResourceKey<Level> dimension) {
		/* 用 overwrites/Train-findCollidingTrain.md 第 3 节的「最小改动版」；
		   不要用那篇文档里 while(iterator) 的版本 —— 它会破坏 continue Trains */
	}
}
```

完整方法与风险分析见 [overwrites/Train-findCollidingTrain.md](overwrites/Train-findCollidingTrain.md)。

**验证方法**

- 语义：`maxAssemblyLength` 默认 128（`CTrains.java:9`），`128*128` 与 `Math.pow(128, 2.0)` 逐位相等。
- 功能：相向而行必须相撞（`:646-652` 的 `explode`）；平行轨道擦肩不得误判。
- 配置：运行中改 `maxAssemblyLength`，验证立即生效。
- 性能：Spark `/spark profiler --timeout 60`，场景为 10+ 列车同图，观察 `Vec3` 构造/`normalize` 样本。

**收益估计**：每 tick 少 `O(T×C×2)` 次 `Vec3.normalize()`（每次 1 个 `Vec3` + 1 次 `sqrt`）与 1 次 `Math.pow`（T=10、C=5 约 100 次/tick，估计值）。
**回归测试点**：① 相向对撞触发爆炸；② 平行擦肩不误判；③ 同向追尾可判到；④ 不同维度不互相检测；⑤ `maxAssemblyLength` 调小后长列车仍被检测；⑥ 运行中改配置立即生效；⑦ 列车装配器搬迁（`TrainRelocator:227`）在目标位置有车时拒绝。

---

### 7. TravellingPoint.edgeTraversedFrom 每个进入区间的边点都构造 Couple+Pair — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/trains/entity/TravellingPoint.java:356-381（关键行 :375-376）` |
| 调用频次 | 每次 travel 至少 1 次（:227）；移动跨边时每跨一步再 1 次（:281、:341）。 |
| 复杂度 / 成本 | 每个通过 :373 区间判断的边点：1 个 Couple（forward ? nodes : nodes.swap() 在 backward 时再多 1 个）+ 1 个 Pair。 |
| 注入方式 | 并入第 2 条的 `@Overwrite travel`（推荐）；单独 Overwrite 本方法收益极小 |
| 目标成员 | `com.simibubi.create.content.trains.entity.TravellingPoint#edgeTraversedFrom(Lcom/simibubi/create/content/trains/graph/TrackGraph;ZLcom/simibubi/create/content/trains/entity/TravellingPoint$IEdgePointListener;Lcom/simibubi/create/content/trains/entity/TravellingPoint$ITurnListener;DD)Ljava/lang/Double;`（protected） |
| 需新增状态 | 无 |
| 风险 | 低 |
| 脆弱度 | 低（方法短、逻辑直白） |
| 置信度 | 确定（Couple.create 在 :373 的 continue 之后，逐行核对） |

**改前**

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
			Couple<TrackNode> nodes = Couple.create(node1, node2);    // :375 循环体内
			if (edgePointListener.test(totalDistance + distance, Pair.of(nextBoundary, forward ? nodes : nodes.swap())))
				return locationOn;                                    // :376
		}

		return null;
	}
```

**为什么慢**

`Couple.create(node1, node2)` 只依赖字段，与循环无关，却写在循环体内 → 有 N 个边点通过区间就构造 N 个 `Couple`（外加 backward 的 `swap()`），每个再配 1 个 `Pair`。而**绝大多数边根本没有 edge point**（`edgePoints` 为空），此时仍会白构造 1 个 `Couple`。

**改后**

```java
	protected Double edgeTraversedFrom(TrackGraph graph, boolean forward, IEdgePointListener edgePointListener,
		ITurnListener turnListener, double prevPos, double totalDistance) {
		if (edge.isTurn())
			turnListener.accept(Math.max(0, totalDistance), edge);

		double from = forward ? prevPos : position;
		double to = forward ? position : prevPos;

		EdgeData edgeData = edge.getEdgeData();
		List<TrackEdgePoint> edgePoints = edgeData.getPoints();
		if (edgePoints.isEmpty())
			return null;                          // 大多数边没有 edge point：零分配

		// 循环不变量：整个循环里 node1/node2 不变，只构造一次
		Couple<TrackNode> nodes = Couple.create(node1, node2);
		Couple<TrackNode> passed = forward ? nodes : nodes.swap();

		double length = edge.getLength();
		for (int i = 0; i < edgePoints.size(); i++) {
			int index = forward ? i : edgePoints.size() - i - 1;
			TrackEdgePoint nextBoundary = edgePoints.get(index);
			double locationOn = nextBoundary.getLocationOn(edge);
			double distance = forward ? locationOn : length - locationOn;
			if (forward ? (locationOn < from || locationOn >= to) : (locationOn <= from || locationOn > to))
				continue;
			if (edgePointListener.test(totalDistance + distance, Pair.of(nextBoundary, passed)))
				return locationOn;
		}

		return null;
	}
```

**Mixin 骨架**

```java
@Mixin(value = TravellingPoint.class, remap = false)
public abstract class TravellingPointEdgeMixin {

	@Overwrite
	protected Double edgeTraversedFrom(TrackGraph graph, boolean forward, IEdgePointListener edgePointListener,
		ITurnListener turnListener, double prevPos, double totalDistance) {
		/* 见上「改后」。目标方法 protected，handler 也写 protected */
	}
}
```

> 与第 2 条的 `@Overwrite travel` 同时安装不冲突，但第 2 条已用 `edgeTraversedFromCached` 实现了同等优化，**二选一即可**（同时做会产生一个「不再被调用」的 Overwrite，无害但冗余）。

**验证方法**

- 探针：在 `edgePoints.isEmpty()` 分支计数；混入后 `Couple`/`Pair` 分配应只出现在有 edge point 的边上。
- 功能：信号机与观察者触发必须与改动前完全一致（本方法唯一的功能语义）。

**收益估计**：每次无 edge point 调用省 1 个 `Couple`；每个通过区间的点省 N-1 个 `Couple`（N 为该边的通过区间的边点数）。量级为「每 tick 边点扫描次数」个对象（估计值）。
**回归测试点**：① 信号机占用/释放时机不变（传给 listener 的距离值不能变）；② 观察者计数不变；③ 反向行驶时 `SignalBoundary.getGroup(...)` 拿到的节点顺序不变（`Train.java:491-492` 用 `couple.getSecond().getSecond()`）；④ 0 长度传送门轨道段行为不变。

---

### 8. CarriageSyncData.getDistanceTo 每次调用构建三个容器 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/trains/entity/CarriageSyncData.java:299-369（关键行 :304-307、:344、:363）` |
| 调用频次 | 客户端：CarriageSyncData.apply:160 在每个轮点的 for (boolean forward : Iterate.trueAndFalse) 里调用两次（:200-206）；apply 由 CarriageContraptionEntity.onSyncedDataUpdated（:132-139）在每次 CARRIAGE_DATA 同步时触发，而 CARRIAGE_DATA 只在 shouldCarriageSyncThisTick 且 isDirty 时写入（:241-247）。 |
| 复杂度 / 成本 | 每次调用：1 个 HashSet + 1 个 IdentityHashMap + 1 个 PriorityQueue + 1 个 comparator lambda + 1 个 ArrayList（:344）+ 每条入队边的 1 个 Pair（:363 的 reachedVia value，write-only 死存储）。 |
| 注入方式 | `@Overwrite`（推荐） |
| 目标成员 | `com.simibubi.create.content.trains.entity.CarriageSyncData#getDistanceTo(Lcom/simibubi/create/content/trains/graph/TrackGraph;Lcom/simibubi/create/content/trains/entity/TravellingPoint;Lcom/simibubi/create/content/trains/entity/TravellingPoint;FZ)F` |
| 需新增状态 | `@Unique private final Set<TrackEdge> createperf$visited` + `@Unique private final PriorityQueue<...> createperf$frontier`：per-instance、每次调用开头 clear()、客户端主线程 |
| 风险 | 中（`@Overwrite` + 容器复用需保证不可重入） |
| 脆弱度 | 中。依赖「apply 里两次调用是串行的」这一调用图 |
| 置信度 | 高概率（reachedVia write-only 已 grep 确认；复用安全性依赖调用图不变） |

**改前（关键行）**

```java
	public float getDistanceTo(TrackGraph graph, TravellingPoint current, TravellingPoint target, float maxDistance,
		boolean forward) {
		if (maxDistance == -1)
			maxDistance = 32;

		Set<TrackEdge> visited = new HashSet<>();                                      // :304
		Map<TrackEdge, Pair<Boolean, TrackEdge>> reachedVia = new IdentityHashMap<>(); // :305
		PriorityQueue<Pair<Double, Pair<Couple<TrackNode>, TrackEdge>>> frontier =      // :306-307
			new PriorityQueue<>((p1, p2) -> Double.compare(p1.getFirst(), p2.getFirst()));

		TrackNode initialNode1 = forward ? current.node1 : current.node2;
		TrackNode initialNode2 = forward ? current.node2 : current.node1;
		Map<TrackNode, TrackEdge> connectionsFromInitial = graph.getConnectionsFrom(initialNode1);
		if (connectionsFromInitial == null)
			return -1;
		TrackEdge initialEdge = connectionsFromInitial.get(initialNode2);
		if (initialEdge == null)
			return -1; // graph changed
		...
		while (!frontier.isEmpty()) {
			...
			List<Entry<TrackNode, TrackEdge>> validTargets = new ArrayList<>();        // :344
			Map<TrackNode, TrackEdge> connectionsFrom = graph.getConnectionsFrom(node2);
			for (Entry<TrackNode, TrackEdge> entry : connectionsFrom.entrySet()) {
				...
				validTargets.add(entry);
			}
			if (validTargets.isEmpty())
				continue;
			for (Entry<TrackNode, TrackEdge> entry : validTargets) {
				...
				reachedVia.put(newEdge, Pair.of(validTargets.size() > 1, edge));       // :363 只写不读
				frontier.add(Pair.of(newEdge.getLength() + distance, Pair.of(Couple.create(node2, newNode), newEdge)));
			}
		}
		return -1;
	}
```

**为什么慢**

- 三个容器在方法体开头**无条件构造**，即使 `initialEdge == null` 立刻返回 `-1`（图变更、切维度）也白扔 4 个对象（含 comparator）。
- `reachedVia` 全仓库只有 `put`（已 grep 确认无读取点）→ 纯死存储，却为每条入队边分配 1 个 `Pair`。
- 调用密度：每次同步 4 轮点 × 2 方向 = 8 次；每次调用在 `maxDistance = 32` 内可能展开数十到数百条边。

**改后**

完整方法体与 import 见 [overwrites/CarriageSyncData-getDistanceTo.md](overwrites/CarriageSyncData-getDistanceTo.md)。要点：

```java
	@Unique private final Set<TrackEdge> createperf$visited = new HashSet<>(64);
	@Unique private final PriorityQueue<Pair<Double, Pair<Couple<TrackNode>, TrackEdge>>> createperf$frontier =
		new PriorityQueue<>(64, (p1, p2) -> Double.compare(p1.getFirst(), p2.getFirst()));

	public float getDistanceTo(/* 同签名 */) {
		if (maxDistance == -1)
			maxDistance = 32;
		Set<TrackEdge> visited = createperf$visited;
		visited.clear();
		PriorityQueue<...> frontier = createperf$frontier;
		frontier.clear();
		// ... 删除 reachedVia；过滤结果直接入队（省 ArrayList 与一趟遍历）
	}
```

**Mixin 骨架**

```java
@Mixin(value = CarriageSyncData.class, remap = false)
public abstract class CarriageSyncDataMixin {

	@Unique private final Set<TrackEdge> createperf$visited = new HashSet<>(64);
	@Unique private final PriorityQueue<Pair<Double, Pair<Couple<TrackNode>, TrackEdge>>> createperf$frontier =
		new PriorityQueue<>(64, (p1, p2) -> Double.compare(p1.getFirst(), p2.getFirst()));

	@Overwrite
	public float getDistanceTo(TrackGraph graph, TravellingPoint current, TravellingPoint target, float maxDistance,
		boolean forward) {
		/* 见 overwrites/CarriageSyncData-getDistanceTo.md 第 3 节 */
	}
}
```

**验证方法**

- 探针：在两次 `clear()` 之后统计「每次调用的 poll 次数」（Dijkstra 展开规模），确认改动前后分布一致 —— 这直接证明搜索行为未变。
- 功能：客户端车厢平滑（见回归测试点）。
- 分配：JFR 对比 `HashSet`/`IdentityHashMap`/`PriorityQueue` 的分配计数（混入后应只在首次构造时出现）。

**收益估计**：每次调用省 3 个容器 + 1 个 comparator + 1 个 ArrayList + 每条入队边的 1 个 `Pair`（估计值，需 Spark 实测）。
**回归测试点**：① 客户端车厢不抖动/不瞬移；② 道岔分叉处客户端跟随分支与服务端一致；③ 维度/传送门切换时车厢不错位（`edge == null → continue` 分支可达）；④ 服务端拆轨导致客户端找不到路径时，快照回退分支（`foundDistance == -1`）仍触发；⑤ 无 `ClassCastException`/`NPE`。

---

### 9. CarriageContraptionEntity.tickContraption 每 tick 构造 Optional（更正：不会强制同步） — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/trains/entity/CarriageContraptionEntity.java:261-262（方法体 :211-317）` |
| 调用频次 | 仅服务端每 tick 每车厢实体一次（:237 的 if (!level().isClientSide) 分支内）；客户端 :226 的 sided(level()) 另有分配（见第 13 条）。 |
| 复杂度 / 成本 | 每 tick 1 个 Optional + 1 次 accessor identity 查找 + 1 次 equals。方法链 Optional.ofNullable(g).map(g -> g.id) 实际产生 2 个对象（ofNullable 的 Optional 与 map 的结果 Optional，中间那个被丢弃）。 |
| 注入方式 | `@Overwrite`（推荐）；`@WrapOperation` 包裹 SynchedEntityData.set 也可但更绕 |
| 目标成员 | `com.simibubi.create.content.trains.entity.CarriageContraptionEntity#tickContraption()V`（protected） |
| 需新增状态 | `@Unique private UUID createperf$lastTrackGraphId`（per-entity；服务端主线程） |
| 风险 | 低（守卫等价于 SynchedEntityData.set 内部已有的 equals 判断）；中（`@Overwrite` 体量 100+ 行） |
| 脆弱度 | 中。整方法复制后任何一行漏抄都是行为 bug |
| 置信度 | 确定（SynchedEntityData.set 的相等判断已用 javap 核对 1.20.1 字节码；1.21.1 同构为需实测项） |

**改前**

```java
			entityData.set(TRACK_GRAPH, Optional.ofNullable(carriage.train.graph)
				.map(g -> g.id));

			level().gameEvent(this, GameEvent.RESONATE_8, this.position());
```

**为什么慢（以及为什么「强制同步」的说法不成立）**

- 清单说「Optional 不做值相等 → 每 tick 强制同步脏标记」——**不成立**。核对 `SynchedEntityData` 字节码后确认：`set(accessor, value)` → `set(accessor, value, false)`，只有 `ObjectUtils.notEqual(value, dataItem.get())` 为真时才执行 `dataItem.set` / `entity.onSyncedDataUpdated` / `dataItem.setDirty(true)` / `this.dirty = true`。
- `java.util.Optional` **实现了值相等的 `equals`**（内部比较 `value.equals(other.value)`）。写入的是 `carriage.train.graph.id`，图不变时值不变 → `notEqual` 为 false → **不置脏、不发包**。
- 佐证：同文件 `:244-245` 为了让 `CARRIAGE_DATA`（无值相等语义的普通对象）被同步，必须「先 `set` null 再 `set` 值」来制造差异 —— 这反证 `set` 确实做相等判断。
- 所以本条的真实成本**只是小对象分配**，不是同步风暴。保留 Medium 但性质改掉。

**改后**

```java
			// 原实现每 tick 构造 Optional；SynchedEntityData.set 本身会做 equals 判断，
			// 所以「值未变」时唯一省下的是这次 Optional 分配。
			TrackGraph trackGraph = carriage.train.graph;
			UUID trackGraphId = trackGraph == null ? null : trackGraph.id;
			if (!Objects.equals(trackGraphId, createperf$lastTrackGraphId) || trackGraphId == null) {
				// null 也每 tick 写一次：原实现每 tick 都写 Optional 空值，
				// 若只依赖「值相等」短路，「脱轨 → 重新上轨」的转换可能漏写。
				createperf$lastTrackGraphId = trackGraphId;
				entityData.set(TRACK_GRAPH, Optional.ofNullable(trackGraphId));
			}
```

**Mixin 骨架**

```java
@Mixin(value = CarriageContraptionEntity.class, remap = false)
public abstract class CarriageContraptionEntityMixin {

	@Unique private UUID createperf$lastTrackGraphId;

	@Overwrite
	protected void tickContraption() {
		/* 完整方法体（逐字复制 :211-317，仅改 TRACK_GRAPH 写入处）
		   见 overwrites/CarriageContraptionEntity-tickContraption.md 第 3 节。
		   目标方法 protected → handler 也必须 protected。 */
	}
}
```

**验证方法**

- 用 `-Dmixin.debug.export=true` 导出类，确认 `tickContraption` 被替换且 `gameEvent` / `arrivalSound` 调用点仍在。
- 功能：脱轨 → 重新上轨时 `TRACK_GRAPH` 必须完成 true→false→true 的变化（客户端 `updateTrackGraph()` 由 `:129-130` 驱动）。
- 分配：JFR 过滤 `java.util.Optional`，混入后服务端稳态应归零。

**收益估计**：服务端每车厢每 tick 省 2 个 `Optional`（估计值；本条优先级最低，可作顺手项）。
**回归测试点**：① 脱轨/上轨转换客户端表现正确；② 换图（`TrackGraph.id` 变化）时客户端 `updateTrackGraph()` 触发；③ 到站音效/调度同步不受影响；④ 声音与粒子（客户端分支）逐字未改。

---

### 10. Carriage.travel 在 2×2 循环内每次重建信号监听器 Lambda — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/trains/entity/Carriage.java:192-193（frontSignalListener/backSignalListener 调用）、:203-212 的 portal lambda；Train.java:462-506（frontSignalListener）、:530-544（backSignalListener）` |
| 调用频次 | Carriage.travel 内层 for (boolean firstWheel : Iterate.trueAndFalse) 循环体内（:172-217）→ 每个转向架 × 每个轮子 = 每车厢每 tick 最多 4 次调用；Train.tick:423 → Carriage.travel。仅服务端（Train.tick 只由 GlobalRailwayManager.tickTrains 调用，后者只由 CommonEvents:142 的服务端路径触发）。 |
| 复杂度 / 成本 | 每次调用创建 1 个捕获 this（Train 实例）的 lambda 对象；:203 的 portal lambda 捕获 this（Carriage 的 entities），同样每次新建。合计每车厢每 tick 最多 4 × 2 + 4 = 12 个 lambda 对象。 |
| 注入方式 | `@Redirect`/`@WrapOperation` 拦 Train.frontSignalListener() / backSignalListener() 调用（不要 Overwrite Carriage.travel，那是 4 处改动却只有这一条收益） |
| 目标成员 | 调用点：com.simibubi.create.content.trains.entity.Carriage#travel(...) 内的 Train.frontSignalListener()Lcom/simibubi/create/content/trains/entity/TravellingPoint$IEdgePointListener; 与 Train.backSignalListener()L...$IEdgePointListener; |
| 需新增状态 | `@Unique private final IEdgePointListener createperf$frontListener` / createperf$backListener（per-Train；服务端主线程；Train 实例随列车缓存，无需失效） |
| 风险 | 低（监听器无参、无状态依赖，只是捕获 this；无代码做 identity 比较） |
| 脆弱度 | 低。方法无参；上游若给监听器加参数则失配报错 |
| 置信度 | 高概率（「每次新建」由 Java 规范保证；Train 的两个方法确实捕获 this——它们读写 speed/navigation/occupiedObservers 等实例成员） |

**改前**

```java
// Carriage.java:172-217（节选）
			for (boolean firstWheel : Iterate.trueAndFalse) {
				...
				IEdgePointListener frontListener = train.frontSignalListener();   // :192 每轮新建 lambda
				IEdgePointListener backListener = train.backSignalListener();     // :193 每轮新建 lambda
				IEdgePointListener passiveListener = point.ignoreEdgePoints();

				toMove += correction + bogeyCorrection;

				ITrackSelector trackSelector = toMove > 0 ? frontTrackSelector : backTrackSelector;
				IEdgePointListener signalListener =
					toMove > 0 ? atFront ? frontListener : atBack ? backListener : passiveListener
						: atFront ? backListener : atBack ? frontListener : passiveListener;

				double moved = point.travel(graph, toMove, trackSelector, signalListener, point.ignoreTurns(), c -> {
					for (DimensionalCarriageEntity dce : entities.values())                 // :203 portal lambda 每轮新建
						if (c.either(tnl -> tnl.equalsIgnoreDim(dce.pivot)))
							return false;
					if (entities.size() > 1) {
						train.status.doublePortal();
						return true;
					}
					return false;
				});
```

```java
// Train.java:462-506（frontSignalListener 完整实现，读写了大量实例状态 → 必然捕获 this）
	public IEdgePointListener frontSignalListener() {
		return (distance, couple) -> {
			if (couple.getFirst() instanceof GlobalStation station) { /* ... */ }
			if (couple.getFirst() instanceof TrackObserver observer) { /* ... */ }
			if (!(couple.getFirst() instanceof SignalBoundary signal))
				return false;
			...
			signalEdgeGroup.reserved = signal;
			occupy(groupId, signal.id);
			return false;
		};
	}

// Train.java:530-544
	public IEdgePointListener backSignalListener() {
		return (distance, couple) -> {
			if (couple.getFirst() instanceof TrackObserver observer) { /* ... */ }
			if (!(couple.getFirst() instanceof SignalBoundary signal))
				return false;
			UUID groupId = signal.getGroup(couple.getSecond().getFirst());
			occupiedSignalBlocks.remove(groupId);
			return false;
		};
	}
```

**为什么慢**

- `frontSignalListener()` / `backSignalListener()` **每次调用都返回一个新 lambda**（方法体是 `return (a,b) -> {...}`；因为 lambda 体引用了 `this` 的字段与方法，它是捕获 `this` 的实例 lambda，JVM 必须在每次执行 `return` 时新建对象）。
- 它们被放在 2×2 循环**最内层**，但 lambda 本身**没有参数化依赖**（无参方法），所以完全可以缓存为字段。
- `:203` 的 portal lambda 同理：捕获 `this`（为了访问 `entities`/`train`），每次 `travel` 调用都新建。
- 规模：10 节车厢 → 每 tick 最多 120 个 lambda 对象。量级不大，但属于「零风险、零语义变更」的免费收益。

**改后**

```java
// Train.java —— 缓存两个监听器（无参、无状态依赖）
// 注意：字段名不能与方法名相同，所以用 createperf$ 前缀（mixin 里用 @Unique）
	private final TravellingPoint.IEdgePointListener createperf$frontSignalListener = (distance, couple) -> {
		/* 原 :463-505 的 lambda 体逐字复制 */
	};
	private final TravellingPoint.IEdgePointListener createperf$backSignalListener = (distance, couple) -> {
		/* 原 :531-543 的 lambda 体逐字复制 */
	};

	public IEdgePointListener frontSignalListener() {
		return createperf$frontSignalListener;
	}

	public IEdgePointListener backSignalListener() {
		return createperf$backSignalListener;
	}
```

**Mixin 骨架**

```java
@Mixin(value = Train.class, remap = false)
public abstract class TrainListenersMixin {

	@Unique private IEdgePointListener createperf$frontListener;
	@Unique private IEdgePointListener createperf$backListener;

	@Overwrite
	public IEdgePointListener frontSignalListener() {
		if (createperf$frontListener == null)
			createperf$frontListener = (distance, couple) -> { /* 逐字复制 Train.java:463-505 */ };
		return createperf$frontListener;
	}

	@Overwrite
	public IEdgePointListener backSignalListener() {
		if (createperf$backListener == null)
			createperf$backListener = (distance, couple) -> { /* 逐字复制 Train.java:531-543 */ };
		return createperf$backListener;
	}
}
```

> **实施建议**：直接 `@Overwrite` 这两个方法要把 40+ 行 lambda 体搬进 mixin，脆弱度不低。另一条路是用 MixinExtras `@WrapOperation`
> 拦 `Carriage.travel` 里的 `Train.frontSignalListener()` / `backSignalListener()` 调用，配一个 `WeakHashMap<Train, IEdgePointListener>` 缓存 ——
> 但手册 §7 禁止静态强引用缓存，必须用 `WeakHashMap`。综合看本条**性价比一般，建议放最后做**。

**验证方法**

- 探针：`@Unique static AtomicLong createperf$listenerCreations`，在原 `return (distance, couple) -> ...` 前自增；混入后应按 Train 实例数封顶（而非每 tick 递增）。
- 功能：信号占用/释放、观察者列表、到站（`GlobalStation`）必须完全一致 —— 这是列车自动化的核心。

**收益估计**：每 tick 每车厢最多省 8 个捕获 lambda（2 个信号监听器 × 4 次）+ portal lambda 4 个；10 节车厢约 120 个/秒（估计值）。
**回归测试点**：① 信号机占用/释放与信号组保留（`signalEdgeGroup.reserved`）不变；② 观察者（`occupiedObservers`）增删不变；③ 到站判定与 `navigation.destination` 清空行为不变；④ 成就 `AllAdvancements.RED_SIGNAL` 在红灯停车时仍发放；⑤ 缓存后的监听器不能被别处改状态（已确认无 setter 给监听器打状态）。

---

### 11. RollerMovementBehaviour.canBreak 每次 6 次世界查询 + getPositionsToBreak 每次新建列表 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/contraptions/actors/roller/RollerMovementBehaviour.java:106-114（canBreak）、:189-230（getPositionsToBreak）` |
| 调用频次 | canBreak 的两条路径：① BlockBreakingMovementBehaviour.tickBreaker:174（每个破坏进度周期一次，周期 = blockHardness / breakSpeed，软方块可达每 tick）；② RollerMovementBehaviour.testBreakerTarget:240 ← getPositionsToBreak:218/226 ← visitNewPosition:130 / onBlockBroken:164。 |
| 复杂度 / 成本 | canBreak：6 次 world.getBlockState(breakingPos.relative(side))（每次 = 区块查找 + BlockState 比较），另有 state.getCollisionShape(...) 与 AllTags.AllBlockTags.TRACKS.matches(state)。getPositionsToBreak：每次 new ArrayList，并在 TUNNEL_PAVE 模式下遍历高度剖面（:207-222）。 |
| 注入方式 | `@Overwrite` `canBreak`（方法短）；`@ModifyExpressionValue` 可拦 BlockTags.PORTALS 但收益有限 |
| 目标成员 | `com.simibubi.create.content.contraptions.actors.roller.RollerMovementBehaviour#canBreak(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z` |
| 需新增状态 | 无（若做「位置缓存」则需 @Unique Map<BlockPos, Boolean>，不推荐：方块可被玩家改动，失效时机难保证） |
| 风险 | 低（6 次邻居查询是引用透明读操作，重排/短路不改变语义） |
| 脆弱度 | 低（方法短）；getPositionsToBreak 的 6 邻居循环是稳定事实 |
| 置信度 | 确定（方法体与两条调用路径已核对） |

**改前**

```java
	@Override
	public boolean canBreak(Level world, BlockPos breakingPos, BlockState state) {
		for (Direction side : Iterate.directions)
			if (world.getBlockState(breakingPos.relative(side))
				.is(BlockTags.PORTALS))
				return false;

		return super.canBreak(world, breakingPos, state) && !state.getCollisionShape(world, breakingPos)
			.isEmpty() && !AllTags.AllBlockTags.TRACKS.matches(state);
	}
```

```java
	protected List<BlockPos> getPositionsToBreak(MovementContext context, BlockPos visitedPos) {
		ArrayList<BlockPos> positions = new ArrayList<>();      // :190 每次调用都分配
		RollingMode mode = getMode(context);
		if (mode != RollingMode.TUNNEL_PAVE)
			return positions;                                   // 大多数模式直接返回空列表
		...
	}
```

**为什么慢**

- 6 次 `getBlockState` 是**区块级查找**。对每个正在被压路机破坏的方块、每个破坏进度周期都要做一遍。虽然单次成本低（区块已在内存），但它是「每 tick × 每装置 × 6」的乘算，且 `AllTags.AllBlockTags.TRACKS.matches(state)` 与 `getCollisionShape` 也在同一路径上。
- `getPositionsToBreak` 在**非 TUNNEL_PAVE** 模式下只是「造一个空列表再返回」——`visitNewPosition` 每次进入新位置都会调一次（`:130`）。

**改后**

```java
	@Override
	public boolean canBreak(Level world, BlockPos breakingPos, BlockState state) {
		// 先做最便宜、最可能为 false 的检查（配置默认不能挖的方块）
		if (!super.canBreak(world, breakingPos, state) || AllTags.AllBlockTags.TRACKS.matches(state)
			|| state.getCollisionShape(world, breakingPos)
				.isEmpty())
			return false;

		// 端口检查：6 次邻居查询保留
		for (Direction side : Iterate.directions) {
			if (world.getBlockState(breakingPos.relative(side))
				.is(BlockTags.PORTALS))
				return false;
		}
		return true;
	}
```

> **行为顺序注意**：原实现是「先查 6 邻居端口 → 再 super/collision/tracks」，改后变成「先 super/collision/tracks → 再查 6 邻居」。
> 两者都是纯读操作、无副作用、返回值相同，但**抛异常的顺序**不同（例如 `state.getCollisionShape` 抛异常时，原实现不会走到那里）。
> 若要求严格零差异，**保持原顺序**，只改 `getPositionsToBreak` 的空列表早退：

```java
	@Override
	public void visitNewPosition(MovementContext context, BlockPos pos) {
		// 非 TUNNEL_PAVE 模式直接跳过列表构造（原实现会 new 一个空 ArrayList 再返回）
		if (getMode(context) != RollingMode.TUNNEL_PAVE) {
			triggerPaver(context, pos);
			return;
		}
		List<BlockPos> positionsToBreak = getPositionsToBreak(context, pos);
		...
	}
```

**Mixin 骨架**

```java
@Mixin(value = RollerMovementBehaviour.class, remap = false)
public abstract class RollerMovementBehaviourMixin {

	@Overwrite
	public boolean canBreak(Level world, BlockPos breakingPos, BlockState state) {
		/* 见上；若要严格零顺序差异，则不要重排，只改 visitNewPosition 的早退 */
	}
}
```

> `canBreak` 覆写的是 `BlockBreakingMovementBehaviour.canBreak`（`src/main/java/com/simibubi/create/content/kinetics/base/BlockBreakingMovementBehaviour.java:237`，注意包名是 `kinetics.base`）。`@Overwrite` 只影响压路机，不影响锯/钻/犁。

**验证方法**

- 功能：让压路机在**传送门方块旁**工作，确认它会停下（`canBreak` 返回 false 的那条路径）。
- 功能：让压路机压过普通泥土/石头/轨道，确认破坏速度与改动前一致（`tickBreaker` 的进度累加依赖 `canBreak` 与 `blockHardness`）。
- 性能：Spark 采样 `Level.getBlockState` 与 `RollerMovementBehaviour.canBreak` 的占比，场景为 5 台压路机同时工作。

**收益估计**：`canBreak` 的收益主要是「短路顺序」带来的平均查询次数下降（最坏情况不变，仍是 6 次）；`getPositionsToBreak` 每调用省 1 个 `ArrayList`（估计值）。
**回归测试点**：① 传送门旁不破坏；② 普通方块破坏速度不变；③ TUNNEL_PAVE 模式的铺路/破路行为不变（`getPositionsToBreak` 的高度剖面逻辑）；④ 轨道方块仍不被压路机破坏；⑤ 压路机与犁/锯/钻互不影响。

---

### 12. HarvesterMovementBehaviour 每个掉落实例构造 ItemStack 只为比较 — [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/contraptions/actors/harvester/HarvesterMovementBehaviour.java:95（new ItemStack(state.getBlock())）、:87（new ItemStack(Items.SHEARS)）` |
| 调用频次 | :87 每次 visitNewPosition（草叶方块）一次；:95 在 BlockHelper.destroyBlockAs 的 Consumer<ItemStack> 回调里，每个掉落物一次。visitNewPosition 由 MovementBehaviour.tick 每 tick 对当前位置调用。 |
| 复杂度 / 成本 | new ItemStack(Block) 会构造完整 ItemStack（含 DataComponentMap 引用与计数），只为比较 getItem()。:95 的成本随掉落物数量线性放大（例如收小麦时每格多个掉落物）。 |
| 注入方式 | `@WrapOperation` 包裹 new ItemStack(Block)（推荐）；或 `@Redirect` |
| 目标成员 | `HarvesterMovementBehaviour#visitNewPosition(Lcom/simibubi/create/content/contraptions/MovementContext;Lnet/minecraft/core/BlockPos;)V` |
| 需新增状态 | 无 |
| 风险 | 低（ItemHelper.sameItem(stack, new ItemStack(state.getBlock())) 等价于 !stack.isEmpty() && stack.is(state.getBlock().asItem()) —— 见 ItemHelper.java:36-38） |
| 脆弱度 | 低。ItemHelper.sameItem 的实现是公开事实 |
| 置信度 | 确定 |

**改前**

```java
		ItemStack item = ItemStack.EMPTY;
		float effectChance = 1;

		if (stateVisited.is(BlockTags.LEAVES)) {
			item = new ItemStack(Items.SHEARS);          // :87 每次 visitNewPosition 一次
			effectChance = .45f;
		}

		MutableBoolean seedSubtracted = new MutableBoolean(notCropButCuttable);
		BlockState state = stateVisited;
		BlockHelper.destroyBlockAs(world, pos, null, item, effectChance, stack -> {
			if (AllConfigs.server().kinetics.harvesterReplants.get() && !seedSubtracted.getValue()
				&& ItemHelper.sameItem(stack, new ItemStack(state.getBlock()))) {   // :95 每个掉落物一次
				stack.shrink(1);
				seedSubtracted.setTrue();
			}
			collectOrDropItem(context, stack);
		});
```

**为什么慢**

`ItemHelper.sameItem(a, b)` 的实现（`src/main/java/com/simibubi/create/foundation/item/ItemHelper.java:36-38`）是：

```java
	public static boolean sameItem(ItemStack stack, ItemStack otherStack) {
		return !otherStack.isEmpty() && stack.is(otherStack.getItem());
	}
```
即它**只用了 `otherStack.getItem()`**。而 `new ItemStack(state.getBlock())` 构造了完整的栈（含组件映射）。对收获类装置（小麦/胡萝卜/土豆等）每次破坏会产生多个掉落物，每次回调都新建一个 `ItemStack`。

**改后**

```java
		BlockState state = stateVisited;
		Item expectedSeed = state.getBlock()
			.asItem();                                   // 提到回调外，且不构造 ItemStack
		BlockHelper.destroyBlockAs(world, pos, null, item, effectChance, stack -> {
			if (AllConfigs.server().kinetics.harvesterReplants.get() && !seedSubtracted.getValue()
				&& !expectedSeed.equals(Items.AIR) && stack.is(expectedSeed)) {   // 与原语义一致
				stack.shrink(1);
				seedSubtracted.setTrue();
			}
			collectOrDropItem(context, stack);
		});
```

> 加 `!expectedSeed.equals(Items.AIR)` 是为了复现原实现的 `!otherStack.isEmpty()`：`new ItemStack(Block)` 在 `Block.asItem() == Items.AIR` 时是空栈。
> `:87` 的 `new ItemStack(Items.SHEARS)` **不能**简单换成 `Items.SHEARS`：`destroyBlockAs` 的 `usedTool` 参数需要一个可变的 `ItemStack`（`usedTool.mineBlock(...)` 会改它）。这一处保留原样。
**Mixin 骨架**

```java
@Mixin(value = HarvesterMovementBehaviour.class, remap = false)
public abstract class HarvesterMovementBehaviourMixin {

	@Overwrite
	public void visitNewPosition(MovementContext context, BlockPos pos) {
		/* 见上「改后」（方法约 40 行，逐字复制仅改 :95 的比较） */
	}
}
```

> `@WrapOperation` 在 `NEW` 上的目标写法在各 Mixin 版本差异较大，且此处**无法只靠包裹 `new` 就把比较改成 `asItem()`**（比较发生在别处）。
> **结论：本条用 `@Overwrite visitNewPosition` 最直接**，或作为 Low 项不做。
**验证方法**

- 功能：让收获机收小麦/胡萝卜/土豆，开启 `harvesterReplants`，确认**每次破坏只扣 1 个种子**（`seedSubtracted` 语义）。
- 功能：草叶方块仍用剪刀效果（`effectChance = .45f`）。
- 分配：JFR 过滤 `net.minecraft.world.item.ItemStack`，在「收获大片小麦」场景下对比样本数。

**收益估计**：每个掉落物省 1 个 `ItemStack`；收获类场景下量级为「掉落物数/tick」（估计值）。
**回归测试点**：① 补种时只扣 1 个种子；② 草叶走剪刀路径；③ 不可收获方块直接返回；④ `CocoaBlock` 等特例（`isValidCrop`）行为不变。

---

### 13. GlobalRailwayManager.sided 每次分配 MutableObject + 两层 Lambda — [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/trains/GlobalRailwayManager.java:289-295` |
| 调用频次 | 服务端：每次调用只做 level.isClientSide() 判断后返回 this，无分配。客户端：每次分配 1 个 MutableObject + 2 个捕获 lambda（外层 supplier 捕获 m，内层 runnable 也捕获 m）。全仓库共 23 处调用点，其中每 tick 路径包括 CarriageContraptionEntity:226（每车厢实体每 tick，两侧都跑）。 |
| 复杂度 / 成本 | 客户端每调用 1 个 MutableObject + 2 个 lambda 对象；服务端 0。 |
| 注入方式 | `@Inject(HEAD, cancellable)` 在客户端直接返回 CreateClient.RAILWAYS（需 `@OnlyIn(Dist.CLIENT)` 隔离，避免专用服务端类加载）；或在上层调用点用 `@WrapOperation` 缓存结果 |
| 目标成员 | `com.simibubi.create.content.trains.GlobalRailwayManager#sided(Lnet/minecraft/world/level/LevelAccessor;)Lcom/simibubi/create/content/trains/GlobalRailwayManager;` |
| 需新增状态 | 无（或上层调用点的 1 个 @Unique 缓存字段） |
| 风险 | 中（客户端强引用 CreateClient.RAILWAYS 需保证只在客户端类里引用，否则专用服务端启动 ClassNotFound） |
| 脆弱度 | 低（方法 7 行） |
| 置信度 | 确定（GlobalRailwayManager.java:289-295 与 :297-300 已核对：外层 executeOnClientOnly 收 supplier 之类，内层才是 runnable） |

**改前**

```java
	public GlobalRailwayManager sided(LevelAccessor level) {
		if (level != null && !level.isClientSide())
			return this;
		MutableObject<GlobalRailwayManager> m = new MutableObject<>();
		CatnipServices.PLATFORM.executeOnClientOnly(() -> () -> clientManager(m));
		return m.getValue();
	}

	@OnlyIn(Dist.CLIENT)
	private void clientManager(MutableObject<GlobalRailwayManager> m) {
		m.setValue(CreateClient.RAILWAYS);
	}
```

**为什么慢**

- 服务端零成本（这是**好事**，也是为什么它只是 Low）。
- 客户端每调用分配 1 个 `MutableObject` + 2 个 lambda（`() -> () -> clientManager(m)` 展开后是两层，各自捕获 `m`）。在 `CarriageContraptionEntity:226` 这条每 tick 路径上，客户端每个车厢实体每 tick 一次。
- 量级：10 节车厢 → 客户端每 tick 约 30 个对象。

**改后（保守版：只缓存上层调用点）**

```java
@Mixin(value = CarriageContraptionEntity.class, remap = false)
public abstract class CarriageContraptionEntitySidedMixin {

	@Unique private GlobalRailwayManager createperf$railways;

	// Create.RAILWAYS / CreateClient.RAILWAYS 都是 static final 单例，缓存是安全的。
	@WrapOperation(method = "tickContraption()V",
		at = @At(value = "INVOKE",
			target = "Lcom/simibubi/create/content/trains/GlobalRailwayManager;sided(Lnet/minecraft/world/level/LevelAccessor;)Lcom/simibubi/create/content/trains/GlobalRailwayManager;"))
	private GlobalRailwayManager createperf$cacheSided(GlobalRailwayManager instance, LevelAccessor level,
		Operation<GlobalRailwayManager> original) {
		if (createperf$railways == null)
			createperf$railways = original.call(instance, level);
		return createperf$railways;
	}
}
```

> 缓存的正确性依赖「`Create.RAILWAYS` 与 `CreateClient.RAILWAYS` 在运行期不会替换实例」—— 已核对它们都是 static final 单例。
> **若上游改成可替换实例，此处会静默失效（返回旧管理器）**，属于脆弱点。
> 直接在 `GlobalRailwayManager.sided` 上做 `@Inject` 返回 `CreateClient.RAILWAYS` 会把客户端类引用带进 mixin 常量池，**有专用服务端加载期崩溃风险**，不推荐。
**验证方法**

- 功能：客户端列车正常渲染/交互（依赖 `CreateClient.RAILWAYS` 的查询）；专用服务端启动不报 `ClassNotFoundException`。
- 分配：JFR 过滤 `org.apache.commons.lang3.mutable.MutableObject` 在客户端的样本数。

**收益估计**：客户端每 tick 每车厢实体少 1 个 `MutableObject` + 2 个 lambda；10 节车厢约 30 个对象/秒（估计值）。
**回归测试点**：① 专用服务端能启动（不加载客户端类）；② 客户端列车 HUD/调度/信号查询正常；③ 切换维度后 `sided` 返回值仍正确。

---

### 14. EdgePointStorage.values 每次调用拷贝整个集合 — [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/trains/graph/EdgePointStorage.java:41-46` |
| 调用频次 | 低频：TrackGraph.getPoints(type)（TrackGraph.java:89-91）的调用点有 RailwaySavedData:70（读档一次）、TrackGraphSync:184（同步给玩家时）、ScheduleScreen:333/348（打开界面时）、Navigation:622/682/721、TrackGraphVisualizer:84/128。不在每 tick 路径上。 |
| 复杂度 / 成本 | O(n) 拷贝 + 1 个流对象链 + 1 个 ArrayList（Stream.toList() 返回不可变 List，仅一步 ArrayList）。n = 该类型 edge point 数（信号机数量级：几十到几百）。 |
| 注入方式 | `@Overwrite`（3 行）或 `@Inject(HEAD, cancellable)` |
| 目标成员 | `com.simibubi.create.content.trains.graph.EdgePointStorage#values(Lcom/simibubi/create/content/trains/graph/EdgePointType;)Ljava/util/Collection;` |
| 需新增状态 | 无 |
| 风险 | 低。但有一个真实语义差异：原实现返回快照（流拷贝），调用方在遍历期间修改底层 map 不会抛 ConcurrentModificationException；返回视图则会抛。已核对全部调用点都是遍历/读取（TrackGraphSync:184 在遍历中改的是 packet 里的另一个集合），仍需实测。 |
| 脆弱度 | 低（方法短） |
| 置信度 | 高概率（语义差异需实测确认） |

**改前**

```java
	@SuppressWarnings("unchecked")
	public <T extends TrackEdgePoint> Collection<T> values(EdgePointType<T> type) {
		return getMap(type).values()
			.stream()
			.map(e -> (T) e)
			.toList();
	}
```

**为什么慢**

- `Stream.toList()` 会分配 1 个 `ArrayList` 并把所有元素拷进去（`Stream.toList` 返回不可修改列表）。对「读一遍就丢」的调用者是纯浪费。
- 不过它确实提供了**快照语义**（防 `ConcurrentModificationException`）。在 `TrackGraphSync` 这种「遍历并可能触发其它修改」的场景，快照是有意义的 —— 所以本条**不应该无条件改成视图**。

**改后（推荐：只加快速路径，保留快照语义）**

```java
	@SuppressWarnings("unchecked")
	public <T extends TrackEdgePoint> Collection<T> values(EdgePointType<T> type) {
		Map<UUID, TrackEdgePoint> map = getMap(type);
		if (map.isEmpty())
			return Collections.emptyList();                  // 空 map 零拷贝（常见）
		if (map.size() == 1)
			return Collections.singletonList((T) map.values()
				.iterator()
				.next());
		return map.values()
			.stream()
			.map(e -> (T) e)
			.toList();
	}
```

如果确实要彻底消除拷贝，则必须同时把所有调用点确认成纯只读，然后返回视图：

```java
	@SuppressWarnings("unchecked")
	public <T extends TrackEdgePoint> Collection<T> values(EdgePointType<T> type) {
		// 视图：不拷贝。要求所有调用方在遍历期间不改动底层 map。
		return (Collection<T>) getMap(type).values();
	}
```

**Mixin 骨架**

```java
@Mixin(value = EdgePointStorage.class, remap = false)
public abstract class EdgePointStorageMixin {

	@Invoker("getMap")
	protected abstract Map<UUID, TrackEdgePoint> createperf$getMap(EdgePointType<? extends TrackEdgePoint> type);

	@Overwrite
	public <T extends TrackEdgePoint> Collection<T> values(EdgePointType<T> type) {
		/* 见上（推荐版：空/单元素快速路径） */
	}
}
```

**验证方法**

- 功能：读档后信号组邻接关系正确（`RailwaySavedData:70` 路径）；打开列车时刻表界面能看到车站列表（`ScheduleScreen:333`）；给玩家同步轨道图时不漏 edge point（`TrackGraphSync:184`）。
- 测 `ConcurrentModificationException`：在服务端同步轨道图给玩家的同时，用另一玩家放置信号机，跑 5 分钟看日志。

**收益估计**：仅在 edge point 数量大时可见（几十到几百元素的拷贝），且不在每 tick 路径 → **收益很小**，属清理项（估计值）。
**回归测试点**：① `RailwaySavedData` 读档后信号组邻接正确；② 时刻表界面的车站列表完整；③ 玩家加入时收到完整 edge point 同步；④ 无 `ConcurrentModificationException`。

---

### 15. CapabilityMinecartController tick 每 tick 新建列表 + getIfPresent 双查找 — [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/contraptions/minecart/capability/CapabilityMinecartController.java:50-102（tick，关键行 :89 new ArrayList）、:168-176（getIfPresent 的 containsKey + get）` |
| 调用频次 | tick(Level) 每世界每 tick 一次（服务端与客户端各一次）；getIfPresent 的调用点包括 MinecartController:104/184（在耦合链遍历的 lambda 内，可被多次调用）、CouplingHandler:56/62/100/101/172、OrientedContraptionEntity:447/451、CouplingCommand:142。 |
| 复杂度 / 成本 | tick：每次 1 个 ArrayList（:89 toRemove），且三个队列的遍历即使在空时也执行。getIfPresent：containsKey + get = 两次哈希查找（Map<UUID, ...>，UUID 的 hashCode 是缓存过的两个 long 的异或，很快）。 |
| 注入方式 | `@Overwrite getIfPresent`（4 行，推荐）；tick 的列表复用需 `@Unique` 字段 |
| 目标成员 | `CapabilityMinecartController#getIfPresent(Lnet/minecraft/world/level/Level;Ljava/util/UUID;)Lcom/simibubi/create/content/contraptions/minecart/capability/MinecartController;`（public static）；`#tick(Lnet/minecraft/world/level/Level;)V`（public static） |
| 需新增状态 | `@Unique private static final Map<Level, List<UUID>> createperf$toRemove`（tick 是 static，服务端/客户端主线程各自调用，静态共享列表会串数据 → 用 WeakHashMap 按 Level 区分） |
| 风险 | 中（静态状态 + 两侧主线程共享；getIfPresent 的改写要保持「map 中 value 为 null 时返回 null」的语义） |
| 脆弱度 | 低（方法短） |
| 置信度 | 确定（containsKey+get 双查找与 toRemove 分配已逐行核对） |

**改前**

```java
	public static void tick(Level world) {
		Map<UUID, MinecartController> carts = loadedMinecartsByUUID.get(world);
		List<AbstractMinecart> queued = queuedAdditions.get(world);
		List<UUID> queuedRemovals = queuedUnloads.get(world);
		Set<UUID> cartsWithCoupling = loadedMinecartsWithCoupling.get(world);
		Set<UUID> keySet = carts.keySet();

		for (UUID removal : queuedRemovals) { ... }
		for (AbstractMinecart cart : queued) { ... }

		queuedRemovals.clear();
		queued.clear();

		List<UUID> toRemove = new ArrayList<>();          // :89 每 tick 分配

		for (Entry<UUID, MinecartController> entry : carts.entrySet()) {
			MinecartController controller = entry.getValue();
			if (controller != null && controller.isPresent())
				continue;
			toRemove.add(entry.getKey());
		}

		for (UUID uuid : toRemove) {
			keySet.remove(uuid);
			cartsWithCoupling.remove(uuid);
		}
	}

	@Nullable
	public static MinecartController getIfPresent(Level world, UUID cartId) {
		Map<UUID, MinecartController> carts = loadedMinecartsByUUID.get(world);
		if (carts == null)
			return null;
		if (!carts.containsKey(cartId))           // :173 第一次查找
			return null;
		return carts.get(cartId);                 // :175 第二次查找
	}
```

**为什么慢**

- `toRemove` 每 tick 分配一个 `ArrayList`（多数 tick 为空或 1~2 个元素）。
- `getIfPresent` 的 `containsKey` + `get` 是双查找。**注意不能简单改成 `carts.get(cartId)` 就完事**：原实现允许 map 中存在 value 为 `null` 的键（`:44` 是 `new HashMap<>()`）；但已核对全部调用点都只做 null 判断，**不区分「键不存在」与「值为 null」**，所以单次 `get` 是等价的。

**改后**

```java
	@Nullable
	public static MinecartController getIfPresent(Level world, UUID cartId) {
		Map<UUID, MinecartController> carts = loadedMinecartsByUUID.get(world);
		return carts == null ? null : carts.get(cartId);     // 单次查找，语义等价
	}
```

`tick` 的列表复用（注意静态并发）：

```java
	// tick 是 static，服务端/客户端主线程各调一次 → 不能用一个静态 List 共享。
	@Unique private static final Map<Level, List<UUID>> createperf$toRemove = new WeakHashMap<>();

	public static void tick(Level world) {
		...
		List<UUID> toRemove = createperf$toRemove.computeIfAbsent(world, w -> new ArrayList<>());
		toRemove.clear();
		for (Entry<UUID, MinecartController> entry : carts.entrySet()) {
			MinecartController controller = entry.getValue();
			if (controller != null && controller.isPresent())
				continue;
			toRemove.add(entry.getKey());
		}
		for (UUID uuid : toRemove) {
			keySet.remove(uuid);
			cartsWithCoupling.remove(uuid);
		}
	}
```

> 用 `WeakHashMap<Level, List<UUID>>` 避免维度卸载后泄漏（手册 §7：不要强引用 Level）。
> 收益仅 1 个 `ArrayList`/tick，**本条可以只做 `getIfPresent` 的简化**，不做列表复用。
**Mixin 骨架**

```java
@Mixin(value = CapabilityMinecartController.class, remap = false)
public abstract class CapabilityMinecartControllerMixin {

	@Shadow @Final public static Map<UUID, MinecartController> loadedMinecartsByUUID;

	@Overwrite
	public static MinecartController getIfPresent(Level world, UUID cartId) {
		/* 单次 get；语义等价（调用方只做 null 判断） */
	}
}
```

> `@Overwrite` handler 必须是 `public static`（目标是 public static）。`loadedMinecartsByUUID` 是 `public static` 字段（`:38`，类型 `WorldAttached<Map<UUID, MinecartController>>`），可直接 `@Shadow`。
**验证方法**

- 功能：矿车耦合（用扳手连接两辆矿车）后推拉行为正常；矿车被卸载/击杀时耦合链正确断开（`handleKilledMinecart` 掉落耦合器）。
- 并发：单人客户端 + 服务端同时跑（联机时客户端也在 tick 自己的 `ClientLevel`），确认无 `ConcurrentModificationException`。

**收益估计**：每世界每 tick 省 1 个 `ArrayList`；`getIfPresent` 每次省 1 次哈希查找（UUID 的 `hashCode` 已缓存，单次很快）。**收益很小**（估计值）。
**回归测试点**：① 矿车耦合/解耦正常；② 矿车卸载后从 `loadedMinecartsByUUID` 移除（不泄漏）；③ 假实体（`getId() != cart.getId()`）仍被忽略；④ 耦合链断裂时掉落的耦合器数量不变。

---

## 不可 Mixin / 建议提给上游的候选

以下条目**不建议**在独立 mixin mod 里做，或需要上游配合；汇总给 Lead 收进 `11-upstream-issues.md`。

1. **`Train.tick` 每 tick 重读配置**：`AllConfigs.server().trains.maxAssemblyLength.get()` 在 `Train.findCollidingTrain:657` 每 tick 一次。**不能**为性能缓存到静态字段（手册 §7：用户改配置必须立即生效）。若上游把它改成「配置变更时广播/失效」的模式，才能安全缓存。**建议上游**：提供带版本号的配置快照。
2. **`GlobalRailwayManager.sided` 的客户端延迟加载设计**：`CatnipServices.PLATFORM.executeOnClientOnly(() -> () -> clientManager(m))` 用两层 lambda 换来「专用服务端不加载客户端类」。**这是有意的设计**，用 mixin 去消除分配必须自己复制「`@OnlyIn(Dist.CLIENT)` 隔离 + 延迟加载」语义，风险大于收益。**建议上游**：在 catnip 里提供零分配实现（例如 platform 侧缓存），让所有调用方受益。
3. **`EdgePointStorage.values` 的快照语义**：`Stream.toList()` 拷贝虽然浪费，但提供了对 `TrackGraphSync` 这类「遍历中可能改动」场景的安全性。**建议上游**：提供 `valuesSnapshot()` 与 `valuesView()` 两个方法让调用方自选，而不是让 mixin 去猜。
4. **`CarriageSyncData.getDistanceTo` 的 `reachedVia` 死存储**：全仓库只有写入没有读取（已 grep 确认）。**建议上游**：直接删掉这一个 `IdentityHashMap` 及每次入队的 `Pair`（纯上游收益，不需要 mixin）。
5. **`TravellingPoint.travel` 的 `validTargets` 分配**：上游把 `validTargets` 改成 `TravellingPoint` 的实例字段（或改用 fastutil 对象数组）比 mixin 改写整个方法安全得多。**建议上游**：内部把该列表做成可复用的草稿缓冲区。
6. **`ContraptionCollider.getPotentiallyCollidedShapes` 的扫描盒策略**：这是算法层面的问题（用实体盒宽高比推装置扫描体积）。**建议上游**：直接改成「遍历 `contraption.blocks` + AABB 相交」，并从源头保证 `simplifiedEntityColliders` 永远非 null（这样 `CarriageContraption` 的 null 返回也能收掉）。
7. **`CarriageContraption` 在传送门中返回 `null`**（`CarriageContraption.java:252-258`）：这个 `@Nullable` 契约迫使 `ContraptionCollider` 保留整条降级路径。**建议上游**：让该方法总是返回非 null（在传送门中返回裁剪后的碰撞列表），从根上删掉降级分支。
8. **`AbstractContraptionEntity.tick` 的 `removeIf`**：属于 JDK 集合 API 的分配，mixin 修它要复制 36 行 `tick()`。**建议上游**：改成 `if (!collidingEntities.isEmpty())` 守卫的迭代器循环（3 行）。
9. **`HarvesterMovementBehaviour:95` 的 `new ItemStack(state.getBlock())`**：比较语义只需要 `getItem()`。**建议上游**：把 `ItemHelper.sameItem(stack, new ItemStack(block))` 改成 `stack.is(block.asItem())`，或新增 `ItemHelper.sameBlockItem(ItemStack, Block)` 重载。
10. **`MountedStorageManager.tick` 的客户端空跑**：客户端根本没有「发同步包」的能力，却走完整路径。**建议上游**：在 `tick` 开头加 `if (entity.level().isClientSide) return;`（但这会改变客户端 `syncCooldown` 语义，需上游确认它是否被依赖）。
11. **`CapabilityMinecartController.getIfPresent` 的双查找**：纯上游 3 行修改。
12. **`RollerMovementBehaviour.canBreak` 的 6 次邻居查询**：如果上游愿意引入「每 tick 一次的 `BlockTags.PORTALS` 邻域缓存」，收益才真正可观；mixin 单独做缓存无法可靠失效。
13. **《需实测》`SynchedEntityData.set` 的相等短路**：本文件第 9 条的「不会强制同步」结论是用 **MC 1.20.1** 的字节码核对的（仓库里没有 1.21.1 的反编译源）。虽然 `SynchedEntityData` 在两个版本间没有已知行为变化，但**要在 1.21.1 上实测确认**（例如在 `TRACK_GRAPH` 写入处加计数器，观察 `dirty` 是否为 false）后再据此判断是否需要本条修复。

---

## 附：文件清单

- 本文件：`docs/perf/03-contraptions-trains.md`（15 条 finding + 本节）
- 整体重写方案（`@Overwrite` 完整方法体 + import + @Unique 字段 + 风险 + 回归测试点）：

| 文件 | 对应 finding |
| --- | --- |
| [overwrites/TrackGraph-getConnectionsFrom.md](overwrites/TrackGraph-getConnectionsFrom.md) | 第 1 条 |
| [overwrites/TravellingPoint-travel.md](overwrites/TravellingPoint-travel.md) | 第 2、7 条 |
| [overwrites/MountedStorageManager-tick.md](overwrites/MountedStorageManager-tick.md) | 第 3 条 |
| [overwrites/ContraptionCollider-getPotentiallyCollidedShapes.md](overwrites/ContraptionCollider-getPotentiallyCollidedShapes.md) | 第 4 条 |
| [overwrites/Train-findCollidingTrain.md](overwrites/Train-findCollidingTrain.md) | 第 6 条 |
| [overwrites/CarriageSyncData-getDistanceTo.md](overwrites/CarriageSyncData-getDistanceTo.md) | 第 8 条 |
| [overwrites/CarriageContraptionEntity-tickContraption.md](overwrites/CarriageContraptionEntity-tickContraption.md) | 第 9 条 |

