# 04 · 实现进度与运行时验证

> 本文件记录**已经实测通过**的东西。所有结论都有可复现的命令或日志行，不是"应该能行"。

## 1. 已完成

| 项 | 状态 | 证据 |
| --- | --- | --- |
| 工程骨架（MDG 2.0.107 + NeoForge 21.1.227 + JDK 21） | ✅ 构建通过 | `BUILD SUCCESSFUL in 3m 52s`（首次），产物 `build/libs/fettle-1.21.1-0.1.0.jar` |
| `neoforge.mods.toml` 元数据展开 | ✅ | jar 内 `version = "0.1.0"`、`license`、`[[dependencies.fettle]]` 均已展开，无残留 `${}` |
| 主类 + 配置 | ✅ | 服务端日志 `[Fettle] loaded; master switch = true, recipe index = true` |
| Mixin 配置被加载 | ✅ | `Selecting config fettle.mixins.json` / `Preparing fettle.mixins.json (2)` |
| Mixin 实际注入 RecipeManager | ✅ | `Mixing accessor.RecipeManagerAccessor from fettle.mixins.json into net.minecraft.world.item.crafting.RecipeManager`、`Mixing recipe.RecipeManagerMixin ... into ... RecipeManager` |
| 无注入错误 | ✅ | 日志无 `InvalidInjectionException` / `MixinApplyError` |
| 服务端可运行 | ✅ | `Done (1.368s)!`，RCON `0.0.0.0:25575` 可用 |
| **配方索引正确性门禁** | ✅ **checked=691 skipped=210 mismatches=0** | 见 §3 |

## 2. 一个必须记住的坑：mixin 配置**不是**在 jar manifest 里声明的

**实测结论**：NeoForge 1.21.1（FML **4.0.42**）只从 `neoforge.mods.toml` 的 **`[[mixins]]`** 段读取 mixin 配置：

```toml
[[mixins]]
config = "fettle.mixins.json"
```

**证据（反汇编，非猜测）**：
```
javap -c net.neoforged.fml.loading.moddiscovery.ModFileParser
  ldc "mixins"          -> IConfigurable.getConfigList(["mixins"])
  ldc "config"          -> getConfigElement(["config"])
  ldc "requiredMods"    -> getConfigElement(["requiredMods"])
  -> new ModFileParser$MixinConfig(String config, List<String> requiredMods)
```
`MixinConfig` 是 record `(config, requiredMods)`，与 TOML 一一对应。

**踩坑经过**（每一步都实测过）：
1. 只写 jar manifest `MixinConfigs` → 开发环境只有 NeoForge 自己的配置被选中，`fettle.mixins.json` 完全没被提及；
2. 加 `-Dmixin.config=fettle.mixins.json` → **无效**（FML 自己设置该属性，我们的值被覆盖）；
3. 在 `src/main/resources/META-INF/MANIFEST.MF` 里放 manifest → **无效**（dev 的 mod file 根目录不认这个资源）；
4. 写入 `[[mixins]]` → **立刻生效**（`Selecting config fettle.mixins.json`）。

> 附注：Create 自己的 `build.gradle` 里那段 `"MixinConfigs": "create.mixins.json"` 是**遗留写法**；真正生效的是 `src/main/templates/META-INF/neoforge.mods.toml` 第 17-18 行的 `[[mixins]]`。我们保留 jar manifest 属性只为兼容旧工具，功能不依赖它。

## 3. 正确性门禁：与"原版暴力扫描"逐条对比

`RecipeParityCheck`（`com.tkisor.fettle.recipe`）在服务端启动时执行：
对**每一条 crafting 配方**，用它自己的 `getIngredients()` 造一个 3×3 合成输入，然后比较
`RecipeManager#getRecipeFor(...)`（走我们的索引）与**暴力扫描**（遍历同类型全部配方，取第一个 `matches()` 为真的）。

实测输出：
```
[Fettle] recipe parity self-test: checked=691 skipped=210 mismatches=0
```
- `checked=691`：可以构造出输入、且结果可比的配方（覆盖全部 crafting 配方的绝大多数）
- `skipped=210`：`getIngredients()` 为空或抓不出具体物品（含 special 配方、非物品谓词），这些在索引里走"永不预筛"的回退路径，本来就与 vanilla 等价
- `mismatches=0`：**索引没有漏掉任何一个 vanilla 会命中的配方**

触发方式（两种，任选）：
```bash
# 1) 系统属性（构建脚本已接线到 run 任务）
FETTLE_SELFTEST=true ./gradlew runServer ...
# 2) 配置文件 run/server/config/fettle-common.toml
[recipe]
    selfTestOnStartup = true
```

## 4. 当前实现范围（明确边界，别夸大）

| 注入点 | 状态 | 说明 |
| --- | --- | --- |
| `RecipeManager#getRecipeFor(RecipeType, RecipeInput, Level)` | ✅ 已注入 | 3 参基础重载；presence of index 时短路，否则回落 vanilla |
| `RecipeManager#getAllRecipesFor(RecipeType)` | ✅ 已注入 | 返回缓存的不可变列表（元素与顺序与 vanilla `List.copyOf` 相同） |
| `getRecipeFor(..., ResourceLocation)` / `(..., RecipeHolder)` | ⬜ 未注入 | 4 参重载，调用频率低，保持原版 |
| `getRecipesFor(...)`（带排序） | ⬜ 未注入 | 排序语义需要精确复刻，留到有基准数据证明值得做时再上 |
| 流体配方 / 非物品谓词配方 | ⬜ 不索引 | 归入 unindexed 集合，永远参与 `matches` |

**索引的安全前提**（已在代码注释与本文件写明）：配方的 `matches()` 若与 `getIngredients()` 无关，必须`isSpecial()` 或 `isIncomplete()` 才能被安全识别；否则理论上存在漏配方的可能。保守化开关：`recipe.strictVanillaSerializersOnly = true`（只索引 `minecraft:` 序列化器）。

## 5. 下一步（按依赖顺序）

1. **L3 诊断层**：挂 `Level.tickBlockEntities` 做按类型/按 mod 的耗时归因（零行为变更），顺便作为基准数据源；
2. **基准基线（Batch 0）**：需要"机器密集"场景才能体现配方索引的收益 —— 单纯 vanilla 场景每 tick 几乎没有 `getRecipeFor` 调用。候选：装 AE2/Create 搭一个自动化装置，或先用**查找级微基准**（N 万次 `getRecipeFor` 计时）证明索引本身的加速比，再谈 MSPT；
3. **L2 传输加速**：先 Mekanism auto-eject；
4. 与 FastSuite / Lithium 的三组对照实测。

## 6. Create 批（第一批，已实测注入成功）

许可证已按你的要求改成 **MIT**（`gradle.properties: mod_license = MIT`）。Create 侧从这一批开始动。

**依赖接线**：`libs/create-1.21.1-6.0.10.jar`（Modrinth 下载，19,123,767 字节，git-ignored）作为 `compileOnly`；Create 的 JiJ（Flywheel 1.0.6 / Ponder 1.0.82 / Registrate 1.3.0）被解到 `libs/jij/` 后同样 `compileOnly`（否则 `BeltBlockEntity` 的父类 `net.createmod.ponder.api.VirtualBlockEntity` 找不到，编译失败——这个坑已记录）。

| # | 目标 | 注入方式 | 语义 |
| --- | --- | --- | --- |
| A1 | `TrackGraph#getConnectionsFrom` 未命中时 `new HashMap<>` | `@Inject(HEAD, cancellable)` + `@Shadow connectionsByNode` | 返回值语义等价（12 处调用点全部只读，已 grep 确认无 put/remove/clear） |
| A8 | `BeltBlock#hasShaftTowards` 的 Optional 包装 | `@Overwrite`（3 行） | 完全等价：`getBlockEntityOptional(...).map(BeltBlockEntity::hasPulley).orElse(false)` ≡ `instanceof` 判定 |

**运行时验证（反编译导出的类，不靠日志自证）**：
```
# TrackGraph：我们注入的 handler 存在
private void handler$zzb000$fettle$avoidEmptyMapAllocation(TrackNode, CallbackInfoReturnable);

# BeltBlock#hasShaftTowards：已不再调用 getBlockEntityOptional，改为
invokeinterface LevelReader.getBlockEntity(BlockPos)
instanceof com/simibubi/create/content/kinetics/belt/BeltBlockEntity
```
（导出目录：`run/server/.mixin.out/class/…`，由 `-Dmixin.debug.export=true` 产生。）

**顺带验证了一次正确性门禁**：装上 Create 后配方总数从 901 涨到 1551 条 crafting 配方，
`[Fettle] recipe parity self-test: checked=1211 skipped=340 mismatches=0` —— 索引对 **Create 自己的配方**同样零偏差。

## 7. 第二个 FML 机制结论：按 mod 条件加载 mixin 配置用 `requiredMods`

```toml
[[mixins]]
config = "fettle.mixins.json"

[[mixins]]
config = "fettle.create.mixins.json"
requiredMods = ["create"]      # 只有装了 create 才会加入 mixin 环境
```

实测：日志出现 `Selecting config fettle.create.mixins.json` + `Preparing fettle.create.mixins.json (2)`，
且插件回调里两条 `create.*` 决策均为 `true`。**这比在插件里手写 `ModList.isLoaded("create")` 更可靠**——
后者在早期类加载阶段可能拿到空的 ModList（我们第一版就踩了这个：配置被 prepare、mixins 被静默跳过）。
`ModList.isLoaded` 仍保留用于 recipe 竞争者（FastSuite）判断。

## 8. 关于"允许行为不完全一致"（你的新授权）的理解与红线

你允许 `1t → Nt` 这类**检查频率放宽**，条件是"效果一致或影响不大"。据此我把它细化为可执行的判定标准：

| 类别 | 判定 | 处理 |
| --- | --- | --- |
| 纯分配消除（A1/A8 这批） | 行为完全一致 | 默认开 |
| 频率放宽（库存/承诺重算 1t→Nt） | 响应延迟增加 N tick，产出速率不变 | **默认开但可配 N**，且必须写清"延迟上限" |
| 少发包/少广播（变化时才发） | 客户端视觉可能少一次中间态 | 默认开，必须在文档标注观感差异 |
| 改变产出/物流速率、影响存档数据 | 不允许 | 不做 |

下一批（Create 第二波，全部属于"频率放宽"或"扫描次数下降"）：
**B8** 工厂面板每 tick 重算库存/承诺 → 版本号门控；
**B11** 传送带漏斗每物品每 tick 重算 amount/mode → 每漏斗每 tick 缓存；
**B9** 打包机 9 次整库存扫描 → 槽位游标 + 箱满 break；
**A13/A14** 溜槽邻块查询、仓库行为循环内重复解析。


## 9. Create 第二批（频率放宽 + 缓存，已实测注入成功）

按你的决定：面板这类**每 tick 检查改成每 5 tick**（不是 10），因为对体验有轻微影响。

| # | 目标 | 方式 | 行为影响 |
| --- | --- | --- | --- |
| **B8** | `FactoryPanelBehaviour#tick` 每 tick 跑 `tickStorageMonitor()` + `tickRequests()`（两者都对整个物流网络做汇总） | `@Inject(HEAD)` 算本 tick 是否执行 ＋ 两处 `@Inject(INVOKE, ordinal)` 取消调用 | **默认 5 tick**（可配 `factoryPanelCheckInterval`，1=原版）。请求逻辑本身未改 → **吞吐不变**，只是观察延迟 ≤5 tick（0.25s）。客户端虚拟面板那条调用（ordinal 0）**故意不动**，屏幕上的面板仍然每 tick 刷新 |
| **A9** | `MechanicalCrafterBlockEntity#tick` 同一次比较里调两次 `level.hasNeighborSignal(pos)` | `@Redirect` + 按 game tick 记忆化 | 5×5 合成器每 tick 的邻居信号查询减半；同一 tick 内放置/破坏的方块会晚一次调用才被观测到（与"tick 开头采样"同性质） |
| **A15** | `PackageItem#matchAddress` 每次 `String.matches(Glob.toRegexPattern(...))` 重新编译正则 | `@Inject(HEAD, cancellable)` + 有界 `Pattern` 缓存（256 条） | 语义逐字保持（提前返回分支照抄，最后仍是 `Pattern.matcher().matches()`）；只把"重复编译同一个 glob"消掉 |

**相位打散**：面板用 `System.identityHashCode(this)` 作为相位，N 个面板的工作均摊到不同 tick，不会"每 5 tick 一起卡一下"。

**验证（反编译导出类，不是看日志）**：
```
FactoryPanelBehaviour:
  private int fettle$tickCounter;
  private boolean fettle$runServerAggregation;
  private void handler$zzc000$fettle$decideThisTick(CallbackInfo);
  private void handler$zzc000$fettle$gateStorageMonitor(CallbackInfo);
  private void handler$zzc000$fettle$gateRequests(CallbackInfo);
  tick() 字节码：客户端调用未加壳；服务端两处为
     handler$…gateStorageMonitor -> ifeq 137 ; 137: invokevirtual tickStorageMonitor
     handler$…gateRequests       -> ifeq 166 ; 166: invokevirtual tickRequests
PackageItem:
  private static final java.util.Map fettle$patternCache;
  private static void handler$zze000$fettle$cachedMatchAddress(String, String, CallbackInfoReturnable);
MechanicalCrafterBlockEntity:
  private long fettle$signalGameTime; private boolean fettle$signalValue;
  private boolean redirect$zzd000$fettle$memoizedNeighborSignal(Level, BlockPos);
```
5 个 Create mixin 全部应用成功，注入错误 **0**。

**又一个编译期事实**：`net.createmod.catnip`（`Glob` 所在库）**没有**独立成 jar，它被 **shade 进 Ponder 的 jar 里** —— 所以引用 Catnip 类不需要额外下载依赖，用 `libs/jij/ponder-*.jar` 就够了（已验证）。

## 10. 尚未做的 Create 条目（按性价比排序）

| # | 目标 | 为什么还没做 |
| --- | --- | --- |
| B9 | 打包机 9 次整库存扫描 → 槽位游标 + 箱满 break | 需要重写一段带 `Outer:` 标签的嵌套循环，语义边界要逐个确认，值得单独一轮 |
| B11 | 传送带漏斗每物品每 tick 重算 amount/mode | 需要一个"每 tick 每漏斗"的小缓存，考虑做成 4 槽环形缓存 |
| A13 | 溜槽每 tick 5 次邻块查询 + LinkedList | 需要先确认哪些查询在同 tick 内可复用 |
| B12 | 股票终端快照 O(类别×物品) | `@Overwrite` 更好，但属于客户端显示逻辑，优先级低 |
| A12 | 物品保险库全体积邻居广播 | 亮度/比较器语义需谨慎 |

**下一步建议**：先跑一次**基线基准**（现在有 RCON 脚本 + 5 个 mixin 可开关），量出这 5 个改动在"面板密集/传送带密集"场景下的真实收益，再决定 B9/B11 的激进程度。

