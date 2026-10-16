# 01 · 基准与图表协议

> 目的：让"优化前后有比较、有图表"从第一天就**可复现**，而不是事后补一堆示意图。
> 所有命令与工具均为本机实测或官方文档核实的范围；未实测项集中在 §8。
> 工程骨架按约定暂不搭建，本文件只描述协议与脚本。

## 1. 环境前置（缺一不可）

| 项 | 值 | 为什么 |
| --- | --- | --- |
| JDK | **21**（`C:\Program Files\Java\jdk-21.0.10`） | 本机 `JAVA_HOME` 是 **25**，而项目 toolchain 与 MC 1.21.1 都要求 21；用 25 跑出来的数字不可信 |
| Gradle | wrapper **8.14.3** | 本地无此版本 → **首次构建需联网下载**；之后的基准复用同一份构建产物 |
| 图工具 | bundled Python 3.12 + `matplotlib`（需 `pip install matplotlib`，本机已实测可装 3.11.2）、numpy、pandas、Pillow | 出图不需要 Node |
| Spark | drop-in 到 `run/server/mods/` | Create dev 环境**不自带** Spark（`gradle.properties` 无此依赖） |
| JFR | 系统自带 `jfr` CLI（Zulu 25 的 `jfr.EXE` 可用） | MC 自带 `minecraft.ServerTickTime` 事件可直采 tick 时长 |

## 2. 可重复对照协议（核心）

**原则：同一份构建产物 + 一个总开关做 A/B**，不换 jar（换 jar 会引入额外变量）。

1. **固定世界**：预先跑好区块生成的固定存档放在 `run/server/world`；基准只测"点着装置"的稳态，**不要把世界生成算进 MSPT**。
2. **固定配置**：同 `server.properties`（固定 seed / view-distance / simulation-distance、`enable-rcon=true`、关 autosave）、同 `config`、同 JVM 参数、同电源策略。
3. **装载场景 → 稳态**：
   - `/tick freeze` 冻结 → 放置/加载目标装置 → `/tick rate 20` → `/tick sprint` 快进到稳定（例如让传送带/网络跑几分钟游戏时间）→ `/tick unfreeze`。
   - ⚠️ **原版 1.21.1 没有 `/tick warp`**（那是 Carpet 的功能）；只有 `/tick rate | freeze | unfreeze | step | sprint | stop`。
4. **预热** 3-5 分钟（让 JIT 完成编译、区块全部加载）。
5. **采样**：`/spark profiler start --timeout 60`（或 JFR 录制 60-120s），**重复 3 轮取中位数**；尖峰定位另用 `--only-ticks-over 50` 与 `/spark tickmonitor --threshold-tick 50`。
6. **三组对照**（证明"我们的优化真的更有效"的关键）：
   - 组 1：只装 Fettle，mixin 开关 **OFF**（基线）
   - 组 2：只装 Fettle，mixin 开关 **ON**
   - 组 3：装上竞争 mod（FastSuite / Lithium / FerriteCore 等），分别测 ON/OFF
   → 组 3 才能回答"是共存叠加、还是必须接管"。

## 3. 采集命令

**Spark（服务端命令是 `/spark`，客户端才是 `/sparkc`）**
```
/spark tps
/tick query
/spark profiler start --timeout 60
/spark profiler start --timeout 60 --only-ticks-over 50
/spark profiler start --alloc                      # 分配采样，默认采样间隔 512KB
/spark profiler stop --comment "fettle-on-create-scene1"
/spark profiler stop --save-to-file                # 存到 config 目录，不上传
/spark gc
```
> Spark 原始数据是 **protobuf 不是 JSON**：查看器 URL 加 `?raw=1` 只给 metadata；真采样数据在 `https://spark-usercontent.lucko.me/<code>`，转 JSON 用官方 `spark2json`（`?raw=1` 背后的实现）。

**JFR**
```
java -XX:StartFlightRecording=duration=120s,filename=run.jfr,settings=profile ...
# 运行中：
jcmd <pid> JFR.start name=bench settings=profile filename=run.jfr
# 事后：
jfr summary run.jfr
jfr view hot-methods run.jfr
jfr view gc-pauses run.jfr
jfr view allocation-by-class run.jfr
jfr print --json --events jdk.ExecutionSample run.jfr > samples.json
jfr print --json --events minecraft.ServerTickTime run.jfr > ticktime.json   # MC 自带事件
```
> **MC 自带 JFR 事件只有"每 tick 聚合时长"（`minecraft.ServerTickTime`，字段 `averageTickDurationNanos`），没有方法级热点** —— 热点必须靠 Spark 或 `jdk.ExecutionSample`。

**调制开关**：Fettle 的所有注入必须能通过**一个系统属性或一条配置**整体关闭（`-Dfettle.disabled=true` 或 `config/fettle.toml`），这是做 A/B 的前提，也是与竞争 mod 共存的自救手段。

## 4. 指标定义（只认这几个）

| 指标 | 定义 | 来源 | 为什么重要 |
| --- | --- | --- | --- |
| **MSPT p50 / p95 / p99** | 每 tick 服务端耗时的中位数与高分位 | `minecraft.ServerTickTime` / Spark | 玩家感知的是**高分位**（卡顿感来自 p95/p99 而非均值） |
| **tick 时间分布** | 全部分位 + 离群值 | 同上 | 均值会被尖峰掩盖 |
| **规模 vs MSPT** | X = 方块实体数/实体数/配方数，Y = MSPT p95 | 上述两者 | **最有说服力的一张图**：证明是规模效应而非噪声 |
| **每 tick 分配量** | MB/s，按类分组 | Spark `--alloc` / JFR `ObjectAllocationSample` | GC 压力是性能 mod 的主战场之一 |
| **热点方法自耗时 %** | top-10 self time | Spark / JFR `hot-methods` | 定位"你改的到底是不是热点" |
| **GC 暂停** | 次数、总时长、单次最长 | `/spark gc` / `jfr view gc-pauses` | 分配优化的最终收益体现 |

## 5. 数据落地格式（CSV，脚本直接吃）

统一放 `docs/fettle/bench/data/`，每个图一个 CSV（第一行表头，UTF-8）：

| 文件 | 列 | 一行代表 |
| --- | --- | --- |
| `tick_times.csv` | `scenario,build,tick_ms` | 一个 tick 样本（`build` = `base`/`fettle`/`fettle+fastsuite`…） |
| `scaling.csv` | `scenario,build,scale_value,scale_label,mspt_p95,mspt_p95_err` | 一个规模档位的 p95 与误差 |
| `alloc.csv` | `scenario,build,class,mb_per_s` | 某个分配类在该组下的 MB/s |
| `hotspots.csv` | `scenario,build,method,self_pct` | 某个方法在该组下的自耗时占比 |
| `gc.csv` | `scenario,build,pause_ms` | 一次 GC 暂停（次数可由行数统计） |

`docs/fettle/bench/make_charts.py` 读这 5 个文件（缺哪个就跳过哪张图），输出到 `docs/fettle/charts/`：
1. `01_tick_distribution.png` —— before/after 箱线（或小提琴）+ p50/p95/p99 标注
2. `02_scaling_vs_mspt.png` —— 规模扫描曲线，两条线带误差棒（**主图**）
3. `03_allocation.png` —— 每 tick 分配量堆叠柱状 + 总量
4. `04_hotspots.png` —— top-10 热点方法横向条形（before/after 并排）
5. `05_gc_pauses.png` —— 暂停时长散点/时间线 + 暂停次数

用法：
```
python docs/fettle/bench/make_charts.py --data docs/fettle/bench/data --out docs/fettle/charts
# 只有部分数据也能跑；缺文件会打印 skip 而不是报错
```

## 6. 图表纪律（避免"假图"）

1. **每张图必须由 CSV 生成**，禁止手绘/示意；CSV 与图一起提交，别人能复跑。
2. 图注必须写：场景、规模、采样时长、轮数、JDK/内存参数、装了哪些 mod。
3. **误差必须画**（3 轮取中位数时报范围）；单次运行的差异不得当作收益。
4. 对比的 `build` 组名要语义化（`base` / `fettle` / `fastsuite` / `both`），不要用日期当组名。
5. 不承诺绝对 MSPT 改善百分比 —— 结论只写"在场景 X、规模 Y 下，p95 从 A 降到 B"。

## 7. 自动化边界

- **CI 只做行为门禁**：本项目已有 `.github/workflows/build.yml` 跑 `./gradlew runGameTestServer`（NeoForge 用**退出码 = 失败测试数**）。gameTest 用小 NBT 模板、短时运行，**不适合测 MSPT**。
- **MSPT 基准只在本机固定机型跑**：CI runner 是共享 CPU，run-to-run 噪声大，只适合同一 runner 内的相对比较。
- 无头服务端**没有 F3+L**，NeoForge 自带 Debug Profiler 用不了；无头基准依赖 RCON 发 Spark 命令。

## 8. 未实测项（正式跑基准前必须逐项验证）

| # | 待验证 | 影响 |
| --- | --- | --- |
| V1 | Spark 能否直接 drop-in 到 Create dev 的 `run/server/mods/` | 决定能否用 Spark 采热点 |
| V2 | `/spark profiler stop --save-to-file` 的确切落盘目录 | 决定数据管线起点 |
| V3 | NeoForge launch 下 `jfrProfile` 是否仍被解析 | 决定能否用 MC 自带的 JFR 开关 |
| V4 | RCON 在本项目的 `server.properties` 下实际可用（含密码/端口） | 决定无头自动化能否成立 |
| V5 | `/tick query` 的实际输出格式（位数/分位定义） | 决定是否能直接当数据源 |
| V6 | `BUNDLE_SIZE_LIMIT(4096)` 是否对 `ClientboundBundlePacket` 强制生效 | 影响网络调度层方案（二期） |

## 9. 最小可执行路径（第一次跑通）

1. 切 Java 21 → `./gradlew build`（首次联网下载 Gradle 8.14.3 + NeoForge artifacts）。
2. 固定存档放 `run/server/world`；写死 `server.properties`（seed/view-distance/RCON/关 autosave）。
3. `./gradlew runServer`，JVM 参数加 `-XX:StartFlightRecording=duration=120s,filename=run.jfr,settings=profile`。
4. RCON 发：`/tick freeze` → 装载场景 → `/tick sprint` → `/tick unfreeze` → `/spark profiler start --timeout 60`。
5. 导出：Spark `?raw=1`/`spark2json`；JFR `jfr print --json`。转成 §5 的 5 个 CSV。
6. `python docs/fettle/bench/make_charts.py ...` → 出图。
7. 同一流程把 mixin 开关切到 ON 再跑一遍，得到 before/after。
