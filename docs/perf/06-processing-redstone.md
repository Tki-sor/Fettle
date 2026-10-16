# 06 · Processing / Redstone / Boiler / VirtualWorld 性能问题

> 覆盖范围：`content/processing.**`（basin / burner）、`content/redstone.**`（含 link）、`content.fluids.spout`、`content.fluids.drain`、`content.fluids.tank.BoilerData`、`foundation.virtualWorld.**`、`content.kinetics.crafter.CrafterHelper`
> 调查依据：Create 6.0.10 / MC 1.21.1 源码逐行核对；行号以本仓库为准
> 公共约定见 [01-mixin-playbook.md](01-mixin-playbook.md)；整体重写方案见 [overwrites/](overwrites/)
> 相邻领域：**流体网络本体**（`FluidManipulationBehaviour`/`FluidPropagator`/`PumpBlockEntity`/`FluidNetwork`/`PipeConnection`/`FluidTransportBehaviour`）在 [05-fluids.md](05-fluids.md)；**客户端**（goggle tooltip、`AirParticle`、`SoundScapes`、`BeltRenderer`）在 [07-client-render-ui.md](07-client-render-ui.md)。

## 摘要

- 本领域最贵的是**"每 tick 重跑整条配方表"**：喷口（Spout）在物品被锁住的每个 tick 重新解析 SequencedAssembly + Filling 配方（第 1 条）；物品沥水器（ItemDrain）在 15 tick 倒计时里每 tick 重扫 emptying 配方并重算掉落（第 2 条）。这是**传送带生产线上的稳态开销**，收益直接体现在 MSPT。
- 第二条主线是**"多方块/容器的重复遍历"**：`BasinRecipe.apply` 对每个候选配方分配 2 个列表 + 4 个 `int[]`（第 3 条）；`BoilerData.evaluate` 在每次蒸汽引擎/哨子放置时三重循环整个锅炉体积并逐格分配 BlockPos（第 4 条）；`VirtualRenderWorld` 用 `HashMap<BlockPos,BlockState>` 存装置方块且虚拟区块永不清理（第 5 条）。
- 红石链接（第 7 条）与合成器连接纹理（第 8 条）是"每变化两遍遍历 + 异常当控制流"的经典模式，绝对量不大，但在大型频段广播/大型合成器阵列下可见；燃烧器粒子（第 6 条）是客户端每 tick 的白算。
- **可 Mixin 性**：8 条里 6 条适合 Mixin；`VirtualRenderWorld` 的容器替换（第 5 条）与 `BasinRecipe.apply`（第 3 条，含 NEWARRAY）需要谨慎的 `@Overwrite`，已在 [overwrites/](overwrites/) 立档。

---

## 发现

### 1. 喷口每 tick 重跑整条配方表扫描（每被锁住的物品） — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/fluids/spout/SpoutBlockEntity.java:109-111`；helper `content/fluids/spout/FillingBySpout.java:40-55`、`content/processing/sequenced/SequencedAssemblyRecipe.java:91-92` |
| 调用频次 | 每个被锁在喷口下的物品**每 tick**（`whenItemHeld` ← `BeltInventory.handleBeltProcessingAndCheckIfRemoved` → `BeltProcessingBehaviour.handleHeldItem`，`BeltInventory.java:241-254`；仓库同理见 `DepotBehaviour.java:138`）；仅在填充动画期间跳过（`:107`） |
| 复杂度 / 成本 | 每 tick 最多 4 次 O(#配方) 全表扫描：① `canItemBeFilled` → `SequencedAssemblyRecipe.getRecipe` → `getAllRecipesFor(SEQUENCED_ASSEMBLY)`（**物化列表** + 线性扫）；② `AllRecipeTypes.FILLING.find` → 原版 `getRecipeFor` 线性扫；③ `getRequiredAmountForItem` 再走一次 ① 并额外 `getRecipesFor(FILLING, ...)`（再一次物化 + 扫描）；另有 `fluid.copy()` 与一个 lambda |
| 注入方式 | `@Redirect` 两个静态 helper 调用 → `@Unique` 缓存；或 `@Inject(HEAD, cancellable)` 重写 |
| 目标成员 | `SpoutBlockEntity#whenItemHeld`；`FillingBySpout#canItemBeFilled` / `#getRequiredAmountForItem` |
| 需新增状态 | `@Unique` 缓存：键 =（物品 + 组件补丁，流体类型），值 =（配方, 所需量）；失效：`RecipeManager` 重载（监听 datapack sync） |
| 风险 | 中：失效漏掉会"喷错流体/喷错量"；必须键在**物品原型**而非传送带上被突变的 `ItemStack` 实例 |
| 脆弱度 | 中：`FillingBySpout` 方法名稳定，但 SequencedAssembly 解析路径在版本间演进过 |
| 置信度 | 高概率（`:109-111` 三行调用与两个 helper 的扫描结构均来自报告；`RecipeManager` 语义属原版行为） |

**改前（关键片段）**

```java
if (!FillingBySpout.canItemBeFilled(level, transported.stack))
    return PASS;
if (tank.isEmpty())
    return HOLD;
FluidStack fluid = getCurrentFluidInTank();
int requiredAmountForItem = FillingBySpout.getRequiredAmountForItem(level, transported.stack, fluid.copy());
```

**为什么慢**

喷口线稳态最常见的情形就是"物品停在喷口下等液体/等输出空间"，而这段等待期恰好是每 tick 全表扫描：SequencedAssembly 配方列表每次 `getAllRecipesFor` 都要重新物化，Filling 配方被线性扫两遍。喷口成排建造时成本 = 物品数 × 喷口数 × 每 tick 4 次全表扫描。

**Mixin 骨架**

```java
@Mixin(value = SpoutBlockEntity.class, remap = false)
public abstract class SpoutBlockEntityMixin {

    @Unique private final Map<Object, int[]> createperf$fillCache = new java.util.HashMap<>();

    @Redirect(method = "whenItemHeld",
        at = @At(value = "INVOKE",
                 target = "Lcom/simibubi/create/content/fluids/spout/FillingBySpout;getRequiredAmountForItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraftforge/fluids/FluidStack;)I"),
        remap = false)
    private int createperf$cachedAmount(Level level, ItemStack stack, FluidStack fluid) {
        Object key = createperf$key(stack, fluid);
        int[] cached = createperf$fillCache.get(key);
        if (cached != null) return cached[0];
        int amount = FillingBySpout.getRequiredAmountForItem(level, stack, fluid);
        createperf$fillCache.put(key, new int[]{ amount });
        return amount;
    }
}
```

**验证方法**

- 探针：统计每 tick 的 `getAllRecipesFor`/`getRecipeFor` 调用次数（改前 ≈ 物品数×喷口数×2~4，改后应趋近缓存未命中即 0）。
- 行为回归：用只改 Filling 配方的数据包 + `/reload` 验证缓存失效；带不同组件（附魔/自定义名）的物品不能命中同一缓存条目。

**收益估计**：每物品每 tick 2-4 次全配方扫描 → 0（缓存命中）（估计）。
**回归测试点**：填充动画时机；液体消耗/返还；SequencedAssembly 下一步解析；`/reload` 后即时生效。

---

### 2. 物品沥水器 15 tick 内每 tick 重扫配方并重算掉落 — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/fluids/drain/ItemDrainBlockEntity.java:241`（`continueProcessing`），倒计时起点 `:227` |
| 调用频次 | 每件经过沥水器的物品 15 次（`processingTicks` 20 → 5，每 tick 一次） |
| 复杂度 / 成本 | 每 tick 调 `GenericItemEmptying.canItemBeEmptied`（`:238`）**与** `emptyItem(level, stack, true)`（`:241`）；后者内部再扫一次配方并 `EmptyingRecipe.rollResults`（分配 `ArrayList`、`ProcessingOutput.rollOutput` → `ItemStack.copy()`，`ProcessingRecipe.java:152-160`，`Pair.of`）。即每件物品约 30 次全表扫描 + 约 30 次列表/栈分配 |
| 注入方式 | `@Redirect` 两个静态调用（缓存）；或 `@Inject(HEAD, cancellable)` 重写 `continueProcessing`（很短） |
| 目标成员 | `ItemDrainBlockEntity#continueProcessing` |
| 需新增状态 | `@Unique` 缓存（键：物品原型 + 流体类型），在 `:227` 进入倒计时时写入，物品离开时清空 |
| 风险 | 低：这 15 tick 内 `heldItem.stack` 不会变化 |
| 脆弱度 | 低 |
| 置信度 | 高概率（`:241` 与倒计时区间来自报告；`rollResults` 分配结构来自报告） |

**Mixin 骨架**

```java
@Mixin(value = ItemDrainBlockEntity.class, remap = false)
public abstract class ItemDrainBlockEntityMixin {

    @Unique private Pair<FluidStack, ItemStack> createperf$cachedEmpty;

    @Redirect(method = "continueProcessing",
        at = @At(value = "INVOKE",
                 target = "Lcom/simibubi/create/content/fluids/drain/GenericItemEmptying;emptyItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Z)Lorg/apache/commons/lang3/tuple/Pair;"),
        remap = false)
    private Pair<FluidStack, ItemStack> createperf$cached(Level level, ItemStack stack, boolean simulate) {
        if (createperf$cachedEmpty == null)
            createperf$cachedEmpty = GenericItemEmptying.emptyItem(level, stack, simulate);
        return createperf$cachedEmpty;
    }

    // 必须在物品离开/处理结束时清空缓存，否则下一件物品会复用上一件的配方
}
```

**验证方法**：探针统计 `emptyItem`/`canItemBeEmptied` 次数（每物品应从约 30 降到约 2）；行为回归：不同物品/流体、有副产物掉落的配方（`rollResults` 的随机数消耗顺序不能变）。
**收益估计**：每物品约 30 次全表扫描 + 30 次分配 → 约 2 次（估计）。
**回归测试点**：副产物随机结果；液体产量；无法排空时的行为；倒计时重置与缓存清空。

---

### 3. 盆式配方匹配每个候选都分配列表与 4 个 int[] — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/processing/basin/BasinRecipe.java:81`（`new LinkedList<>(recipe.getIngredients())`）、`:90-91`（两个 `int[]`）、`:67-68`（两次 capability 解析）；调用方 `content/processing/basin/BasinOperatingBlockEntity.java:153-155` |
| 调用频次 | 每次盆内容变化（`contentsChanged` → `scheduleUpdate`）× **每个候选配方**（模拟 + 执行各一遍） |
| 复杂度 / 成本 | 每次 `apply`：2 个 `ArrayList`（产物）+ 1 个 `LinkedList` 拷贝（原料）+ 每次 `Iterate.trueAndFalse` 迭代 2 个 `int[]` → 共 4 个数组 + 2 次 `getCapability`；候选数取决于配方 trie（混合物品时可能多个） |
| 注入方式 | `@Overwrite` `apply(BasinBlockEntity, Recipe, boolean)`（约 110 行、static、自洽）。**注意**：`int[]` 是 NEWARRAY，`@Redirect` 不可靠（手册 §3） |
| 目标成员 | `BasinRecipe#apply(BasinBlockEntity, Recipe, boolean)` |
| 需新增状态 | 无（纯重写）；可在 BE 上缓存两个 capability 引用（`invalidateCapabilities` 已有失效钩子） |
| 风险 | 中：执行 pass 会修改盆内库存，重写必须忠实复制原语义（包括枚举顺序与提前返回） |
| 脆弱度 | 中：方法体较长，上游改动会导致 `@Overwrite` 编译不过（安全失败） |
| 置信度 | 高概率（`:81` 与 `:90-91` 的分配结构来自报告） |

**改后（要点）**

```java
// 不复制原料列表：直接按索引访问 recipe.getIngredients()
NonNullList<Ingredient> ingredients = recipe.getIngredients();
// int[] 提到 simulate 循环外，每轮重置
int[] extractedItems = new int[availableItems.getSlots()];
int[] extractedFluids = new int[availableFluids.getTanks()];
for (boolean simulate : Iterate.trueAndFalse) {
    java.util.Arrays.fill(extractedItems, 0);
    java.util.Arrays.fill(extractedFluids, 0);
    ...
}
```

**Mixin 骨架**

```java
@Mixin(value = BasinRecipe.class, remap = false)
public abstract class BasinRecipeMixin {
    @Overwrite(remap = false)
    private static boolean apply(BasinBlockEntity basin, Recipe<?> recipe, boolean test) {
        // 忠实重写：省略 LinkedList 拷贝、数组提到循环外、capability 只解析一次
        return false;
    }
}
```

**验证方法**：探针统计每次 `apply` 的分配次数与 capability 解析次数；行为回归：混合物品/流体的盆配方匹配（含"部分匹配但最终不成立"的失败路径）、执行 pass 的库存扣减与产物放入、加热等级判定。
**收益估计**：每候选从 8 次分配 + 2 次 capability 解析 → 接近 0（估计）。
**回归测试点**：盆配方匹配与失败回滚；不同加热等级；流体罐交互；合成器/盆混合配方。

---

### 4. 锅炉评估是 O(width² × height)，每格 7 次方块查询，且每个引擎放置都重跑 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/fluids/tank/BoilerData.java:325`（`evaluate`）；驱动 `content/fluids/tank/FluidTankBlockEntity.java:331`（`updateBoilerState`）← `SteamEngineBlock.onPlace/onRemove`（`:111/`:122`）、`WhistleBlock.onPlace/onRemove`（`:185/`:193`）；`checkPipeOrganAdvancement` `:347` 重复同款循环 |
| 调用频次 | 每个蒸汽引擎/哨子放置或拆除时（建造大型锅炉时连续触发） |
| 复杂度 / 成本 | 三重循环覆盖 `width² × height` 格：每格 1 个 `controllerPos.offset(...)` BlockPos + 最多 6 个 `pos.relative(d)` BlockPos + 7 次 `getBlockState`；9×9×9 锅炉 = 729 格 ≈ 每放一个引擎约 5000 次 BlockPos 分配（估计） |
| 注入方式 | `@Redirect` `BlockPos.offset`（→ `@Unique MutableBlockPos`）即时消除大部分分配；增量计数需 `@Overwrite` |
| 目标成员 | `BoilerData#evaluate(...)` |
| 需新增状态 | `@Unique MutableBlockPos` 临时量（结果只被 `getBlockState`/`relative` 消费，不存储 → 共享安全） |
| 风险 | 低（`@Redirect` 版）／中（增量计数版：需要正确处理"放引擎同时放多个"的场景） |
| 脆弱度 | 低 |
| 置信度 | 高概率（`:325` 的三重循环与调用链来自报告） |

**改后（@Redirect 版）**

```java
@Unique private final BlockPos.MutableBlockPos createperf$scratch = new BlockPos.MutableBlockPos();

// BlockPos.offset(int,int,int) 被重定向为在这个 scratch 上 setWithOffset 并返回它
// 因为 evaluate 内不会把结果存起来（只用于 getBlockState/relative），共享是安全的
```

**Mixin 骨架**

```java
@Mixin(value = BoilerData.class, remap = false)
public abstract class BoilerDataMixin {
    @Unique private final BlockPos.MutableBlockPos createperf$scratch = new BlockPos.MutableBlockPos();

    @Redirect(method = "evaluate",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;offset(III)Lnet/minecraft/core/BlockPos;"),
        remap = true)
    private BlockPos createperf$reuseOffset(BlockPos base, int dx, int dy, int dz) {
        return createperf$scratch.setWithOffset(base, dx, dy, dz);
    }
}
```

> 判断依据：报告说明这些位置"只被 `getBlockState`/`relative` 消费，从不存储"。**若上游改动导致它们被存进集合，这个重定向会引入别名 bug** —— 属于"脆弱度：中"。

**验证方法**：探针/JFR 观察 `BlockPos` 分配；行为回归：锅炉尺寸/等级判定（引擎与哨子计数）、锅炉被破坏后的降级、`checkPipeOrganAdvancement` 成就触发。
**收益估计**：9×9×9 锅炉每个引擎放置约 5000 次 BlockPos 分配 → 接近 0（估计）。
**回归测试点**：引擎/哨子计数；锅炉等级与效率；放置后立即生效。

---

### 5. 虚拟世界的方块用 HashMap&lt;BlockPos,...&gt; 存储，且虚拟区块永不清理 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `foundation/virtualWorld/VirtualRenderWorld.java:189`（`blockStates.put(pos, newState)`）、`:69`（字段声明）、`:229`（读）、`clear()` `:136`；`foundation/virtualWorld/VirtualChunkSection.java:30`；`foundation/virtualWorld/VirtualChunkSource.java:31` |
| 调用频次 | 装置/蓝图装载时逐方块 `setBlock`（N 次）；每次网格重建时逐方块读取 |
| 复杂度 / 成本 | 每次 `setBlock` 分配一个不可变 `BlockPos` 作键（N 方块 → N 个 BlockPos + N 个 HashMap.Node）；读取走哈希 + equals；`VirtualChunkSection.getBlockState` 把每个网格查询转发给 `owner.world.getBlockState(...)`（每方块一次哈希，而非分区数组读取）。此外 `VirtualChunkSource.getChunk` 用 `computeIfAbsent` 构造完整 `LevelChunk`（含注册表查找与分区数组），而该 `Long2ObjectOpenHashMap` **从不清理**：`VirtualRenderWorld.clear()` 只清 `blockStates`/`blockEntities`/`nonEmptyBlockCounts`，长寿命的 `ClientContraption` 会保留它碰过的每个区块列 |
| 注入方式 | `@Inject(HEAD)` 在 `getBlockState`/`getBlockEntity` 读 `@Unique Long2ObjectOpenHashMap`，并在 `setBlock` HEAD 写入；虚拟区块驱逐用 `@Accessor` + `clear()` 尾部 `@Inject` |
| 目标成员 | `VirtualRenderWorld#setBlock` / `#getBlockState` / `#getBlockEntity` / `#clear`；`VirtualChunkSource` |
| 需新增状态 | `@Unique Long2ObjectOpenHashMap<BlockState>`（作用域：每虚拟世界；失效：`clear()`；并发：客户端单线程）；区块上限可配（例如 LRU） |
| 风险 | 中-高：`blockStates` 是 `protected final`，**不能**直接换字段；必须用 `@Inject` 接管全部读写路径（漏一处就会读到空数据），且虚拟世界还有 `getBlockEntity`/`nonEmptyBlockCounts` 等衍生状态 |
| 脆弱度 | 中-高：依赖 `blockStates`/`chunks` 的字段名与读写方法集合 |
| 置信度 | 高概率（`:189`、`:69`、`:136` 与虚拟区块路径来自报告，其中"永不清理"是报告对 `clear()` 的逐行核对结论） |

**改后（两个独立修复，建议分开做）**

```java
// 修复 A：键改 long（无键分配 + 更快探测）
@Unique private final Long2ObjectOpenHashMap<BlockState> createperf$states = new Long2ObjectOpenHashMap<>();

// 修复 B：clear() 时同时驱逐虚拟区块（或加入上限）
@Inject(method = "clear", at = @At("TAIL"), remap = false)
private void createperf$evictChunks(CallbackInfo ci) { /* 清空 VirtualChunkSource 的 chunks */ }
```

**Mixin 骨架**

```java
@Mixin(value = VirtualRenderWorld.class, remap = false)
public abstract class VirtualRenderWorldMixin {
    @Unique private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState> createperf$states =
        new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

    // 在 setBlock HEAD 写入 createperf$states（用 pos.asLong()）
    // 在 getBlockState/getBlockEntity HEAD 优先读 createperf$states，命中则 setReturnValue
}
```

**验证方法**：探针统计 `setBlock`/`getBlockState` 次数与 map 容量；行为回归：装置渲染是否完整（缺块/错块会立刻可见）、传送带装置上的物品渲染、蓝图预览；长跑：反复生成/销毁大型装置后观察 `VirtualChunkSource.chunks` 规模是否收敛。
**收益估计**：N 方块装置从 N 个 BlockPos + N 个 HashMap 节点 → 0 键分配；虚拟区块内存不再无界增长（估计）。
**回归测试点**：装置渲染完整性；光源/亮度查询；蓝图预览；跨维度装置。

---

### 6. 燃烧器粒子在 75% 的提前返回之前先算 3 个 Vec3 — [Low-Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/processing/burner/BlazeBurnerBlockEntity.java:362-370`，调用点 `:98`（客户端 `tick()`） |
| 调用频次 | 客户端每 tick × 每个点燃的燃烧器 |
| 复杂度 / 成本 | `spawnParticles` 先算 `c = VecHelper.getCenterOf(worldPosition)`、`VecHelper.offsetRandomly(Vec3.ZERO, r, .125f)`、`multiply(1,0,1)`、`add(...)`（约 3-4 个 Vec3），**然后**才 `if (r.nextInt(4) != 0) return;` —— 75% 的调用把这些对象全扔掉；`:369/:370` 还对 `worldPosition.above()` 各分配一个 BlockPos |
| 注入方式 | `@Overwrite` `spawnParticles`（约 35 行、类私有、无原版依赖）；`@Redirect` `VecHelper.getCenterOf` 不足以消除 multiply/add 的分配 |
| 目标成员 | `BlazeBurnerBlockEntity#spawnParticles` |
| 需新增状态 | 无 |
| 风险 | 低（粒子外观必须保持） |
| 脆弱度 | 低 |
| 置信度 | 高概率（`:362` 的顺序与 `:98` 调用点来自报告） |

**改后（要点）**

```java
if (r.nextInt(4) != 0) return;          // 提前返回放在最前
Vec3 c = VecHelper.getCenterOf(worldPosition);
...                                      // 用 double 直接算，只在 addParticle 前构造 Vec3
BlockPos above = createperf$scratch.setWithOffset(worldPosition, 0, 1, 0);  // 复用一个 BlockPos
```

**Mixin 骨架**

```java
@Mixin(value = BlazeBurnerBlockEntity.class, remap = false)
public abstract class BlazeBurnerBlockEntityMixin {
    @Overwrite(remap = false)
    protected void spawnParticles(Object heatLevel, int amount) {
        // 忠实重写：随机早退提到最前、double 直算、复用 BlockPos
    }
}
```

**验证方法**：JFR/探针观察 `Vec3`/`BlockPos` 分配；行为回归：火焰粒子密度与位置（含 SUPER_HEATED 与普通加热两种状态）。
**收益估计**：每燃烧器每 tick 约 3-4 个 Vec3 + 2 个 BlockPos → 约 1/4（估计）。
**回归测试点**：粒子外观与频率；创造模式/生存模式下的表现；不同加热等级。

---

### 7. 红石链接网络更新是 O(频段组)，并伴随三重查找与一次性 map — [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/redstone/link/RedstoneLinkNetworkHandler.java:81-84`（`getNetworkOf`）、`:101`（`updateNetworkOf`）、`:141`（`networksIn`）、`:103`（原子计数） |
| 调用频次 | 每次红石发射器信号变化（每个上升/下降沿） |
| 复杂度 / 成本 | `updateNetworkOf` 对整个频段组做**两遍遍历**，对每对调用 `withinRange`（距离计算），并对**每个** listener 调 `setReceivedStrength`（即使值未变）；`getNetworkOf` 走 `containsKey` + `put` + `get` 三次哈希；`networksIn` 对"未准备的世界"每次 `new HashMap<>()`（且写入被丢弃）；`connections` 是静态 `IdentityHashMap<LevelAccessor,...>`，被服务端/客户端线程无同步访问 |
| 注入方式 | `@Overwrite` `getNetworkOf`/`updateNetworkOf`（方法小、无原版内部依赖） |
| 目标成员 | `RedstoneLinkNetworkHandler#getNetworkOf` / `#updateNetworkOf` |
| 需新增状态 | `@Unique` 上次广播功率（按频段组），用于"值未变即早退" |
| 风险 | 低-中：红石语义（同频段广播、范围限制）必须完全保持 |
| 脆弱度 | 低 |
| 置信度 | 高概率（`:81-84`、`:101`、`:141` 来自报告） |

**改后（要点）**

```java
Set<IRedstoneLinkable> network = networksInWorld.computeIfAbsent(key, k -> new LinkedHashSet<>());  // 单次查找
// updateNetworkOf：记录该组上次广播功率，未变化直接 return；只对数值变化的 listener 调 setReceivedStrength
// networksIn：返回持久空 map（或首次准备时创建），不要每次 new
```

**Mixin 骨架**

```java
@Mixin(value = RedstoneLinkNetworkHandler.class, remap = false)
public abstract class RedstoneLinkNetworkHandlerMixin {
    @Overwrite(remap = false)
    private Set<IRedstoneLinkable> getNetworkOf(IRedstoneLinkable actor) {
        // 单次 computeIfAbsent 实现
        return null;
    }
}
```

**验证方法**：探针统计 `updateNetworkOf` 的 listener 调用次数与 `setReceivedStrength` 实际变化次数；行为回归：同频段广播、跨距离衰减（`withinRange`）、红石链接的延迟特性。
**收益估计**：大频段组下每次边沿从 O(2n) 次遍历 + n 次无谓赋值 → O(n) + 仅变化者（估计）。
**回归测试点**：频段广播范围；多发射器/接收器同频段；无线红石与列车条件联动。

---

### 8. 合成器连接判定用异常当控制流，每次连接纹理重建都做流+排序 — [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/kinetics/crafter/CrafterHelper.java:31-38`（`areCraftersConnected` 的 `try/catch(IndexOutOfBoundsException)`）；消费侧 `content/kinetics/crafter/ConnectedInputHandler.java:165`（`Collections.synchronizedList`）、`:208-213`（`getInventories` 的 stream+sorted+map+collect）；调用方 `content/kinetics/crafter/CrafterCTBehaviour.java:28`、`MechanicalCrafterBlockEntity.java:128` |
| 调用频次 | 每次连接纹理重建的每个方块面；每次多方块 capability 重建 |
| 复杂度 / 成本 | `areCraftersConnected`：2 次 `getBlockEntity` + 2 个 `BlockPos.offset` 分配 + 异常路径开销；`getInventories`：stream + 排序缓冲 + 结果列表 + 每条目一个 `BlockPos`；`synchronizedList` 让每次 `get(0)`/迭代都进监视器 |
| 注入方式 | `@Overwrite` 两个小方法（`areCraftersConnected`、`getInventories`），或 `@Inject(HEAD, cancellable)` 加缓存 |
| 目标成员 | `CrafterHelper#areCraftersConnected`；`ConnectedInput#getInventories` |
| 需新增状态 | 可选 `@Unique` 按 `BlockPos.asLong()` 的连接查找缓存（失效：`connectivityChanged()`） |
| 风险 | 低 |
| 脆弱度 | 低 |
| 置信度 | 高概率（`:31-38` 的 try/catch 与 `ConnectedInputHandler.java:165` 的 synchronizedList 来自报告） |

**改后（要点）**

```java
List<BlockPos> data = input.data;
if (data.isEmpty()) return false;                       // 显式判空，替代 try/catch
return pos.offset(data.get(0)).equals(otherPos.offset(input2.data.get(0)));
```

**Mixin 骨架**

```java
@Mixin(value = CrafterHelper.class, remap = false)
public abstract class CrafterHelperMixin {
    @Overwrite(remap = false)
    private static boolean areCraftersConnected(BlockPos pos, BlockState state, BlockPos otherPos, BlockState otherState) {
        // 显式判空 + 单次 getBlockEntity
        return false;
    }
}
```

**验证方法**：探针统计 `getBlockEntity` 与 `getInventories` 调用次数；行为回归：合成器阵列的连接纹理、输入格共享（`ConnectedInput`）的输入/输出行为、合成器被破坏后的连接更新。
**收益估计**：每次连接判定去掉异常路径与 offset 分配；`getInventories` 去掉 stream/排序/每条目分配（估计）。
**回归测试点**：合成器连接纹理与朝向；阵列输入分布；破坏/放置后的即时更新。

---

## 交叉引用（本文不展开）

- **流体网络本体**（BFS 的 `ArrayList.remove(0)`、压力逐管道 `sendData()`、`FluidTransportBehaviour.tick` 的每连接 lambda、`FluidNetwork.tick` 的容器分配、`FluidPropagator`/`PumpBlockEntity` 的装箱 map）→ [05-fluids.md](05-fluids.md)。
- **goggle tooltip 每帧重建**（含 `BasinBlockEntity.addToGoggleTooltip` 每帧遍历 18 槽 + 流体罐）→ [07-client-render-ui.md](07-client-render-ui.md)。注意：这条虽然实现类在 processing 包，但调用方是客户端 GUI 层，故归 07。
- **`AirParticle` / `SoundScapes`** → [07-client-render-ui.md](07-client-render-ui.md)。
- **`ItemHelper.extract` 的谓词链重启**（影响所有处理机抽取）→ [04-logistics.md](04-logistics.md) 第 10 条 + [11-upstream-issues.md](11-upstream-issues.md)。

## 不可 Mixin / 建议提给上游的候选

| 条目 | 理由 |
| --- | --- |
| `BasinRecipe.apply` 的 `int[]` 分配（第 3 条） | 无 `@Redirect` 切入点（NEWARRAY），只能 `@Overwrite` 一个约 110 行、**会修改库存**的方法。风险随上游重构上升；上游只要把两个数组提到 `Iterate.trueAndFalse` 外即可 |
| `VirtualRenderWorld` 的 `HashMap<BlockPos,BlockState>` 字段类型（第 5 条） | `protected final` 字段无法安全替换，必须接管全部读写路径；`VirtualChunkSource` 的区块缓存还缺少清理入口。建议上游改为 `Long2ObjectOpenHashMap` + `clear()` 驱逐（对双方都更简单） |
| `ConnectedInput.data` 用 `Collections.synchronizedList`（第 8 条） | 线程安全语义属于上游设计（是否存在跨线程读）；Mixin 换成普通列表可能引入竞态，需先确认调用线程模型 |
| 原版 `RecipeManager` 缺少 `lastRecipe` 快路径（第 1、2 条的根因） | Create 侧只能缓存；真正的通用解是原版提供"按输入物品缓存配方查找"的能力。建议在 `05-fluids`/`06` 相关 issue 里一并提出 |

## 快速收益清单（本领域，按性价比）

1. **喷口配方缓存（第 1 条）** —— 生产线稳态的最大 CPU 节省点，且 `@Redirect` 两个静态 helper 即可落地。
2. **沥水器 15 tick 缓存（第 2 条）** —— 每件物品约 30 次全表扫描降到约 2 次，风险低（倒计时内物品不变）。
3. **锅炉 `BlockPos` 复用（第 4 条）** —— 一处 `@Redirect` 消掉建造大型锅炉时的数千次分配。
4. **盆配方数组提取（第 3 条）** —— 需要 `@Overwrite`，但收益在频繁交互的盆式产线上很直接。
5. **虚拟世界键改 long + 区块驱逐（第 5 条）** —— 前者提升渲染期查询速度，后者修复长跑内存无界增长（这条更接近"正确性"而不仅是性能）。

> **写作说明**：本文件由 Lead 亲自撰写（原子代理在写入过程中失败，只留下第 1 条的部分内容，已由本版本整合）。条目来源为已逐行核对过的调查清单；「置信度」列逐条标注，其中第 1、2 条的代码片段与调用链由报告给出、Lead 复核了调用关系；涉及`@Overwrite` 的方案均在 [overwrites/](overwrites/) 单独立档。


