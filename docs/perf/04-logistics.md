# 04 · Item Logistics 性能问题

> 覆盖范围：`com.simibubi.create.content.logistics.**`（box / packager / packagePort / factoryBoard / stockTicker / tableCloth / vault / depot / chute / packagerLink）、`foundation/item/ItemHelper.java`、`content/equipment/blueprint/BlueprintOverlayRenderer.java`（商店/购物清单 overlay 部分）
> 调查依据：Create 6.0.10 / MC 1.21.1 源码逐行核对；行号以本仓库为准
> 公共约定见 [01-mixin-playbook.md](01-mixin-playbook.md)；整体重写方案见 [overwrites/](overwrites/)
> 相邻领域：**传送带/连锁传送带本体**（`content/kinetics/belt`、`content/kinetics/chainConveyor`）的条目在 [02-kinetics.md](02-kinetics.md)，本文只做交叉引用。

## 摘要

- 本领域**最贵的单点是包地址匹配**：`PackageItem.matchAddress` 用 `String.matches`，每次调用都重新 `Pattern.compile`，而连锁传送带在每 tick 的「包 × 端口 × 路由条目」三层循环里反复调用它（High，第 1 条）。这是全报告里性价比最高的修复之一。
- 第二条主线是**「库存反复全量扫描」**：打包机对目标库存最多 9 轮全扫且箱满不退出（第 2 条）、青蛙港 lazy tick 多轮全扫并在谓词里逐槽做正则匹配（第 4 条）、`ItemHelper.extract` 在部分匹配时重启整个槽循环（第 10 条）。
- 第三条主线是**「内容变化时的全多方块遍历」**：物品仓库每次槽位变动都遍历整个多方块体积并 poke 邻居（第 3 条），对最大 3×3×9 的仓库相当于每次物品进出上百次方块状态查询。
- 面板/终端类条目（第 5、7 条）与客户端商店 overlay（第 11 条）属于「每 tick / 每帧重算本质是缓存内容」的模式，收益与设备数量成正比。
- 全部 11 条中 **10 条可 Mixin**；只有 `ItemHelper.extract` 的谓词链重启（第 10 条）因为牵动所有抽取路径，建议只做保守改写或留给上游（见文末）。

---

## 发现

### 1. 包地址匹配每次调用都重新编译正则 — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/logistics/box/PackageItem.java:184-193` |
| 调用频次 | 连锁传送带每 tick：包 × travelPorts（`ChainConveyorBlockEntity.java:229`）、包 × loopPorts（`:280`）、包 × 连接 × 路由条目（`:304`，经 `ChainConveyorRoutingTable.getExitFor` `:56-61`）；另有青蛙港谓词（第 4 条） |
| 复杂度 / 成本 | 每次 `matchAddress` 最多 2 次 `Pattern.compile` + 2 次 `String.matches` + `Glob.toRegexPattern` 的字符串构造；Pattern.compile 实测量级 1-10 µs（估计），5 包 × 4 端口 × 20 路由条目 ≈ 每 tick 数百次编译 |
| 注入方式 | `@Inject(method = "matchAddress(Ljava/lang/String;Ljava/lang/String;)Z", at = @At("HEAD"), cancellable = true)`，用 `@Unique` 缓存重实现 |
| 目标成员 | `com.simibubi.create.content.logistics.box.PackageItem#matchAddress(String,String)Z` |
| 需新增状态 | `@Unique` 静态 `ConcurrentHashMap<String, Pattern>`（作用域：全局；失效：不需失效，键是 glob 字符串本身；并发：可能被客户端/服务端线程同时访问 → 用 ConcurrentHashMap 或每线程缓存） |
| 风险 | 低（静态纯函数，但**正则语义必须逐字保持**） |
| 脆弱度 | 低：依赖公开静态方法签名。但若上游改成 `Glob` 对象或其他 API，注入仍会命中 HEAD，只是你的实现会失效 |
| 置信度 | 确定（`PackageItem.java:184-193` 已逐行核对） |

**改前**

```java
public static boolean matchAddress(String boxAddress, String address) {
    if (address.isBlank())
        return boxAddress.isBlank();
    if (address.equals("*") || boxAddress.equals("*"))
        return true;
    return address.matches(Glob.toRegexPattern(boxAddress, "")) ||
        boxAddress.matches(Glob.toRegexPattern(address, ""));
}
```

**为什么慢**

`String.matches` 内部就是 `Pattern.compile(regex).matcher(input).matches()`——每次调用都编译一遍正则，而且 `Glob.toRegexPattern` 还会先构造一次正则字符串。
调用点全部落在每 tick 的三层嵌套循环里（包 × 端口 / 包 × 连接 × 路由条目），所以 `Pattern.compile` 的调用次数随「在途包裹数 × 端口数 × 路由条目数」增长。路由表越长、网络越大，这个开销越显著——它是"包数量"与"网络规模"的乘积项。

**改后（纯 Java 语义等价版，供参考；Mixin 里照搬这个逻辑）**

```java
private static final Map<String, Pattern> GLOB_CACHE = new ConcurrentHashMap<>();

private static boolean matchesCached(String input, String glob) {
    Pattern p = GLOB_CACHE.computeIfAbsent(glob, g -> Pattern.compile(Glob.toRegexPattern(g, "")));
    return p.matcher(input).matches();
}

public static boolean matchAddress(String boxAddress, String address) {
    if (address.isBlank())
        return boxAddress.isBlank();
    if (address.equals("*") || boxAddress.equals("*"))
        return true;
    return matchesCached(address, boxAddress) || matchesCached(boxAddress, address);
}
```

> 注意：缓存键必须是 **glob 字符串**，而不是输入地址。glob 有界（玩家自定义端口过滤串，数量 = 端口数），不会无界增长；若担心极端情况，加一个 `maximumSize`（Caffeine/Guava）或简单的容量上限。

**Mixin 骨架**

```java
@Mixin(value = com.simibubi.create.content.logistics.box.PackageItem.class, remap = false)
public abstract class PackageItemMixin {

    @Unique
    private static final Map<String, Pattern> createperf$globCache = new ConcurrentHashMap<>();

    @Inject(method = "matchAddress(Ljava/lang/String;Ljava/lang/String;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void createperf$fastMatchAddress(String boxAddress, String address,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (address.isBlank()) {
            cir.setReturnValue(boxAddress.isBlank());
            return;
        }
        if (address.equals("*") || boxAddress.equals("*")) {
            cir.setReturnValue(true);
            return;
        }
        cir.setReturnValue(createperf$matches(address, boxAddress)
            || createperf$matches(boxAddress, address));
    }

    @Unique
    private static boolean createperf$matches(String input, String glob) {
        Pattern p = createperf$globCache.computeIfAbsent(
            glob, g -> Pattern.compile(net.createmod.catnip.lang.Glob.toRegexPattern(g, "")));
        return p.matcher(input).matches();
    }
}
```

**验证方法**

- 探针：在 `createperf$matches` 里累加静态计数与 `System.nanoTime`，每 100 tick 打日志，对比开关 mixin。要看的数字是"每 100 tick 的缓存未命中次数"（应该稳定 ≈ 端口数）而不是命中次数。
- 行为回归：用带 `*`、空字符串、大小写、带 `_` 与中文/特殊字符的端口过滤串各测一遍，确认与改前逐字一致（`String.matches` 是整体匹配，不是 `find`）。
- JFR：`Pattern.compile` 栈帧应从采样里消失。

**收益估计**：每次调用 2 次 `Pattern.compile` → 0（命中缓存后）。连锁传送带网络规模越大收益越大（估计：单台繁忙连锁传送带每 tick 数百次编译 → 个位数）。
**回归测试点**：端口过滤匹配结果（含通配符、空串、非 ASCII）；路由表广告（`ChainConveyorRoutingTable`）行为；包裹投递目标选择。

---

### 2. 打包机对目标库存最多 9 轮全扫，且箱满后不退出 — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/logistics/packager/PackagerBlockEntity.java:431-455` |
| 调用频次 | 每生成一个包裹（`attemptToSend` / `performPackageRequests` 周期） |
| 复杂度 / 成本 | `PackageItem.SLOTS = 9`（`PackageItem.java:57`）。外层 `Outer:` 循环 9 次，每次内层从 slot 0 重扫整个目标库存：`extractItem(slot, n, true)` + `extracted.copy()` + `ItemHandlerHelper.insertItemStacked`。最大物品仓库（81 方块 × 20 槽的 `SameSizeCombinedInvWrapper`）单轮就上千次模拟抽取（估计），且**箱子满了也不 break** |
| 注入方式 | `@Inject(HEAD, cancellable)` 重实现，或 `@Overwrite`（方法体较长，含 `Outer:` 标签与 `nextRequest` 状态） |
| 目标成员 | `PackagerBlockEntity#attemptToSend`（核对时见 `Outer:` 标签位于 `:431`，`for (int i = 0; i < PackageItem.SLOTS; i++)` 于 `:432`） |
| 需新增状态 | `@Unique` 槽位游标（`int nextSlot`）与"箱满"短路；**必须先确认** `attemptToSend` 是否可能被同一 tick 重入（若会，游标需按 tick 重置） |
| 风险 | 中（包裹内容/顺序语义） |
| 脆弱度 | 中：方法体较长，上游重构会改动局部结构；`Outer:` 标签这类结构变化会让 `@Overwrite` 直接编译不过（编译期就能发现，属"安全失败"） |
| 置信度 | 确定（`:431-432` 与内层 `extractItem/insertItemStacked` 已核对；"上千次模拟抽取"是按最大仓库的估计） |

**改前（关键片段）**

```java
Outer:
for (int i = 0; i < PackageItem.SLOTS; i++) {
    boolean continuePacking = true;
    while (continuePacking) {
        continuePacking = false;
        for (int slot = 0; slot < targetInv.getSlots(); slot++) {
            int initialCount = requestQueue ? Math.min(64, nextRequest.getCount()) : 64;
            ItemStack extracted = targetInv.extractItem(slot, initialCount, true);
            ...
            int leftovers = ItemHandlerHelper.insertItemStacked(extractedItems, extracted.copy(), false);
```

**为什么慢**

对**每个包裹槽**都从 slot 0 重新扫描目标库存；当包裹已经装满（`transferred == 0`）时，剩余槽位仍会走完 `extractItem(...,true)` + `copy()` + `insertItemStacked`。规模化场景：大仓库 + 高频下单（工厂面板请求），每次包裹生成都产生数百到上千次模拟抽取与 `ItemStack` 复制，全是纯 GC 与 CPU 浪费。

**改后**

```java
// 伪代码要点：满即停 + 记录游标
for (int i = 0; i < PackageItem.SLOTS && !isFull(extractedItems); i++) {
    boolean continuePacking = true;
    while (continuePacking && !isFull(extractedItems)) {
        continuePacking = false;
        for (int slot = createperf$nextSlot; slot < targetInv.getSlots(); slot++) {
            ... // 同原逻辑
            if (leftovers == extracted.getCount()) {           // 该槽塞不进任何东西
                if (extracted.getCount() == 0) createperf$nextSlot = slot; // 整槽都被抽空 → 记住进度
                continue;
            }
            continuePacking = true;
        }
    }
}
createperf$nextSlot = 0; // 一次 attemptToSend 结束后复位
```

**Mixin 骨架**

```java
@Mixin(value = PackagerBlockEntity.class, remap = false)
public abstract class PackagerBlockEntityMixin {

    @Unique private int createperf$nextSlot = 0;

    @Inject(method = "attemptToSend", at = @At("HEAD"), cancellable = true)
    private void createperf$fastAttemptToSend(CallbackInfo ci) {
        // 或：@Inject 到内层循环处做游标+满即停（更保守）
    }
}
```

> 更保守的替代：不改结构，只 `@Redirect` 内层的 `ItemHandlerHelper.insertItemStacked`，当返回值等于传入 count 时用 `@Unique boolean` 标记"箱满"，并在下一次 `ItemStackHandler.extractItem` 调用前用 `@Inject` 直接 return —— 但 `@Inject` 无法从循环内部跳出方法，实际仍以 `@Overwrite` 最干净。

**验证方法**

- 探针：统计单次 `attemptToSend` 里 `extractItem` 的调用次数（改前 vs 改后，同一个大仓库场景）。
- 行为回归：包裹内容与顺序、请求数量（`nextRequest.getCount()`）满足情况、`failedLastExport` 与失败上报路径、库存余量。

**收益估计**：每包 5-9 轮全库存扫描 → ≈1 轮（估计）。
**回归测试点**：请求式打包（`requestQueue`）与普通打包两种模式；目标库存跨多个方块的情况；包裹槽位顺序。

---

### 3. 物品仓库每次内容变化都遍历整个多方块并 poke 邻居 — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/logistics/vault/ItemVaultBlockEntity.java:56-62`（`onContentsChanged` 回调）与 `:94-157`（`updateComparators`） |
| 调用频次 | **每次槽位内容变化**：漏斗/打包机/溜槽/自动化每移动一个物品一次 |
| 复杂度 / 成本 | 三重循环覆盖 `radius × zMax × xMax`（最大 3×3×9 = 81 格），每格 `level.getBlockState(provokingPos).getBlock()`，表面格还要做 6 方向邻居 poke；代码注释自己说明内部格是白做的（`:131` 附近）。即每个物品进出 ≈ 81 次方块状态解析 + 数百次邻居操作（估计） |
| 注入方式 | `@Inject` 在 `updateComparators` 内加"亮度/比较器值未变即早退"的门控，或 `@Overwrite` 只处理表面格 |
| 目标成员 | `ItemVaultBlockEntity#updateComparators`（`onContentsChanged` 在构造器里以 lambda 注册于 `:56-62`） |
| 需新增状态 | `@Unique` 上一次的 `comparatorOutput`/`luminosity` 快照（作用域：每方块实体；失效：每次内容变化重新比较；并发：仅服务端主线程） |
| 风险 | 中（比较器输出与多方块状态必须保持一致） |
| 脆弱度 | 低-中：方法名与字段名稳定；但多方块几何逻辑若被重写，你的"表面格"假设需要重新核对 |
| 置信度 | 确定（`:56-62` 已核对：`onContentsChanged` lambda 内先 `updateComparators()` 再 `level.blockEntityChanged`；`:94-157` 循环结构来自逐行阅读） |

**改前（关键片段）**

```java
// :56-62 构造器里注册
inventory = new ItemStackHandler(AllConfigs.server().logistics.vaultCapacity.get()) {
    @Override
    protected void onContentsChanged(int slot) {
        updateComparators();
        level.blockEntityChanged(worldPosition);
    }
};

// :94+ updateComparators（三重循环，节选）
for (int y = 0; y < radius; y++)
    for (int z = 0; z < zMax; z++)
        for (int x = 0; x < xMax; x++) {
            ...
            Block provokingBlock = level.getBlockState(provokingPos).getBlock();
            ...
        }
```

**为什么慢**

原版箱子只需要一次 `updateNeighbourForOutputSignal`；这里对**整个多方块体积**逐格查询方块状态并对表面格做 6 向邻居通知，而调用时机是"每移动一个物品"。仓库越大越贵，且这个成本与物品吞吐量线性相乘 —— 大型仓储区（多个 3×3×9 仓库 + 高频漏斗）是典型的卡顿源。

**改后**

```java
public void updateComparators() {
    int newOutput = createperf$computeComparatorOutput();   // 只读 inventory，不碰世界
    if (newOutput == createperf$lastComparatorOutput)
        return;                                             // 绝大多数调用在这里返回
    createperf$lastComparatorOutput = newOutput;

    // 只有真的变化了，才遍历并通知（且只在表面格上做 6 向 poke）
    ...
}
```

**Mixin 骨架**

```java
@Mixin(value = ItemVaultBlockEntity.class, remap = false)
public abstract class ItemVaultBlockEntityMixin {

    @Unique private int createperf$lastComparatorOutput = -1;

    @Inject(method = "updateComparators", at = @At("HEAD"), cancellable = true)
    private void createperf$skipUnchanged(CallbackInfo ci) {
        int now = createperf$computeOutput();
        if (now == createperf$lastComparatorOutput) {
            ci.cancel();
            return;
        }
        createperf$lastComparatorOutput = now;
    }
    // createperf$computeOutput 用 @Shadow 的 inventory 计算原版比较器公式
}
```

**验证方法**

- 探针：统计 `updateComparators` 的进入次数与真正执行遍历的次数（比值应接近 10:1 以上）。
- 行为回归：比较器输出值随物品数量变化正确；多方块被破坏/重建后比较器仍正确；`blockEntityChanged` 仍被调用（阻止它被合并）。
- 注意：**必须保留 `level.blockEntityChanged(worldPosition)`**，否则存档不会标记脏。

**收益估计**：每次物品进出最多 81 次 BlockState + 数百次邻居操作 → 通常 0（估计）。
**回归测试点**：比较器（`AnalogLever` 之外的 `Comparator` 读取）输出；多方块表面/内部格判定；存档脏标记。

---

### 4. 青蛙港 lazy tick 多轮全库存扫描，且谓词里逐槽做正则匹配 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/logistics/packagePort/frogport/FrogportBlockEntity.java:124-135`（`lazyTick`）→ `:261-289`（`tryPushingToAdjacentInventories`）、`:295-326`（`tryPullingFromOwnAndAdjacentInventories`，谓词在 `:313-320`） |
| 调用频次 | lazy tick（默认 10 tick，`SmartBlockEntity` 的 lazy 节奏）× 每个青蛙港 |
| 复杂度 / 成本 | 每轮：自身库存全扫（push）+ 自身与相邻库存各一次 `ItemHelper.extract` 全扫（pull）；谓词对**每个非空槽**调用 `PackageItem.matchAddress`，即每槽最多 2 次 `Pattern.compile`（第 1 条同源）。O(槽数 × 2) 次正则编译 + 多轮 O(槽数) 抽取尝试 |
| 注入方式 | `@Inject(HEAD, cancellable)` 或 `@Redirect` 谓词内的两个 `matchAddress` 调用 → 走第 1 条的 Pattern 缓存即可消掉大头 |
| 目标成员 | `FrogportBlockEntity#lazyTick` / `tryPullingFromOwnAndAdjacentInventories` |
| 需新增状态 | 可选：把 `getFilterString()` 与编译后的 Pattern 提到谓词外（`@Unique` 字段，过滤串变化时失效） |
| 风险 | 低（先做第 1 条即可获得大部分收益，此处只需提串） |
| 脆弱度 | 低 |
| 置信度 | 确定（`:124-135` 已核对：客户端/动画中直接 return，随后 push + pull） |

**改前**

```java
public void lazyTick() {
    super.lazyTick();
    if (level.isClientSide() || isAnimationInProgress())
        return;

    boolean prevFail = failedLastExport;
    tryPushingToAdjacentInventories();
    tryPullingFromOwnAndAdjacentInventories();

    if (failedLastExport != prevFail)
        sendData();
}
```

**为什么慢**

两件事叠加：(a) 每轮做多次整库存扫描；(b) 抽取谓词在**每个槽**上重建过滤条件并走 `matchAddress`（`PackageItem.java:191`），于是 lazy tick 的一次调用可能带来几十次 `Pattern.compile`。由于青蛙港常与大型仓库相邻，槽数很多，成本被放大。

**改后**

```java
// 在 lazyTick 开头解析一次
String filter = getFilterString();
boolean hasFilter = filter != null;
...
// 谓词内复用（不再每次 getFilterString / 不再每次编译正则）
ItemHelper.extract(handler, stack -> {
    if (!PackageItem.isPackage(stack)) return false;
    return !hasFilter || handler instanceof PackagerItemHandler
        || PackageItem.matchAddress(stack, filter);      // 走第 1 条的 Pattern 缓存
}, false);
```

**Mixin 骨架**

```java
@Mixin(value = FrogportBlockEntity.class, remap = false)
public abstract class FrogportBlockEntityMixin {
    @Redirect(method = "tryPullingFromOwnAndAdjacentInventories",
              at = @At(value = "INVOKE",
                       target = "Lcom/simibubi/create/content/logistics/box/PackageItem;matchAddress(Ljava/lang/String;Ljava/lang/String;)Z"),
              remap = false)
    private boolean createperf$cachedMatch(String a, String b) {
        return PackageItemMixinBridge.matchAddressCached(a, b); // 与第 1 条共用同一缓存
    }
}
```

**验证方法**：探针统计 lazy tick 内的 `matchAddress` 调用次数与缓存未命中次数；行为回归：过滤串为空/为 `*`/精确匹配三种情况下的抽取结果。

**收益估计**：每 10 tick 每港 O(槽数×2) 次正则编译 → O(1) 次（估计）。
**回归测试点**：过滤串语义；相邻库存抽取优先级；`failedLastExport` 上报。

---

### 5. 工厂面板每 tick 无条件重算库存与承诺 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/logistics/factoryBoard/FactoryPanelBehaviour.java:294-313`（`tick`）→ `:356-393`（`tickStorageMonitor`）、`:726-744`（`getUnloadedLinks`/`getLevelInStorage`）、`:756-783`（`getPromised`） |
| 调用频次 | 每个工厂面板每 tick（服务端；客户端虚拟面板也走 `tickStorageMonitor`，见 `:296-298`） |
| 复杂度 / 成本 | `getLevelInStorage()` → `LogisticsManager.getSummaryOfNetwork` → `InventorySummary.getCountOf(filter)`（线性扫描 + `ItemStack.isSameItemSameComponents` 全组件相等）；`getPromised()` → 遍历该物品的承诺列表并清理过期项。网络汇总本身有 20 tick 缓存（`LogisticsManager.java:39`），但面板层每 tick 都重新查一次 |
| 注入方式 | `@Inject(HEAD, cancellable)` 在 `tickStorageMonitor` 上加版本号/惰性 tick 门控 |
| 目标成员 | `FactoryPanelBehaviour#tickStorageMonitor` / `#getPromised` |
| 需新增状态 | `@Unique` 上次刷新 tick 或汇总版本号（作用域：每 behaviour；失效：汇总版本变化或承诺队列变化；并发：服务端主线程） |
| 风险 | 中（面板显示滞后会改变玩家体验，门控窗口要小，建议 ≤5 tick） |
| 脆弱度 | 中：依赖 `tickStorageMonitor` 方法名与 `LogisticsManager` 汇总缓存语义 |
| 置信度 | 确定（`:294-313` 已核对：服务端分支末尾依次 `tickStorageMonitor(); tickRequests();`） |

**改前**

```java
public void tick() {
    super.tick();
    if (getWorld().isClientSide()) {
        if (blockEntity.isVirtual())
            tickStorageMonitor();
        bulb.updateChaseTarget(redstonePowered || satisfied ...);
        ...
    }
    ...
    tickStorageMonitor();
    tickRequests();
}
```

**为什么慢**

面板数量可以很多（每个工厂面板一个 behaviour），而每次 `tickStorageMonitor` 都做「未加载链接计数 + 网络汇总按物品计数 + 承诺遍历清理」。其中按物品计数是**线性扫描 + 组件相等比较**，在物品类型多、承诺多的网络里是每 tick 的固定开销。

**改后**

```java
@Unique private int createperf$monitorCooldown = 0;

// tickStorageMonitor 开头
if (--createperf$monitorCooldown > 0) return;
createperf$monitorCooldown = 2; // 或按 summary 版本号判断，变化即刻刷新
```

**Mixin 骨架**

```java
@Mixin(value = FactoryPanelBehaviour.class, remap = false)
public abstract class FactoryPanelBehaviourMixin {

    @Unique private int createperf$cooldown = 0;

    @Inject(method = "tickStorageMonitor", at = @At("HEAD"), cancellable = true)
    private void createperf$throttle(CallbackInfo ci) {
        if (createperf$cooldown-- > 0) ci.cancel();
        else createperf$cooldown = 2;
    }
}
```

**验证方法**：探针统计每 tick 的 `getCountOf`/`getPromised` 调用次数；行为回归：面板库存/承诺数字在物品进出后 ≤2 tick 内更新；请求满足判定不变。

**收益估计**：每面板每 tick 2-4 次汇总查找 + 承诺遍历 → 减半或更多（估计）。
**回归测试点**：面板显示数值的刷新延迟；`tickRequests` 的请求发放时机；虚拟面板（客户端）行为。

---

### 6. 溜槽每 tick 做 5 次邻块查询并分配 LinkedList — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/logistics/chute/ChuteBlockEntity.java:149-154`（`canPickUpItems = canDirectlyInsert()`）、`:126-136`、`:708-717`（`getInputChutes`） |
| 调用频次 | 每个溜槽每 tick（服务端） |
| 复杂度 / 成本 | 每 tick 至少一次 `canDirectlyInsert()`；当 `getItemMotion() > 0` 时 `getInputChutes()` 每次 `new LinkedList<>()` 并对 5 个非 DOWN 方向各做 `getBlockState` + `getBlockEntity`。溜槽常常成列建造 → 成本 O(溜槽数)/tick + 每溜槽 5 次方块/方块实体查询 |
| 注入方式 | `@Overwrite` 小方法（`getInputChutes`）或 `@Inject` 加缓存 + `@Redirect` 构造器 |
| 目标成员 | `ChuteBlockEntity#getInputChutes` / `#canDirectlyInsert` |
| 需新增状态 | `@Unique` 缓存（输入方向位掩码 + 上次判定），失效条件：相邻方块变化（可用 `neighborChanged`/`onNeighborChanged` 注入清空） |
| 风险 | 中（缓存失效时机错 → 物品上行/下行行为异常） |
| 脆弱度 | 低 |
| 置信度 | 确定（`:708-709` 已核对：`List<ChuteBlockEntity> inputs = new LinkedList<>();`） |

**改后（不引入缓存的最小改法）**

```java
// 返回位掩码，不分配集合
private int getInputChuteMask() {
    int mask = 0;
    for (Direction d : Iterate.directions) {
        if (d == Direction.DOWN) continue;
        BlockPos p = worldPosition.relative(d);
        if (level.getBlockState(p).getBlock() instanceof ChuteBlock && ...)
            mask |= 1 << d.get3DDataValue();
    }
    return mask;
}
```

**Mixin 骨架**

```java
@Mixin(value = ChuteBlockEntity.class, remap = false)
public abstract class ChuteBlockEntityMixin {
    @Unique private int createperf$inputMask = 0;
    @Unique private boolean createperf$maskDirty = true;

    // 在 getInputChutes 里改为读 mask；在 neighborChanged 类入口把 maskDirty 置 true
}
```

**验证方法**：探针统计 `getBlockEntity` 调用次数；行为回归：多个溜槽相连时的输入选择、被红石锁定的溜槽、溜槽上方漏斗输出。

**收益估计**：每溜槽每 tick 5 次 BE 查询 + 1 次集合分配 → 仅在邻块变化时（估计）。
**回归测试点**：溜槽链的物品方向仲裁；`canPickUpItems` 与传送带交互。

---

### 7. 股票终端快照构建 O(类别 × 物品) 且用 ArrayList 移位删除 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/logistics/stockTicker/StockTickerBlockEntity.java:211-226` |
| 调用频次 | 客户端收到 `LogisticalStockResponsePacket` 流结束时（每次打开/刷新股票界面） |
| 复杂度 / 成本 | 对**每个类别**遍历"剩余快照"并 `Iterator.remove()`——`ArrayList` 的 `remove` 会搬移尾部元素，最坏 O(类别 × 物品²)（估计）。整合包里股票条目可达数百到数千 |
| 注入方式 | `@Overwrite` 快照构建方法（单遍分类） |
| 目标成员 | `StockTickerBlockEntity` 中构建 `newlyReceivedStockSnapshot` 分类的方法（`:211` 起，`:226` 有 `List<BigItemStack> unsorted = new ArrayList<>(newlyReceivedStockSnapshot);`） |
| 需新增状态 | 无（纯重写） |
| 风险 | 中（界面内容必须完全一致） |
| 脆弱度 | 中：方法名/结构在上游可能变化 |
| 置信度 | 高概率（`:211-216` 与 `:226` 结构已核对；二次方项按 `ArrayList.remove` 语义推得） |

**改后**

```java
// 单遍：为每个 stack 求类别索引，直接追加到对应类别列表，无删除
Map<BigItemStack, Integer> categoryIndex = new IdentityHashMap<>();
List<List<BigItemStack>> buckets = new ArrayList<>();
for (ItemStack filter : categories) { buckets.add(new ArrayList<>()); ... }
for (BigItemStack stack : newlyReceivedStockSnapshot) {
    int idx = createperf$categoryOf(stack);   // 一次判定
    if (idx >= 0) buckets.get(idx).add(stack);
    else unsorted.add(stack);
}
```

**Mixin 骨架**

```java
@Mixin(value = StockTickerBlockEntity.class, remap = false)
public abstract class StockTickerBlockEntityMixin {
    @Overwrite(remap = false)
    private void <快照构建方法名>(...) { /* 单遍分类实现 */ }
}
```

**验证方法**：用同一份大股票列表对比改前改后界面渲染结果（类别归属、未分类项、排序）；用探针统计元素搬移次数（可用 `ArrayList` 包装计数）。

**收益估计**：客户端收包时的 O(类别×物品²) 元素搬移 → O(物品)（估计）。
**回归测试点**：类别归属与未匹配项；搜索框过滤；面板排序。

---

### 8. 全局物流 tick 遍历所有网络，即使承诺队列为空 — [Low-Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/logistics/packagerLink/GlobalLogisticsManager.java:107-113`（由 `foundation/events/CommonEvents.java:143` 每 level tick 调用）→ `RequestPromiseQueue.tick()` |
| 调用频次 | 每服务端 level tick |
| 复杂度 / 成本 | `logisticsNetworks.forEach((id, network) -> network.panelPromises.tick())`；每个网络一个 `IdentityHashMap`（构造即预分配 32 槽表，`RequestPromiseQueue.java:19-23`），空队列也要遍历一次。成本随"股票链接频率数"增长，而非随"实际待处理承诺数" |
| 注入方式 | `@Inject(HEAD, cancellable)` 在 `GlobalLogisticsManager.tick` 上改为只 tick 非空队列集合 |
| 目标成员 | `GlobalLogisticsManager#tick` |
| 需新增状态 | `@Unique` 非空队列集合（作用域：全局；失效：队列增删时维护；并发：服务端主线程）——或用 `@Shadow` 直接读私有字段 |
| 风险 | 中（漏 tick 会导致承诺过期清理停滞） |
| 脆弱度 | 低-中：字段是私有的，需 `@Shadow` 或 `@Accessor` |
| 置信度 | 确定（调用点与循环体来自逐行阅读） |

**验证方法**：探针统计每 tick 实际 tick 的队列数与空队列数；行为回归：物品请求的承诺过期清理及时性（长跑测试：下单后取消，观察面板数字是否恢复）。

**收益估计**：每 tick 遍历 N 个网络 → 只遍历非空网络（估计）。
**回归测试点**：`RequestPromiseQueue` 的过期语义；跨维度物流网络。

---

### 9. 仓库行为在 8 槽循环内重复解析 behaviour — [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/logistics/depot/DepotBehaviour.java:163-178`（`handleBeltFunnelOutput`） |
| 调用频次 | 每仓库每 tick（当有物品被持有/积压时） |
| 复杂度 / 成本 | `processingOutputBuffer` 的每个非空槽都调一次 `blockEntity.getBehaviour(DirectBeltInputBehaviour.TYPE)`（behaviour map 查找），核对的代码段里 `:169` 与 `:185` 各一次 |
| 注入方式 | `@Inject` 在方法开头解析一次（或用 `@Redirect` 把 `getBehaviour` 调用重定向到缓存），成功即 break |
| 目标成员 | `DepotBehaviour#handleBeltFunnelOutput` |
| 需新增状态 | `@Unique` 局部缓存即可（单次调用内有效） |
| 风险 | 低 |
| 脆弱度 | 低 |
| 置信度 | 确定（`:163-178` 已核对） |

**改后**

```java
private boolean handleBeltFunnelOutput() {
    BlockState funnel = getWorld().getBlockState(getPos().above());
    Direction funnelFacing = AbstractFunnelBlock.getFunnelFacing(funnel);
    if (funnelFacing == null) return false;
    DirectBeltInputBehaviour behaviour =
        blockEntity.getBehaviour(DirectBeltInputBehaviour.TYPE);   // 循环外解析一次
    for (int slot = 0; slot < processingOutputBuffer.getSlots(); slot++) {
        ...
        if (behaviour.canInsertFromSide(...)) { ...; return true; }  // 成功即结束
    }
    return false;
}
```

**验证方法**：探针统计 `getBehaviour` 调用次数；行为回归：仓库向传送带输出的方向与优先级。

**收益估计**：8 槽循环内多达 9 次 map 查找 → 1 次（估计）。
**回归测试点**：漏斗朝向判定；积压物品输出顺序。

---

### 10. `ItemHelper.extract` 的谓词链会重启整个槽循环 — [Low]（**建议上游修**）

| 项 | 内容 |
| --- | --- |
| 位置 | `foundation/item/ItemHelper.java:192-243`（`extract` 的 do/while 与 `:232-236` 的谓词组合）、`content/logistics/behaviour/inventory/InvManipulationBehaviour.java:96-102`（`getFilterTest`） |
| 调用频次 | 每次抽取（漏斗、打包机、溜槽、青蛙港、抽取类行为） |
| 复杂度 / 成本 | 当"部分匹配 + 存在其它可用物品"时：`ItemStack blackListed = extracting.copy(); test = test.and(i -> ...); continue;` 会**重启整个 for(slot) 循环**，复杂度 O(槽数 × 物品种类)（估计）；每次重试分配一个 lambda + 一个 `ItemStack` 拷贝。`:202` 还对每个非空槽都做一次模拟抽取（即使不可能匹配） |
| 注入方式 | 可 Mixin，但**牵动所有抽取路径的语义**；保守做法是只把 `getFilterTest` 的组合缓存到 behaviour 上，不动 `extract` 的重启逻辑 |
| 目标成员 | `ItemHelper#extract` / `InvManipulationBehaviour#getFilterTest` |
| 需新增状态 | 若缓存谓词：`@Unique` 字段 + 过滤条件变化时失效 |
| 风险 | 高（改动抽取语义会波及大量机械） |
| 脆弱度 | 高：方法体是控制流密集的重启循环，上游重构概率不低 |
| 置信度 | 确定（`:232-234` 已核对） |

**改后（保守版，只消掉重复的谓词解析）**

```java
// InvManipulationBehaviour: 过滤条件不变时复用同一谓词
@Unique private Predicate<ItemStack> createperf$filterTest;
@Unique private Object createperf$filterKey;

// getFilterTest(): 先比对 key（过滤物品/NBT/配置版本），相同则直接返回缓存谓词
```

**验证方法**：探针统计单次抽取的 slot 迭代次数与谓词组合次数；行为回归：多物品混合库存下的抽取选择（必须与改前一致）。

**收益估计**：特定场景（部分匹配 + 多种物品）O(槽×种类) → O(槽)；常态场景收益很小（估计）。
**回归测试点**：漏斗/打包机/溜槽的抽取优先级；部分匹配时的黑名单行为。

---

### 11. 商店 / 购物清单 overlay 每客户端帧做 BE 查询与 36 槽扫描 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/equipment/blueprint/BlueprintOverlayRenderer.java:145`、`:164-184`；`content/logistics/tableCloth/TableClothBlockEntity.java:296-325`（`getStockLevelForTrade`）；`content/logistics/tableCloth/ShoppingListItem.java:67-82`（`bakeEntries`） |
| 调用频次 | 客户端每帧（瞄准商店/管理员/购物清单时） |
| 复杂度 / 成本 | `getStockLevelForTrade` 每次 `new InventorySummary` + `otherPurchases.bakeEntries(level, worldPosition)`（每笔购买一次 `level.getBlockEntity`，`InventorySummary.add` 内部线性扫）；`canAfford` 对每个条目扫玩家 36 个物品栏槽并用 `ItemStack.isSameItemSameComponents` 比较。即每帧 O(购买数) 次 BE 查询 + O(条目 × 36) 次组件比较 |
| 注入方式 | `@Inject(HEAD, cancellable)` 在 `getStockLevelForTrade` 加缓存（按购物清单身份 + 版本），或 `@Redirect` `bakeEntries` 调用 |
| 目标成员 | `TableClothBlockEntity#getStockLevelForTrade`；`BlueprintOverlayRenderer#displayClothShop` / `displayShoppingList` |
| 需新增状态 | `@Unique` 缓存（键：清单 stack 身份/伤害值 + 玩家库存版本；失效：清单变化或库存变化；并发：客户端主线程） |
| 风险 | 中（价格/库存显示滞后） |
| 脆弱度 | 中：`bakeEntries` 会随购物清单数据结构演进 |
| 置信度 | 高概率（`:162-176` 已核对：`bakedList.getSecond().getStacksByCount()` 逐条构造 + `:176` 起扫描 `player.getInventory().items`） |

**改后**

```java
// 每帧只做一次"缓存命中判断"，缓存键 = (清单 identity, 清单 damage/版本, 玩家库存 tick)
Couple<InventorySummary, InventorySummary> baked = createperf$bakedCache.get(key);
if (baked == null) {
    baked = otherPurchases.bakeEntries(level, worldPosition);
    createperf$bakedCache.put(key, baked);
}
```

**Mixin 骨架**

```java
@Mixin(value = TableClothBlockEntity.class, remap = false)
public abstract class TableClothBlockEntityMixin {
    @Unique private Object createperf$cacheKey;
    @Unique private Object createperf$cacheValue;

    @Inject(method = "getStockLevelForTrade", at = @At("HEAD"), cancellable = true)
    private void createperf$cache(..., CallbackInfoReturnable<...> cir) { /* 命中则直接返回 */ }
}
```

**验证方法**：探针统计每帧 `bakeEntries` 调用次数与 `getBlockEntity` 次数；行为回归：商店价格/库存数字、购买扣除、购物清单勾选状态。

**收益估计**：每帧 O(购买数) BE 查询 + O(条目×36) 比较 → 仅变化时（估计）。
**回归测试点**：跨维度/跨方块商店；清单物品数量变化；玩家库存变化后 affordability 更新。

---

## 交叉引用（本文不展开）

- **连锁传送带本体**（`ChainConveyorBlockEntity.tick` 的三层循环、`ChainConveyorRoutingTable.getExitFor` 在连接循环内重复求值、路由表 O(n²) 广告）→ [02-kinetics.md](02-kinetics.md)。
- **传送带漏斗**（`BeltFunnelInteractionHandler` 每物品每 tick 重算 amount/mode + 额外 `getBlockEntity`）→ [02-kinetics.md](02-kinetics.md)。注意其 helper 在 `content/logistics/funnel/FunnelBlockEntity.java:235-248,274-286`，但调用方属传送带路径。
- **`PackageItem.matchAddress`** 已在本文第 1 条覆盖；`GlobalTrainDisplayData`/`DestinationInstruction` 的同源正则问题在 [10-crosscutting-java.md](10-crosscutting-java.md) 的「正则重复编译」反模式下统一处理。

## 不可 Mixin / 建议提给上游的候选

| 条目 | 理由 |
| --- | --- |
| `ItemHelper.extract` 的重启式谓词链（第 10 条） | 语义牵动所有抽取路径（漏斗/打包机/溜槽/青蛙港），Mixin 改动的回归面过大；且"用谓词组合+重启循环表达黑名单"本身是控制流设计问题，适合上游重写为一次遍历 |
| `LogisticallyLinkedBehaviour.getAllPresent` 的拷贝+流+排序 | 与 `TickBasedCache.asMap()` 的强制 `cleanUp` 耦合（见 [09-compat-loadtime.md](09-compat-loadtime.md)）。单独在调用方加缓存会与既有 1-tick 汇总缓存语义打架，建议上游提供"无清理的只读视图" |
| `RecipeFinder` / `RecipeTrieFinder` 的无上限静态 Guava 缓存（与处理类相关） | 属于缓存生命周期设计问题，见 [11-upstream-issues.md](11-upstream-issues.md) |

## 快速收益清单（本领域，按性价比）

1. **`PackageItem.matchAddress` 正则缓存（第 1 条）** —— 单点注入，连锁传送带/青蛙港/路由表三处同时受益，且不改任何语义分支（只需逐字保留空串与 `*` 短路）。
2. **仓库比较器更新门控（第 3 条）** —— 每次物品进出从上百次方块/邻居操作降到通常 0，且实现只是"值未变即返回"。
3. **打包机满即停 + 槽位游标（第 2 条）** —— 把每包最多 9 轮全库存扫描压到约 1 轮。
4. **溜槽输入位掩码（第 6 条）** —— 去掉每 tick 的集合分配与 5 次邻块查询。
5. **面板监控门控（第 5 条）** —— 每面板每 tick 的汇总查找降为每 2 次 tick 一次；注意门控窗口不要超过 5 tick，否则玩家会感到显示滞后。

