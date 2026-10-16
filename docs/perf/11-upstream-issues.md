# 11 · 不宜用 Mixin 修复 / 建议提给上游的清单

> 判据：**能注入 ≠ 该注入**。以下条目要么无法在不重写大量逻辑的前提下注入，要么用 Mixin 改会与 Create 后续版本持续打架，
> 要么本质是上游的设计/健壮性问题。文档收录它们的目的是：让你在施工时**不要**在这些点上浪费风险预算。
> 行号基于本仓库 Create 6.0.10 源码。

## A. 顺带发现的真实 Bug（与性能无关，但值得上报/自行规避）

| # | 位置 | 问题 | 影响 |
| --- | --- | --- | --- |
| A1 | `content/contraptions/minecart/CouplingHandler.java:57,60,63` | 遍历 `cartsWithCoupling` 时遇到 `controller == null` / 非 leading / 不完整 coupling 用的是 `return` 而非 `continue` | **遍历被提前终止**，同一次调用中后续所有 coupling 都被静默跳过 → 矿车连接链在客户端部分不渲染 |
| A2 | `content/trains/entity/CarriageCouplingRenderer.java:42` | 该处读的是 `carriage.getDimensional(level)`，从上下文看本意应为 `carriage2` | 跨维度判定取错对象；两个车厢处于不同维度时行为可疑 |
| A3 | `foundation/utility/TickBasedCache.java:33,52,84` | 值存 `ConcurrentHashMap`，但配套的 `timestamps` 却是普通 `HashMap` | 该缓存**并非真正线程安全**（并发读写下 timestamps 可能损坏）；标注为"并发缓存"具有误导性 |
| A4 | `content/schematics/SchematicItem.java:126` | `NbtAccounter.create(0x20000000L)`（约 512MB，且无 component 配额）用于解析上传的蓝图文件 | 解压炸弹 / 内存放大攻击面，属于安全加固项而非性能项 |

> A1 是**纯 bug**：即使你不做任何性能优化，也值得单独提 issue —— 用 Mixin 把它改成 `continue` 是低风险顺手修复。

## B. Mixin 不划算（收益存在，但注入成本/风险高于收益）

| # | 位置 | 为什么不做 |
| --- | --- | --- |
| B1 | `content/decoration/palettes/PalettesVariantEntry.java:34,69`（`AllPaletteBlocks` 静态块触发） | 7 石材×6 图案×（方块+物品+3 partial）≈300+ Registrate builder 链，耗时约 100ms 量级但**散布在构造函数与逐元素 builder 调用里**。要优化只能 `@Overwrite` 构造函数或直接跳过整个调色板注册（后者会缺内容）。属于"上游可做成 datagen/延迟注册"的架构问题 |
| B2 | `compat/trainmap/TrainMapRenderer.java:148` `getOrCreateAt` | `Couple<Integer>` 键贯穿 `setPixel/getPixel/alphaAt/blendPixel` 与 `TrainMapManager.renderPhase`。改成 `long` 键 + fastutil `Long2ObjectOpenHashMap` 需要同时替换私有字段 `maps` 并接管 `getMaps()/render/finishDrawing` 的迭代，类型必须整体兼容 → 实际等于重写这块地图渲染。风险中高 |
| B3 | `Create.java:214` `registrate()` 的 `StackWalker` 守卫 | 技术上一条 `@Inject(HEAD, cancellable)` 就能删掉守卫，但那是 Create **刻意设置**的防滥用保护（"Other mods are not permitted to use create's registrate instance"）。删掉它等于改设计契约，不建议 |
| B4 | `impl/registry/SimpleRegistryImpl.java:56,77` `synchronized get` | 收益只是去掉一次无竞争锁；但"注册完成后不可变 + provider 值缓存"的语义属于上游职责。Mixin 只做 `@Overwrite` get() 会把缓存失效语义一并接管，容易在后续版本漂移 |
| B5 | `foundation/recipe/RecipeFinder.java:27`、`foundation/recipe/trie/RecipeTrieFinder.java:19` | 静态 Guava 缓存无 `maximumSize`、世界卸载不清理。要修需要一个"世界卸载"钩子来 invalidate；用 Mixin 硬插会在多人/多存档切换时引入难以复现的串档 bug。**建议上游加 `expireAfterAccess` 或按 level 维度分键** |
| B6 | `foundation/item/ItemHelper.java:192-243` `extract` | 部分匹配时复制栈 + `test = test.and(...)` 后**重启整个槽循环**，复杂度 O(槽×物品种类)，且把谓词组合当成控制流。重写会触及 Create 所有抽取路径（漏斗/打包机/溜槽/青蛙港）的语义，风险远大于收益 |

## C. 属于"上游架构问题"的性能模式

| # | 位置 | 问题本质 |
| --- | --- | --- |
| C1 | `foundation/utility/ServerSpeedProvider.java:24,43`；`compat/trainmap/TrainMapManager.java:64-65,98`；`foundation/events/ClientEvents.java:273` | 在**每 tick / 每帧循环里**读 `ModConfigSpec.ConfigValue.get()`（含类型检查与自动拆箱）。单点很小，但这是一个系统性模式：配置应缓存成基本类型字段并在 `AllConfigs.onReload` 刷新。上游改一处即可全文受益 |
| C2 | `content/fluids/PipeConnection.java:212-236` + `content/fluids/FluidTransportBehaviour.java:206-213` | "压力变化 → 立刻 `sendData()`"，导致一次泵压传播 = 网络规模次 NBT 序列化 + 方块实体包。真正的正解是**脏标记 + tick 末统一发送**的数据同步模型，属于上游网络层设计 |
| C3 | `content/kinetics/KineticNetwork.java:133-155` | 用 `level.getBlockEntity(pos) != be` 做"成员是否仍存活"的判定，本质是**缺少卸载/移除状态位**（已有 `unloadedMembers` 计数却没用于成员清理）。上游补一个 `removed/loaded` 标志即可，Mixin 只能靠重写整个方法 |
| C4 | `compat/trainmap/TrainMapSync.java:167-181` | 只要有**任一**玩家开着地图，就把**全服所有列车**序列化一遍再按玩家逐个发送；没有按维度/距离过滤。这是同步模型问题，Mixin 改包体结构要求客户端与服务端 codec 同时改（而你只做单侧 mod 会有兼容面） |
| C5 | `foundation/utility/TickBasedCache.java:122,140` | `size()/asMap()` 内部强制 `cleanUp()`（O(entries) + 临时 HashSet + 三趟 lambda）。缓存 API 的设计问题：度量操作不应付出全表代价 |

## D. 明确不要碰的地方（避免浪费精力）

- `AllConfigs` 已用 `EnumMap`；`Mods` 的 24 次 `getModFileById` 与 `RemapHelper` 的上百次 `put` 都是**一次性类初始化**。
- `CommonEvents.addReloadListeners` 注册的 3 个监听器只是清缓存。
- `IBE.getTicker` 每次调用新建 `SmartBlockEntityTicker`，但只在方块实体加载时发生，不是每 tick。
- 全树反射几乎为零：`Class.forName` / `getDeclaredMethod` 在 `com.simibubi.create` 下只有 gametest 的 `CreateTestFunction.java:54`；三处 `Pattern.compile`（`RuntimeDataGenerator.java:44-48`、`PojavChecker.java:25`）**已经**正确提升为 `static final`。
  → 因此"Create 用反射拖慢性能"这个直觉在本项目里**不成立**；真正的问题是**每 tick/每帧的集合扫描与分配**。

## E. Contraptions / Trains 领域（由 `03-contraptions-trains.md` 汇总；Lead 已核对，与 A–D 节无重复条目）

> 来源：`docs/perf/03-contraptions-trains.md` 的 15 条 finding。这些条目**不建议**在独立 mixin mod 里做，或需要上游配合。

| # | 位置 | 为什么不做 / 建议上游怎么做 |
| --- | --- | --- |
| E1 | `content/trains/entity/Train.java:657` | 每 tick 读 `AllConfigs.server().trains.maxAssemblyLength.get()`。**不能**为性能缓存到静态字段（手册 §7：用户改配置必须立即生效）。**建议上游**：提供带版本号的配置快照，或把值缓存到 `Train` 实例并在 `ModConfigEvent.Reloading` 时失效 |
| E2 | `content/trains/GlobalRailwayManager.java:289-295` | `sided` 用 `MutableObject` + `executeOnClientOnly(() -> () -> clientManager(m))` 两层 lambda，换来「专用服务端不加载客户端类」。这是**有意的设计**；mixin 要消除分配就得自己复制「`@OnlyIn(Dist.CLIENT)` 隔离 + 延迟加载」语义，风险大于收益。**建议上游**：在 catnip 的 platform 层提供零分配实现 |
| E3 | `content/trains/graph/EdgePointStorage.java:41-46` | `values()` 用 `stream().map().toList()` 拷整个集合（几十到几百元素），但拷贝同时提供了对 `TrackGraphSync` 这类「遍历中可能改动」场景的安全性。**建议上游**：拆成 `valuesSnapshot()` 与 `valuesView()` 两个方法让调用方自选 |
| E4 | `content/trains/entity/CarriageSyncData.java:305,363` | `reachedVia` 是 **write-only 死存储**（全仓库仅 `put`，已 grep 确认无读取点），每条入队边还配一个 `Pair`。**建议上游**：直接删掉这个 `IdentityHashMap` 与对应的 `Pair` 分配（零风险纯收益，不需要 mixin） |
| E5 | `content/trains/entity/TravellingPoint.java:221` | `travel` 每次调用 `new ArrayList<>()` 作为候选缓冲区。**建议上游**：把它提升为 `TravellingPoint` 的实例字段（或 fastutil 对象数组），比 mixin 改写整个 `travel` 安全得多 |
| E6 | `content/contraptions/ContraptionCollider.java:582-608` | 扫描盒按**被检测实体包围盒的宽高比**膨胀：1×20×1 的竖直装置会扫数千格而命中不到 20 格。**建议上游**：改成「遍历 `contraption.blocks` + AABB 相交」，并从源头保证 `simplifiedEntityColliders` 永远非 null |
| E7 | `content/trains/entity/CarriageContraption.java:252-258` | `getSimplifiedEntityColliders()` 在传送门中返回 `null`，这个 `@Nullable` 契约迫使 `ContraptionCollider` 保留整条降级路径（见 E6）。**建议上游**：总是返回非 null（传送门中返回裁剪后的碰撞列表） |
| E8 | `content/contraptions/AbstractContraptionEntity.java:374-376` | `entrySet().removeIf(...)` 每 tick 分配一个 `IdentityHashMap` 迭代器，**空 map 时也分配**；mixin 修它要复制 36 行 `tick()`。**建议上游**：改成 `if (!collidingEntities.isEmpty())` 守卫的迭代器循环（3 行） |
| E9 | `content/contraptions/actors/harvester/HarvesterMovementBehaviour.java:95` | `ItemHelper.sameItem(stack, new ItemStack(state.getBlock()))` 每个掉落物构造一个完整 `ItemStack`，而 `sameItem` 只用了 `getItem()`。**建议上游**：改成 `stack.is(block.asItem())`，或新增 `ItemHelper.sameBlockItem(ItemStack, Block)` |
| E10 | `content/contraptions/MountedStorageManager.java:190-216` | 客户端根本没有「发同步包」的能力（`sendToClientsTrackingEntity` 客户端 no-op），却每 8 tick 走完整路径并分配两个 `HashMap`。**建议上游**：`tick` 开头加 `if (entity.level().isClientSide) return;`（需先确认客户端 `syncCooldown` 语义无人依赖） |
| E11 | `content/contraptions/minecart/capability/CapabilityMinecartController.java:169-176` | `containsKey` + `get` 双查找，纯上游 3 行可改（`return carts == null ? null : carts.get(cartId);`） |
| E12 | `content/contraptions/actors/roller/RollerMovementBehaviour.java:106-114` | `canBreak` 每个破坏进度周期做 6 次 `world.getBlockState` 邻域查询；mixin 单独加缓存无法可靠失效（方块可被玩家改动）。**建议上游**：若要做，就在上游引入「每 tick 一次的 `BlockTags.PORTALS` 邻域缓存」 |
| E13 | `src/main/resources/create.mixins.json`（对照用） | **给施工者的提示**：Create 自身的 mixin 只注入**原版类**（`Entity`/`Player`/`ItemStack`/`Block`/… + 一批 `@Accessor`），**没有任何针对 Create 业务类的 mixin**。因此对 `TrackGraph`/`TravellingPoint`/`Train`/`ContraptionCollider` 等的注入不存在与 Create 抢点的冲突 |

> ⚠️ 关于第 9 条 finding（`CarriageContraptionEntity:261` 的 `Optional`）：结论「不会强制同步脏标记」是用 **MC 1.20.1** 的 `SynchedEntityData` 字节码核对的（本仓库无 1.21.1 反编译源）。**要在 1.21.1 上实测确认**后再决定是否需要该修复。
