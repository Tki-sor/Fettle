# 24 · 更激进：对**已发布**优化的二次推进（候选清单）

> 口径：本文只回答一件事——**已经发布并被验证的每一项，还能不能更激进、更通用、推到更多调用点**。
> 少数与它们同源、且已核实过 jar 的"邻近项"单独标出（§3）。
>
> **快照说明**：写于 2026-10-17，当时 `Fettle-1.21.1/src` 正被 Lead 修改，本轮只读观察。
> 写作期间 Lead 已新增 `mixin/create/fluids/FluidPropagatorDequeMixin`、`mixin/create/fluids/PipeConnectionAccessor`、
> `util/PosDist`（`com.tkisor.fettle.util.PosDist` = `record PosDist(BlockPos pos, int distance)`），
> 配置项 `create.fluidPropagatorDeque`（`FettleConfig.java:154-159`，默认 true）也已接线，
> 注释指向尚未生成的 `docs/fettle/25-fluid-propagator-deque.md`。
> **因此 F1 的 FluidPropagator 两处（propagateChangedPipe / resetAffectedFluidNetworks）不算候选**，剩下的一处是 §1-C3。
>
> 所有 `class#method` 都用下面这条命令核实过（MC 原版类用 §7 的 NeoForm 产物 jar）；未能核实的在 §6 单列：
> ```bash
> "C:/Program Files/Java/jdk-21.0.10/bin/javap.exe" -p -c -cp Fettle-1.21.1/libs/create-1.21.1-6.0.10.jar <class>
> ```

## 0. 已发布项 → 可推进点 一览

| 已发布项（[20 台账](20-status-ledger.md)） | 现状 | 还能推什么 | 候选 |
| --- | --- | --- | --- |
| 配方 bitset 预筛 `RecipeManager#getRecipeFor` | ✅ −85%（23.4 → 3.5–4.1 µs） | ① 补 vanilla 的 `input.isEmpty()` 早退（**正确性**）；② 扩到 4 参重载与 `getRecipesFor` | **C4** |
| `getAllRecipesFor` 列表缓存 | ✅ | 已到位，无可推 | §2 |
| 动能网络校验查询 memo | ✅ 442,200 → 201（4.5–5×） | `isRemoved()` 快判（行为放宽，需差分） | **C6** |
| 动能增量应力 O(1) + 每 tick 对账 | ✅ 213×，`afterReconcile` 0 mismatch | ① 容量侧同构增量（`updateCapacityFor`）；② 对账降频 + `updateFromNetwork` 钩子 | **C2** |
| 列车碰撞候选过滤 | ✅ 候选 −94%，差分 0 mismatch | 把已原型化的 `SpatialBuckets` 接进 `TrainCollisionIndex#filter` | **C1** |
| `TravellingPoint` 位置 memo | ✅ 6–13× | 命中率计数器 + 双槽 / `TrackGraph#getChecksum()` 跨 tick 复用 | **C9** |
| `TrackGraph#getConnectionsFrom` 去分配 | ✅ | 无剩余分配与热点，不建议再动 | §2 |
| 包裹地址正则缓存 | ✅ ~4× | 再加一层"地址对 → 结果"memo | **C5** |
| 流体 BFS 出队 F1 | ✅ 纯队列 519× / 真实 ~1.5× | 最后一处 `PumpBlockEntity#distributePressureTo`（FluidPropagator 两处 Lead 已做） | **C3** |
| 面板聚合节流 B8 | ⚠️ 未证实（默认 1=关） | 自适应/版本门控（行为放宽，需先证明"值长期不变"） | **C8** |
| 沉睡试点（观察层 + 白名单） | ✅ 19:1（默认关） | 观察层 → 自动白名单（`/fettle idle candidates`） | **C7** |
| ①`MountedStorageManager#tick` / A8 皮带轴 / ②`SmartBlockEntity#tick` | ⚠️ 稳态无收益 | **不再推进**（逃逸分析已吃掉，见 §4） | — |
| 邻近（非已发布项）：罐体红石广播、溜槽、打包机 | 未实现 | 已核实类/方法，见 §3 | C10–C12 |

建议顺序（越小越先做）：**C4(正确性) → C1 → C2 → C3 → C7 → C5 → C9 → C8 → C6 → C10–C12**。

---

## 1. 主候选（直接推进已发布项）

### C1 · 把 `SpatialBuckets` 接进 `TrainCollisionIndex#filter`（列车候选过滤的算法级二次推进）

**位置**
- 目标：`com.simibubi.create.content.trains.entity.Train#findCollidingTrain(Level, Vec3, Vec3, ResourceKey)`。
  javap 已确认：循环体是 `Create.RAILWAYS.sided(level).trains.values()` 的迭代（`Map#values()` 就是 Fettle `@Redirect` 的锚点），
  跳过 `train == this` 与 `graph != this.graph`，内层对每节车厢取
  `Carriage#getLeadingPoint()` / `#getTrailingPoint()`（返回 `TravellingPoint`），
  然后 `Math.min(start2.distanceToSqr(start), end2.distanceToSqr(start)) > maxAssemblyLength^2 → continue`。
- Fettle 侧：`com.tkisor.fettle.alg.TrainCollisionIndex#ensureBuilt(Level)` / `#filter(Map, Train, Vec3, double)`；
  现有实现每次查询都 **O(T)** 遍历所有列车做 AABB 距离测试。

**依据 / 为什么这是"推进"**
- `com.tkisor.fettle.alg.SpatialBuckets` **已经存在但只被 `CreateMicroBenchmarks.collisionFilter`（第 284 行）使用**——
  正是 [11](11-collision-spatial-index.md) 里 200 列车 × 4 车厢、半径 32 的原型：
  距离判断 **160,000 → 1,999（−98.8%）**、耗时 **343.9 → 25.1/31.1 µs（13.7×/11.0×）**、命中数 999/999 一致。
- 也就是说：算法原型、微基准、保守性论证都齐了，唯独没有接进真实路径。

**做法**
1. `ensureBuilt` 时（每 tick 一次）把每列车的车厢端点灌进 `SpatialBuckets`（格子边长 = `maxAssemblyLength`）；
2. `filter` 用 `countWithin` 只访问查询球覆盖的格子 → 候选列车集合；
3. **必须原样保留** `movedSinceSnapshot` 守卫（[12](12-train-collision-differential.md) 实测：去掉守卫的方案 B 在"同 tick 内移进范围"场景下 3/3 碰撞全部漏检）；
4. 无 box 的新列车继续无条件保留（现有语义）。

**安全性 / 行为风险**
- 保守性论证来自原版自身：原版一旦发现某车厢两端都超出 `maxAssemblyLength` 就整列 `continue`；
  格子筛选保留了"至少一个端点在该半径内"这一必要条件 → 是超集筛选，不是近似。
- 风险在守卫与格子快照的一致性：格子是快照，守卫仍按车厢字段逐个复核 → 语义与现在的 AABB 版本相同。

**预期效果**
- 算法级（已实测，来源 [11.4](11-collision-spatial-index.md)）：距离测试 −98.8%，13.7×。
- **真实路径的净收益必须重新测**：守卫本身仍是 O(T) 字段读，所以收益上限是"box 距离测试"那一段；
  不要直接引用 13.7× 当作端到端收益（见 §6）。

**怎么测**
- `fettle micro collisionFilter 1000`（算法级，已存在）；
- `fettle debug trains 3`：必须仍是 `fresh mismatches=0` / `stale_guarded mismatches=0 missed=0`，
  且 `index=… queries=… kept=… keptMoved=… dropped=…` 五项与改动前**逐字相同**（`TrainCollisionIndex#counters()`）；
- 给 `filter` 加一个"实际做 box 距离测试的次数"计数器（确定性指标），比较新旧；
- `Fettle-1.21.1/tools/fettle_gate.py` 的 train 段会自动断言 `fresh` / `stale_guarded`。

**风险等级**：中（精确性依赖守卫；有现成差分测试作为回归门禁） · **顺序 2**

---

### C2 · 动能：容量侧增量 + 对账降频（把 213× 的结论推广到 `updateCapacityFor`）

**位置**
- `com.simibubi.create.content.kinetics.KineticNetwork#updateCapacityFor(KineticBlockEntity, float)`（javap 已确认 public）。
  字节码只有三条语义：`sources.put(be, capacity)` → `updateCapacity()` → `calculateCapacity()` 全量扫。
- 对照：`#updateStressFor(KineticBlockEntity, float)` 已被 `KineticNetworkIncrementalMixin` `@Overwrite`；
  `#calculateStress` / `#calculateCapacity` 的 `Level#getBlockEntity` 已被 `KineticNetworkLookupMixin` 记忆化。
- 已有接口方法：`com.tkisor.fettle.alg.KineticNetworkAdjust#fettle$adjustCapacity(float)`（**已存在但当前无人调用**）。

**依据**
- [14.1](14-incremental-stress-and-observer.md)：增量应力更新路径 **6.612 → 0.031 µs/op（213×）**，`afterReconcile` 0 mismatch；
- [13.3](13-kinetic-network-lookup.md)：201 成员网络全量扫描 **30.04/36.46 µs**（memo 后仍是 O(M) 遍历）；
- [14.3](14-incremental-stress-and-observer.md) 明确把"挂 `updateFromNetwork` 钩子"列为收尾项。

**做法**
1. 在 `KineticNetworkIncrementalMixin` 里对称地 `@Overwrite updateCapacityFor`：`sources.get(be)` 旧值 → running sum ±Δ；
   首见成员回退全量扫描（与应力路径同构）；把 `fettle$adjustCapacity` 接上；
2. 把 `add` / `remove` / `addSilently` / `initFromTE` 也纳入增量记账（现版本只在对账时靠全量扫描清理）；
3. 再把对账从"每 tick"放宽到"每 N tick"（新配置 `create.kineticReconcileTicks`，默认保持 1 先不动）——
   这一步只有在第 2 步做完、且差分测试 `speedChanged` 阶段变 0 差异之后才允许。

**安全性 / 行为风险**
- 与应力同构：增量 + 全量对账 + 差异计数。当前已知窗口是"转速变了但没人调用 `updateStressFor`"
  （[14.1](14-incremental-stress-and-observer.md)：15 次检查 10 次不一致，对账后 0）→ 容量侧同样存在。
- 放宽对账频率会把窗口从 ≤1 tick 拉长到 ≤N tick ⇒ 属于行为放宽，必须显式配置并写进文档。

**预期效果（预测，未测）**
- 单次容量变化从 O(M)（201 成员 ≈ 30 µs 量级）降到 O(1)（数十 ns 量级）；
- 对账降频后，`reconciles` 与全量扫描次数 ÷N；对大盘基地更明显。

**怎么测**
- `fettle micro kineticStress 2000`（现有输出含 `members=… us/op=… realLookups=… cacheHits=…`，需扩展 `reconciles=` / `capacityUpdates=`）；
- `fettle debug kinetics 200`（200 根轴 + 创造马达）作为场景；
- `fettle debug kinetictest 3`：`stress` / `speedChanged` / `afterReconcile` 三段；
- `Fettle-1.21.1/tools/fettle_gate.py` 的 kinetic 段断言 `mismatches=0`。

**风险等级**：中（float 漂移；必须保留对账与差异计数） · **顺序 3**

---

### C3 · 流体 BFS 出队的最后一处：`PumpBlockEntity#distributePressureTo`

**位置**
- `com.simibubi.create.content.fluids.pump.PumpBlockEntity#distributePressureTo(Direction)`，**protected**。
  javap 确认（偏移）：`166 new java/util/ArrayList`、`192 Integer.valueOf` + `199 Pair.of`、
  `221 invokeinterface java/util/List.remove:(I)Ljava/lang/Object;` —— 正是 F1 的 `remove(0)` + `Pair<Integer,BlockPos>` 装箱。
- 同类：`#hasReachedValidEndpoint(LevelAccessor, BlockFace, boolean)` 是 **private**（javap 第 685 行）；
  `#isFront(Direction)`、`#searchForEndpointRecursively(...)` 是 **protected**。

**依据**
- 已发布的 F1：`FluidManipulationBehaviour#search` 纯队列 519×、真实 5000 格水体 ~1.5×（[23](23-fluid-search-deque.md)）；
- Lead 本轮已用同一手法落地 `FluidPropagator#propagateChangedPipe` / `#resetAffectedFluidNetworks`（`FluidPropagatorDequeMixin`）；
- [perf/overwrites/PumpBlockEntity-distributePressureTo.md](perf/overwrites/PumpBlockEntity-distributePressureTo.md) 已把该方法完整贴出并判为低风险（局部变量、无包私有字段依赖）。

**做法**
- 复用 `com.tkisor.fettle.util.PosDist` 与相同的 `@Inject(HEAD, cancellable)` 模式，把
  `List<Pair<Integer,BlockPos>> frontier` + `remove(0)` 换成 `ArrayDeque<PosDist>` + `pollFirst()`；
- 因 `hasReachedValidEndpoint` 是 private → **必须** `@Invoker`（不要 `@Shadow`，[23.2](23-fluid-search-deque.md) 的 APPLY 失败教训）；
  `isFront` / `searchForEndpointRecursively` 用 `@Shadow protected abstract`；
- `worldPosition` 用 `@Shadow protected BlockPos worldPosition`（或 `getBlockPos()`）；
- 不动 `PumpFluidTransferBehaviour` / `distance + 1 >= maxDistance` / `blockFace.isEquivalent(start)` 这些分支。

**安全性**
- 两结构都是 FIFO → 访问顺序、`targets`、`validFaces`、压力施加结果不变；
- 触发频次低（只在泵转速绝对值变化 → `onSpeedChanged` → `updatePressureChange` → `sidesToUpdate` 时），
  所以这是**尖峰消除**而不是稳态收益；n 受 `FluidPropagator#getPumpRange()`（默认 16，`mechanicalPumpRange`）限制。

**预期效果（预测）**
- 单次压力重算：出队 O(n²) arraycopy → O(n)，并去掉每次入队的 `Integer` 装箱；
  在密集分支管道网络（几百格 frontier）上才有可见量级；纯队列上下界可引用 `fluidSearchQueue` 的 519×。

**怎么测**
- `fettle micro fluidSearchQueue 50000`（数据结构上下界，已存在）；
- 真实场景目前**没有**压力重算计时器 → 建议先在 `/fettle debug` 下加一个"压力重算次数/耗时"计数器，
  再用离合器切换泵转速触发；对照用 `/fettle debug fluidcheck`（读方块实体状态，见 [23.7](23-fluid-search-deque.md)）；
- APPLY 门禁：启动日志 `mixinApplyErrors=0`（[23.5](23-fluid-search-deque.md) 的验证方法）。

**风险等级**：低–中（本领域最低风险的一类，但必须重跑 APPLY 门禁） · **顺序 4**

---

### C4 · 配方索引：补 `input.isEmpty()` 等价分支（正确性）+ 扩到 4 参 / 列表重载

**位置（javap 反汇编 MC 原版，NeoForm 产物 jar，见 §7）**
- `net.minecraft.world.item.crafting.RecipeManager#getRecipeFor(RecipeType, RecipeInput, Level, RecipeHolder)`：
  偏移 0–12 先做 `input.isEmpty() → Optional.empty()`；偏移 13–38 是 holder 快路径
  （`recipe.value().matches(input, level)` 为真就直接返回该 holder）；否则偏移 41 起
  `byType(type).stream().filter(matches).findFirst()`。
- `#getRecipeFor(RecipeType, RecipeInput, Level)`（3 参）只是委托到上面这个 4 参方法（偏移 8）；
- `#getRecipeFor(..., ResourceLocation)` 先 `byKeyTyped` 再委托到 4 参（javap 已确认）；
- `#getRecipesFor(RecipeType, RecipeInput, Level)`：`byType(type).stream().filter(matches).sorted(Comparator.comparing(level...)).toList()`。

**发现（正确性，重要）**
- 现有 `RecipeManagerMixin#fettle$fastGetRecipeFor` 在 **3 参的 HEAD 直接 cancel**，**没有复刻 `input.isEmpty()` 早退**。
  vanilla 3 参是委托到 4 参、由 4 参先判空返回 `Optional.empty()`。
- 后果：若某个已索引类型里存在"`matches()` 对空输入返回 true"的配方（这类配方通常 `isIncomplete/isSpecial` → 落在 `unindexed` 永远候选），
  Fettle 会返回它，而原版返回空。—— 这是一个**尚未被覆盖**的偏差（`/fettle selftest` 的 parity 输入都是非空的，
  [04 §3](04-implementation-status.md) 的 `checked=1211 mismatches=0` 证明不了这一条）。
- 修法：在 `fettle$fastGetRecipeFor` 开头加 `if (input == null || input.isEmpty()) { cir.setReturnValue(Optional.empty()); return; }`，
  与 vanilla 逐字一致，顺带省掉一次索引查表。**这是本轮唯一确定的正确性修复。**

**推进（性能）**
- 4 参 `getRecipeFor(..., RecipeHolder)`：若 `recipe != null`，保留 vanilla 的"先测该 holder"语义；否则走 bitset 超集筛选；
- `getRecipesFor`：把 `filter(matches)` 之前的流换成"bitset 候选 → 只在候选上调用 `matches`"。
  `Stream.sorted` 是稳定排序，且被筛掉的都是"永不匹配"的元素（本来也不在输出里）→ 输出顺序与内容不变。
- 4 参重载调用频率低（[04 §4](04-implementation-status.md) 已判定"保持原版"），收益小；价值主要在 `getRecipesFor` 与正确性。

**预期效果**
- `getRecipesFor` 的 `matches` 调用次数从"该类型全部配方"降到候选数（候选比例取决于物品共用度，需实测；现有 −85% 是 3 参首命中的数字，不能直接套用）；
- 空输入路径变成 O(1)。

**怎么测**
- `fettle micro getRecipeFor 200000` → 扩展输出 `candidates=` / `matchesCalls=`；
- `fettle selftest`：`checked=… mismatches=0` 必须保持；
- **新增 parity 断言**：用空 `RecipeInput` 分别走 Fettle 与原版 4 参路径比较（可直接加进 `RecipeParityCheck`）；
- `Fettle-1.21.1/tools/fettle_gate.py` 的 parity 段。

**风险等级**：低（除 `isEmpty` 修复外都是收窄筛选；4 参的 holder 快路径要保留语义） · **顺序 1**

---

### C5 · `PackageItem#matchAddress`：从"缓存正则"推到"缓存结果"

**位置**
- `com.simibubi.create.content.logistics.box.PackageItem#matchAddress(String, String)`（javap 已核，static public）；
- `#matchAddress(ItemStack, String)` 只是 `getAddress(stack)` 后委托给前者（javap 偏移 0–8）→ **同一份缓存天然覆盖两个入口**，不需要额外注入。
- 现有实现：`PackageItemMixin` 缓存 `Pattern`（`ConcurrentHashMap`，≥256 条 `clear()`）。

**做法**
- 再加一层有界的 `(boxAddress, address) → boolean` 结果 memo（例如 512 条，满了整体清空，与现有策略一致）。
- 命中时连 `Matcher` 都不建；两个方向的匹配只在未命中时做。

**安全性**
- `matchAddress` 是**纯函数**（只依赖两个字符串），且现有 mixin 已逐字复刻 vanilla 的早退分支（javap 已核对：
  `address.isBlank()` → `boxAddress.isBlank()`；星号字符串 → true；`equals` → true）。
- 唯一注意点：缓存必须有界（地址是玩家输入）。

**预期效果（预测，需实测）**
- 现有：552–567 → 136–145 ns/op（**~4×**，[20 台账](20-status-ledger.md)）；
- 结果 memo 命中后约等于一次 map 查找 → 预测再快 2–5×，**但前提是地址对在实际物流里高度重复**。
  链式传送带上地址对通常只有个位数种，重复度高；必须在真实场景确认。

**怎么测**
- `fettle micro matchAddress 3000000`：建议给微基准加"使用 N 个不同地址对"的参数，分别测 1 对 / 1k 对（命中率两端）；
- 真实场景：链式传送带 + 若干分拣地址，用 `/fettle bench` 对比开关。

**风险等级**：低 · **顺序 6**

---

### C6 · 动能校验的"标志位快判"（激进：`Level#getBlockEntity` → `isRemoved()`）

**位置**
- `KineticNetwork#calculateStress` / `#calculateCapacity` 中的判据
  `if (be.getLevel().getBlockEntity(be.getBlockPos()) != be) { iterator.remove(); continue; }`
  （javap 已确认 `calculateStress` 用 `Iterator.remove()` 剪枝）。
- MC `net.minecraft.world.level.block.entity.BlockEntity#isRemoved()` 与 `#getLevel()` 已核实存在（§7）。

**现状与依据**
- memo 已把 442,200 次查询降到 201（[13.3](13-kinetic-network-lookup.md)，−99.95%，4.5–5×），但仍是 **O(M) 次查询/每次全量扫描**。
- [perf/02-kinetics.md](perf/02-kinetics.md) 的 upstream 建议就是换成 `be.isRemoved() || be.getLevel() == null`。

**为什么不直接做**
- **不严格等价**：区块卸载时 `Level#getBlockEntity(pos)` 返回 null（触发剪枝），但此时 `isRemoved()` 可能为 false、`getLevel()` 也可能非 null
  → 会漏剪枝，成员表里留下"已不在世界上"的 BE，后续求和可能算出漂移值。
- 因此这属于**行为放宽**，不是等价重写。

**预期效果（预测）**
- 把每次全量扫描的 O(M) 区块查询（201 成员时实测 ~30 µs 的原路径）压到 O(M) 标志读；量级待测。

**怎么测**
- `fettle debug kinetictest 3`（已有 mismatch 计数）+ `fettle micro kineticStress 2000`；
- 需新增计数器："因快判而未剪枝的成员数"、"members 表与实际 BE 数差异"，先证明差异恒为 0 再谈性能。

**风险等级**：中–高（语义放宽） · **顺序 9（先只做测量，不落地）**

---

### C7 · 沉睡试点：把观察层变成自动白名单

**位置**
- `com.tkisor.fettle.observe.BehaviourProbe`：已有 `tick(Class)` / `changed(Class)` / `sentData(Class)` / `report(int)` / `idleClasses()` / `dump()`（写 `fettle-observe.csv`）。
- `com.tkisor.fettle.observe.BeSleepController`：白名单 `HotFlags.BE_SLEEPING_CLASSES`、心跳 `BE_SLEEPING_MAX_SKIP_TICKS`、
  唤醒（setChanged / 方块+邻居更新 / capability / 玩家交互 / 玩家接近）、自动拉黑、`suspend()`/`wakeAll()` 都已存在；
  `BeSleepState` 是每实例字段，区块重载天然安全（[20 台账](20-status-ledger.md)）。
- `BehaviourProbe#idleClasses()` 已经就是"`ticks>0 && changed==0 && sendData==0`"的计数——缺的只是把它输出成**可粘贴的配置**。

**做法**
1. 新增 `/fettle idle candidates <minTicks>`：按 tick 数排序，过滤 `changed/tick == 0 && sendData/tick == 0 && ticks ≥ minTicks`，
   输出 `simpleName`（`BeSleepController#match` 支持 simple name / 全限定名 / `modid:` 前缀，源码已核）；
2. 可选 opt-in `beSleeping.autoWhitelistFromObservation`（默认 false）：观察满 `observeTicks` 后自动填充一次白名单，
   仍然保留"自动拉黑 + 一键 revert"。

**依据**
- [14.2](14-incremental-stress-and-observer.md) 的首份画像：`MechanicalCrafterBlockEntity ticks=1351792 changed/tick=0`、
  `BracketedKineticBlockEntity ticks=214208`、`CreativeMotorBlockEntity ticks=536`；
- 已实测沉睡比 **19:1（95% 跳过）**（[20 台账](20-status-ledger.md)、[16](16-ticker-level-sleep-and-gate.md)）。

**安全性 / 行为风险**
- 默认仍关、白名单仍空；auto 只是替人填名字，不会自己开 `beSleeping.enabled`；
- 放宽项：沉睡实例只靠既有唤醒条件被唤醒（心跳兜底），`changed/tick > 0` 的真实基地类会被自动拉黑。

**预期效果**
- 不是新的性能机制，而是把 [14.3](14-incremental-stress-and-observer.md) 的"下一步"从"人工挑一个类"变成"数据挑 N 个类"；
  收益 = 19:1 × 被选中类的 tick 占比（需在真实基地用 `/fettle idle report` + `/fettle bench` 量化）。

**怎么测**
- `fettle feature observe true` → `/fettle idle report` → 新 `/fettle idle candidates 100000`；
- 开 `beSleeping.enabled` 后用 `/fettle idle sleeptest`、`/fettle idle dump`、`/fettle bench start|stop`；
- `Fettle-1.21.1/tools/fettle_gate.py` 的 pilot 段（ratio 断言）。

**风险等级**：中（行为放宽，但护栏最全） · **顺序 5**

---

### C8 · 面板节流：自适应间隔 / 版本门控（行为放宽，必须先补证据）

**位置**
- `com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour#tick()`
  （javap 已确认 `tick` / `tickStorageMonitor` / `tickRequests` 三个方法）；
  现有 `FactoryPanelBehaviourMixin` 用 `@Inject(HEAD)` + 两处 `@Inject(INVOKE)` 做 tick 门控，相位用 `System.identityHashCode(this)`。

**现状（关键）**
- `FettleConfig.java:135-144`：`factoryPanelCheckInterval` **默认 1 = 原版行为**，注释明确写"NOT proven"；
- [19](19-client-spark-test.md)：脚手架场景面板段 −72%，但自然激活客户端场景 0.5210 vs 0.5302 ms **无差异** → 台账标"未证实"。

**推进方向**
1. 只有先证明"`tickStorageMonitor` 的输出长期不变"才值得做自适应：加一个"本次计算值与上次相同"的占比计数；
2. 自适应：值不变则把间隔按 5→10→20 退避（上限可配），值一变立即重算；
3. 版本门控：若将来确认存在可用的网络/摘要版本号，则以版本变化触发（[04 §10](04-implementation-status.md) 的建议）。

**安全性 / 行为风险**
- 明确的放宽：观察延迟上限从"固定 N tick"变成"最多 maxN tick"；吞吐不变（请求逻辑本身未改）。
- 未证实项不要贸然调大默认值；建议保持默认 1，作为 opt-in 实验项。

**怎么测**
- `fettle debug panels` / `panelwire` / `panelpack`（命令已存在）；
- 复刻 [19] 的两套场景：脚手架场景 + 自然激活场景（4096 面板），面板段用 `FactoryPanelBenchMixin` 的 section 计时；
- `/fettle bench start|stop|status` 看 tick_ms。

**风险等级**：中（收益未证实 + 行为放宽） · **顺序 8**

---

### C9 · `TravellingPoint` memo：先量命中率，再决定要不要加槽

**位置**
- `com.simibubi.create.content.trains.entity.TravellingPoint#getPositionWithOffset(TrackGraph, double, boolean)`
  （javap 已确认；并且 `#getPosition(TrackGraph)` → `#getPosition(TrackGraph, boolean)` → `#getPositionWithOffset(graph, 0.0, flip)` 的委托链
  ⇒ 碰撞路径 `findCollidingTrain` 里的 `getPosition(graph)` 确实经过 memo）。
- 现有 memo：**单槽** + `ServerTickClock` 每 tick 失效，键 = (tick, graph, edge, position, offset, flip, upsideDown)。

**推进方向**
1. 先加 hit/miss 计数器（判定单槽是否够；碰撞路径的 `flip` 取值集合有限，若确实交替，单槽会互相驱逐）；
2. 若命中率不足：改成 2–4 槽 direct-mapped（键不变，只是多槽）；
3. 激进：用 `com.simibubi.create.content.trains.graph.TrackGraph#getChecksum()`（javap 已确认 `public int getChecksum()`）
   作为跨 tick 有效性键，点没动时跨 tick 复用 → 命中窗口从一个 tick 扩到"图未变"。

**依据**
- 已发布：30–33 → 2.4–5.5 ns/op（6–13×，[10](10-algorithmic-memoisation.md)、[20 台账](20-status-ledger.md)）；
- 局限：memo 只降低"每次比较的成本"，比较次数仍是 O(T²·C)（[10.3](10-algorithmic-memoisation.md)）→ 次数问题交给 C1。

**安全性**
- 现键是精确键 + tick 戳；第 3 项把 tick 戳换 checksum 是**放宽**（checksum 碰撞/图编辑后未更新时可能命中旧值），
  必须只在"点未动且 checksum 未变"时命中，并写进配置注释。

**预期效果（预测）**
- 第 2 项收益取决于命中率，先测；第 3 项可把 6–13× 的适用范围扩大，但收益上限受 C1 影响（候选数先下来）。

**怎么测**
- `fettle micro travelPointPosition 3000000`（现存）+ 新增 hit/miss 输出；
- `fettle debug trains 3` 与 gate 的 train 段（跨 tick 版本必须仍 0 mismatch）。

**风险等级**：低–中 · **顺序 7**

---

## 2. 已发布项中"确认没有二次推进空间"的

- **`TrackGraph#getConnectionsFrom`**：HEAD 注入后 miss 路径只剩 `connectionsByNode.get(node)` + 共享的 `Collections.emptyMap()`，
  没有剩余分配；12 个调用点全部只读（[04 §6](04-implementation-status.md)）。写入路径 `putConnection` 的 `computeIfAbsent`
  （javap：`lambda$putConnection$4`）不在 tick 热路径上 → 不值得动。
- **`getAllRecipesFor` 列表缓存**：已返回 `TypeIndex.ordered`（元素与顺序与 `List.copyOf(byType)` 相同）。
- **`MountedStorageManager#tick` 快速路径**：稳态 0.0 B/op（[22.1](22-allocation-optimisations.md)）→ 已到顶。

## 3. 邻近项（已核实 jar，非已发布项）

### C10 · `FluidTankBlockEntity#onFluidStackChanged` 的红石/邻居广播门控
- 位置：`com.simibubi.create.content.fluids.tank.FluidTankBlockEntity#onFluidStackChanged(FluidStack)`（javap 已确认，protected）。
- 做法与预测来自 [perf/05-fluids.md](perf/05-fluids.md) 的 F2 与 [perf/overwrites/FluidTankBlockEntity-onFluidStackChanged.md](perf/overwrites/FluidTankBlockEntity-onFluidStackChanged.md)：
  仅当"比较器红石等级变化 OR 流体类型变化"才 `Level#updateNeighbourForOutputSignal`；3×3×3 罐体（27000 mB / 15 级 / 32 mB/tick）
  预测 ~1 次广播 / ~56 tick。
- 测：罐体填充场景 + `/fettle bench`，同时回归比较器读数。
- 风险：中（红石/比较器语义）· 顺序 10。

### C11 · `ChuteBlockEntity#getInputChutes` / `canDirectlyInsert`
- javap：`canDirectlyInsertCached()` **public 且已存在**，`canDirectlyInsert()` 与 `getInputChutes()` 是 private。
- ⚠️ 这说明 [perf/04-logistics.md](perf/04-logistics.md) 的"C4 缓存方向"可能**已被上游拿走一部分**：Create 6.0.10 自己就有缓存入口。
  建议先读 `canDirectlyInsertCached` 的调用点再决定是否值得做，别重复上游已做的事。**本轮未反汇编该方法体，标记未验证。** · 顺序待定。

### C12 · `PackagerBlockEntity#attemptToSend(List<PackagingRequest>)`
- javap 已确认签名（public）。做法来自 [perf/04-logistics.md](perf/04-logistics.md) 的 C1：
  9 次整库存扫描 → 槽位游标 + 箱满 break，预测 5–9 轮 → ~1 轮。
- 风险：中（要重写带标签的嵌套循环，语义边界逐个确认，[04 §10](04-implementation-status.md) 也是这个理由没做）· 顺序 11。

## 4. 明确不建议做（已验证无收益）

- **纯分配消除类**：A8 `BeltBlock#hasShaftTowards`（9.14/12.09/11.49 vs 10.17/11.15 ns/op）、
  ① `MountedStorageManager#tick`（C2 后原版 0.0 B/op）、② `SmartBlockEntity#tick`（0.0 B/op）。
  结论见 [22.3](22-allocation-optimisations.md)：**HotSpot 的逃逸分析已经把短命分配消掉，稳态拿不到收益**。
  同类的 [perf/02](perf/02-kinetics.md) D8 `SmartBlockEntity#forEachBehaviour`、[perf/03](perf/03-contraptions-trains.md) A5 也应先量 B/op 再决定。
- **A8 mixin 的维护成本**：行为完全等价，[20.1](20-status-ledger.md) 已判定"上游冲突时可直接删除，无功能损失"。
- **面板节流调大固定 N**：在没有新场景证据前不要动默认值（现在是 1）。
- **把 `maxAssemblyLength` 静态缓存**：配置可热改，[perf/03](perf/03-contraptions-trains.md) 明确反对。

## 5. 激进模式（允许行为放宽时才能做，逐条点名"放宽了什么"）

| 编号 | 做法 | **放宽的到底是什么** | 现有证据 |
| --- | --- | --- | --- |
| A1 | `create.trainCollisionIndexVerifyMoved = false`（方案 B） | 同一 tick 内"其他列车移进碰撞范围"时，该列车在**本 tick 剩余时间**可能被漏检（最坏：碰撞判定晚一个 tick / 高速对向短暂互穿） | [12.2](12-train-collision-differential.md) 实测 `stale_raw` 3/3 碰撞全部漏检；候选从 −94% 变 −97.5% |
| A2 | 动能对账从每 tick 改为每 N tick（需先补 `updateFromNetwork` 钩子） | 应力/容量读数最多陈旧 **N tick**（当前窗口是 ≤1 tick） | [14.1](14-incremental-stress-and-observer.md)：`speedChanged` 15 次检查 10 次不一致，对账后 0 |
| A3 | C6 的 `isRemoved()` 快判替代 `Level#getBlockEntity(pos) != be` | 区块卸载/替换的成员可能**不被剪枝**，成员表里保留已不存在的 BE | javap 已证两者不等价；需先用计数器证明差异为 0 |
| A4 | C8 面板自适应间隔（5→10→20 退避，上限 maxN） | 面板数值的观察延迟上限从 N tick 变为 **maxN tick**（吞吐不变） | [19](19-client-spark-test.md)：脚手架 −72%、自然场景无差异（未证实） |
| A5 | C9 用 `TrackGraph#getChecksum()` 取代每 tick 失效 | 图被编辑但 checksum 未变/碰撞时，memo 可能返回**旧位置**，直到 checksum 变化 | `getChecksum()` 已核实存在；跨 tick 复用**未测** |
| A6 | 沉睡试点开 `beSleeping.enabled` + 自动白名单 | 沉睡实例在心跳间隔内不参与 tick，只靠 6 类唤醒条件（setChanged / 方块+邻居 / capability / 交互 / 玩家接近 / 心跳） | [18](18-wake-conditions.md)、[20 台账](20-status-ledger.md) 19:1；真实基地 `changed/tick>0` 的类会被拉黑 |
| A7 | `MountedStorageManager#tick` 客户端早退（`entity.level().isClientSide → return`） | 客户端不再做每 8 tick 的同步图遍历；若客户端有依赖该路径的逻辑会失效 | [perf/03](perf/03-contraptions-trains.md) 明确要求先确认 client 侧不用 `syncCooldown`；**未验证** |
| A8 | `TrackGraph#getConnectionsFrom` 的"空 map 哨兵"推广到 `getConnectionsFrom(target) == null` 的 NPE 路径 | 把 NPE 变成**优雅返回**（静默跳过），错误会被吞掉 | [perf/overwrites/TravellingPoint-travel.md](perf/overwrites/TravellingPoint-travel.md)、[perf/overwrites/CarriageSyncData-getDistanceTo.md](perf/overwrites/CarriageSyncData-getDistanceTo.md)；**属于语义改变，不是优化** |

## 6. 未验证 / 存疑（诚实清单）

1. **C1 的真实收益未测**：doc 11 的 13.7× 是算法原型数字；接进 `filter` 后仍保留 O(T) 的 `movedSinceSnapshot` 守卫，
   净收益只来自 box 距离测试那一段。**必须**用 `fettle debug trains` 的计数 A/B 重新测，不能引用 13.7×。
2. **C2 的 `updateFromNetwork` 钩子未核实可行性**：需要确认 `KineticBlockEntity#updateFromNetwork` 是所有转速变化路径的必经点
   （[14.3](14-incremental-stress-and-observer.md) 列为下一步，本轮未反汇编该类）。
3. **C4 的 `input.isEmpty()` 偏差未构造出实例**：只在"某配方 `matches()` 对空输入返回 true"时可见；
   本轮不能起服务器（Lead 在编译），标记为"由字节码推出的潜在偏差"，建议用新增 parity 断言证实/证伪。
4. **C5 / C9 的命中率未知**：预测的再加速倍数依赖重复度，必须先加计数器。
5. **C11 很可能已被上游部分实现**（`canDirectlyInsertCached` 已存在），未反汇编其方法体。
6. **§3 的 C10–C12 全部来自 docs/perf 的预测数字**，本轮只核实了类/方法存在，没有实测。
7. 本文所有端到端（MSPT/tick_ms）数字都是预测；本轮不跑 gradle、不起服务器（Lead 正在 `src` 编译），
   **只有算法级/微基准数字来自 [10](10-algorithmic-memoisation.md)/[11](11-collision-spatial-index.md)/[12](12-train-collision-differential.md)/[13](13-kinetic-network-lookup.md)/[14](14-incremental-stress-and-observer.md)/[22](22-allocation-optimisations.md)/[23](23-fluid-search-deque.md)**。

## 7. 复现：核实与测量命令

```bash
# —— 字节码核实（本文所有 class#method）——
JAVAP='C:/Program Files/Java/jdk-21.0.10/bin/javap.exe'
JAR='Fettle-1.21.1/libs/create-1.21.1-6.0.10.jar'
"$JAVAP" -p -c -cp "$JAR" com.simibubi.create.content.kinetics.KineticNetwork                 # C2/C6
"$JAVAP" -p -c -cp "$JAR" com.simibubi.create.content.trains.entity.Train                      # C1（findCollidingTrain 循环体）
"$JAVAP" -p -c -cp "$JAR" com.simibubi.create.content.trains.entity.TravellingPoint            # C9（getPosition 委托链）
"$JAVAP" -p -c -cp "$JAR" com.simibubi.create.content.fluids.pump.PumpBlockEntity              # C3（remove(0)+Pair 装箱+private 方法）
"$JAVAP" -p -c -cp "$JAR" com.simibubi.create.content.logistics.box.PackageItem                # C5
"$JAVAP" -p -c -cp "$JAR" com.simibubi.create.content.fluids.tank.FluidTankBlockEntity         # C10
"$JAVAP" -p -c -cp "$JAR" com.simibubi.create.content.logistics.chute.ChuteBlockEntity         # C11
"$JAVAP" -p -c -cp "$JAR" com.simibubi.create.content.logistics.packager.PackagerBlockEntity   # C12

# —— MC 原版类（RecipeManager / BlockEntity）：NeoForm 产物 jar ——
MCJAR=$(ls -t ~/.gradle/caches/neoformruntime/intermediate_results/compiledWithNeoForge_*_output.jar | head -1)
"$JAVAP" -p -c -cp "$MCJAR" net.minecraft.world.item.crafting.RecipeManager                   # C4
"$JAVAP" -p -cp "$MCJAR" net.minecraft.world.level.block.entity.BlockEntity                    # C6

# —— 行为门禁（Lead 编译结束后再跑）——
python Fettle-1.21.1/tools/fettle_gate.py            # parity + kinetic 差分 + train 差分 + sleep 比

# —— 运行时 A/B ——
fettle micro collisionFilter 1000        # C1 算法级
fettle micro kineticStress 2000          # C2
fettle micro fluidSearchQueue 50000      # C3 数据结构上下界
fettle micro getRecipeFor 200000         # C4
fettle micro matchAddress 3000000        # C5
fettle micro travelPointPosition 3000000 # C9
fettle debug trains 3                    # C1 差分（fresh / stale_raw / stale_guarded）
fettle debug kinetictest 3               # C2 差分（stress / speedChanged / afterReconcile）
fettle debug kinetics 200                # C2 场景
fettle debug fluidsearch / fluidcheck / fluidscene   # C3 场景
fettle idle report | fettle idle candidates 100000   # C7（candidates 为本文建议新增）
fettle bench start | fettle bench stop    # 端到端 tick_ms
```

## 8. 建议顺序（合并表）

| 顺序 | 候选 | 为什么排这里 | 风险 |
| --- | --- | --- | --- |
| 1 | **C4**（`input.isEmpty()` 等价 + 索引扩容） | 唯一确定的正确性修复，改动小、可被 `selftest` 直接覆盖 | 低 |
| 2 | **C1**（SpatialBuckets 接入） | 原型/微基准/差分测试三件套都在，收益空间最大 | 中 |
| 3 | **C2**（增量容量 + 对账） | 直接复用已验证的应力方案与差分；容量侧完全对称 | 中 |
| 4 | **C3**（泵 BFS 出队） | Lead 已把同款手法用于 FluidPropagator，剩下这一处 | 低–中 |
| 5 | **C7**（自动白名单） | 护栏最全，把"下一步"变成数据驱动 | 中 |
| 6 | **C5**（matchAddress 结果 memo） | 纯函数、低风险，先加计数器 | 低 |
| 7 | **C9**（memo 命中率/多槽） | 收益依赖命中率，先测量 | 低–中 |
| 8 | **C8**（面板自适应） | 收益未证实，必须补"值不变占比"证据 | 中 |
| 9 | **C6**（isRemoved 快判） | 语义放宽，先只做测量 | 中–高 |
| 10+ | **C10–C12**（罐体/溜槽/打包机） | 邻近项，独立于已发布项 | 中 |
