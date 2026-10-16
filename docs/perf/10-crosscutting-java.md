# 横切 Java / JVM 反模式清单

> 覆盖范围：全树（`src/main/java`）按「反模式」归类，而非按包划分；每个反模式给出全树受影响位置
> 调查依据：Create 6.0.10 / MC 1.21.1 源码逐行核对；行号以本仓库为准
> 公共约定见 [01-mixin-playbook.md](01-mixin-playbook.md)
> 本文件性质：**施工图** —— 读者不改 Create 源码，只写独立的 NeoForge 1.21.1 Mixin mod（包名占位 `com.example.createperf.mixin`）

## 摘要

- 全树最贵的 Java 层面反模式是 **A2：`SimpleRegistryImpl` 的 `synchronized` 查询**。它位于基类 `get(StateHolder)`（:56-60）与 `SingleImpl.get(K)`（:77-99）上，同时是 `MovementBehaviour.REGISTRY`、`BlockStressValues.IMPACTS/CAPACITIES/RPM` 等全部「简单注册表」的唯一入口；mounted actor 每 tick 查一次（AbstractContraptionEntity:448），渲染每帧再查一次（ContraptionVisual:180）。开销本身是常量级，但它在**已被 JIT 内联的热循环里反复进出 monitor**，且方法级 modifier **无法用 `@Inject` 去掉**。
- **A1：`ArrayList.remove(0)` 当 BFS 队列**是全树唯一成规模的 `O(n²)`：6 个类、11 个调用点。流体 3 处已归 [05-fluids.md](05-fluids.md)（本文件只交叉引用）；`TrackPropagator` / `ConnectivityHandler` 依赖 frontier 顺序，只能 `@Overwrite`。
- **A6：`String.matches(regex)` 重复编译**是「小方法 × 大循环」的典型：单次 `Pattern.compile` 约微秒量级，却在「每个目的地键 / 每张站台 / 每个包裹槽位」的内层循环里被调用成百上千次。全树 4 个同 idiom 位置 + `PackageItem.matchAddress`（归 04）。
- **A4：`getAllPresent` 的三段流管线 + `LinkedList` 拷贝**：每次查询都把 `TickBasedCache.asMap()`（内部强制 `cleanUp()` 全表扫描 + 临时 `HashSet`）的 values 拷进 `LinkedList`，再跑 map/filter/sorted/toList。所有物流网络查询（补货、库存、广播）都走它。
- 其余（A3/A5/A7/A8）是「规模放大后才痛」的中低优先级项：单次成本小，但分别在「每次广播」「每 5 tick × 256 实体」「每被吹实体每 tick」的频次上放大。
- 本文件另收录一批**不建议单独开 Mixin** 的通用 idiom（§A9）与**不可 Mixin / 建议提上游**的候选（文末），避免同一反模式在多个领域文件里被重复描述。

## 反模式总表：反模式 → 全树受影响位置

| # | 反模式 | 级别 | 全树受影响位置（file:line） | 注入方式 | 备注 |
| --- | --- | --- | --- | --- | --- |
| A1 | `ArrayList.remove(0)` 当 BFS 队列（数组整体移位） | High | `content/trains/track/TrackPropagator.java:105,167,192`；`content/trains/signal/SignalPropagator.java:165`；`content/trains/graph/TrackGraph.java:293`；`api/connectivity/ConnectivityHandler.java:71`；~~`PumpBlockEntity.java:146`~~；~~`FluidPropagator.java:58,106`~~；~~`FluidManipulationBehaviour.java:159`~~ | `@Overwrite`（顺序敏感 4 处）/ `@Inject(HEAD,cancellable)`（可换序处） | 流体 3 处 → [05](05-fluids.md)，只交叉引用 |
| A2 | 热路径 `synchronized` 方法 + 双 `IdentityHashMap` 探测 | High | `impl/registry/SimpleRegistryImpl.java:56-60,77-99`；调用点 `content/contraptions/AbstractContraptionEntity.java:448`、`content/contraptions/render/ContraptionEntityRenderer.java:158`、`content/contraptions/render/ContraptionVisual.java:180`、`api/stress/BlockStressValues.java:25,30` | `@Overwrite`（modifier 无法用 `@Inject` 去除） | 需 `@Shadow registrations / providedValues` |
| A3 | 嵌套循环内线性查找（`List.indexOf`） | Medium | `content/logistics/packagerLink/LogisticsManager.java:151`（外层 :144-150，stacks × availableLinks） | `@Overwrite` 或 `@Inject(HEAD,cancellable)` + `@Unique` 索引 | 同型见 A8 |
| A4 | 每次查询重建集合 + 流管线 + 无用拷贝 | Medium | `content/logistics/packagerLink/LogisticallyLinkedBehaviour.java:69-88`（拷贝 :79-80、sorted :85、toList :87）；放大源 `foundation/utility/TickBasedCache.java:134-137` → [09](09-*.md) | `@Inject(HEAD,cancellable=true)` | 私有静态字段需 `@Accessor` |
| A5 | `ArrayList.contains/remove` 在实体循环内（O(n·m)，无索引） | Medium | `content/kinetics/fan/NozzleBlockEntity.java:159,163`（循环 :149-165，字段 :32，`setLazyTickRate(5)` :39，阈值 :174） | `@Overwrite`（字段类型变更）或 `@Inject` + `@Unique` Set 镜像 | n 上限 256，超过即 discard |
| A6 | `String.matches(regex)` / `Glob.toRegexPattern` 重复编译 | Medium | `content/trains/display/GlobalTrainDisplayData.java:35,39`；`content/trains/schedule/destination/DestinationInstruction.java:81,94`；`content/trains/schedule/destination/FetchPackagesInstruction.java:106,138`；`content/trains/schedule/ScheduleScreen.java:334`；`PackageItem`（见 [04](04-logistics.md)） | `@ModifyExpressionValue` / `@Inject(HEAD,cancellable)` 短接 | 与 `PackageItem.matchAddress` 同源 |
| A7 | 每 tick 每实体的临时 `Vec3` 分配 | Medium | `content/kinetics/fan/AirCurrent.java:100`（循环 :85-100） | `@ModifyExpressionValue` / `@WrapOperation` | 每被吹实体每 tick 3 个 Vec3 |
| A8 | 循环内重复调用 getter + `indexOf` | Low | `content/contraptions/Contraption.java:296-304`（:300） | `@Inject(HEAD,cancellable)` | `getSeats()`（:1407）仅返回字段，成本在 `indexOf` |
| A9 | 其它通用 idiom（纯遍历 `stream()`、`.toList()`、每 tick `Optional`、`LinkedList` 当队列、`getOrDefault(..., new HashMap<>())`、枚举 `values()`、`catch` 当控制流） | Low | 见 §A9 清单 | 多数**不建议单独 Mixin** | 只列出、不展开 |
| A10 | 正则/Glob 路径上的实体（`PackageItem`） | — | `content/logistics/packageItem/PackageItem.java` | 已在 [04](04-logistics.md) 完整覆盖 | 反模式本体见 A6 |
| — | 交叉引用（不展开）：`BeltInventory` → [02](02-kinetics.md)；`KineticNetwork/RotationPropagator` → [02](02-kinetics.md)；`BeltRenderer` → [07](07-*.md)；`PumpBlockEntity`/流体 BFS → [05](05-fluids.md)；`TickBasedCache` → [09](09-*.md) | — | — | — | 避免重复条目 |

## A1. ArrayList.remove(0) 当 BFS 队列 — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | content/trains/track/TrackPropagator.java:95,101,105,167,192；content/trains/signal/SignalPropagator.java:57,164-165；content/trains/graph/TrackGraph.java:279,292-293；api/connectivity/ConnectivityHandler.java:35,44,70-71 |
| 调用频次 | 触发式而非每 tick：信号更新（SignalPropagator）、轨道/道岔状态变化（TrackPropagator）、多方块结构成型（ConnectivityHandler.formMulti，放置/破坏部件时）。三者在**单次调用内**做整图 BFS。 |
| 复杂度 / 成本 | ArrayList.remove(0) 是 System.arraycopy 左移，单次 O(n)，BFS 全程 O(V·n) = O(V²)（V = frontier 峰值）。ConnectivityHandler 的 frontier 是**多方块结构全部部件**，且循环内还会 frontier.add(...)，全程反复左移。 |
| 注入方式 | @Overwrite（顺序敏感处）/ @Inject(at=HEAD, cancellable=true)（可换序处） |
| 目标成员 | TrackPropagator#discoverAllTracksAround（:95-124）与 :160-180、:183-208 的三段 BFS；SignalPropagator#walkSignals（:160）；TrackGraph 的连通域收集（:279 起）；ConnectivityHandler#formMulti（:41 起） |
| 需新增状态 | 无（局部变量 List 换 ArrayDeque） |
| 风险 | 低（局部变量）；用 @Overwrite 则为中（必须逐字保持 frontier 顺序语义） |
| 脆弱度 | 中：@Overwrite 后上游若新增 frontier 操作（如临时排序）会静默改变行为；@Inject 版本依赖局部变量名 frontier，上游改名即失效（本地变量表，不会崩） |
| 置信度 | 确定 |

**改前**

```java
// TrackPropagator.java:95-106（同一文件内还有 :160-180、:183-208 两处相同结构）
List<FrontierEntry> frontier = new ArrayList<>();
addInitialEndsOf(reader, pos, state, track, frontier, false);
while (!frontier.isEmpty()) {
    FrontierEntry entry = frontier.remove(0);   // ← O(n) 左移
    ...
    continueSearch(frontier, visited, entry, ends);
}

// ConnectivityHandler.java:35-44 与 :70-71
List<T> frontier = new ArrayList<>();          // :35
frontier.add(be);                              // :36
formMulti(be.getType(), be.getLevel(), cache, frontier);

private static <T extends ...> void formMulti(..., List<T> frontier) {
    Direction.Axis mainAxis = frontier.get(0)   // :44 依赖「index 0 = 起始 BE」
        .getBlockState().getValue(...);
    for (T be : frontier) { ... }               // :54 依赖 frontier 当前内容
    minX -= frontier.get(0).getBlockPos().getX();  // :61-67 再次依赖 index 0
    while (!frontier.isEmpty()) {
        T part = frontier.remove(0);            // :71 ← O(n) 左移
        ...
        frontier.add(nextBe);                   // :96
    }
}
```

**为什么慢**

- ArrayList.remove(0) 在 JDK 里是 System.arraycopy(elementData, 1, elementData, 0, --size)：一次 BFS 出队 V 次，每次搬运当前 frontier 长度个引用，总搬运量 ~V²/2。
- 规模放大：ConnectivityHandler 的 V = 多方块结构部件数（大型蒸汽引擎/储罐/车架，数十到数百个 BE）；TrackPropagator 的 frontier 含整张轨道的端点与道岔，可达数百 → ~10⁵ 次引用搬运 + 同等次数 arraycopy 调用。
- **顺序语义是本条的关键判断**：ArrayDeque 的 addLast/removeFirst 与 ArrayList 的 add/remove(0) 行为**完全等价**（都是 FIFO），可直接替换且语义不变。
  - 因此 **swap-and-pop 不可用**：ConnectivityHandler 的 frontier.get(0)（:44、:61-67）读的是「仍在 frontier 中的第一个元素」，换序会改变按 axis 聚合与 minX/minY/minZ 的计算结果；TrackPropagator.continueSearch 依赖先入先出的扩散顺序（ends 收集顺序会变）。
  - TrackGraph.java:292-293 是连通域收集，SignalPropagator.walkSignals（:160-217）的 frontier 元素是 Couple<TrackNode>：两者都只允许 ArrayDeque，不允许 swap-and-pop。

**改后**（纯 Java：ArrayList → ArrayDeque）

```java
// 只改声明与出队两点；ArrayDeque.add == addLast，入队处可不动
Deque<FrontierEntry> frontier = new ArrayDeque<>();
addInitialEndsOf(reader, pos, state, track, frontier, false);
while (!frontier.isEmpty()) {
    FrontierEntry entry = frontier.removeFirst();   // O(1)，无搬运
    ...
}

// ConnectivityHandler.formMulti：参数类型一起换成 Deque<T>
private static <T extends ...> void formMulti(..., Deque<T> frontier) {
    Direction.Axis mainAxis = frontier.peekFirst()   // 原 frontier.get(0)，语义相同
        .getBlockState().getValue(...);
    for (T be : frontier) { ... }                    // ArrayDeque 可迭代，顺序同 ArrayList
    minX -= frontier.peekFirst().getBlockPos().getX();
    while (!frontier.isEmpty()) {
        T part = frontier.removeFirst();
        ...
        frontier.addLast(nextBe);
    }
}
```

> 注意：ConnectivityHandler.formMulti 是 private static，且 frontier 由外部传入（:37、:368）**不是方法内局部变量**。改它的参数类型必须同时改调用点 —— 单方法 @Overwrite 覆盖不了，需连 :35-37 与 :353,:368 一起重写。

**Mixin 骨架**

```java
// 1) frontier 是局部变量：整体重写方法体（保留原 FIFO 语义）
package com.example.createperf.mixin.trains;

import java.util.ArrayDeque;
import java.util.Deque;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(value = com.simibubi.create.content.trains.track.TrackPropagator.class, remap = false)
public abstract class TrackPropagatorMixin {
    /**
     * @author createperf
     * @reason frontier 由 ArrayList.remove(0) 换 ArrayDeque（保持 FIFO 与 visited/ends 顺序）
     */
    @Overwrite
    private static void discoverAllTracksAround(/* 与源码逐字一致的参数表 */) {
        Deque<FrontierEntry> frontier = new ArrayDeque<>();
        addInitialEndsOf(reader, pos, state, track, frontier, false);
        while (!frontier.isEmpty()) {
            FrontierEntry entry = frontier.removeFirst();
            // ... 原方法体逐行照抄，仅替换 frontier 的构造与出队；
            //     注意 :98 的 addInitialEndsOf 与 :121 的 continueSearch 参数类型也要换成 Deque
        }
    }
}
```

```java
// 2) ConnectivityHandler：签名一起改
package com.example.createperf.mixin.api;

import java.util.ArrayDeque;
import java.util.Deque;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(value = com.simibubi.create.api.connectivity.ConnectivityHandler.class, remap = false)
public abstract class ConnectivityHandlerMixin {
    @Overwrite
    private static <T> void formMulti(/* 原签名，List<T> → Deque<T> */) {
        // 逐行照抄 :44-96：frontier.get(0) → peekFirst()，remove(0) → removeFirst()，add → addLast
    }
    // :35-37 与 :353,:368 两处调用点同样需要 @Overwrite（或 @Inject 内联改造）
}
```

**验证方法**（High 必给）

- 注入生效：-Dmixin.debug.export=true，检查 run/.mixin.out/ 导出的 TrackPropagator.class 中 frontier 的运行时类型是否按预期改变。
- 探针：@Unique 静态计数器 + System.nanoTime() 累计，对比开/关 mixin；观察 ArrayList.remove 是否还出现在分配/采样里。
- 端到端：Spark /spark profiler --timeout 60，对照场景 =「铺设 200+ 段轨道并触发一次道岔切换」；观测 TrackPropagator 自耗时占比。
- 正确性回归（比性能更重要）：同一存档下道岔切换后的可达站台集合、信号边界集合、多方块结构 minX/minY/minZ 与部件列表，必须与原版逐项一致。

**收益估计**：BFS 内部引用搬运量 O(V²) → O(V)（估计：V=200 时约 2×10⁴ 次搬运 → 0）。单次信号/结构更新总耗时通常仍由世界读写主导，可见收益**需实测**；仅当 V 很大（大车架、长轨道）才可能进入 profiler 前列。
**回归测试点**：道岔/信号传播范围不变；多方块结构成型与拆分不变；轨道连通域划分不变。


## A2. 热路径 synchronized 方法 + 双 IdentityHashMap 探测 — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | impl/registry/SimpleRegistryImpl.java:56-60（基类 get(StateHolder)）与 :77-99（SingleImpl.get(K)）；两者都是 public synchronized |
| 调用频次 | 每 actor 每 tick：AbstractContraptionEntity.tickActors():448（MovementBehaviour.REGISTRY.get(blockInfo.state())，循环 :445 起）；每 actor 每帧：ContraptionVisual.java:180（Flywheel visual 路径）与 ContraptionEntityRenderer.java:158（原版渲染路径）；另有 BlockStressValues.java:25,30 的 IMPACTS.get/CAPACITIES.get（每次工具提示/应力查询）。 |
| 复杂度 / 成本 | 单次是常数级（1 次 getter + 2~3 次 IdentityHashMap.get），但：(a) 进出 monitor 的 monitorenter/monitorexit 使方法**无法被 JIT 完全消除同步**（锁消除只适用于可证明无逃逸的对象锁 —— 这里的锁对象是注册表实例，会被其它线程看到，JIT 不会消除）；(b) 它出现在已内联的 actor 循环里，每次迭代至少两次锁操作。 |
| 注入方式 | @Overwrite（**必需**：方法级 synchronized 是 modifier，@Inject/@Redirect/@ModifyExpressionValue 都无法移除它） |
| 目标成员 | SimpleRegistryImpl$SingleImpl#get(Ljava/lang/Object;)Ljava/lang/Object; 与 SimpleRegistryImpl#get(Lnet/minecraft/world/level/block/state/StateHolder;)Ljava/lang/Object; |
| 需新增状态 | 无新增字段；需要 @Shadow 读取 registrations（基类 protected final IdentityHashMap）与 providedValues（SingleImpl 的 private final IdentityHashMap），并把 providedValues 换成 ConcurrentHashMap |
| 风险 | 中：改变了并发模型（见下方「并发论证」），但只影响初始化期的竞争，不影响稳态语义 |
| 脆弱度 | 中：依赖 registrations/providedValues 字段名与 nullMarker 的语义（SingleImpl:71,107-109）；上游若改缓存策略会静默失效或编译期报错（@Shadow 找不到字段时启动即崩，符合 defaultRequire=1 的期望） |
| 置信度 | 高概率（机制确定；实际收益需实测） |

**改前**（SimpleRegistryImpl.java）

```java
// :53-60 —— 基类：StateHolder 重载，先解出 owner 再委托给 SingleImpl.get(K)
@Override @Nullable @SuppressWarnings("unchecked")
public synchronized V get(StateHolder<K, ?> state) {            // ← 锁 1（外层）
    Objects.requireNonNull(state, "state");
    K owner = ((StateHolderAccessor<K, ?>) state).getOwner();
    return this.get(owner);
}

// :70-99 —— SingleImpl
static final class SingleImpl<K, V> extends SimpleRegistryImpl<K, V> {
    private static final Object nullMarker = new Object();
    private final Map<K, V> providedValues = new IdentityHashMap<>();

    @Override @Nullable
    public synchronized V get(K object) {                      // ← 锁 2（内层，可重入）
        Objects.requireNonNull(object, "object");
        if (this.registrations.containsKey(object))            // #1 探测
            return this.registrations.get(object);             // #2 取值（同一对象查两遍）
        else if (this.providedValues.containsKey(object))      // #3 探测
            return this.providedValues.get(object) == nullMarker ? null : provided;  // #4
        for (Provider<K, V> provider : this.providers) { ... } // :88-94 仅 miss 时
        this.providedValues.put(object, nullMarker());         // :97 负缓存
        return null;
    }
}
```

**为什么慢**

- **成对加锁**：MovementBehaviour.REGISTRY 的字段类型是接口 SimpleRegistry<Block, MovementBehaviour>（api/behaviour/movement/MovementBehaviour.java:31），运行期实现是 SingleImpl。调用 get(BlockState) 时先进入基类 synchronized get(StateHolder)，再进入 SingleImpl.get(K) —— 同一对象上两次 monitorenter（可重入，代价仍是非零）。
- **为什么不能靠 @Inject 变快**：拿掉 synchronized 必须改方法 modifier，Mixin 的 @Inject/@Redirect 只在方法体内插桩，modifier 原样保留 → 只有 @Overwrite 能去掉。
- **谁在竞争**：源码注释（SimpleRegistryImpl.java:20）说明加锁理由是「注册可能发生在并行 mod 加载期」。稳态游戏内（客户端主线程 / 服务端主线程）几乎无竞争，因此这里付出的是**纯 uncontended fast-path 开销**（CAS/monitor 的固定成本 + 与 JIT 优化对抗）。
- **规模放大**：一个 200 个 actor 的车架，每 tick 200 次 × 每帧 200 次；多个车架/多个玩家在场时线性叠加。
- **并发论证**（替换为 ConcurrentHashMap 后为何安全）：
  1. 缓存值由 Provider.get(object) 决定，是「key 的纯函数」；并发重复计算最多写两次相同值，最终结果一致。
  2. invalidate() 只做 providedValues.clear()（:102-104）；与并发 put 竞争最多产生一次「读到已失效值」的短暂窗口 —— 原实现 synchronized 也存在同样的「读后写」语义，并非本改动引入。
  3. 主线程稳态下无写者，ConcurrentHashMap.get 是无锁读。
  4. registrations 保持 IdentityHashMap（只在 register 期写入，稳态只读）—— 读 IdentityHashMap 本身安全，故无需换类型、无需额外内存。

**改后**（纯 Java 等价改写）

```java
// SingleImpl：去掉 synchronized，providedValues 换 ConcurrentHashMap
private final Map<K, V> providedValues = new ConcurrentHashMap<>();

@Override @Nullable
public V get(K object) {                       // ← 无锁
    Objects.requireNonNull(object, "object");
    V registered = this.registrations.get(object);        // 一次取值代替 containsKey+get
    if (registered != null) return registered;
    V provided = this.providedValues.get(object);         // 一次取值；缺键返回 null
    if (provided != null) return provided == nullMarker ? null : provided;
    for (Provider<K, V> provider : this.providers) {      // providers 仅初始化期增长，稳态只读
        V value = provider.get(object);
        if (value != null) { this.providedValues.put(object, value); return value; }
    }
    this.providedValues.put(object, nullMarker());        // 保留负缓存语义
    return null;
}

// 基类：去掉 synchronized（单次委托，无状态）
@Override @Nullable
public V get(StateHolder<K, ?> state) {
    Objects.requireNonNull(state, "state");
    return this.get((K) ((StateHolderAccessor<K, ?>) state).getOwner());
}
```

> 为什么可以直接删 synchronized 而不换锁类型：稳态读路径上 registrations 与 providers 不再被写；唯一写入者是 register/registerProvider，它们仍需保持可见性 —— 若担心并行 mod 加载期的事件可见性，可把两个字段分别换成 ConcurrentHashMap / CopyOnWriteArrayList（仍需实测，但成本远低于每次查询进出 monitor）。

**Mixin 骨架**

```java
package com.example.createperf.mixin.registry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.simibubi.create.api.registry.SimpleRegistry;
import com.simibubi.create.impl.registry.SimpleRegistryImpl.SingleImpl;

@Mixin(value = SingleImpl.class, remap = false)
public abstract class SingleImplMixin<K, V> {
    @Shadow @Final protected Map<K, V> registrations;      // 继承自 SimpleRegistryImpl
    @Shadow @Final private Map<K, V> providedValues;       // 声明处是 IdentityHashMap，可 @Shadow 为 Map
    @Shadow private java.util.List<SimpleRegistry.Provider<K, V>> providers;
    @Shadow private static Object nullMarker;

    /** @author createperf @reason 去掉查询路径上的 monitor；见 10-crosscutting-java.md A2 */
    @Overwrite
    @Nullable
    public V get(K object) {
        V registered = this.registrations.get(object);
        if (registered != null) return registered;
        V provided = this.providedValues.get(object);
        if (provided != null) return provided == nullMarker ? null : (V) provided;
        for (SimpleRegistry.Provider<K, V> provider : this.providers) {
            V value = provider.get(object);
            if (value != null) { this.providedValues.put(object, value); return value; }
        }
        this.providedValues.put(object, (V) nullMarker);
        return null;
    }

    // providedValues 仍在 ConcurrentHashMap 上工作：@Overwrite 不改字段初始化。
    // 若希望字段本身也换成 ConcurrentHashMap，用 @Accessor 或第二个 mixin 改字段声明（不推荐）。
    @Unique private static Object createperf$marker() { return nullMarker; }
}

// 基类重载同样需要去 synchronized（否则 StateHolder 路径仍有一次锁）
@Mixin(value = com.simibubi.create.impl.registry.SimpleRegistryImpl.class, remap = false)
public abstract class SimpleRegistryImplMixin<K, V> {
    @Shadow @Final protected Map<K, V> registrations;
    @Shadow abstract V get(K object);   // 需与目标签名一致；若不可见则用 @Invoker 或直接内联实现

    @Overwrite
    @Nullable
    @SuppressWarnings("unchecked")
    public V get(net.minecraft.world.level.block.state.StateHolder<K, ?> state) {
        java.util.Objects.requireNonNull(state, "state");
        K owner = ((com.simibubi.create.foundation.mixin.accessor.StateHolderAccessor<K, ?>) state).getOwner();
        return this.get(owner);
    }
}
```

> 实施提示：SingleImplMixin 里的 providedValues 实际是 IdentityHashMap，不能靠 @Overwrite 把字段类型变成 ConcurrentHashMap。两种做法：(a) 只在 @Overwrite 里当做 Map 读写（如上，改动最小，仍消除锁）；(b) 用 @Unique 建一个并行的 ConcurrentHashMap 缓存并镜像 invalidate()（更复杂，收益不明确）。**推荐 (a)，但必须实测确认「并行 mod 加载期」不再出现可见性问题**。

**验证方法**（High 必给）

- 注入生效：-Dmixin.debug.verbose=true -Dmixin.debug.export=true，确认导出类中 get 的 ACC_SYNCHRONIZED 标志已消失；未生效时启动会因 defaultRequire=1 直接报错（这是期望行为）。
- 探针：@Unique 静态计数器统计 get 调用次数 + nanoTime 累计；对照场景 =「一个 200 actor 的车架持续运转 60s」。
- 分配/锁：JFR 事件 jdk.JavaMonitorEnter（或 Spark 的锁采样），对比改动前后 SimpleRegistryImpl 的 monitor 进入次数应降为 0。
- 端到端：Spark /spark profiler --timeout 60，观察 SimpleRegistryImpl.get 及其被内联后的父帧占比。

**收益估计**：每次查询省去 1~2 次 unintended monitor 操作（估计：百纳秒量级），在「每 actor 每 tick + 每帧」频次上累计。总体 TPS/FPS 影响**需实测**；本条更接近「消除持续的背景开销」而非「让卡顿消失」。
**回归测试点**：所有 SimpleRegistry 派生查询（MovementBehaviour.REGISTRY、应力 IMPACTS/CAPACITIES/RPM、其它 tag 注册表）返回值不变；invalidate() 后仍能重新查询到新值（数据包重载 / 标签重载场景必测）；并行 mod 加载不报错、不出现空洞值。

## A3. 嵌套循环内线性查找（List.indexOf） — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | content/logistics/packagerLink/LogisticsManager.java:151（usedLinks.indexOf(link)；外层 :144，内层 :150） |
| 调用频次 | 每次库存补货广播（broadcastPackageRequest → findPackagersForRequest，:85-86）。玩家点「补货」或红石触发一次。 |
| 复杂度 / 成本 | O(stacks × availableLinks × usedLinks)，最坏 O(stacks × links²)。每次 indexOf 是线性扫描（LogisticallyLinkedBehaviour 未覆写 equals → 引用比较，单次便宜但次数多）。 |
| 注入方式 | @Overwrite（改动集中在 usedLinks 的数据结构） |
| 目标成员 | LogisticsManager#findPackagersForRequest（返回 Multimap<PackagerBlockEntity, PackagingRequest>） |
| 需新增状态 | 无（方法内局部 Reference2IntOpenHashMap） |
| 风险 | 低（但必须保持 r.nextInt() 的调用顺序与次数） |
| 脆弱度 | 中：方法体长，@Overwrite 后上游任何逻辑调整都会静默丢失 |
| 置信度 | 确定（复杂度）；收益需实测 |

**改前**

```java
List<LogisticallyLinkedBehaviour> usedLinks = new ArrayList<>();   // :135
PackageOrderWithCrafts context = order;
int orderId = r.nextInt();                                        // :142 随机数消耗

for (int i = 0; i < stacks.size(); i++) {                         // :144
    BigItemStack entry = stacks.get(i);
    int remainingCount = entry.count;
    boolean finalEntry = i == stacks.size() - 1;
    ItemStack requestedItem = entry.stack;

    for (LogisticallyLinkedBehaviour link : availableLinks) {     // :150
        int usedIndex = usedLinks.indexOf(link);                   // :151 ← O(n) 线性查找
        int linkIndex = usedIndex == -1 ? usedLinks.size() : usedIndex;
        MutableBoolean isFinalLink = new MutableBoolean(false);    // :153 每次迭代都 new
        if (linkIndex == usedLinks.size() - 1)
            isFinalLink = finalLinkTracker;
        Pair<PackagerBlockEntity, PackagingRequest> request = link.processRequest(
            requestedItem, remainingCount, address, linkIndex, isFinalLink, orderId, context, ignoredHandler);
        if (request == null) continue;
        requests.put(request.getFirst(), request.getSecond());
        int processedCount = request.getSecond().getCount();
        if (processedCount > 0 && usedIndex == -1) {
            context = null;
            usedLinks.add(link);                                   // :169
            finalLinkTracker = isFinalLink;
        }
        remainingCount -= processedCount;
        if (remainingCount > 0) continue;
        if (finalEntry) finalLinkTracker.setTrue();
        break;
    }
}
```

**为什么慢**

- usedLinks.indexOf 每遇到一个候选链接就跑一次，而 usedLinks 在「同一订单跨多个包容器」时持续增长。规模场景：一次补货订单 10 种物品 × 30 个已链接库存 → 单次广播数千次引用比较；100+ 链接则上万次。
- 附带分配：new MutableBoolean(false)（:153）在内层循环每次迭代都创建，而只有 usedIndex == -1 且 processedCount > 0 时才需要。**注意**：不能简单地把创建推迟 —— finalLinkTracker = isFinalLink 保存的是**引用**，后续 finalLinkTracker.setTrue() 会写入那个对象；改动创建时机必须保证等价（本条不推荐顺手改这项）。

**改后**（纯 Java：fastutil 引用索引代替线性查找）

```java
Reference2IntOpenHashMap<LogisticallyLinkedBehaviour> usedIndexById = new Reference2IntOpenHashMap<>();
usedIndexById.defaultReturnValue(-1);
List<LogisticallyLinkedBehaviour> usedLinks = new ArrayList<>();

for (int i = 0; i < stacks.size(); i++) {
    ... 同前 ...
    for (LogisticallyLinkedBehaviour link : availableLinks) {
        int usedIndex = usedIndexById.getInt(link);                 // O(1)
        int linkIndex = usedIndex == -1 ? usedLinks.size() : usedIndex;
        MutableBoolean isFinalLink = new MutableBoolean(false);
        if (linkIndex == usedLinks.size() - 1) isFinalLink = finalLinkTracker;
        ... 同前（processRequest / requests.put / remainingCount 逻辑逐字保留）...
        if (processedCount > 0 && usedIndex == -1) {
            context = null;
            usedIndexById.put(link, usedLinks.size());             // 登记下标
            usedLinks.add(link);
            finalLinkTracker = isFinalLink;
        }
        ... 同前 ...
    }
}
```

**Mixin 骨架**

```java
package com.example.createperf.mixin.logistics;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import com.google.common.collect.Multimap;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;

@Mixin(value = LogisticsManager.class, remap = false)
public abstract class LogisticsManagerMixin {
    /** @author createperf @reason usedLinks.indexOf → Reference2IntOpenHashMap（10-crosscutting-java.md A3） */
    @Overwrite
    public static Multimap<PackagerBlockEntity, PackagingRequest> findPackagersForRequest(
            UUID freqId, com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts order,
            com.simibubi.create.content.logistics.packager.IdentifiedInventory ignoredHandler, String address) {
        // 逐行照抄 :100-181，仅替换 usedLinks 的查找/登记。
        // 必须保留：r.nextInt() 的调用顺序与次数、context 置 null 的时机、finalLinkTracker 的引用语义。
        throw new AssertionError("skeleton");
    }
}
```

**收益估计**：内层查找 O(links) → O(1)；10 物品 × 30 链接规模下约 3×10² 次比较 → 30 次哈希查找（估计）。广播路径整体（含 getSummary 与 NBT 写入）通常远大于此，可见收益**需实测**。
**回归测试点**：同一订单下包容器分配、每个包的 linkIndex/isFinalLink、随机数序列必须与原版一致（改变 r.nextInt 调用次数会破坏跨端一致性）。

## A4. 每次查询重建集合 + 流管线 + 无用拷贝 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | content/logistics/packagerLink/LogisticallyLinkedBehaviour.java:69-88（拷贝 :79-80、排序 :85、toList :87） |
| 调用频次 | 每次物流网络查询。调用者：LogisticsManager.createSummaryOfNetwork(:55)、LogisticsManager.getStockOf(:74，未缓存，每次查库存都走)、findPackagersForRequest(:109, sortByPriority=true) |
| 复杂度 / 成本 | 每次：1 个 LinkedList 拷贝（N 个 Node）+ N 次 WeakReference 解引用 + 1 条 Stream 管线 + 可选 N log N 排序 + 1 个 toList。放大源：TickBasedCache.asMap()（foundation/utility/TickBasedCache.java:134-137）每次强制 cleanUp()（:140-150 全表 forEach + 临时 HashSet + 两轮 remove） |
| 注入方式 | @Inject(at = @At("HEAD"), cancellable = true) 直接构造返回值 |
| 目标成员 | LogisticallyLinkedBehaviour#getAllPresent(Ljava/util/UUID;ZZ)Ljava/util/Collection;（3 参版本是真正实现） |
| 需新增状态 | 无（复用静态字段 LINKS/CLIENT_LINKS，配 @Shadow 或 @Accessor） |
| 风险 | 低-中：不排序时返回任意 Collection 必须可接受（调用者只读遍历） |
| 脆弱度 | 低：返回类型是 Collection、方法名稳定；静态字段名若变动会启动期报错 |
| 置信度 | 确定 |

**改前**

```java
public static Collection<LogisticallyLinkedBehaviour> getAllPresent(UUID freq, boolean sortByPriority,
                                                                   boolean clientSide) {
    Cache<Integer, WeakReference<LogisticallyLinkedBehaviour>> cache =
        (clientSide ? CLIENT_LINKS : LINKS).getIfPresent(freq);
    if (cache == null)
        return Collections.emptyList();
    Stream<LogisticallyLinkedBehaviour> stream = new LinkedList<>(cache.asMap()   // :79-80 全量拷贝（asMap 内部再 cleanUp）
        .values()).stream()
        .map(WeakReference::get)                                      // :81
        .filter(LogisticallyLinkedBehaviour::isValidLink);            // :82

    if (sortByPriority)
        stream = stream.sorted((e1, e2) -> Integer.compare(e1.redstonePower, e2.redstonePower));  // :85

    return stream.toList();                                           // :87
}
```

**为什么慢**

- new LinkedList<>(cache.asMap().values())：先全量拷进链表（每个元素一个 Node 对象）再把链表当 Stream 源 —— 这次拷贝**对结果没有任何贡献**。
- cache.asMap() 不是廉价访问：TickBasedCache.asMap() 先 cleanUp()（遍历 timestamps 全表 + 构造 HashSet + 两轮移除）再返回 ConcurrentHashMap。于是每次查库存都顺手做一次**全表过期扫描**。
- Stream.toList() 在 JDK 21 上返回 Collections.unmodifiableList(new ArrayList<>(...))，至少一次数组分配 + 拷贝。
- 规模放大：网络链接数 N 通常 5~50；但 getStockOf 在「每个请求物品 × 每个请求者」循环里被调用 → 单 tick 内可达数百次。N=50 时每次约 50~100 个对象分配。

**改后**（纯 Java：去掉拷贝与流，单遍迭代）

```java
public static Collection<LogisticallyLinkedBehaviour> getAllPresent(UUID freq, boolean sortByPriority,
                                                                   boolean clientSide) {
    Cache<Integer, WeakReference<LogisticallyLinkedBehaviour>> cache =
        (clientSide ? CLIENT_LINKS : LINKS).getIfPresent(freq);
    if (cache == null)
        return Collections.emptyList();

    List<LogisticallyLinkedBehaviour> result = new ArrayList<>();          // 直接一次分配
    for (WeakReference<LogisticallyLinkedBehaviour> ref : cache.asMap().values()) {
        LogisticallyLinkedBehaviour behaviour = ref.get();
        if (isValidLink(behaviour))
            result.add(behaviour);
    }
    if (sortByPriority)
        result.sort((e1, e2) -> Integer.compare(e1.redstonePower, e2.redstonePower));
    return result;
}
```

> 本条真正的收益点在 asMap() 触发的 cleanUp()（O(全表)，与本次查询规模无关）。修它需要改 TickBasedCache，已在 [09](09-*.md) 覆盖 —— 此处只标注它是 A4 的放大源，不重复该修复。

**Mixin 骨架**

```java
package com.example.createperf.mixin.logistics;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.google.common.cache.Cache;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;

@Mixin(value = LogisticallyLinkedBehaviour.class, remap = false)
public abstract class LogisticallyLinkedBehaviourMixin {
    @Shadow private static Cache<UUID, Cache<Integer, WeakReference<LogisticallyLinkedBehaviour>>> LINKS;
    @Shadow private static Cache<UUID, Cache<Integer, WeakReference<LogisticallyLinkedBehaviour>>> CLIENT_LINKS;
    @Shadow private static boolean isValidLink(LogisticallyLinkedBehaviour behaviour) { return false; }

    /** @author createperf @reason 移除 LinkedList 全量拷贝与流管线（10-crosscutting-java.md A4） */
    @Inject(method = "getAllPresent(Ljava/util/UUID;ZZ)Ljava/util/Collection;", at = @At("HEAD"), cancellable = true)
    private static void createperf$fastGetAllPresent(UUID freq, boolean sortByPriority, boolean clientSide,
            CallbackInfoReturnable<Collection<LogisticallyLinkedBehaviour>> cir) {
        Cache<Integer, WeakReference<LogisticallyLinkedBehaviour>> cache =
            (clientSide ? CLIENT_LINKS : LINKS).getIfPresent(freq);
        if (cache == null) { cir.setReturnValue(Collections.emptyList()); return; }
        List<LogisticallyLinkedBehaviour> result = new ArrayList<>();
        for (WeakReference<LogisticallyLinkedBehaviour> ref : cache.asMap().values()) {
            LogisticallyLinkedBehaviour b = ref.get();
            if (isValidLink(b)) result.add(b);
        }
        if (sortByPriority)
            result.sort((e1, e2) -> Integer.compare(e1.redstonePower, e2.redstonePower));
        cir.setReturnValue(result);
    }
}
```

> LINKS/CLIENT_LINKS 是 private static final：若 @Shadow 编译期报「cannot shadow final」，改用 @Accessor 接口或另建一个暴露 public static getter 的 @Mixin；Mixin 对 private static final 的处理随版本而异，**需实测确认**。

**收益估计**：每次查询从「LinkedList（N 个 Node）+ Stream 管线 + ArrayList」降到「1 个 ArrayList」（估计：N=50 时省去约 50 个 Node 对象与 3 个中间对象）。频次高，累计可测。
**回归测试点**：sortByPriority=true 时按红石等级升序的顺序不变；getSummary/getStockOf/findPackagersForRequest 输出一致；空网络返回 emptyList。

## A5. ArrayList.contains/remove 在实体循环内 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | content/kinetics/fan/NozzleBlockEntity.java:159,163；字段 :32；循环 :149-165 |
| 调用频次 | setLazyTickRate(5)（:39）→ lazyTick() 每 5 tick 一次；另有 tick()（:91-115）每 tick 遍历同一列表（只读 + 移除） |
| 复杂度 / 成本 | 每个被吹实体：contains O(n) + remove O(n)（数组移位）；整体 O(m·n)，m = AABB 内实体数，n = 已记录数 |
| 注入方式 | @Overwrite（字段是 private 且无 NBT/包读取，可整体换 Set）或 @Inject + @Unique 镜像 Set |
| 目标成员 | NozzleBlockEntity#lazyTick()V（另 5 个访问点 :91,:122,:163-164,:167,:176） |
| 需新增状态 | 走镜像方案时：@Unique private final ObjectOpenHashSet<Entity> createperf$pushingSet；作用域 per-instance；失效时机 = 与列表同步（每次 add/remove/clear 同时改）；并发 = 仅主线程 |
| 风险 | 低-中：换 Set 会改变 push 顺序，但 tick() 中逐实体施力无顺序依赖；>256 爆炸时的 discard 顺序会变，需确认无快照依赖 |
| 脆弱度 | 低：字段与 6 个访问点集中在同一文件，方法体短 |
| 置信度 | 确定 |

**改前**

```java
private List<Entity> pushingEntities = new ArrayList<>();   // :32 私有；全文件仅 :91-176 使用，无 NBT/包同步

public void lazyTick() {                                    // :140
    ...
    for (Entity entity : level.getEntitiesOfClass(Entity.class, bb)) {   // :149
        Vec3 diff = entity.position().subtract(center);
        double distance = diff.length();
        if (distance > range || entity.isShiftKeyDown() || AirCurrent.isPlayerCreativeFlying(entity))
            continue;
        boolean canSee = canSee(entity);
        if (!canSee) {
            pushingEntities.remove(entity);      // :159 ← O(n) 查找 + O(n) 左移
            continue;
        }
        if (!pushingEntities.contains(entity))   // :163 ← O(n)
            pushingEntities.add(entity);         // :164
    }
    for (Iterator<Entity> it = pushingEntities.iterator(); it.hasNext(); ) { ... }  // :167 清理死亡实体
    if (!pushing && pushingEntities.size() > 256 && !level.isClientSide) {          // :174
        level.explode(...);
        for (Iterator<Entity> it = pushingEntities.iterator(); it.hasNext(); ) {     // :176
            it.next().discard(); it.remove();
        }
    }
}
```

**为什么慢**

- ArrayList.contains/remove 都是线性扫描（Entity 用默认引用相等，单次便宜，但每实体每 5 tick 一次）。
- 规模放大：n 有硬上限 256（:174），但**只有超过 256 才触发清零**，因此列表会长期停在 200+ 规模。此时每个 AABB 内实体要付 200+ 次比较 + remove 的数组左移（最多 256 个引用）。典型场景：风扇对着掉落物堆/刷怪塔。
- :167 与 :176 的 iterator.remove() 同为 O(n) 左移，在 256 长度上每次清理约 256 次搬运。

**改后**（纯 Java：引用哈希集合）

```java
// 方案 A（推荐）：字段直接换成 fastutil 的引用哈希集合
private final ObjectOpenHashSet<Entity> pushingEntities = new ObjectOpenHashSet<>();

// lazyTick 中
if (!canSee) {
    pushingEntities.remove(entity);        // O(1)
    continue;
}
pushingEntities.add(entity);               // O(1)，自动去重，无需 contains

// :167 的清理
pushingEntities.removeIf(e -> !e.isAlive());   // fastutil 提供 removeIf

// :174/:176
if (!pushing && pushingEntities.size() > 256 && !level.isClientSide) {
    level.explode(null, center.x, center.y, center.z, 2, ExplosionInteraction.NONE);
    for (Entity entity : pushingEntities) entity.discard();
    pushingEntities.clear();
}

// tick() :91 的迭代：用迭代器以便删除
var it = pushingEntities.iterator();
while (it.hasNext()) {
    Entity entity = it.next();
    ... 原逻辑 ...
    if (distance > range || entity.isShiftKeyDown() || AirCurrent.isPlayerCreativeFlying(entity))
        it.remove();                         // fastutil 迭代器支持 remove
}
```

> 方案 A 需要改字段声明并 @Overwrite tick/lazyTick/setRange 三个方法；方案 B（保守）：保留 List 字段，另加 @Unique 的 ObjectOpenHashSet 只用于 contains/remove，列表仍用于迭代 —— 改动面更小，代价是两份状态。

**Mixin 骨架**（方案 B，改动最小）

```java
package com.example.createperf.mixin.kinetics;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.world.entity.Entity;
import com.simibubi.create.content.kinetics.fan.NozzleBlockEntity;

@Mixin(value = NozzleBlockEntity.class, remap = false)
public abstract class NozzleBlockEntityMixin {
    @Shadow private List<Entity> pushingEntities;
    @Unique private final ObjectOpenHashSet<Entity> createperf$pushingSet = new ObjectOpenHashSet<>();

    /** @author createperf @reason contains/remove O(n) → O(1)（10-crosscutting-java.md A5） */
    @Inject(method = "lazyTick()V", at = @At("HEAD"), cancellable = true)
    private void createperf$fastLazyTick(CallbackInfo ci) {
        createperf$pushingSet.clear();
        createperf$pushingSet.addAll(pushingEntities);   // 入口与列表对齐
        // 随后用 set 做 contains/remove 并写回列表，最后 ci.cancel();
        ci.cancel();
    }
}
```

> 实施建议：这条用 @Inject 拼凑很啰嗦，**推荐直接 @Overwrite lazyTick()**（约 40 行、无早退、无随机数）并把字段换成 ObjectOpenHashSet。改 private 字段类型的具体手法（第二个 @Mixin 覆盖字段声明 vs @Accessor+@Overwrite 全部访问方法）**需实测**。

**收益估计**：单次 lazyTick 匹配成本 O(m·n) → O(m)（估计：m=n=200 时约 8×10⁴ 次比较/左移 → 200 次哈希查找）；每 5 tick 一次，收益与「风扇下实体堆」规模正相关，**需实测**。
**回归测试点**：风扇推实体行为不变（哪些实体被推、推力方向）；过载 >256 的爆炸与 discard 行为不变；无重复实体残留。

## A6. String.matches(regex) / Glob.toRegexPattern 重复编译 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | GlobalTrainDisplayData.java:35,39；DestinationInstruction.java:81,94；FetchPackagesInstruction.java:106,138；ScheduleScreen.java:334 |
| 调用频次 | prepare：每次列车时刻显示刷新，对每个目的地键一次；DestinationInstruction：每次路径查找，对每张站台；FetchPackagesInstruction：每次搜包，对每个邮政箱的每个包裹槽位；ScheduleScreen：每次 UI 重算可行图，对每张图的每张站台 |
| 复杂度 / 成本 | String.matches(regex) 实现为 Pattern.compile(regex).matcher(this).matches()；Pattern.compile 是微秒量级（随表达式长度增长），远贵于一次匹配本身 |
| 注入方式 | 推荐 @WrapOperation（MixinExtras）包裹 String.matches 的调用，保留原有流管线；次选 @Inject(HEAD,cancellable) 短接整个方法 |
| 目标成员 | GlobalTrainDisplayData#prepare(Ljava/lang/String;I)Ljava/util/List;；DestinationInstruction / FetchPackagesInstruction 中调用 matches 的方法；ScheduleScreen 的私有方法（:325-338 所在） |
| 需新增状态 | @Unique static ConcurrentHashMap<String, Pattern> createperf$patterns；作用域 = 全局；失效时机 = filter 串变化即换键（**必须设上限或只缓存单个最近值**，否则玩家可控字符串会无界增长）；并发 = ConcurrentHashMap |
| 风险 | 中：缓存键必须包含完整 filter 串；无界缓存是内存泄漏风险 |
| 脆弱度 | 低（@WrapOperation 只认 String.matches 调用，上游重构不影响）；
ScheduleScreen 是客户端 UI 类，mixin 必须放进 mixins.json 的 
client
 数组 |
| 置信度 | 确定（重复编译的事实与位置）；收益需实测 |

**改前**

```java
// GlobalTrainDisplayData.java:34-45 —— 每个目的地键一次 String.matches（每次重新编译）
public static List<TrainDeparturePrediction> prepare(String filter, int maxLines) {
    String regex = Glob.toRegexPattern(filter, "");            // :35 Glob→字符串（只做一次）
    return statusByDestination.entrySet()
        .stream()
        .filter(e -> e.getKey()
            .matches(regex))                                 // :39 ← 每个 key 一次 Pattern.compile
        .flatMap(e -> e.getValue().stream())
        .sorted()
        .limit(maxLines)
        .toList();
}

// DestinationInstruction.java:81,93-94 —— 每张站台一次
String regex = getFilterForRegex();                          // :81
for (GlobalStation globalStation : train.graph.getPoints(EdgePointType.STATION)) {
    if (!globalStation.name.matches(regex))                  // :94
        continue;
}

// FetchPackagesInstruction.java:106,136-139 —— 每个包裹槽位一次
String regex = getFilterForRegex();                          // :106
if (!PackageItem.getAddress(stack).matches(regex))           // :138
    continue;

// ScheduleScreen.java:334 —— 每张图每张站台一次
if (station.name.matches(filter))                            // :334
    continue Graphs;
```

**为什么慢**

- String.matches 每次调用都重新编译。JDK 内部虽有小容量字符串→Pattern 缓存（Pattern.Cache，容量约 12 且会被其它正则挤掉），在混用多个 filter 时几乎必然 miss。
- 主要成本不是「编译一次」而是「在内层循环里编译 N 次」：N = 目的地键数 / 站台数 / 包裹槽位数。
- 规模放大：FetchPackagesInstruction 的包裹槽位数可达数百（多个邮政箱 × 每箱槽位）；GlobalTrainDisplayData.prepare 在列车时刻屏刷新时被调用；ScheduleScreen 是客户端 UI，还会带来帧内分配压力。

**改后**（纯 Java：一处编译，循环内复用）

```java
Pattern pattern = Pat| FetchPackagesInstruction.java:142 的 catch (PatternSyntaxException ignored) | 与 **A6** 同源：每次匹配失败都可能构造异常。 | 与 A6 合并：预编译 Pattern 时在方法入口 try/catch 一次，循环内不再抛。 |

**A9 总体结论**：除 A9.1（TrackGraph.getConnectionsFrom —— 与 A1 同文件但不同反模式）外，本节各项**都不建议单独排期做 Mixin**；应作为「顺手改」，或在实测确认热点后单独立项。

## A10. 交叉引用（不重复展开）

以下条目已在其它领域文件完整覆盖；本文件只登记归属，避免同一 finding 出现两份不一致描述。

| 条目 | 归属文件 | 本文件中的关联反模式 |
| --- | --- | --- |
| content/logistics/packageItem/PackageItem.java（matchAddress 正则路径） | [04-logistics.md](04-logistics.md) | A6（反模式说明在 A6，实体在 04） |
| content/kinetics/belt/transport/BeltInventory.java | 02（**本仓库当前未产出**） | A9.2（LinkedList 字段 :51-53） |
| content/kinetics/KineticNetwork.java、content/kinetics/RotationPropagator.java | 02（**未产出**） | A9.2（LinkedList frontier :342,343,411,423） |
| content/kinetics/belt/BeltRenderer 一类渲染路径 | [07-client-render-ui.md](07-client-render-ui.md) | A7（渲染路径分配） |
| 流体 BFS：PumpBlockEntity.java:146、FluidPropagator.java:58,106、FluidManipulationBehaviour.java:159 | [05-fluids.md](05-fluids.md) | A1（同一反模式，流体侧已有完整条目） |
| LogisticallyLinkedBehaviour.java 依赖的 TickBasedCache | [09-compat-loadtime.md](09-compat-loadtime.md) | A4（asMap() 强制 cleanUp() 是 A4 的放大源） |
| LogisticsManager.java 的缓存读取 | [09-compat-loadtime.md](09-compat-loadtime.md) | A4 |

> 02-kinetics.md 在本次调查时**尚未产出**，故 BeltInventory/KineticNetwork/RotationPropagator 没有可指向的文件。若最终编号不同，以 [00-overview-and-roadmap.md](00-overview-and-roadmap.md) 的文件清单为准。

## 不可 Mixin / 建议提给上游的候选

本节列出 **不该由本 Mixin mod 解决**的问题。判据：无法用 Mixin 表达、或实现代价与冲突面远大于收益、或用 Mixin 修属「错层」。每条给出上游建议。

| 候选 | 为什么不可 / 不该 Mixin | 上游建议 |
| --- | --- | --- |
| TickBasedCache 的语义缺陷（foundation/utility/TickBasedCache.java:134-137） | asMap() 被实现为 **先 cleanUp() 再返回 map**（:140-150：全表 timestamps.forEach + 临时 HashSet + 两轮 remove）—— 「查询接口顺带做全表维护」的设计问题。单点 @Overwrite 到 asMap() 虽可行，但它是**客户端与服务端共用**的基础设施，被 LogisticallyLinkedBehaviour 等多处依赖，改它跨领域（A4、09）；过期语义一旦漂移会产生「链接不消失」这类难复现 bug。 | 建议上游：把清理从 asMap() 移出（只返回视图），或改为摊还的增量清理（按时间桶分片）；并补语义注释。 |
| ConnectivityHandler 的队列类型（api/connectivity/ConnectivityHandler.java:35,41,71,353,368） | formMulti 的 frontier 是**跨方法传递的参数**，改类型必须同时改 formMultiTop 与两处调用点；且 :44、:61-67 依赖 frontier.get(0) = 起始 BE 的隐含约定。Mixin 可做（A1 骨架），但改动量大、回归面广（多方块结构的成型/拆分/尺寸计算）。 | 建议上游：把 List<T> frontier 改为 Deque<T>（或方法内部自建），并显式注释 index 0 是起始 BE。低风险纯收益。 |
| SimpleRegistryImpl 的 synchronized（impl/registry/SimpleRegistryImpl.java:26,42,56,77,116,133,139,164） | 本 mod 可用 @Overwrite 去掉读路径的锁（A2）；但「用 synchronized 保护读缓存」本身是上游设计选择 —— 源码注释（:20）写明理由是「注册可能发生在并行 mod 加载期」。真正修法是上游把**注册期**与**查询期**分离。 | 建议上游：注册完成后把 registrations/providedValues 转成不可变快照（或改 ConcurrentHashMap），并提供显式的「冻结」时机。 |
| BlockEntityBehaviour.get 的 try/catch（:101-109） | 没有可预判条件可替代 try/catch（要预判就得先知道会话是否在并发修改，API 不提供）。Mixin 只能改到异常抛出处（vanilla Level.getBlockEntity），属错层。 | 建议上游：Level.getBlockEntity 的并发修改应返回 null 而非抛异常，或提供 getBlockEntityOrNull 变体。 |
| CrafterHelper.areCraftersConnected 的 catch (IndexOutOfBoundsException)（:31-38） | 条件可预判（data 是否为空），但纯 Java 修法涉及重新定义竞态语义，属行为变更，不由第三方 mod 决定。 | 建议上游：先取 data.get(0) 到局部变量并在一次检查内使用。 |
| Contraption.getSeats()（:1407-1409）返回内部可变列表 | Mixin 无法安全地「返回防御性拷贝」：调用方 Contraption.java:552 会 getSeats().add(...)，:1265 会 get(index) —— 返回不可变视图会直接破坏功能。正确修法是上游明确 API 契约。 | 建议上游：暴露 addSeat/seatCount/seatAt 等窄接口，替代直接返回 List。 |
| 全树 .toList() 共 72 处（A9.3） | 逐个 Mixin 短接代价极高且收益不明。 | 若实测确认为热点，建议上游在 API 层提供「返回 Iterable / 复用缓冲」的变体。 |
| 加载期单例 / 静态初始化 | 与 mod 加载顺序耦合；Mixin 在此的失败模式（静默不生效、早于目标类初始化）难以验证。 | 见 [09-compat-loadtime.md](09-compat-loadtime.md)。 |

**提给上游的统一格式**（建议 [11-upstream-issues.md](11-upstream-issues.md) 采用）：问题一句话 + 文件:行 + 为什么下游 mod 改不了 + 建议改法 + 是否影响存档兼容。

## 与其它领域文件的边界

1. **本文件只负责「反模式本体」**：机制解释 + 通用修复范式 + 代表性 Mixin 骨架。
2. 若同一位置在领域文件里有更完整的条目（流体 BFS、BeltInventory、TickBasedCache 等），本文件**只保留一行交叉引用**，不复制其 finding 表。
3. 反向引用时，领域文件应写「反模式 A#」，而不是重新解释一遍 JVM 机制。
4. 行号以本仓库源码为准。本文件中标注「需实测」的项目（A7 的 setDeltaMovement 重载、A4/A5 的 @Shadow 到 private static final / private 字段类型替换、AirCurrent 的 @ModifyExpressionValue 实参绑定、A8 的 initialPassengers 字段可见性）在动手写代码前必须先在开发环境验证。

**需要实测的清单（汇总）**

| 项 | 需要验证什么 |
| --- | --- |
| A2 | 去掉 synchronized 后并行 mod 加载期是否出现可见性问题；providedValues 只当 Map 用是否足够 |
| A4 | @Shadow 到 private static final 字段（LINKS/CLIENT_LINKS）是否被 Mixin 接受 |
| A5 | 改 private List<Entity> 字段类型的具体手法（第二个 @Mixin 覆盖字段声明 vs @Accessor + @Overwrite 全部访问点） |
| A6 | @WrapOperation 对 String.matches 的注入点在编译后是否唯一（上游流管线可能有多处 matches） |
| A7 | 该项目 MC 1.21.1 映射上是否存在 Entity.setDeltaMovement(double,double,double)；@ModifyExpressionValue 的 handler 实参如何绑定 |
| A8 | Contraption.initialPassengers 的字段名与可见性（Contraption.java:297 使用处） |
| A9.4 | 分配采样确认热路径上是否真的存在 Optional 分配 |

**A1–A10 与领域文件的编号对照**

| 本文件 | 同一问题的负责文件 |
| --- | --- |
| A1（流体 3 处） | [05-fluids.md](05-fluids.md) |
| A6（PackageItem） | [04-logistics.md](04-logistics.md) |
| A9.2（BeltInventory/RotationPropagator） | 02（未产出） |
| A9.3（Contraption.blocks 遍历） | [03-contraptions-trains.md](03-contraptions-trains.md) |
| A4 / 缓存清理 | [09-compat-loadtime.md](09-compat-loadtime.md) |
| 渲染路径分配 | [07-client-render-ui.md](07-client-render-ui.md) |