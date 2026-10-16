# 05 · 基准实测结果（第一轮）

> 这一轮的目标是**把测量管线跑通并拿到第一组真实数字**，不是证明收益。结论里有一部分是"没测出来"，那部分同样重要。

## 1. 实验设置

| 项 | 值 |
| --- | --- |
| 服务端 | NeoForge 21.1.227 / MC 1.21.1 / JDK 21.0.10，headless（`--nogui`），RCON 开 |
| 装载 | Create 6.0.10（+ 内置 Flywheel/Ponder/Registrate）、Spark 1.10.124、Fettle dev |
| 世界 | 超平坦、`doMobSpawning/doDaylightCycle/doWeatherCycle=false`、`randomTickSpeed=0`、区域 forceload |
| 场景 | **202 个正在 tick 的机械合成器**（`create:mechanical_crafter`，4 格间距避免合并成多方块） |
| 采样 | Fettle 自带记录器（`/fettle bench start|stop`）记录**每个服务端 tick** 的耗时，丢弃窗口前 40 tick |
| A/B 方式 | **同一个 JVM 内**用 `/fettle feature crafterSignalCache true|false` 运行时切换，交替测量 6 个窗口（3 ON / 3 OFF），避免重启带来的 JIT/启动差异 |

## 2. 结果

| 窗口 | ticks | mean (ms) | p50 | p95 | 合成器段 (ms/tick) | 每合成器 (µs) |
| --- | --- | --- | --- | --- | --- | --- |
| fettle-on #1 | 760 | 0.379 | 0.282 | 0.845 | 0.0877 | 0.434 |
| fettle-off #1 | 760 | 0.701 | 0.651 | 1.082 | 0.1625 | 0.805 |
| fettle-on #2 | 760 | 0.606 | 0.600 | 1.003 | 0.1479 | 0.732 |
| fettle-off #2 | 760 | 0.355 | 0.262 | 0.740 | 0.0888 | 0.440 |
| fettle-on #3 | 360 | 0.603 | 0.588 | 0.893 | 0.1376 | 0.681 |
| fettle-off #3 | 360 | 0.588 | 0.567 | 0.854 | 0.1353 | 0.670 |

图：[tick 分布](../charts/01_tick_distribution.png)（`fettle-on` 与 `fettle-off` 各 3 组）、[规模 vs p95](../charts/02_scaling_vs_mspt.png)。

**读法（诚实版）**：
- ON 组 p95 = 0.845 / 1.003 / 0.893；OFF 组 = 1.082 / 0.740 / 0.854 —— **两组范围完全重叠，且顺序在重复之间会翻转**（#1 是 ON 更快，#2 是 OFF 更快）。
- 也就是说：**在这个场景下，A9 的收益低于本机噪声地板**。206 个合成器整段只占 ~0.09–0.16 ms/tick，而总 tick 只有 0.3–0.7 ms（预算 50 ms）——场景比"能看出 MSPT 差异"的规模**轻了两个数量级**。
- 本机噪声地板量级：同一场景重复测量之间 mean 波动 ±0.15 ms、p95 ±0.25 ms。**任何小于 ~2–3 ms 的收益都不可能在单条曲线里被可靠看到。**

## 3. 确定性证据（不靠计时）

计时会被噪声淹没，但"做了多少次邻居信号查询"是确定性的。A9 在 mixin 内部计数**真正落到 `Level#hasNeighborSignal` 的次数**：

| 窗口 | 合成器数 | 实际查询次数/tick | 原版会查询 | 节省 |
| --- | --- | --- | --- | --- |
| `crafterSignalCache=true` | 202 | **202**（=1/合成器） | 404（=2/合成器） | **50%** |
| `crafterSignalCache=false` | 202 | 0（走原版分支，不计数） | 404 | — |

这证明 **A9 的机制正确且精确减半**；两处 `hasNeighborSignal` 被合并成一次真实查询。第二行的 0 是计数器的口径（关闭时直接走原版分支），不是"省了 100%"。

## 4. 这一轮发现的两个硬问题（比数字更重要）

1. **工厂面板无法用 `/setblock` 造出来**：`create:factory_gauge`（方块 id）放下去后会被立刻移除（`data get block` 在几秒后报 "not a block entity"），各种 `face/facing` 组合与垫墙都试过，全部不存活。推测它依赖正常放置流程（`setPlacedBy`/SpecialBlockItemRequirement）建立面板结构。
   → **B8 的收益这一轮没能测**。要测它必须用**真实放置路径**：GameTest 结构模板、或写一个调试命令调用 `FactoryPanelBlock` 的放置逻辑。
2. **Spark 的 `/spark tps` 经 RCON 没有任何文本返回**，控制台也没有输出（Spark 1.10.124 / Windows / Java 引擎）。
   → 本项目的测量改为**自带记录器**（`TickRecorder` + `/fettle bench start|stop`），这也是好事：不依赖第三方、可直接按 tick 导出 CSV。

## 5. 结论与下一步

**结论**：
- 测量管线可用：场景生成、运行时 A/B 开关、逐 tick 记录、CSV、出图全链路跑通（脚本见 `tools/fettle_bench.py`、`tools/fettle_analyze.py`）。
- A9（合成器邻居信号记忆化）= **确定性 50% 查询减少**，已用计数器证明。
- **MSPT 级别的收益宣言，现在一个都还没资格下** —— 场景太轻。

**下一步（按优先级）**：
1. **把场景做重**：目标是把稳态 tick 推到 **8–20 ms**（有对比空间）。可行组合：数千个 ticking 方块实体（合成器/传送带/风扇）、真实物流（打包机 + 网络）、或直接用 Create 自带结构做"工厂测试台"。
2. **用确定性计数作为一等指标**：每个优化项都记录"消除了多少次调用/多少字节分配"，然后才用计时验证总体收益。
3. **修掉面板的可测性**：写一个调试命令走真实放置路径，或改用 GameTest 结构。
4. 需要更稳的机器（或更长的采样 + 多次重复取中位数）才能可靠测 1 ms 以下的收益。


> ⚠️ **关于 `02_scaling_vs_mspt.png` 的读法**：目前只有**一个规模点**（202 个合成器），所以它不是"规模曲线"而是**同一规模的 6 次重复**在纵向散开——那一列高度（0.74–1.08 ms）正好就是本机噪声的幅度，ON/OFF 两条线互相穿插。要做成真正的规模曲线，需要 3–4 个规模档位（例如 200 / 800 / 2000 / 5000 个 ticking BE）各跑 ON/OFF。

