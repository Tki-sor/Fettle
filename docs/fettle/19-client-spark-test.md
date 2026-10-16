# 19 · 客户端实测（spark 1.10.124，NeoForge 21.1.227 + Create 6.0.10）

## 19.1 环境

真实客户端（不是服务端）：开发用 `runClient`，游戏目录 `Fettle-1.21.1/run`，
mods = **Create 6.0.10 + spark 1.10.124 + minecraft-mcp(桥接)**，Fettle 即本体；
世界 `fettle-client` 由 quick-play 直接载入（临时给 client run 加了
`--quickPlaySingleplayer fettle-client`，跑完已还原）。
用户自己的 `~/.minecraft` 与 ATM10 整合包**未做任何保留性改动**（临时放进去的 jar 已全部移除、1.18.2 的 MCP jar 已归位）。

## 19.2 客户端里的 mixin 落地证据（不是"没报错"，是逐条 "Mixing"）

开发环境带 `-Dmixin.debug.verbose=true`，所以客户端日志里能看到实际注入：

```
[mixin/]: Mixing accessor.RecipeManagerAccessor from fettle.mixins.json into ...RecipeManager
[mixin/]: Mixing recipe.RecipeManagerMixin from fettle.mixins.json into ...RecipeManager
[Server thread/INFO] [mixin/]: Mixing observe.BlockEntityTickerMixin from fettle.mixins.json
        into net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity
```

同时这条警告暴露了一个真实缺陷（本轮已修）：

```
@Shadow target ...BlockEntityTickerMixin::blockEntity is final but shadow is not decorated with @Final
```

→ 已在 `BlockEntityTickerMixin` 上加 `@Final`（见本次提交）。

## 19.3 spark 数据（客户端内，同一世界同一场景）

场景：`/fettle debug kinetics 200`（200 根传动轴 + 创造马达），创造模式。

`/spark tps`（Fettle 默认，panelInterval=5）：
```
TPS from last 5s, 10s, 1m, 5m, 15m:  20.0, 20.0, 14.88, 18.71, 19.55
Tick durations (min/med/95%ile/max ms) from last 10s, 1m:  2.4/3.1/5.0/8.2;  2.4/3.1/4.7/20.8
```

切到原版面板行为（`/fettle feature factoryPanelCheckInterval 1`）后再次 `/spark tps`：
```
TPS: 20.0, *20.0, *20.0, 18.71, 19.55
Tick durations:  2.4/3.1/4.4/8.1;  2.4/3.1/4.7/9.7
```

**诚实结论：这一组 A/B 不能证明面板节流的效果**——中位 MSPT 两次都是 3.1 ms，
因为场景里**面板没有真正铺开**（`/fettle debug panels 1500` 在该客户端没有回执，面板数量为 0），
所以关掉面板节流自然没有差别。要复现 B8 的 −72% 需要在客户端里先铺 4500 个面板。

40 秒客户端采样曲线（spark profiler，注释 `fettle-on`）：
**https://spark.lucko.me/2WJdS1oa8b**

## 19.4 引擎与工具限制（实测踩到的）

| 现象 | 结论 |
| --- | --- |
| `spark: The async-profiler engine is not supported for your os/arch (windows11/amd64), so the built-in Java engine will be used instead.` | spark 1.10.124 在 Windows 上只能跑内置 Java 采样引擎，采样精度/开销不如 async-profiler |
| MCP `execute_command` 在客户端报 `no command method found (LocalPlayer)` | 客户端无法用该接口发指令；改用**聊天注入**（`open_chat` + `type_text` + `ENTER`）成功，指令回执可从 `run/logs/latest.log` 的 `ChatComponent` 行读到 |
| MCP 自带的版本目录里 `1.21.1-neoforge` 只有 **21.1.172** | Create 6.0.10 要求 **≥21.1.219**（Ponder ≥21.1.206），用它启动会 FML 崩在 mod 加载阶段；所以客户端测试走开发环境自带的 **21.1.227** |

## 19.5 复现（客户端）

```bash
# run/mods/ 里放 create + spark（+ 如需 MCP 桥接再加其 mod）
./gradlew runClient          # 或临时加 --quickPlaySingleplayer <world> 直接进世界
# 进世界后：
/fettle debug kinetics 200
/spark tps
/spark profiler start --timeout 40 --comment fettle-on
```


## 19.6 面板节流客户端 A/B（4096 个真面板，spark 双曲线）

场景：`/fettle debug panels 64 2` → `[Fettle] placed 4096 factory panels at -2, -60, 0 (keep-alive=false)`。
客户端加了 `pauseOnLostFocus:false`，否则控制模式放开鼠标会让单机存档暂停，测出来的 TPS 没有意义。

| 状态 | TPS (5s/10s/1m/5m/15m) | Tick min/med/95%/max ms（10s；1m） | spark 曲线 |
| --- | --- | --- | --- |
| **节流开** `factoryPanelCheckInterval=5`（默认） | 20.0, 20.0, *20.0, 20.0, *20.0 | 2.4 / **3.1** / 4.5 / 6.3；2.4 / **3.2** / 4.8 / 70.5 | https://spark.lucko.me/s8lBpEzdjS |
| **节流关** `factoryPanelCheckInterval=1`（原版行为） | 20.0, 20.0, *20.0, 20.0, *20.0 | 2.4 / **3.1** / 4.6 / 7.3；2.4 / **3.2** / 4.4 / 63.4 | https://spark.lucko.me/fh3nRzhaFb |

**结论（诚实版）**：客户端整 tick 的 MSPT 在两态下**分不出来**（中位都是 3.1 ms，95% 4.5 vs 4.6 ms），
这和之前服务端侧的结果一致——B8 的 **−72% 是"面板段落耗时"**，整 tick 收益只有 −0.4…−1.2 ms，在 3 ms 的基线上就是噪声。
要看这段收益必须用**分段计时**（我们服务端 A/B 用的 `TickRecorder`），spark 的整 tick 采样看不到。
4096 个面板在这个客户端也远没到瓶颈（20 TPS、3 ms/50 ms 预算）。

顺带一条工程笔记：spark 从游戏内自动上传**连续失败两次**（`An error occurred whilst uploading the results`），
回退把曲线存到 `run/config/spark/profile-*.sparkprofile`；
我用 `curl -X POST --data-binary @file -H 'Content-Encoding: gzip' https://bytebin.lucko.me/post` 手动补传，拿到 key 后即上面第二条链接。


## 19.7 线程级采样（`--thread "Server thread"`）与分段计时：本轮**没有**测到面板收益，原因已定位到场景

按计划跑了两条**锁定 Server thread** 的曲线（同一客户端、同一世界、4096 个面板已放置）：

| 状态 | 采样 | 曲线 |
| --- | --- | --- |
| 节流开 `factoryPanelCheckInterval=5` | Server thread，40 s | https://spark.lucko.me/7CqNopXyGG |
| 节流关 `factoryPanelCheckInterval=1` | Server thread，40 s | https://spark.lucko.me/5bM8znPzfC |

（第二条游戏内上传失败，仍是从 `run/config/spark/profile-*.sparkprofile` 手动补传到 bytebin 得到的 key。）

同时用 `/fettle bench start <tag>` + `bench stop` 做了分段计时（`/fettle feature benchProbes true` 打开探针），两个窗口各 **747 tick**：

| 分段 | 节流开 | 原版 | 差 |
| --- | --- | --- | --- |
| `tick_ms`（整 tick） | 3.5842 | 3.5149 | **−1.9%**（在噪声内，方向还不利于我们） |
| `crafters_tick_ms` | 1.5364 | 1.5345 | −0.1% |
| `crafters_tick_calls` | 4429.93 / tick | 4430.29 / tick | 0.0% |
| `panels_tick_ms` | **0.0000** | **0.0000** | — |
| `panels_tick_calls` | **0.0000** | **0.0000** | — |

**结论：这一轮同样不能证明面板节流。** 关键证据是 `panels_tick_calls = 0`——我放置的那 4096 个面板**根本没有 tick**
（`benchProbes` 已打开，而同一份 CSV 里 crafters 每 tick 4400 次调用是正常的），所以"节流开/关"没有可作用的对象。
整 tick 的 −1.9% 是噪声，不能算作回归也不能算作收益。

**下次要拿到客户端面板收益，必须先把"面板在 tick"这件事坐实**（候选原因：面板 behaviour 需要接入网络/被激活才 tick，
或者放置位置的区块不在客户端 ticking 集合内）。可用的判定手段：
`/fettle idle report`（看 `FactoryPanelBlockEntity` 是否出现在按类 tick 计数里）→ 若为 0，
说明场景问题而非优化问题；坐实之后再重跑本节两条曲线即可对齐服务端 B8 的 −72%（那个数字是**面板段落**，不是整 tick）。

记录一下踩坑：`/fettle bench start` **必须带 tag**（`bench start <tag>`），不带 tag 时指令被参数校验拒绝，
`bench stop` 只会写出一个 0 tick 的空 CSV —— 第一轮我就这么被骗了一次。


## 19.8 让面板真的 tick 之后再测：结果是**负结果**（附原因）

上一节没测到的根因就是场景：**空闲面板没有 keep-alive 根本不 tick**（`panels_tick_calls = 0`）。
打开诊断项后同一个场景立刻活了：

```
/fettle feature debugPanelKeepAlive true
/fettle debug panels 64 2          → placed 4096 factory panels (keep-alive=true)
/ fettle bench start tick1 ...
panels_tick_calls = 33,792 /tick    panels_tick_ms = 1.865 ms/tick    tick_ms = 9.111 ms
```

然后做了正式的两条 **653-tick** 窗口（节流 `=5` vs 原版 `=1`）：

| 分段 | 节流 =5 | 原版 =1 | 差 |
| --- | --- | --- | --- |
| `tick_ms`（整 tick） | 9.1123 | 8.6946 | −4.6% |
| `panels_tick_ms` | 1.8421 | 1.7006 | **−7.7%** |
| `crafters_tick_ms` | 2.4180 | 2.2470 | −7.1% |
| `panels_tick_calls` | 34100 | 33542 | −1.6% |
| `crafters_tick_calls` | 4428 | 4461 | +0.7% |

**结论：客户端这一轮是负结果，不能支持 B8 的 −72%。** 两个理由，都很硬：

1. **面板段落没降反升 7.7%**，而同一份 CSV 里 `crafters_tick_ms`、`tick_ms` **同样**朝"更快"方向偏了 4.6–7.1%。
   也就是说变快的是**整个窗口**（先跑节流窗、后跑原版窗，JIT/GC/刚放置场景的沉降），
   面板那 −7.7% 属于**漂移**，不是节流效果。
2. **`debugPanelKeepAlive=true` 本身就在强行"吊着"面板保持活跃**——这恰好抵消了"空闲面板节流"这条优化赖以成立的前提。
   所以这个场景从设计上就不适合验证它。B8 的 −72% 来自**自然活跃**的面板网络场景，两者前提不同，不能混用。

要得到客户端侧的正结果，正确做法是：**让面板处在真实网络里自然 tick**（例如用面板+打包机+请求器的实际产线），
而不是靠 keep-alive 强行唤醒——后者是诊断手段，不是测量手段。

### 同轮的线程级曲线（面板确实在 tick 的场景）

| 状态 | 曲线 |
| --- | --- |
| 节流 =5 | https://spark.lucko.me/8JZZeaAitu |
| 原版 =1 | https://spark.lucko.me/B15LtlUFlR |

（两条都在游戏内上传成功。之前上传失败的真正原因是 spark 的 `spark-usercontent.lucko.me/post` 返回 **400**，与 Fettle 无关；
失败时会回退成 `run/config/spark/profile-*.sparkprofile`，需要的话可以手动 POST 到 `https://bytebin.lucko.me/post` 拿 key。）


## 19.9 尝试"真实产线"场景（packager + 面板）：**脚本场景做不出自然 tick 的面板**

思路：把 `/fettle debug panels` 的石头底座换成 **packager**（`create:packager`），每个 packager 贴一块 `create:factory_gauge`，
让面板通过真实物流关系自然激活——新增调试指令 `/fettle debug panelpack <side> <spacing>`（本轮一并入库）。

实测（**keep-alive = false**）：

```
/fettle feature debugPanelKeepAlive false
/fettle debug panelpack 40 2      → placed 1600 packagers with factory gauges at 5, -60, 5
/ fettle bench start nat ...      → panels_tick_calls = 0.0000   crafters_tick_calls = 4438.6
```

面板**依旧 0 次 tick**，而同一窗口 crafters 正常计数 → 探针没问题，是**面板又消失了**。
原因在代码里写得很清楚（`FactoryPanelDebugMixin` 的注释）：

> `FactoryPanelBlockEntity#lazyTick` deletes a panel block whose `activePanels()` is zero

也就是说：脚本 `/setblock` 出来的 gauge **没有任何连接**（`activePanels() == 0`），Create 自己会在几秒内把它删掉；
**把底座换成 packager 并不会自动建立连接**——真实玩法里连接要么是玩家在面板 GUI 里手动连线，要么来自整套 stock 网络。

### 因此的结论（重要）

1. **用脚本场景无法得到"自然 tick 的面板"**，除非：① 在 GUI 里真的连线（人工，无法批量），或 ② 直接往
   `FactoryPanelBlockEntity` 的 `Connections`/`targetedByLinks` NBT 里注入连接（需要逆向其存档结构），或 ③ **在一个本来就有真实面板网络的存档里测**。
2. 打开 `debugPanelKeepAlive` 强行吊活面板后可以测（见 19.8），但那个场景**恰恰抹掉了"空闲面板节流"赖以成立的前提**，
   所以那里测出来的"无差别"不能用来否定优化，也不能用来支持优化——**两边都不能算数**。
3. 我们目前唯一有说服力的面板节流证据，仍是服务端 B8 那次**自然活跃**面板网络下的分段测量（面板段落 1.16–1.30 → 0.35–0.37 ms）。
   客户端要复现它，必须先有一个**真实连线**的面板网络。

### 下一步（选一个）

- **A（推荐，最真实）**：你给一个已经有工厂面板产线的存档/世界（ATM10 里的基地就行），我直接在客户端测；
- **B（我可以试）**：逆向 `FactoryPanelBlockEntity` 的 NBT，写一个"批量连线"的调试指令，在客户端脚本化造出自然活跃的面板网络；
- **C**：接受"面板节流只在服务端分段测量里有数据"这一现状，把它在文档里标注为**服务端验证、客户端未复现**。


## 19.10 方案 B 完成：脚本化"自然活跃"面板网络 → 面板节流**在这类场景下没有收益**（并且 B8 需要重新审视）

### 做法（新增 `/fettle debug panelwire <side> <spacing>`）

按 Create 自己的存活判定来接线，而不是取消它：

- `FactoryPanelBlockEntity#lazyTick` 会删除 `activePanels() == 0` 的面板；`activePanels()` 统计的是
  `FactoryPanelBehaviour#isActive()`，而 `isActive()` 直接返回 `active` 字段；
- 所以 `panelwire` 对每块面板：置 `behaviour.active = true`，并**按 UI 连线的语义**补上对称连接
  （`targeting` + `targetedBy`，`FactoryPanelPosition`/`FactoryPanelConnection` 都是 Create 的公开类型）；
- `debugPanelKeepAlive`（我们自己的脚手架、cancel 掉 lazyTick）**全程关闭**。

### 验证：面板活着并且在 tick（无脚手架）

```
/fettle feature debugPanelKeepAlive false
/fettle debug panelwire 40 2
/fettle bench start wired （612 tick）
panels_tick_calls = 12,769 /tick    panels_tick_ms = 0.510 ms/tick    tick_ms = 4.873
```

对比 19.8 那轮 keep-alive 强行吊活的场景：`panels_tick_ms` 从 **1.86 ms 降到 0.51 ms**——
说明**keep-alive 让面板每 tick 做了本不该做的聚合工作**，那个场景本身是被脚手架扭曲的。

### 正式 A/B（两条 693-tick 窗口）

| 分段 | 节流 =5 | 原版 =1 | 差 |
| --- | --- | --- | --- |
| `tick_ms` | 4.6161 | 4.5260 | −2.0% |
| `panels_tick_ms` | 0.5210 | 0.5302 | **+1.8%** |
| `crafters_tick_ms` | 1.6257 | 1.5890 | −2.3% |
| `panels_tick_calls` | 12781.5 | 12771.7 | −0.1% |
| `crafters_tick_calls` | 4526.2 | 4523.2 | −0.1% |

**面板段落两态相同（0.521 vs 0.530 ms，差异是噪声且方向互斥），即在这类场景里节流没有收益。**
原因看起来是：真实激活的空闲面板本来就走"轻"路径（0.52 ms/12.8k 次调用），
节流省下的那部分聚合工作在总耗时里不占分量。

### ⚠️ 由此必须重新审视 B8 的 −72%

B8 那次（面板段落 1.16–1.30 → 0.35–0.37 ms）是在**keep-alive 强行吊活**的场景里测的；
本轮显示那个场景让面板每 tick 多做约 **3.6 倍**的工作（1.86 vs 0.51 ms）。
也就是说：**B8 很可能测的是脚手架诱发的工作量被节流消掉的部分，而不是真实玩法中的工作量。**
在拿到"真实产线（有物流流量、面板自然联网）"的测量之前，面板节流这个优化**应当被标记为未证实**，
不能按 −72% 对外宣称。

### 同轮线程级曲线（面板自然活跃）

| 状态 | 曲线 |
| --- | --- |
| 节流 =5 | https://spark.lucko.me/LryJSyi4BM |
| 原版 =1 | https://spark.lucko.me/6nDvqJ28va |

（两条游戏内上传成功。）

