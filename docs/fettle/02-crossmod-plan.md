# 02 · 跨 mod 优化方案（Fettle）

> 目标：**把多个 mod 重复计算的同一件事，算一次、大家共用。**
> 本文只写方案与依据，不含工程骨架（按约定暂不搭建）。所有 API 结论均已用本机反编译的 1.21.1 vanilla 源码 + NeoForge 21.1.227 编译产物核对过。

## 0. 先说结论：三个"合并"设想里只有一个成立

| 设想 | 实测结论 |
| --- | --- |
| 把多个 mod 的 BlockEntity ticker 合并成一个 list | ❌ **幻觉**。1.21.1 的 `Level.blockEntityTickers` 本来就是**每 Level 一个扁平 `List<TickingBlockEntity>`**，不存在"每 mod 一个 list"。而且 vanilla 是"注册即持有实例引用"，自己再维护一层按 `BlockPos` 反查的调度器会**更慢**（多一次 map 查找） |
| 多个 mod 共用一份 capability 解析缓存 | ❌ 大部分不成立。NeoForge 已有 `BlockCapabilityCache`；但解析结果由各 mod 自己的 provider 产生，我们**无法替它调 `invalidateCapabilities`**，也拿不住生命周期；且它只接受 `ServerLevel`。AE2 / Mekanism / RS2 **已经在用**它（源码已核实） |
| 把多个 mod 的小包合并成一个包 | ❌ 协议层堵死。`ClientboundCustomPayloadPacket` 是**单组件 record**，wire format 是 `[type id][body]`；NeoForge 的 `filterGameBundlePackets` 还会**静默丢弃** bundle 里未协商 channel 的第三方 payload。只能做"自己的包 + 按 tick 批量 flush"的调度/限流 |
| **按输入物品/流体给配方建统一索引** | ✅ **成立**。1.21.1 的 `RecipeManager` 只按 `RecipeType` 用 `Multimap` 分区，`getRecipeFor` 在**分区内**线性 `matches`；没有按输入物品的索引。而且 **FastSuite 在 1.21.1 NeoForge 上只有 6.x「并发匹配」**（Lead 用 Modrinth API 复核：1.21.1+neoforge 仅 `1.21.1-6.0.7` 一个版本），7.x「索引匹配」在此平台**是空白** |

另有一个**多 mod 共有、且 1.21.1 尚无底层解法**的点：**向邻居 inventory 传输物品**（满库存仍全槽扫描、`ItemStack` 创建/拷贝、每次尝试的包装对象分配）。证据：Modern Industrialization 连续 5 个插入优化 PR 与 issue #1173（作者原话：NeoForge **1.21.9+** 的新 transfer API 才会从底层缓解）、Mekanism #8439（维护者亲口："关掉 auto eject 问题就消失"）/#7084、RS2 #1206。

---

## 1. 四层设计（L1 优先做，L2 第二期，L3 顺带，L4 明确降级）

### L1 配方索引层（一号目标，只读加速）

**做法**：在 `RecipeManager` 的查找路径之上做"候选集预筛"，**最终仍交回 vanilla 的 `matches()` 判定** —— 语义零变更，这是它最大的卖点。

- 注入点：`RecipeManager#getRecipeFor`、`#getRecipesFor`、`#getAllRecipesFor`（`@WrapOperation`，不是 `@Redirect`）。
- 索引结构：按"输入物品/流体 → `RecipeHolder` 集合"建 map（或复用 Create `RecipeTrie`/`IntArrayTrie` 的思路做多输入 trie）。**粗筛只允许返回真超集**，否则必须放弃该配方进索引。
- 失效：服务端挂 `AddReloadListenerEvent` + `ResourceManagerReloadListener`；客户端挂 `RecipesUpdatedEvent`（在 `client.event` 包）。
- **必须处理的坑**（全部已实测/查证）：
  1. **`/reload` 会整体替换 `RecipeManager` 实例**（服务端），而客户端是 `replaceRecipes` 原地替换 → 语义不对称。**绝不能长期持有旧引用**，每次从 `level.getRecipeManager()` 重取或挂 reload 钩子重建。
  2. `RecipeManager.byType` 是 **private** → 需要 `@Accessor` 或直接从公开 API 重建索引。
  3. `getAllRecipesFor` 每次都是 `List.copyOf(...)`（实测确定）→ 它自身就是分配源，索引层可顺带消除。
  4. `Ingredient` 不一定等价于物品集合（`SizedFluidIngredient`、tag、自定义 `Ingredient`、`DataComponentPredicate`）→ 只按 `Ingredient.items()` 提取会漏，必须为"不可安全索引"的配方保留回退路径。
  5. `RecipeHolder` 的 `equals/hashCode` **只看 id** → 不能当"内容键"。
  6. `isSpecial()`/运行时生成的动态配方**不得进静态索引**。
  7. vanilla 自带 `RecipeManager.CachedCheck`（只缓存最近一条配方）——可作为"轻量回退"，也是我们对照的基线之一。
- **与 FastSuite 6.x 的关系**：它用**并发匹配**，我们用**索引预筛**，两者是**叠加关系**（我们缩小候选集，它并发匹配更少的配方）。但必须用**三组对照实测**（只我们 / 只它 / 两个都装）决定配置默认值，而不是拍脑袋说"我们更强"。

### L2 传输加速层（第二期，定点放大）

**共享部分**（对愿意接入的路径通用）：
- "满库存即跳过"的快路径（避免对每个槽做模拟抽取）；
- 复用 `ItemStack`/缓冲对象，避免每次尝试都创建/拷贝；
- 批量插入（一次性枚举可接收槽位，而不是逐槽试插）。

**必须逐 mod 挂 hook**（因为各家 handler 语义不同，且大多有自研 tick 循环）：
| 目标 | 切入点（候选热点） |
| --- | --- |
| Mekanism | auto-eject 传输路径（`TileComponentEjector`；#8439/#7084 已证实是热点） |
| Modern Industrialization | 物品管道/机器插入（5 个官方 PR 已指向；注意它是**尖刺**而非总量） |
| Create | 漏斗/传送带交互（`BeltFunnelInteractionHandler`、`FunnelBlockEntity`） |
| AE2 | import/export bus（注意：它已自适应降频，收益面小） |
| RS2 / IE | 节点 tick / 流体出口（证据较弱，放最后） |

### L3 诊断与调度层（安全、零冲突、顺带产出基准数据）

- 挂 `Level.tickBlockEntities` 做**计数与耗时归因**（按类型/按 mod 统计），**不改任何行为**。
- 同时它就是你基准数据的稳定来源（不依赖 Spark 可用性）。
- ⚠️ 绝不自己维护第二份 ticker list 去替代 vanilla（见 §0 第一条）。

### L4 网络调度/限流（明确降级）

- 只能做**自己 payload** 的按 tick 批量 `sendBundled`/flush；**不能**合并别人的包。
- 价值是减少 flush 次数与同 tick 多包抖动，属于"调度优化"而非"合并优化"。

---

## 2. 冲突矩阵（Lead 用 Modrinth API 复核过可用性）

| Mod | 1.21.1 NeoForge | 它优化了什么 | 与我们的冲突 |
| --- | --- | --- | --- |
| **Lithium** | ✅（官方，1.37 亿下载） | BE tick 循环、BE sleeping、实体 tick、POI、集合 | 🔴 **最高**：它的 `world.block_entity_ticking.*` 正挂在 `Level.tickBlockEntities` 与 `LevelChunk$BoundTickingBlockEntity.tick`；它用 `@Redirect`（独占，撞同点即硬冲突） |
| **FastSuite** | ✅（仅 `1.21.1-6.0.7`） | 配方匹配（**并发匹配**） | 🔴 高：同点位；但我们的索引是**叠加**关系（见 L1） |
| **ModernFix** | ✅ | 启动、动态资源、DFU、内存 | 🟡 低但 mixin 数量极大，`@Redirect` 易撞 |
| **FerriteCore** | ✅ | blockstate 缓存、NBT 去重 | 🟡 低（动 BlockState 缓存会撞） |
| **Sodium / Embeddium** | ✅ | 客户端渲染 | 🟢 无重叠 |
| **Clumps** | ✅ | XP orb 合并 | 🟢 无重叠 |
| C2ME / Krypton | ❌ 无 NeoForge 版 | 区块/网络 | ⚪ 不存在 → 无冲突（网络调度层也不撞它） |
| Radium / Canary | ✅ / Forge | Lithium 分支 | 🔴 与 Lithium 同类，条件注入时要**同时查多个 modid** |

## 3. 注入策略（共存优先，但不做"让位即无用"的装饰品）

1. **`@WrapOperation` 优先于 `@Redirect`**：后者独占，撞同点位就硬冲突（Lithium 正是 `@Redirect`）；前者可叠加组合，也是我们能和 Lithium 共存的技术前提。
2. **条件注入**：`IMixinConfigPlugin.shouldApplyMixin` + `ModList.get().isLoaded(...)`，且**必须同时检查** `lithium`/`radium`/`canary`/`fastsuite`（不能只写一个 id）。
3. **默认 `require = 0`**：注入点找不到时静默跳过而不是崩游戏（对"锦上添花"的优化这是基本礼貌）。
4. **逐项开关**：每个优化项独立可关（学 Lithium 的做法），冲突时玩家能自救。
5. **"更强才接管"的量化规则**：
   - 对方**未覆盖**该路径 → 我们直接启用；
   - 对方**覆盖**该路径 → 只有在**三组对照实测**（只我们 / 只对方 / 两个都装）中我们单独收益更高时，才默认接管；否则默认共存模式，并提供"强制接管"配置。
   - 基准协议见 [01-benchmark-and-charts.md](01-benchmark-and-charts.md)。
6. **许可证与代码来源**：只做**运行时 Mixin 注入**，不复制、不重分发对方代码 —— 这条界线必须守住（AE2 源代码头是 LGPL，其余协议需逐项确认）。若借鉴 Create 的 trie 算法，只借鉴思路、自己实现。

## 4. 目标 mod 与预期（不含 TiC3，待确认）

| 目标 | 我们的切入点 | 预期收益面 |
| --- | --- | --- |
| **Create**（起点） | L1 配方索引（盆/混合器/压机等走 `RecipeManager` 的路径）+ 已有分析里的一批热点 | 中（Create 自研 trie 已缓存部分路径） |
| **AE2** | L1（合成计算/终端配方刷新走 `RecipeManager`）；L3 诊断 | 中（它已有自适应降频，通用层打不到核心成本） |
| **Mekanism** | L2 auto-eject 传输 | 中高（有维护者诊断背书） |
| **RS2 / MI / IE** | 第二期 | 低-中（证据较弱或已自我优化） |

> **期望管理**：这些 mod 的自带优化都很厚，且大量使用自研 tick 循环（AE2 走 `ServerTickEvent`、Mekanism 有 `CommonWorldTickHandler`、IE 走组件 tick）→ **"改原版 tick 分派"的通用优化打不到它们的核心成本**。所以 L1 的定位是"跨 mod 自动生效的中等收益"，L2 才是"定点放大"。

## 5. 待定与待实测

| # | 项 | 影响 |
| --- | --- | --- |
| P1 | **TiC3 到底是哪个版本/哪个移植** | Modrinth 显示 Tinkers' Construct **无 1.21.1 官方版**（最高 1.20.1），1.21.1 上只有非官方移植。若确认用移植版，建议**不进首批**（非官方移植的类名/结构会随作者改动，mixin 极易失效） |
| P2 | 目标 mod 是否都走**统一**的 `RecipeManager` 入口 | 决定 L1 能否"自动"覆盖它们；不走的（自研缓存）必须单列 |
| P3 | 与 Lithium / FastSuite 的**实际** mixin apply 冲突 | 需装齐实测：观察 mixin 冲突日志 + 行为差异 |
| P4 | `BUNDLE_SIZE_LIMIT(4096)` 是否对 `ClientboundBundlePacket` 强制生效 | 影响 L4（二期） |

## 6. 落地顺序（等你说开工）

1. **Batch 0**：基准管线跑通（Java 21 → 构建 → 固定存档 → Spark/JFR → 5 张图），拿到**未优化基线**。
2. **Batch 1**：L1 配方索引（只读预筛 + 语义零变更 + reload 失效），用三组对照验证；
3. **Batch 2**：L3 诊断层（兼作基准数据源）；
4. **Batch 3**：L2 传输加速（先 Mekanism，再 MI/Create）；
5. **Batch 4**：L4 网络调度（如仍有需要）。
