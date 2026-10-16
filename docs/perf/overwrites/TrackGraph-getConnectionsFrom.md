# @Overwrite / @Inject 详解：`TrackGraph.getConnectionsFrom`

> 关联 finding：`03-contraptions-trains.md` 第 1 条（High）
> 目标：`com.simibubi.create.content.trains.graph.TrackGraph`
> 本文件同时给出 **推荐方案（@Inject 短路）** 与 **备选方案（@Overwrite）**。

## 1. 原方法完整源码（Create 6.0.10，行号已逐行核对）

文件：`src/main/java/com/simibubi/create/content/trains/graph/TrackGraph.java`，第 374–378 行。

```java
	public Map<TrackNode, TrackEdge> getConnectionsFrom(TrackNode node) {
		if (node == null)
			return null;
		return connectionsByNode.getOrDefault(node, new HashMap<>());
	}
```

字段声明（同文件第 52 行、70 行）：

```java
	Map<TrackNode, Map<TrackNode, TrackEdge>> connectionsByNode;   // 第 52 行
	...
	connectionsByNode = new IdentityHashMap<>();                    // 第 70 行（构造器）
```

## 2. 为什么值得重写

1. `getOrDefault(node, new HashMap<>())` 的 `new HashMap<>()` 是**求值实参**：无论命中与否都要构造一个 `HashMap`（默认容量 16、阈值 12、table 延迟到首次 put，所以是 1 个对象 + 48 字节 header）。命中路径上这个对象立刻被丢弃 → **纯垃圾**。
2. 调用链密度（已核对全部 43 处 `getConnectionsFrom` 调用点）：
   - `TravellingPoint.travel`：`:239`、`:299`、`:304`、`:334`（其中 `:304` 在候选邻居循环内，每邻居一次）
   - `CarriageSyncData.apply`：`:187`（每轮点一次）；`CarriageSyncData.getDistanceTo`：`:312`、`:322`、`:345`
   - `TrackEdge.canTravelTo`：`:121`、`:125`、`:138`、`:142`
   - `TrackEdge` 的这两个方法又是 `TravellingPoint.travel` 里 `edge.canTravelTo(newEdge)` 的被调方 → 一次 travel 里会叠加放大
3. 每个**命中**调用都白扔 1 个 HashMap；节点不存在时（`TrackGraphBounds` 遍历无连接节点、`Navigation` 的 pathfind 试探）也白扔 1 个。

## 3. 关键陷阱：为什么不能只在 `getOrDefault` 上做 @Redirect / @ModifyExpressionValue

- 手册 §3.1：`@Redirect` 的 handler 拿到的实参里，`new HashMap<>()` **已经构造完毕**，分配照样发生。
- 手册 §3.2：`@ModifyExpressionValue` 修饰 `new HashMap<>()` 会插入 `checkcast HashMap`，返回 `Collections.emptyMap()` 会 `ClassCastException`。
- 返回 `null` 也不安全：`TrackGraphBounds.java:27-28` 拿到返回值后**直接** `.values()`，而 `addNode`/`addNodeIfAbsent`（`:150-163`）**不会**给 `connectionsByNode` 建条目 → 该节点存在但无连接时原方法返回空 map，返回 null 就会在该处 NPE。

## 4. 推荐方案：@Inject(HEAD, cancellable) + 共享哨兵空 map

需要的 import（若采用本方案）：

```java
import java.util.HashMap;
import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
```


用 `@Accessor` 读私有字段，HEAD 短路。共享哨兵必须是 `HashMap` 类型（避免 checkcast 问题），且**绝不能**被写入：

```java
@Mixin(value = TrackGraph.class, remap = false)
public abstract class TrackGraphMixin {

	// 哨兵：保持「非 null、空、类型为 HashMap」的原契约，消除每次调用的分配。
	// 绝不能写入；一旦有代码 put 进来，会污染所有图。
	@Unique private static final Map<Object, Object> createperf$EMPTY = new HashMap<>(0);

	@Shadow @Final Map<TrackNode, Map<TrackNode, TrackEdge>> connectionsByNode;

	@Inject(method = "getConnectionsFrom(Lcom/simibubi/create/content/trains/graph/TrackNode;)Ljava/util/Map;",
		at = @At("HEAD"), cancellable = true)
	private void createperf$fastGetConnectionsFrom(TrackNode node, CallbackInfoReturnable<Map<TrackNode, TrackEdge>> cir) {
		if (node == null) {
			cir.setReturnValue(null);
			return;
		}
		Map<TrackNode, TrackEdge> hit = connectionsByNode.get(node);
		cir.setReturnValue(hit != null ? hit : (Map<TrackNode, TrackEdge>) (Map<?, ?>) createperf$EMPTY);
	}
}
```

`@Shadow @Final` 对非 static 字段是合法的（`remap = false`，字段名 `connectionsByNode` 直接可见）。若不愿意 `@Shadow`，改用 `@Accessor("connectionsByNode")` 并声明 `protected abstract Map<TrackNode, Map<TrackNode, TrackEdge>> createperf$getConnectionsByNode();`。

## 5. 备选方案：@Overwrite

```java
	@Unique private static final Map<TrackNode, TrackEdge> createperf$EMPTY_CONNECTIONS = new HashMap<>(0);

	@Overwrite
	public Map<TrackNode, TrackEdge> getConnectionsFrom(TrackNode node) {
		if (node == null)
			return null;
		Map<TrackNode, TrackEdge> connections = connectionsByNode.get(node);
		return connections != null ? connections : createperf$EMPTY_CONNECTIONS;
	}
```

- 优点：语义一眼看清，逐字保持「非 null」契约。
- 缺点：与上游 3 行 getter 的重构冲突面比 `@Inject` 大；且 `connectionsByNode` 的字段访问要靠 mixin 继承 `TrackGraph` 的字段（`@Mixin` 类需 `extends TrackGraph`？不需要 —— `@Overwrite` 方法体里直接引用 `connectionsByNode` 是编译期字段解析，Mixin 会把该引用重定向到目标类的同名字段，前提是 mixin 类**声明**了该字段或用 `@Shadow`）。**实务上建议直接用第 4 节方案。**

## 6. 风险

| 风险 | 说明 |
| --- | --- |
| 哨兵被写入 | 若有代码对返回值 `put`，会污染整张图。全仓库已核对无此类用法（所有调用点都是 `.get` / 迭代 / `entrySet`）。**但第三方 mod 可能写** —— 用 `Collections.unmodifiableMap` 代替会引入 checkcast 问题，所以只能写注释 + 在测试里盯。 |
| 语义微差 | 原方法 miss 时返回**新的可变空 map**，改后返回**共享空 map**。`isEmpty()` / 迭代 / `.get()` 行为完全一致；`put` 行为不同（原来写进垃圾，现在写进共享 map）。 |
| 私有字段访问 | `@Shadow` 字段名写错 → 启动即 `MixinApplyError`（`required:true` + `defaultRequire:1`）。这是好事。 |
| 与 Create 自带 mixin | 无重叠（create.mixins.json 只注入原版类）。 |

## 7. 回归测试点

1. **轨道图不串**：铺一张含 5 个以上岔口的轨道网，跑列车 A→B→A 往返 5 分钟，确认没有「列车突然切到另一条支线」。
2. **无连接节点**：找一个只有 1 个连接的端点节点（死胡同），让列车开到头，确认 `TrackGraphBounds` 重建（拆/铺轨道触发）不崩。
3. **跨维度轨道**：在下界与主世界各铺一段轨道并连通，确认 `TravellingPoint.migrateTo` 路径正常（`:413`）。
4. **信号传播**：让信号灯状态机跑一轮（`SignalPropagator` 大量使用 `getConnectionsFrom`），确认信号分组不错乱。
5. **反写检查**：临时在 mixin 里给哨兵 map 套一个会抛异常的包装（**仅测试用**），跑 1 分钟；如果没有异常，说明没有任何调用点写入。

## 8. 收益

**估计**：每次命中调用省 1 个 `HashMap`；每 tick 量级 = 4×车厢数 + 若干（`canTravelTo` 叠加）。100 节车厢规模下每 tick 数百个对象。具体数字需 JFR/Spark `--alloc` 实测。
