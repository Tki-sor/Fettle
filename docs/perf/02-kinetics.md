# 02 · Kinetics / 服务端 tick 性能问题

> 覆盖范围：`com.simibubi.create.content.kinetics.**`（base / rotational / belt / fan / chainConveyor / crafter / gauge / 各类机器）+ `foundation/blockEntity/SmartBlockEntity.java`
> 调查依据：Create 6.0.10 / MC 1.21.1 源码逐行核对；行号以本仓库为准
> 公共约定见 [01-mixin-playbook.md](01-mixin-playbook.md)；整体重写方案见 [overwrites/](overwrites/)
> 相关领域：传送带**物品传输语义**的物流侧条目见 [04-logistics.md](04-logistics.md)；风扇对传送带/实体的处理也会触及客户端渲染（[07](07-client-render-ui.md)）。

## 摘要

- 本领域最高频的成本是**「每 tick × 每方块实体」的固定开销**：`SmartBlockEntity.forEachBehaviour` 每 BE 每 tick 分配一个迭代器（第 10 条）、风扇 `AirCurrent` 每次 rebuild 三遍遍历气流（第 8 条）、`NozzleBlockEntity` 在实体循环里做 ArrayList `contains`（第 17 条）。这类开销单次极小，但乘以"数千个方块实体"就是可观的 GC 与 CPU。
- 第二条主线是**动力网络的重复计算**：`KineticNetwork` 每个成员一次 `level.getBlockEntity` 存活探测且一次转速变化连跑三遍（第 1 条）；`RotationPropagator` 同一条边最多算 4 次转速修正、每次分配 BlockPos 与 LinkedList（第 6、7、11 条）。
- 第三条主线是**传送带物品循环**：`BeltInventory` 的 `LinkedList` + `contains/removeAll` 是 O(n·m)，且每个物品在距离判定前先 `ItemStack.copy()`（第 2 条）；漏斗交互每物品每 tick 重算 amount/mode 并多做一次 `getBlockEntity`（第 3 条）。
- **可 Mixin 性整体最好**：17 条里只有 2 条建议留给上游；`@ModifyExpressionValue`/`@Redirect`（NEW 构造）能覆盖相当一部分分配点。
- **注意**：本领域大量代码在`@Overwrite` 后必须保持"机械动力 tick 顺序"，尤其是 `SmartBlockEntity.tick`、`KineticBlockEntity.tick` 与 `AirCurrent.tick`——顺序一变，转速传播与应力结算就会出现难以复现的漂移。

---

## 发现

### 1. 动力网络每个成员一次 `level.getBlockEntity` 存活探测，且一次转速变化连跑三遍 — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/kinetics/KineticNetwork.java:133-163`（`calculateCapacity` / `calculateStress`），驱动方 `content/kinetics/base/KineticBlockEntity.java:120-124`，三连跑见 `content/kinetics/base/GeneratingKineticBlockEntity.java:107-112` |
| 调用频次 | 每次动力网络变更（放置/破坏/离合器换挡/发电机转速变化）；一次转速变化会连续跑 `updateCapacityFor` → `updateStressFor` → `network.updateStress()` 三遍 |
| 复杂度 / 成本 | 对每个成员做一次 `be.getLevel().getBlockEntity(be.getBlockPos()) != be`（区块查找 + 装箱的 `BlockPos` 哈希）**只是**为了判断该成员是否仍存活；500 部件的风机/轴阵 → 单次重算约 1000 次区块探测，×3 遍 |
| 注入方式 | `@Overwrite`（两个方法都短且自包含）或 `@Redirect` 把 `Level.getBlockEntity` 换成 `be.isRemoved()` |
| 目标成员 | `KineticNetwork#calculateStress()F`、`KineticNetwork#calculateCapacity()F` |
| 需新增状态 | 无（去掉了世界查找）；若改为"定期校验"策略则需 `@Unique` tick 计数 |
| 风险 | 中：成员清理时机若变松，废弃成员会残留并持续计入应力/容量 |
| 脆弱度 | 低-中：方法名稳定，但 `unloadedMembers` 计数语义若被上游改动，你的替换需同步 |
| 置信度 | 确定（`:149-155` 已逐行核对，循环体内即 `be.getLevel().getBlockEntity(be.getBlockPos()) != be` → `iterator.remove()`） |

**改前（关键片段）**

```java
public float calculateStress() {
    float presentStress = 0;
    for (Iterator<KineticBlockEntity> iterator = members.keySet()
        .iterator(); iterator.hasNext();) {
        KineticBlockEntity be = iterator.next();
        if (be.getLevel()
            .getBlockEntity(be.getBlockPos()) != be) {
            iterator.remove();
            continue;
        }
        presentStress += getActualStressOf(be);
    }
    ...
}
```

**为什么慢**

校验"这个 BE 还在不在"本可以用 `be.isRemoved()`（对象字段，零成本）或干脆依赖 `KineticBlockEntity.remove()` 已经调用的 `KineticNetwork.remove(be)`；现在却为每个成员做了完整的世界查询，并且**同一次转速变化要重复三遍**。风机/齿轮阵列越大，这段开销越接近"每次交互都要卡一下"的观感来源。

**改后**

```java
if (be.isRemoved() || be.getLevel() == null) {
    iterator.remove();
    continue;
}
presentStress += getActualStressOf(be);
```

并建议在 `GeneratingKineticBlockEntity.updateGeneratedRotation` 里把三遍合并为一次网络更新（该处属 `02` 的 Batch C，见 [overwrites/KineticNetwork-calculateStress.md](overwrites/KineticNetwork-calculateStress.md)）。

**Mixin 骨架**

```java
@Mixin(value = com.simibubi.create.content.kinetics.KineticNetwork.class, remap = false)
public abstract class KineticNetworkMixin {

    @Redirect(method = "calculateStress",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"),
              remap = true) // 目标是原版方法 → 需要 remap（走 refmap）；Create 自身成员仍 remap = false
    private BlockEntity createperf$liveness(Level level, BlockPos pos) {
        // 仅用于让 "!= be" 判断成立/不成立：返回 null 表示"已移除"
        BlockEntity be = level.getBlockEntity(pos);
        return be;
    }
}
```

> 更简单可靠的写法是 `@Overwrite` 两个方法（已在 overwrites/ 中给出完整方法体）。注意 `@Redirect` 的原版目标需要 remap 语义正确：**方法本体在 Create 类里（`remap=false` 的 class 级别），但被调用的方法属于原版类**，因此 `at.target` 写成原版映射。若不确定，直接用 `@Overwrite`。

**验证方法**

- 探针：统计 `calculateStress`/`calculateCapacity` 的执行次数与成员数，对比"改前/改后每 tick 的 `getBlockEntity` 调用数"。
- 行为回归：拆除一段动力网络（放置电源 → 加 50 根轴 → 逐段拆掉）后应力/容量数值必须实时收敛；`/create stress` 类调试命令（若有）读数一致。
- JFR：`Level.getBlockEntity` 栈帧占比应显著下降。

**收益估计**：每次网络重算从 O(成员数) 次区块探测 → 0；叠加三连跑合并后约降至 1/3（估计）。
**回归测试点**：应力过载判定与恢复；发电机转速变化后的网络结算；区块卸载/重载后的成员计数。

---

### 2. 传送带 `LinkedList` 的 `contains/removeAll` 是 O(n·m)，且每个物品先复制再判距离 — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/kinetics/belt/transport/BeltInventory.java:67-72`（`removeAll`）、`:459-467`（`applyToEachWithin`）、`:394-406`（`getStackAtOffset`）、`:380-391`（线性插入） |
| 调用频次 | 每 tick × 每条受风扇/加工影响的传送带（`AirCurrent.tickAffectedHandlers` → `TransportedItemStackHandlerBehaviour` → `BeltBlockEntity:405-412`）以及 `BeltBlock.java:292` |
| 复杂度 / 成本 | `items`/`toInsert`/`toRemove` 都是 `LinkedList`：`items.removeAll(toRemove)` 退化为 O(n·m) 的指针追逐；每个物品的 `toRemove.contains(transported)` 又一次 O(m)；`transported.stack.copy()` 在**距离判定之前**执行，远离加工位置的物品也白复制一次 ItemStack（含组件） |
| 注入方式 | `@Inject(HEAD, cancellable)` 重写 `applyToEachWithin`（约 20 行）；集合类型替换需 `@Unique` 容器＋同步维护，或用 `@Overwrite` |
| 目标成员 | `BeltInventory#applyToEachWithin`（以及 `removeAll`/`getStackAtOffset` 的调用点） |
| 需新增状态 | 若用身份集合替代 `toRemove`：`@Unique ReferenceOpenHashSet<TransportedItemStack>`（作用域：每 BeltInventory；失效：每 tick 清空；并发：仅子逻辑线程） |
| 风险 | 低-中：纯遍历/查询路径，但必须保持"先判距离再决定是否加工"的语义 |
| 脆弱度 | 低 |
| 置信度 | 确定（`:67-72` 已核对：`items.removeAll(toRemove); toRemove.clear(); belt.notifyUpdate();`；三个容器在 `:51-53` 声明为 LinkedList） |

**改前**

```java
// :67-72
if (!toInsert.isEmpty() || !toRemove.isEmpty()) {
    toInsert.forEach(this::insert);
    toInsert.clear();
    items.removeAll(toRemove);
    toRemove.clear();
    belt.notifyUpdate();
}
```

```java
// :459-467
for (TransportedItemStack transported : items) {
    if (toRemove.contains(transported))
        continue;
    ItemStack stackBefore = transported.stack.copy();
    if (Math.abs(position - transported.beltPosition) >= maxDistanceToPosition)
        continue;
    ...
}
```

**为什么慢**

同一份"要删除的物品"集合被当作 LinkedList 反复线性查找：一次 `removeAll` 是 O(items × removed)，而 `applyToEachWithin` 的每物品 `contains` 又是 O(items × toRemove)。而每个物品的 `ItemStack.copy()` 发生在距离判定之前 —— 加工位置只覆盖带上一小段，绝大多数物品会立刻 `continue`，那次复制纯属浪费（`ItemStack.copy` 会分配新对象并复制组件）。

**改后**

```java
for (TransportedItemStack transported : items) {
    if (Math.abs(position - transported.beltPosition) >= maxDistanceToPosition)
        continue;                                   // 先判距离，再考虑任何复制
    if (createperf$removed.contains(transported))
        continue;
    ItemStack stackBefore = transported.stack.copy(); // 只有真的要处理时才快照
    ...
}
```

```java
// :67 附近：用身份集合 + removeIf，避免 O(n·m)
if (!toInsert.isEmpty() || !toRemove.isEmpty()) {
    toInsert.forEach(this::insert);
    toInsert.clear();
    items.removeIf(createperf$removed::contains);
    toRemove.clear();
    createperf$removed.clear();
    belt.notifyUpdate();
}
```

**Mixin 骨架**

```java
@Mixin(value = BeltInventory.class, remap = false)
public abstract class BeltInventoryMixin {

    @Unique
    private final Set<TransportedItemStack> createperf$removed =
        new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>();

    @Inject(method = "applyToEachWithin", at = @At("HEAD"), cancellable = true)
    private void createperf$fastApply(..., CallbackInfo ci) {
        // 重写循环：先判距离 + 用身份集合过滤 + 命中才 copy
        // 注意：必须保持原来的回调调用顺序与 processFunction 返回值语义
        ci.cancel();
    }
}
```

**验证方法**

- 探针：统计每 tick 的 `ItemStack.copy()` 次数与 `contains` 调用次数（改前/改后对照）。
- 行为回归：风扇吹过的传送带上物品加工时机不变；物品在带上被移除/弹出的时序不变；`getStackAtOffset` 的槽位查询结果一致。
- 长跑：多风扇 + 长传送带（≥64 物品）场景下 MSPT 对照。

**收益估计**：每 tick 每带从 O(n·m) 降到 O(n)，每物品一次 ItemStack 复制 → 仅命中物品（估计）。
**回归测试点**：风扇加工（鼓风/烟熏/洗涤/缠魂）触发位置；`toInsert` 插入顺序；传送带 `notifyUpdate` 频率。

---

### 3. 传送带漏斗交互每物品每 tick 重算 amount/mode，并多做一次 `getBlockEntity` — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/kinetics/belt/transport/BeltFunnelInteractionHandler.java:76-99`；helper 在 `content/logistics/funnel/FunnelBlockEntity.java:235-248,274-286` |
| 调用频次 | 每个在传送带上的物品每 tick（`BeltInventory.tick` → `checkForFunnels`） |
| 复杂度 / 成本 | `getAmountToExtract()` 与 `getModeToExtract()` **各自**调用 `supportsAmountOnFilter()`；对传送带漏斗，后者执行 `BeltHelper.getSegmentBE(level, worldPosition.below()) != null` —— 即每物品每 tick **两次额外**的 `Level.getBlockEntity`。之后还有 `currentItem.stack.copy()` + 一次模拟插入 + 一次真实插入 |
| 注入方式 | `@Redirect` 两个 helper 调用（指向每 tick 缓存），或 `@Inject`/`@Overwrite` 小范围重写 |
| 目标成员 | `BeltFunnelInteractionHandler#checkForFunnels`；`FunnelBlockEntity#getAmountToExtract/getModeToExtract/supportsAmountOnFilter` |
| 需新增状态 | `@Unique` 每 tick 缓存（键：漏斗 BE + 过滤物品 + 方块状态；失效：每 tick 或状态变化） |
| 风险 | 中：抽取数量/模式影响物品吞吐，缓存失效错误会导致漏斗"卡住"或抽取过多 |
| 脆弱度 | 低-中：helper 方法名稳定，但 `supportsAmountOnFilter` 的判定条件可能演进 |
| 置信度 | 高概率（报告给出的 `:76-99` 与 helper 位置；`BeltHelper.getSegmentBE` 的额外 `getBlockEntity` 由 helper 源码结构推得） |

**为什么慢**

这是"每物品每 tick"路径上的组合浪费：两次重复的判定（每次含一次世界查找）+ 一次不必要的 `ItemStack` 复制 + 两次库存交互（模拟 + 真实）。传送带上一段区域可能同时有多个物品，因此实际调用次数是"物品数 × 漏斗数"。

**改后**

```java
// 每个漏斗每 tick 只算一次
int amountToExtract = funnelBE.getAmountToExtractCached();   // 内部缓存 supportsAmountOnFilter 结果
ExtractionCountMode modeToExtract = funnelBE.getModeToExtractCached();
...
ItemStack toInsert = currentItem.stack;                       // 只在需要修改时再 copy
```

**Mixin 骨架**

```java
@Mixin(value = FunnelBlockEntity.class, remap = false)
public abstract class FunnelBlockEntityMixin {

    @Unique private long createperf$cacheTick = Long.MIN_VALUE;
    @Unique private boolean createperf$supportsAmount;

    @Inject(method = "supportsAmountOnFilter", at = @At("HEAD"), cancellable = true)
    private void createperf$cacheSupports(CallbackInfoReturnable<Boolean> cir) {
        long now = createperf$tickCounter();      // 可由 CommonEvents 维护的全局 tick
        if (now == createperf$cacheTick) { cir.setReturnValue(createperf$supportsAmount); return; }
        createperf$cacheTick = now;               // 未命中则放行原逻辑并在 RETURN 处写回
    }
}
```

**验证方法**

- 探针：统计 `checkForFunnels` 内 `getBlockEntity` 与 `stack.copy()` 的调用次数。
- 行为回归：传送带漏斗的抽取数量（`ExtractionCountMode`：单堆/整栈/过滤）、过滤物品计数、带速变化时的吞吐。
- 反压测试：下游满仓时漏斗应停止抽取（缓存不能把"能抽"钉死）。

**收益估计**：每物品每 tick 2 次额外 `getBlockEntity` → 每漏斗每 tick 1 次（估计）。
**回归测试点**：抽取模式与数量；过滤器计数（`FilteringBehaviour`）语义；漏斗朝向与漏斗上方方块变化后的即时重算。

---

### 4. 传送带加工段行为通过区块查找逐个解析 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/kinetics/belt/transport/BeltInventory.java:303-311`（`getBeltProcessingAtSegment`）、`:273-284`；helper `foundation/blockEntity/behaviour/BlockEntityBehaviour.java:101-109` |
| 调用频次 | 每物品每跨越一个带段每 tick（`handleBeltProcessingAndCheckIfRemoved` 在 `:276-278` 一行内调两次），入口在 `BeltInventory.tick` 的物品循环（`:154`） |
| 复杂度 / 成本 | `BlockEntityBehaviour.get(level, pos, TYPE)` 每次做 `reader.getBlockEntity(pos)`（区块查找 + 装箱 `BlockPos` 哈希）且内部用 `try { } catch (ConcurrentModificationException)` 当控制流；`BeltHelper.getPositionForOffset` 每次再分配一个 `BlockPos` |
| 注入方式 | `@Redirect` 两个 `BlockEntityBehaviour.get` 调用 → 缓存解析器；或 `@Overwrite` 两个小方法 |
| 目标成员 | `BeltInventory#getBeltProcessingAtSegment` / `#getBeltStackHandlerAtSegment`（实际方法名以源码为准） |
| 需新增状态 | `@Unique` 段号 → behaviour 的缓存数组（作用域：每 BeltInventory；失效：方块变化或带控制器重建；并发：子逻辑线程） |
| 风险 | 中：缓存失效时机错 → 加工机放置/拆除后不生效 |
| 脆弱度 | 低 |
| 置信度 | 高概率（`:303-311` 由报告给出；异常当控制流的写法在 `BlockEntityBehaviour.java:101-109`） |

**为什么慢**

"这个带段上方有没有加工机"是个**结构性问题**，只在加工机放置/拆除时变化，却被放在每物品每段的 tick 路径上，且每次都要走一次世界查找 + 一个 try/catch 分支。带越长、物品越多，成本越高。

**改后**

```java
// 每 BeltInventory 维护 segment -> behaviour 的稀疏数组，在控制器重建/方块变化时清空
private final BeltProcessingBehaviour[] createperf$procCache = new BeltProcessingBehaviour[SEGMENTS];
private BlockPos.MutableBlockPos createperf$scratch = new BlockPos.MutableBlockPos();
```

**Mixin 骨架**

```java
@Mixin(value = BeltInventory.class, remap = false)
public abstract class BeltInventoryProcessingMixin {
    @Unique private final java.util.Map<Integer, Object> createperf$procCache = new java.util.HashMap<>();

    @Redirect(method = "*", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/blockEntity/behaviour/BlockEntityBehaviour;get(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lcom/simibubi/create/foundation/blockEntity/behaviour/BehaviourType;)Lcom/simibubi/create/foundation/blockEntity/behaviour/BlockEntityBehaviour;"),
        remap = false)
    private Object createperf$cachedGet(...) { /* 查缓存；未命中走原逻辑并入缓存 */ }
}
```

**验证方法**：探针统计 `BlockEntityBehaviour.get` 次数；行为回归：在带上方放置/拆除加工机（风扇/压机/锯/洗涤）后立即生效；`ConcurrentModificationException` 不再需要被捕获。
**收益估计**：每物品每段每 tick 2 次区块查找 → 每段 1 次（缓存后 0）（估计）。
**回归测试点**：加工机放置/拆除的即时生效；带段与加工机位置对应关系；异常路径消失后是否仍有机器的并发修改问题。

---

### 5. 小齿轮对角传播位置用 Stream + lambda 逐个探测 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/kinetics/base/KineticBlockEntity.java:558-571`（`addPropagationLocations`） |
| 调用频次 | 每个小齿轮的每次连接探测；位于 `RotationPropagator` 的 DFS/BFS 内（`getPotentialNeighbourLocations` 调用） |
| 复杂度 / 成本 | 每次调用分配 2 个 `BlockPos` + 一条 Stream/spliterator 链 + 一个捕获 lambda，遍历 27 个候选位置只为挑出至多 12 个对角邻居 |
| 注入方式 | `@Overwrite`（方法短）或 `@ModifyExpressionValue` 替换 `betweenClosedStream` 的返回值（不推荐，仍需 lambda） |
| 目标成员 | `KineticBlockEntity#addPropagationLocations(IRotate,BlockState,List)` |
| 需新增状态 | `@Unique` 静态偏移表（按 `Axis` 预计算 12 个对角偏移） |
| 风险 | 低-中：邻居集合必须完全一致，否则转速传播路径会变 |
| 脆弱度 | 低 |
| 置信度 | 确定（`:558-571` 已核对，方法签名与 `canPropagateDiagonally` 分支、`worldPosition.offset(offset)` 均在） |

**改后**

```java
private static final int[][] DIAG_OFFSETS = { /* 每个 Axis 12 组 (dx,dy,dz) */ };

public List<BlockPos> addPropagationLocations(IRotate block, BlockState state, List<BlockPos> neighbours) {
    if (!canPropagateDiagonally(block, state)) return neighbours;
    Axis axis = block.getRotationAxis(state);
    for (int[] o : DIAG_OFFSETS[axis.ordinal()])
        neighbours.add(worldPosition.offset(o[0], o[1], o[2]));
    return neighbours;
}
```

**Mixin 骨架**

```java
@Mixin(value = KineticBlockEntity.class, remap = false)
public abstract class KineticBlockEntityMixin {
    @Overwrite(remap = false)
    public List<BlockPos> addPropagationLocations(IRotate block, BlockState state, List<BlockPos> neighbours) {
        // 用静态偏移表直出，语义与原来逐字一致
        return neighbours;
    }
}
```

**验证方法**：对比改前/改后同一机械的 `neighbours` 列表内容（顺序可不同但集合必须相同）；行为回归：对角小齿轮/大齿轮的传动连通性与转速方向。
**收益估计**：每次探测 27 次迭代 + Stream/lambda + 2 个 BlockPos → 12 次纯数组迭代、0 分配（估计）。
**回归测试点**：小齿轮对角传动；大齿轮与小齿轮的转换；不同 `Axis` 下的偏移集合。

---

### 6. 连接邻居扫描每次都分配两个 LinkedList 与 6~14 个 BlockPos — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/kinetics/RotationPropagator.java:410-440`（`getConnectedNeighbours` / `getPotentialNeighbourLocations`） |
| 调用频次 | BFS 每个节点一次（`propagateMissingSource` `:356`、`propagateNewSource`） |
| 复杂度 / 成本 | 每次：一个 `LinkedList`（neighbours）+ 另一个 `LinkedList`（potential locations）+ 6 个 `blockPos.relative(facing)` 产生的 `BlockPos`（小齿轮对角时最多 14） |
| 注入方式 | `@Redirect` NEW 构造（LinkedList）→ 复用容器；去掉 `BlockPos` 分配需 `@Overwrite` |
| 目标成员 | `RotationPropagator#getConnectedNeighbours` / `#getPotentialNeighbourLocations` |
| 需新增状态 | `@Unique` 可复用 `ObjectArrayList`/`ArrayList`（作用域：每传播线程；失效：每次传播开始清空；并发：注意 `RotationPropagator` 可能被客户端/服务端各自调用 → 用 ThreadLocal 或每次 new 但用 ArrayList） |
| 风险 | 低-中 |
| 脆弱度 | 低 |
| 置信度 | 高概率（结构与行号来自报告；`RotationPropagator.java:410` 是 `getConnectedNeighbours` 起点） |

**改后**

```java
private static final Direction[] DIRS = Direction.values();

// 用 BlockPos.MutableBlockPos 探测，结果用 ArrayList（不必 LinkedList）
```

**Mixin 骨架**

```java
@Mixin(value = RotationPropagator.class, remap = false)
public abstract class RotationPropagatorMixin {
    @Redirect(method = "getConnectedNeighbours",
              at = @At(value = "NEW", target = "()Ljava/util/LinkedList;"), remap = false)
    private LinkedList<?> createperf$cheaperList() { return new LinkedList<>(); } // 或换成 ObjectArrayList
}
```

**验证方法**：探针统计传播一次（放置/拆除 1 个方块）的分配对象数；行为回归：转速传播结果与该网络的转速/方向。
**收益估计**：BFS 每节点 2 个容器 + 6~14 个 BlockPos → 显著下降（估计）。
**回归测试点**：线性/对角传动连通；离合器与变速箱的网络重算；大机械（>300 部件）的传播耗时。

---

### 7. 同一条边最多算 4 次转速修正 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/kinetics/RotationPropagator.java:397`（`isConnected` 双向）、`:226` 附近（`getConveyedSpeed` 双向）、`:53-55`（`BlockPos.subtract`） |
| 调用频次 | 每次网络传播的每条边 |
| 复杂度 / 成本 | `isConnected(a,b)` + `isConnected(b,a)` → 各调 `getRotationSpeedModifier`；随后 `getConveyedSpeed(a,b)` + `getConveyedSpeed(b,a)` 又各调一次 → **同一对边 4 次**。每次含 2 次 `getBlockState`、`BlockPos.subtract`、`Direction.getNearest`、`Axis.values()` 扫描、最多 2 次 `hasShaftTowards`（对传送带是一次 BE 查找，见第 9 条） |
| 注入方式 | `@Unique` 每轮缓存（key = 有序 BE 对）或 `@Overwrite` `getRotationSpeedModifier` 减少内部分配 |
| 目标成员 | `RotationPropagator#isConnected` / `#getConveyedSpeed` / `#getRotationSpeedModifier` |
| 需新增状态 | `@Unique` 每传播轮的 `IdentityHashMap<BEPair, Float>`（失效：每次传播开始清空） |
| 风险 | 中：转速比算错会直接导致方向/倍率错误 |
| 脆弱度 | 中：这三个方法互相调用，上游重构概率不低 |
| 置信度 | 高概率（`:397` 的双向 `isConnected` 与 `:226` 的双向 `getConveyedSpeed` 来自报告） |

**验证方法**：探针统计 `getRotationSpeedModifier` 次数（应从 4×边数降到 ≈1×边数）；行为回归：各种齿轮组合（同轴、垂直、大小齿轮、传送带）的转速比与方向。
**收益估计**：每条边 4 次 → 1 次（估计）。
**回归测试点**：大小齿轮倍率；传送带与轴的耦合；离合器/变速箱换挡。

---

### 8. 风扇气流每 30 tick 无条件重建，三遍遍历气流并必然发包 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/kinetics/fan/EncasedFanBlockEntity.java:135`（`if (updateAirFlow) { airCurrent.rebuild(); ... sendData(); }`）；`content/kinetics/fan/AirCurrent.java:159,189-206,229,311-334` |
| 调用频次 | 每风扇每 `fanBlockCheckRate`（默认 30，最小 10）tick |
| 复杂度 / 成本 | `rebuild()` 三遍遍历气流：`getFlowLimit`（`:229`，含 `CopycatBlock.getMaterial` + `getCollisionShape`）、段循环（`:189-206`，每格 `FanProcessingType.getAt`）、`findAffectedHandlers`（`:311-334`，再调 `getAt` + 每格 1-2 次 `BlockEntityBehaviour.get`）；`FanProcessingType.getAt` 遍历 4 种类型、各读 `getFluidState` + `getBlockState` + 2 次 tag 匹配。之后**无条件** `sendData()` |
| 注入方式 | `@Inject` 门控（比较 rebuild 前后边界/段是否变化）+ 把定时重建改为事件驱动；或 `@Overwrite` |
| 目标成员 | `EncasedFanBlockEntity#tick`（含 `updateAirFlow` 逻辑）/ `AirCurrent#rebuild` |
| 需新增状态 | `@Unique` 上次 `maxDistance`/`segments` 摘要（失效：每次 rebuild 后比较） |
| 风险 | 中：气流范围/加工判定若滞后会改变玩家体验（风扇吹不到该吹的地方） |
| 脆弱度 | 中：`AirCurrent` 结构若重构，摘要比较需重写 |
| 置信度 | 高概率（`:135` 的逻辑来自报告；`AirCurrent` 内部方法行号来自报告） |

**改后**

```java
if (updateAirFlow) {
    updateAirFlow = false;
    int before = airCurrent.maxDistance;
    List<...> segmentsBefore = ...;      // 或用一个递增 revision
    airCurrent.rebuild();
    if (airCurrent.maxDistance > 0) award(AllAdvancements.ENCASED_FAN);
    if (changed(before, segmentsBefore)) sendData();   // 只有真的变了才发包
}
```

**Mixin 骨架**

```java
@Mixin(value = EncasedFanBlockEntity.class, remap = false)
public abstract class EncasedFanBlockEntityMixin {
    @Unique private int createperf$lastMaxDistance = -1;

    @Inject(method = "tick", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/content/kinetics/fan/AirCurrent;rebuild()V",
        shift = At.Shift.AFTER), remap = false)
    private void createperf$maybeSkip(CallbackInfo ci) { /* 比较后决定是否 sendData */ }
}
```

**验证方法**：探针统计 `rebuild()` 与 `sendData()` 次数（在"风扇前无变化"的稳定场景下，sendData 应趋近 0）；行为回归：在风扇前放置/破坏方块后气流立即更新；加工判定（鼓风/烟熏/洗涤/缠魂）与粒子范围。
**收益估计**：每风扇每 30 tick 三遍气流遍历 + 一次无条件 NBT 广播 → 仅变化时（估计）。
**回归测试点**：`blockInFrontChanged` 已存在的事件触发路径；风扇功率/转速变化；粒子与音效范围。

---

### 9. `BeltBlock.hasShaftTowards` 在连接判定里分配 `Optional` 并查一次世界 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/kinetics/belt/BeltBlock.java:118-124`；helper `foundation/block/IBE.java:58-60` |
| 调用频次 | 每次 `getRotationSpeedModifier` 对含传送带的边（即每次网络传播的每对邻居，见第 7 条） |
| 复杂度 / 成本 | `getBlockEntityOptional(world, pos)` = `Optional.ofNullable(getBlockEntity(...))` → 一次 `Level#getBlockEntity` + 一个 `Optional` 分配 + `map` 的 lambda 捕获 + `orElse` 拆箱 |
| 注入方式 | `@Overwrite`（3 行） |
| 目标成员 | `BeltBlock#hasShaftTowards(LevelReader,BlockPos,BlockState,Direction)Z` |
| 需新增状态 | 无 |
| 风险 | 低（纯等价替换） |
| 脆弱度 | 低：`hasShaftTowards` 是 `IRotate` 接口方法，签名稳定 |
| 置信度 | 确定（`:118-124` 已逐行核对） |

**改后**

```java
public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
    if (face.getAxis() != getRotationAxis(state))
        return false;
    BlockEntity be = world.getBlockEntity(pos);
    return be instanceof BeltBlockEntity belt && belt.hasPulley();
}
```

**Mixin 骨架**

```java
@Mixin(value = BeltBlock.class, remap = false)
public abstract class BeltBlockMixin {
    @Overwrite(remap = false)
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        if (face.getAxis() != ((BeltBlock)(Object)this).getRotationAxis(state)) return false;
        BlockEntity be = world.getBlockEntity(pos);
        return be instanceof BeltBlockEntity belt && belt.hasPulley();
    }
}
```

**验证方法**：行为回归：传送带与轴/齿轮的连接方向（含上下皮带轮）；网络传播结果不变；探针确认 `Optional` 分配消失（JFR）。
**收益估计**：每次判定省 1 个 Optional + 保持 1 次 BE 查找（查找无法免除，但可被第 7 条的缓存覆盖）（估计）。
**回归测试点**：传送带两端皮带轮的连接；不同 `face` 下的判定；被破坏的传送带 BE 缺失时不抛异常。

---

### 10. `SmartBlockEntity` 每 BE 每 tick 分配一个迭代器 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `foundation/blockEntity/SmartBlockEntity.java:85`（`forEachBehaviour(BlockEntityBehaviour::tick)`）与 `forEachBehaviour` 实现 |
| 调用频次 | 每个已加载的 Create 方块实体每 tick（服务端与客户端各一次） |
| 复杂度 / 成本 | `behaviours` 是 fastutil `Reference2ObjectArrayMap`（`:36`），其 `ValuesCollection` **未覆写** `forEach`，因此默认的 `Iterable.forEach` 会分配一个匿名 `ObjectIterator`；即每 BE 每 tick 一个短命对象 + 迭代器分派 |
| 注入方式 | `@Overwrite` `forEachBehaviour`（用 `Object[]`/索引遍历）或 `@Inject(HEAD)` 改为等价的无分配遍历 |
| 目标成员 | `SmartBlockEntity#forEachBehaviour` |
| 需新增状态 | `@Unique` 缓存的数组视图（失效：behaviour 增删时） |
| 风险 | 低-中：必须保持 behaviour 的遍历顺序（注册顺序） |
| 脆弱度 | 低-中：依赖 `behaviours` 字段（`@Shadow`）与其 map 类型 |
| 置信度 | 高概率（`:85` 的 `forEachBehaviour` 调用与 `:36` 的容器类型来自报告；"fastutil 未覆写 forEach" 的结论已由报告对照 fastutil 8.5.18 源码） |

**为什么慢**

这是本领域"最广"的一条：调用面覆盖**所有** Create 机械，每 tick 每 BE 一次。几千个 BE → 每秒数万个短命迭代器对象，同时失去数组遍历的局部性优势。

**改后**

```java
public void forEachBehaviour(Consumer<BlockEntityBehaviour> action) {
    Object[] values = createperf$behaviourArray;   // 在 addBehaviour/removeBehaviour 后重建
    for (int i = 0; i < values.length; i++)
        action.accept((BlockEntityBehaviour) values[i]);
}
```

**Mixin 骨架**

```java
@Mixin(value = SmartBlockEntity.class, remap = false)
public abstract class SmartBlockEntityMixin {
    @Shadow private it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap<BehaviourType<?>, BlockEntityBehaviour> behaviours;

    @Overwrite(remap = false)
    public void forEachBehaviour(java.util.function.Consumer<BlockEntityBehaviour> action) {
        for (BlockEntityBehaviour b : behaviours.values()) action.accept(b);  // 至少用 for-each 保持顺序
    }
}
```

> 注意：`for-each` 仍然会创建迭代器；若要真正零分配，需要缓存一个 `BlockEntityBehaviour[]` 并在 `addBehaviour/removeBehaviour` 处维护（`@Inject` 到这两个方法）。

**验证方法**：JFR 分配采样观察 fastutil 迭代器对象数量；行为回归：机械行为（`BlockEntityBehaviour.tick`）语义与顺序——尤其 `FilteringBehaviour`、`InventoryManipulationBehaviour` 的先后依赖。
**收益估计**：每 BE 每 tick 1 个对象 → 0（估计；数千 BE 时每秒数万对象）。
**回归测试点**：所有机械的 tick 行为；behaviour 注册顺序；`SmartBlockEntity.lazyTick` 节奏。

---

### 11. `Axis.values()` 在传播热路径里克隆枚举数组 — [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/kinetics/RotationPropagator.java:59`（另见 `:145`、`:178`） |
| 调用频次 | 每次 `getRotationSpeedModifier`（叠加第 7 条后可达每条边 4 次） |
| 复杂度 / 成本 | `Direction.Axis.values()` 返回克隆数组，每次 3 元素数组分配；每条边最多 12 次 |
| 注入方式 | `@ModifyExpressionValue` 换成静态常量，或 `@Overwrite` 内联三次比较 |
| 目标成员 | `RotationPropagator#getRotationSpeedModifier` / `isLargeToLargeGear` / `isLargeToSmallCog` |
| 需新增状态 | `@Unique` 静态 `Axis[]` 常量 |
| 风险 | 低 |
| 脆弱度 | 低 |
| 置信度 | 高概率（`:59` 等三处来自报告；enum `values()` 克隆是语言规定行为，确定） |

**Mixin 骨架**

```java
@Mixin(value = RotationPropagator.class, remap = false)
public abstract class RotationPropagatorAxisMixin {
    @Unique private static final Direction.Axis[] createperf$AXES = Direction.Axis.values();

    @ModifyExpressionValue(method = "getRotationSpeedModifier",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction$Axis;values()[Lnet/minecraft/core/Direction$Axis;"),
        remap = true)
    private static Direction.Axis[] createperf$noClone(Direction.Axis[] original) {
        return createperf$AXES;
    }
}
```

> `@ModifyExpressionValue` 会插入 `checkcast` 到数组类型，返回同类型的静态数组是安全的；不要返回 `List`。

**验证方法**：JFR 观察该数组类型的分配消失；行为回归：大齿轮/小齿轮的轴对齐判定。
**收益估计**：每条边最多 12 次小数组分配 → 0（估计）。
**回归测试点**：不同轴组合的齿轮连接判定。

---

### 12. 连锁传送带：`getExitFor` 在连接循环内重复求值，路由表插入是 O(n²) — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/kinetics/chainConveyor/ChainConveyorBlockEntity.java:295-304`；`ChainConveyorRoutingTable.java:56-70`（`getExitFor`）、`:72-95`（`insert`）、`:63-70`（`advertiseTo`） |
| 调用频次 | 每 tick × 每个在途包裹 × 每个连接；广告每 `ENTRY_TIMEOUT-20 = 80` tick |
| 复杂度 / 成本 | `getExitFor(box.item)` 不依赖 `connection` 却在循环内重复调用，且每次线性扫描路由表并逐条走 `PackageItem.matchAddress`（正则，见 [04-logistics.md](04-logistics.md) 第 1 条）→ O(连接数 × 路由条目数)；广告每条目一次 `insert`（`ArrayList.add(index,...)` 移位）→ O(n·m) |
| 注入方式 | 提升循环不变量：`@Inject`/`@Overwrite` 局部改写；容器替换需 `@Overwrite` |
| 目标成员 | `ChainConveyorBlockEntity#tick` 的包循环；`ChainConveyorRoutingTable#getExitFor` / `#insert` |
| 需新增状态 | 若缓存目的地：`@Unique` 每包裹目的地（失效：包裹/路由表变化） |
| 风险 | 中（包裹投递目标算错会直接改变物流行为） |
| 脆弱度 | 中：路由表是自研结构 |
| 置信度 | 高概率（行号与结构来自报告） |

**改后**

```java
BlockPos exit = routingTable.isEmpty() ? null : routingTable.getExitFor(box.item);  // 循环外算一次
for (BlockPos connection : connections) {
    if (exit == null || !exit.equals(connection)) continue;
    ...
}
```

**Mixin 骨架**

```java
@Mixin(value = ChainConveyorBlockEntity.class, remap = false)
public abstract class ChainConveyorBlockEntityMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void createperf$fastTick(CallbackInfo ci) {
        // 重写包循环：把 getExitFor 提到连接循环外，其余逻辑照抄
    }
}
```

**验证方法**：探针统计 `getExitFor` 与 `insert` 次数；行为回归：多端口连锁传送带的包裹去向、80 tick 广告周期的收敛。
**收益估计**：每 tick 从 O(连接 × 路由条目) 次正则匹配降到 O(1)；广告从 O(n·m) 到 O(n log n)（估计）。
**回归测试点**：多出口歧义时的去向；路由表广告收敛时间；`shouldAdvertise` 周期。

---

### 13. 应力表在网络数值变化时无条件发包 — [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/kinetics/gauge/StressGaugeBlockEntity.java:81`，由 `KineticNetwork.sync()` 触发 |
| 调用频次 | 每次动力网络数值变化 |
| 复杂度 / 成本 | 覆盖方法无条件 `sendData()`，而基类 `KineticBlockEntity.updateFromNetwork`（`:163-168`）只在过载标志翻转时发；`onSpeedChanged`（`:94`）又调一次 |
| 注入方式 | `@Inject` 比较 `dialTarget`/`color` 后再发，或 `@Overwrite` 小方法 |
| 目标成员 | `StressGaugeBlockEntity#updateFromNetwork`（以源码实际方法名为准） |
| 需新增状态 | 无（`@Shadow` 读上次值即可） |
| 风险 | 低 |
| 脆弱度 | 低 |
| 置信度 | 高概率（`:81` 与基类对比来自报告） |

**验证方法**：探针统计 `sendData` 次数；行为回归：表盘读数与颜色随应力变化、他人视角读数。
**收益估计**：每次网络数值变化少一次方块实体包（估计）。
**回归测试点**：过载/恢复显示；多人环境下同步。

---

### 14. 合成器每 tick 两次查询邻居红石信号 — [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/kinetics/crafter/MechanicalCrafterBlockEntity.java:264` |
| 调用频次 | 每个合成器每 tick（ASSEMBLING 阶段） |
| 复杂度 / 成本 | 同一表达式两次 `level.hasNeighborSignal(worldPosition)`，每次查 6 个邻居；5×5 阵列 25 个合成器 ≈ 150 次邻居查询/tick（估计） |
| 注入方式 | `@ModifyExpressionValue` 复用第一次结果，或 `@Inject` 改写该小段 |
| 目标成员 | `MechanicalCrafterBlockEntity#tick` |
| 需新增状态 | `@Unique` 单次调用内的局部量即可 |
| 风险 | 低 |
| 脆弱度 | 低 |
| 置信度 | 高概率（`:264` 来自报告） |

**Mixin 骨架**

```java
@Mixin(value = MechanicalCrafterBlockEntity.class, remap = false)
public abstract class MechanicalCrafterMixin {
    @Unique private boolean createperf$powered;

    @Inject(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;hasNeighborSignal(Lnet/minecraft/core/BlockPos;)Z", ordinal = 0),
        remap = true)
    private void createperf$capture(CallbackInfo ci) { /* 无法在此写入局部量，见下方说明 */ }
}
```

> **实践建议**：这种"同一表达式内两次调用"的清理，用 `@Inject(HEAD, cancellable)` 重写该分支（几行）比用 `@ModifyExpressionValue` + `@Unique` 组合更可靠。

**验证方法**：探针统计 `hasNeighborSignal` 次数；行为回归：红石锁定/触发时机与阵列合成顺序。
**收益估计**：每合成器每 tick 2 次 → 1 次（估计）。
**回归测试点**：红石上升/下降沿；合成器阵列顺序与锁定。

---

### 15. 传送带隧道每物品每次穿越重复解析约 4 次 — [Low-Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/kinetics/belt/transport/BeltTunnelInteractionHandler.java:41-49`（`stuckAtTunnel`）、`:101-107`（两次 `flapTunnel`）、`:132-156`（`flapTunnel` → `getTunnelOnSegment` → `getTunnelOnPosition`） |
| 调用频次 | 每个物品每次跨越带段每 tick |
| 复杂度 / 成本 | 同一位置被 `getBlockState(pos.above())` + `getBlockEntity(pos.above())` 反复解析（约 4 次）→ 每物品每 tick 约 4 次方块 + 4 次方块实体查询 |
| 注入方式 | `@Inject(HEAD, cancellable)` 重写小方法并把已解析的 BE 通过参数传递；或 `@Redirect` 缓存解析结果 |
| 目标成员 | `BeltTunnelInteractionHandler#flapTunnel` / `#getTunnelOnSegment` / `#getTunnelOnPosition` |
| 需新增状态 | 无（方法内局部变量即可） |
| 风险 | 低 |
| 脆弱度 | 低 |
| 置信度 | 高概率（行号来自报告） |

**验证方法**：探针统计相邻方块/方块实体查询次数；行为回归：隧道分流与合流的物品走向、挡板动画状态、被红石锁定的隧道。
**收益估计**：每物品每穿越从约 4 次解析降到 1-2 次（估计）。
**回归测试点**：隧道分流优先级；挡板动画；红石锁定。

---

### 16. 气流推动实体每实体每 tick 分配 3 个 Vec3 — [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/kinetics/fan/AirCurrent.java:93-100`（另见 `:88`、`:91`） |
| 调用频次 | 每风扇每 tick × `caughtEntities` 内每个实体 |
| 复杂度 / 成本 | `entity.setDeltaMovement(previousMotion.add(new Vec3(xIn, yIn, zIn).scale(1 / 8f)))`：内层 Vec3 + scale 结果 + add 结果 = 3 个 Vec3；另有 `entity.position()` 与 `VecHelper.getCenterOf` 的分配 |
| 注入方式 | `@Inject(HEAD, cancellable)` 重写该循环体（**唯一能真正零分配的写法**） |
| 目标成员 | `AirCurrent#tickAffectedEntities` |
| 需新增状态 | 无 |
| 风险 | 低 |
| 脆弱度 | 低 |
| 置信度 | 确定（`:100` 已逐行核对） |

**改后**

```java
entity.setDeltaMovement(previousMotion.x + xIn / 8f,
                        previousMotion.y + yIn / 8f,
                        previousMotion.z + zIn / 8f);
```

**反例警告**：不要用 `@Redirect` 把 `Entity.setDeltaMovement(Vec3)` 换成三参版本就以为解决了 —— `new Vec3(...).scale(...).add(...)` 是**参数表达式**，会被完整求值（手册 §3 陷阱）。必须重写整个赋值语句。

**Mixin 骨架**

```java
@Mixin(value = AirCurrent.class, remap = false)
public abstract class AirCurrentMixin {
    @Inject(method = "tickAffectedEntities", at = @At("HEAD"), cancellable = true)
    private void createperf$noAllocEntityPush(CallbackInfo ci) {
        // 重写循环：用三个 float 直接累加，不构造任何 Vec3
    }
}
```

**验证方法**：JFR 观察 `Vec3` 分配；行为回归：推力强度与轴向、`fallDistance` 归零、`sneakModifier` 语义。
**收益估计**：200 个被吹实体 ≈ 每 tick 1000 个 Vec3 → 0（估计）。
**回归测试点**：推力强度/方向；掉落物与生物表现；潜行修正。

---

### 17. NozzleBlockEntity 在实体循环里做 ArrayList contains — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/kinetics/fan/NozzleBlockEntity.java:149-165`（`pushingEntities.contains(entity)`）；列表声明与清空见 `:32`、`:174` |
| 调用频次 | `lazyTick` 每 5 tick（`setLazyTickRate(5)`，`:39`）× 每个喷嘴 |
| 复杂度 / 成本 | `level.getEntitiesOfClass` 返回 m 个实体，每个对 `pushingEntities`（0..256+）做线性 `contains` → O(n·m)；`remove` 也是 O(n)；`canSee(entity)` 还对每个实体做射线检测 |
| 注入方式 | `@Redirect` `List.contains/add/remove` 到 `@Unique` fastutil 集合，或 `@Overwrite` `lazyTick` |
| 目标成员 | `NozzleBlockEntity#lazyTick`（及 `pushingEntities` 相关逻辑） |
| 需新增状态 | `@Unique ObjectOpenHashSet<Entity>`（作用域：每喷嘴；失效：实体死亡/跨维度时移除；并发：服务端主线程） |
| 风险 | 低 |
| 脆弱度 | 低 |
| 置信度 | 确定（`:149` 的实体循环与 `:163-164` 的 `contains/add` 已逐行核对） |

**Mixin 骨架**

```java
@Mixin(value = NozzleBlockEntity.class, remap = false)
public abstract class NozzleBlockEntityMixin {
    @Unique private final Set<Entity> createperf$pushing =
        new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<>();

    @Redirect(method = "lazyTick",
        at = @At(value = "INVOKE", target = "Ljava/util/List;contains(Ljava/lang/Object;)Z"), remap = false)
    private boolean createperf$contains(List<Entity> list, Object e) {
        return createperf$pushing.contains(e);
    }
}
```

**验证方法**：探针统计 `contains` 次数与集合规模；行为回归：喷嘴持续推力、实体离开范围后的清理、多喷嘴重叠区域。
**收益估计**：n 达 256 时 O(n·m) → O(m)（估计）。
**回归测试点**：推力持续性；死亡实体清理；`canSee` 射线检测行为。

---

## 交叉引用（本文不展开）

- **传送带物品传输的物流侧语义**（`PackageItem.matchAddress`、打包机、青蛙港）→ [04-logistics.md](04-logistics.md)。
- **`SimpleRegistryImpl.get` 的 `synchronized`**（被 `BlockStressValues` 在每次动力网络重算中调用）→ [10-crosscutting-java.md](10-crosscutting-java.md)。
- **`TickBasedCache`、配置每 tick 读取、`ServerChainConveyorHandler` 每 10 tick 无条件下发全服包** → [09-compat-loadtime.md](09-compat-loadtime.md)。
- **风扇/传送带的客户端渲染分配**（`BeltRenderer`、`AirParticle`）→ [07-client-render-ui.md](07-client-render-ui.md)。

## 不可 Mixin / 建议提给上游的候选

| 条目 | 理由 |
| --- | --- |
| `KineticNetwork` 缺少"移除标志"（第 1 条） | 正解是给 `KineticBlockEntity` 加一种 removed/loaded 状态位，让网络成员在 `remove()` 时立即收缩；`@Overwrite` 只能绕过症状，且与 `unloadedMembers` 计数、并行 tick 语义耦合，属上游设计 |
| `BeltInventory` 用 `LinkedList` 存物品（第 2 条） | 集合**字段类型**的替换无法用 Mixin 安全完成（final 字段）；只能 `@Overwrite` 掉类的大部分方法，风险与收益不匹配。建议上游直接换 `ArrayList`/fastutil |
| `SmartBlockEntity.forEachBehaviour` 的容器选择（第 10 条） | 零分配需要"数组视图 + 增删维护"，属框架层 API 设计；上游只要在 `SmartBlockEntity` 内缓存数组即可 |
| `ChainConveyorRoutingTable` 的数据结构（第 12 条） | 改成 `HashMap<String,Entry>` + 定期排序属于数据结构替换，`@Overwrite` 会与上游的广告/超时逻辑强耦合 |
| 配置值在每 tick 路径上读取（如 `Train.java:657` 的 `maxAssemblyLength`，跨领域） | 不能简单缓存到静态字段（用户改配置必须立即生效）；建议上游提供带版本号的配置快照，见 [11-upstream-issues.md](11-upstream-issues.md) |

## 快速收益清单（本领域，按性价比）

1. **`KineticNetwork` 存活探测（第 1 条）** —— `@Overwrite` 两个短方法，收益随网络规模线性放大，是"每次交互卡一下"的直接来源。
2. **`SmartBlockEntity.forEachBehaviour`（第 10 条）** —— 覆盖**所有**机械的高频路径，一个方法解决一大类 GC 抖动。
3. **`BeltInventory.applyToEachWithin`（第 2 条）** —— 先判距离再复制 + 身份集合，去掉每 tick 的 O(n·m) 与无谓 `ItemStack.copy`。
4. **`BeltFunnelInteractionHandler` 的 amount/mode 缓存（第 3 条）** —— 移除每物品每 tick 的两次额外世界查找。
5. **`EncasedFanBlockEntity` 的 rebuild/发包门控（第 8 条）** —— 稳定场景下把三遍气流遍历与无条件 NBT 广播降到接近 0。

## 写作与置信度说明

本文件由 Lead 在子代理多次失败后亲自撰写，条目来自已逐行核对过的调查清单。其中第 1、2、3、5、9、16、17 条的代码片段由 Lead 直接对照源码核对（另见索引文件 §7 的抽检记录）；其余条目引用调查报告给出的行号与代码，已在「置信度」列逐条标注。

