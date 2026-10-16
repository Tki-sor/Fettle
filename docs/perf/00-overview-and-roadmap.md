# 00 · 总览与实施路线图

> 目标：**一个独立的 NeoForge 1.21.1 Mixin mod**，在加载期优化 Create 6.0.10（MC 1.21.1 / NeoForge 21.1.219）。
> 约束：不改 Create 源码；收益优先级 = 服务端 MSPT 为主、GC/内存为辅；修复优先纯 Java。
> 公共写法约定见 [01-mixin-playbook.md](01-mixin-playbook.md)；每条发现的完整字段见各领域文件。

## 1. 三批路线（按「注入风险」从低到高）

### Batch A — 零状态、单点注入（当天可上，风险最低）

判据：不新增字段、不改方法结构，只替换一次调用/一个表达式或提前返回。

| # | 目标 | 文件:行 | 注入方式 | 预期收益（估计） | 回归测试点 |
| --- | --- | --- | --- | --- | --- |
| A1 | `TrackGraph.getConnectionsFrom` 命中路径仍 `new HashMap` | `content/trains/graph/TrackGraph.java:374` | `@Inject(HEAD, cancellable)` 直接返回 `connectionsByNode.get(node)` | 每 tick O(4×车厢×列车) 次 HashMap 分配归零 | 列车寻路/信号行为不变；`connectionsFrom == null` 分支仍可达 |
| A2 | 流体图 `ArrayList.remove(0)` 出队 → O(n²) | `content/fluids/pump/PumpBlockEntity.java:146`、`fluids/FluidPropagator.java:58,106`、`fluids/transfer/FluidManipulationBehaviour.java:159` | `@ModifyExpressionValue` 把 frontier 换成 `ArrayDeque` / `@Overwrite` 三处局部 | 泵/管道/软管搜索从 O(n²) 降到 O(n) | 泵范围、软管搜索上限 1024、网络重置行为 |
| A3 | 压力更新对每根管道 `sendData()` | `content/fluids/FluidTransportBehaviour.java:212` | `@Inject` 加脏标记，改到 tick 末统一发；或 `@Overwrite` `addPressure` | 一次泵压传播 = 网络规模次完整 NBT 序列化 → 每管道每 tick ≤1 次 | 客户端管道压力显示、流量视觉、存档读写 |
| A4 | 每连接每 tick 新建 `Predicate` lambda | `content/fluids/FluidTransportBehaviour.java:130-135` | `@WrapOperation` / `@Unique` 复用谓词 | 每管道每 tick 最多 6 个 lambda → 0 | 抽取规则（canPullFluidFrom）不变 |
| A5 | 风箱/风扇每 30 tick 无条件 rebuild + sendData | `content/kinetics/fan/EncasedFanBlockEntity.java:135` | `@Inject` 在 rebuild 后比较结果再 sendData；或 `@Overwrite` | 每风扇每 30 tick 一次三方块遍历 + 无条件 NBT 广播 → 仅变化时 | 气流范围/粒子/加工判定；`blockInFrontChanged` 仍触发 |
| A6 | 转速修正重复计算 4 次/边 | `content/kinetics/RotationPropagator.java:397,226` | `@Unique` 每轮缓存（或在 findConnectedNeighbour 内一次性算） | 每次传动网络变更节省 ~75% 的 getBlockState/Axis 扫描 | 齿轮大小/轴朝向的转速比、大齿轮换向 |
| A7 | `Axis.values()` 数组克隆 | `content/kinetics/RotationPropagator.java:59,145,178` | `@ModifyExpressionValue` 换成静态 `Axis[]` | 每条边最多 12 次数组分配 → 0 | 无行为变化（纯等价替换） |
| A8 | `BeltBlock.hasShaftTowards` 的 Optional + BE 查找 | `content/kinetics/belt/BeltBlock.java:122` | `@Overwrite`（3 行） | 连接判定每次省 1 次 `getBlockEntity` + 1 个 Optional | 传送带皮带轮连接判定 |
| A9 | 合成器每 tick 两次 `hasNeighborSignal` | `content/kinetics/crafter/MechanicalCrafterBlockEntity.java:264` | `@ModifyExpressionValue` / `@Inject` 取一次局部量 | 5×5 合成器 ~150 次邻居查询/tick → ~75 | 红石锁定/触发时机 |
| A10 | 应力表无条件 `sendData` | `content/kinetics/gauge/StressGaugeBlockEntity.java:81` | `@Inject` 比较变化后再发 | 每次网络数值变化少一次方块实体包/表 | 表盘读数与颜色 |
| A11 | 日志字符串拼接 | `ServerSchematicLoader.java:71,248`、`TorquePropagator.java:18,23`、`RedstoneLinkNetworkHandler.java:70,75` | `@ModifyExpressionValue` 改参数化日志 / 直接 `@Overwrite` 日志行 | 半热路径无谓字符串构造 | 日志内容与等级 |
| A12 | `ItemVaultBlockEntity` 每次内容变化全体积邻居广播 | `content/logistics/vault/ItemVaultBlockEntity.java:177-194` | `@Inject` 把 `updateNeighbourForOutputSignal` 移到亮度变化之后 + 只处理表面格 | 每次物品进出省最多 81 次 BlockState 解析 + 邻居广播 | 比较器输出、多方块重组 |
| A13 | 溜槽每 tick 5 次邻块查询 + LinkedList | `content/logistics/chute/ChuteBlockEntity.java:708-717` | `@Overwrite`（小方法）+ 缓存 | 每溜槽每 tick 5 次 BE 查询/1 次分配 → 变化时才算 | 溜槽输入方向、物品上行 |
| A14 | 仓库行为循环内重复解析 behaviour | `content/logistics/depot/DepotBehaviour.java:169-174` | `@Inject` 提到循环外 | 8 槽循环内 9 次 map 查找 → 1 次 | 仓库向传送带输出的优先级 |
| A15 | `PackageItem.matchAddress` 每次 `String.matches` 编译正则 | `content/logistics/box/PackageItem.java:191` | `@Inject(HEAD, cancellable)` 走 Pattern 缓存 | 连锁传送带每 tick 数百次 `Pattern.compile` → 缓存命中 | 通配符/大小写/空地址语义必须逐字保持（高风险点：正则语义不能变） |

### Batch B — 需要 `@Unique` 状态（缓存/复用，中等风险）

判据：修复需要新增字段并明确失效时机。**每个字段都必须写清作用域、失效条件、并发侧**（手册 §4）。

| # | 目标 | 文件:行 | 状态设计 | 预期收益（估计） |
| --- | --- | --- | --- | --- |
| B1 | `TravellingPoint.travel` 每次 new `ArrayList` + 边点 `Pair/Couple` | `content/trains/entity/TravellingPoint.java:221,375` | `@Unique` 复用列表（每次 clear）+ 只在通过范围判定后构造 Couple | 每车厢每 tick 4 次列表分配 + 边点级对象 → 接近 0 |
| B2 | `MountedStorageManager.tick` 每装置每 tick 两个 HashMap | `content/contraptions/MountedStorageManager.java:196` | `@Unique` 惰性分配 + `anyDirty` 短路 | 每装置每 tick 2 次 map 分配 → 无脏数据时 0 |
| B3 | 列车找出撞车 O(T²·C) + 内部 Vec3/Math.pow | `content/trains/entity/Train.java:655-730` | `@Unique` 缓存 `maxDistanceSqr`、按 graph 索引列车 | 20 列车×5 车厢 ≈ 400 Vec3 + 2000 距离计算/tick → 大幅下降 |
| B4 | 车厢转向架循环内重建信号监听 lambda | `content/trains/entity/Carriage.java:192-212` | `@Unique` 每 Train/TravellingPoint 缓存监听实例（无捕获状态的） | 每车厢每 tick ~16 个 lambda → 0 |
| B5 | `collidingEntities` 每 tick 装箱 + lambda 清理 | `content/contraptions/AbstractContraptionEntity.java:374` | `@Unique` 显式 Iterator + 基本类型计数 | 每装置每 tick Iterator/lambda/装箱 → 无 |
| B6 | 传送带 `LinkedList` 的 `removeAll/contains` O(n·m) | `content/kinetics/belt/transport/BeltInventory.java:67-72,421,460` | `@Unique` 用 `ArrayList`/`ObjectOpenHashSet` 替换（需改字段类型 → 用 `@Redirect` 到 `@Unique` 容器或 `@Overwrite`） | 每 tick 每条带 O(n·m) 扫描 → O(n) |
| B7 | 传送带每 tick 对每个物品 `ItemStack.copy()` | `.../BeltInventory.java:462` | 先判距离再 copy（`@ModifyExpressionValue`/`@Inject` 调整顺序） | 风扇路径上每物品每 tick 一次 ItemStack 复制 → 仅命中时 |
| B8 | 工厂面板每 tick 重算库存/承诺 | `content/logistics/factoryBoard/FactoryPanelBehaviour.java:294-313` | `@Unique` 版本号/惰性 tick 门控 | 每面板每 tick 数次汇总查找 → 仅变化时 |
| B9 | 打包机 9 次整库存扫描、箱满不退出 | `content/logistics/packager/PackagerBlockEntity.java:431-455` | `@Unique` 槽位游标 + 箱满 break | 每包最多 9×库存 次模拟抽取 → ~1× |
| B10 | 青蛙港 lazyTick 多轮整库存扫描 + 每槽正则 | `content/logistics/packagePort/frogport/FrogportBlockEntity.java:124-135,313-326` | 复用 filter/Pattern + 失败槽游标 | 每 10 tick 的 O(槽×2) 正则编译 → 0 |
| B11 | 传送带漏斗每物品每 tick 重算 amount/mode | `content/kinetics/belt/transport/BeltFunnelInteractionHandler.java:76-99` | `@Unique` 每 tick/每漏斗缓存 + 缓存下方带段 BE | 每物品每 tick 2 次额外 `getBlockEntity` → 每漏斗每 tick 1 次 |
| B12 | 股票终端快照构建 O(类别×物品) 且 ArrayList 移位 | `content/logistics/stockTicker/StockTickerBlockEntity.java:211-224` | 单遍分类（`@Overwrite` 更好） | 客户端收包时的 O(categories×items²) 搬移 → O(items) |
| B13 | `CardSyncData.getDistanceTo` 每次建 3 个集合 + 分配型比较器 | `content/trains/entity/CarriageSyncData.java:299-369` | `@Unique` 复用集合 + fastutil 堆（见 overwrites/） | 客户端每次同步每轮点一次大分配 → 复用 |
| B14 | 风扇 `AirCurrent.rebuild` 三遍遍历同一气流 | `content/kinetics/fan/AirCurrent.java:159,189-206,229,311-334` | 合并为一遍 + 每位置缓存加工类型 | 每 rebuild 3 遍 `FanProcessingType.getAt`（各 4 类型判定）→ 1 遍 |

### Batch C — `@Overwrite` 大改造（高风险，逐条独立评审）

判据：方法体整体重写收益远大于局部注入；必须在 `overwrites/` 给出完整可粘贴方法体。**注意不要 Overwrite 已被 Create 自身 mixin 注入的方法。**

| # | 目标 | 文件 | 风险 |
| --- | --- | --- | --- |
| C1 | `ContractCollider.getPotentiallyCollidedShapes` 按宽高比 inflate 扫描盒 | `content/contraptions/ContraptionCollider.java:582-608` | 高：碰撞行为直接可见，需与简化碰撞体一致 |
| C2 | `CarriageSyncData.getDistanceTo` 客户端 Dijkstra | `content/trains/entity/CarriageSyncData.java:299-369` | 高：客户端预测与服务器同步必须一致 |
| C3 | `FluidTankBlockEntity.onFluidStackChanged` 全体积扫描 | `content/fluids/tank/FluidTankBlockEntity.java:177-194` | 中高：亮度/比较器语义 |
| C4 | `FluidNetwork.tick` 双遍防御拷贝 + 装箱累加器 | `content/fluids/FluidNetwork.java:185,219,247` | 高：流体守恒与两端模拟/执行语义 |
| C5 | `FluidManipulationBehaviour.search` 每 tick 1024 次 O(n) 移位 | `content/fluids/transfer/FluidManipulationBehaviour.java:159` | 中：软管搜索上限语义 |
| C6 | `KineticNetwork.calculateStress/Capacity` 每成员存活探测 | `content/kinetics/KineticNetwork.java:133-155` | 中高：网络成员清理时机易错 |
| C7 | `Train.findCollidingTrain` O(T²·C) | `content/trains/entity/Train.java:655-730` | 高：列车碰撞与脱轨判定 |
| C8 | `TravellingPoint.travel` | `content/trains/entity/TravellingPoint.java:221` | 高：列车运动核心 |

（客户端渲染 / 处理与红石 / compat 与加载期的对应批次，待其领域文件完成后补入本表。）

## 2. 建议的验收流程

1. **基线**：同一存档、同一装置规模，`/spark tps` + `/spark profiler start --timeout 60`，重复 3 次取中位数。
2. **注入确认**：`-Dmixin.debug.verbose=true -Dmixin.debug.export=true`，检查 `run/.mixin.out/` 导出的类中你的注入是否存在。
3. **探针**：每个 Batch A/B 条目在 mixin 内加静态计数器 + `System.nanoTime` 累加，每 100 tick 打一次日志；对比开关 mixin 两组数据。
4. **分配观测**：JFR `ObjectAllocationSample` 或 `/spark profiler start --alloc`，重点看 `HashMap`/`ArrayList`/`Pair`/`Couple`/`ItemStack` 的分配量与 `Pattern.compile` 栈帧。
5. **回归**：每个条目的「回归测试点」列必须逐项手动过一遍；行为变更（少发包、少广播）必须显式记录。

## 2.5 后续补充（来自第二轮调查，未计入上面的 A/B/C 编号）

> 本节是报告定稿前追加的、**性价比最高的一批条目**，它们来自 fluids / processing / compat 三个领域的调查。建议与 Batch A 一起做。

| # | 目标 | 文件:行 | 注入方式 | 预期收益（估计） |
| --- | --- | --- | --- | --- |
| D1 | 喷口每 tick 重跑整条配方表（SequencedAssembly + Filling，最多 4 次全表扫描） | `content/fluids/spout/SpoutBlockEntity.java:109-111` | `@Redirect` 两个静态 helper + `@Unique` 缓存 | 生产线稳态下每物品每 tick 2-4 次全配方扫描 → 缓存命中后 0 |
| D2 | 物品沥水器 15 tick 内每 tick 重扫配方并重算掉落 | `content/fluids/drain/ItemDrainBlockEntity.java:241` | `@Redirect`/`@Inject(HEAD)` + 倒计时内缓存 | 每物品约 30 次全表扫描 + 30 次分配 → 约 2 次 |
| D3 | 矿车控制器：每区块卸载遍历全部矿车；每 tick 全表扫描 + 分配列表 | `content/contraptions/minecart/capability/CapabilityMinecartController.java:89,113` | `@Inject(HEAD)` + ChunkPos 索引 | O(卸载数×矿车数) → O(区块内矿车)；每 tick 分配归零 |
| D4 | 数据包生成对每个注册物品跑 3 次正则 | `foundation/data/RuntimeDataGenerator.java:57` | `@Inject(HEAD)` 廉价 contains 前置过滤 | 大整合包省掉约 95% 的物品级正则/资源路径工作 |
| D5 | 红石链接每次信号变化两遍遍历频段组 | `content/redstone/link/RedstoneLinkNetworkHandler.java:81,101` | `@Overwrite` 两个小方法 + 功率变化早退 | 大频段组每次边沿 O(2n) → O(n) 且只通知变化者 |
| D6 | 蓝图哈希每次部署/旋转后重算整份组件补丁并触发重载 | `content/schematics/SchematicInstances.java:76-82` | `@Inject` + revision 组件 | 每次操作从"遍历全部组件 + 缓存重键重载" → O(1) |

**不要做**（已核实为上游设计或收益不匹配，详见 [11-upstream-issues.md](11-upstream-issues.md)）：调色板注册展开、`Create.registrate()` 的 `StackWalker` 守卫、`SimpleRegistryImpl` 的 `synchronized`（除非接受丢失并行初始化保护）、`RecipeFinder` 缓存失效策略、`BeltInventory` 的集合字段类型替换。

---

## 3. 全局风险提示

- **优先级冲突**：Create 自带 mixin 配置 `priority: 1000`；你的 mixin 建议显式设更高优先级，避免目标调用点被提前 redirect 掉（见手册 §1）。
- **`@Redirect` 无法消除参数表达式里的分配**：这类问题（如 `getOrDefault(node, new HashMap<>())`）必须用 `@Inject(HEAD, cancellable)` 或 `@Overwrite`（见手册 §3）。
- **不要 Overwrite 已被 Create 注入的方法**，否则会丢掉 Create 自己的修修补补。
- 所有收益数字目前均为**静态推理的量级估计**，不是实测值。
