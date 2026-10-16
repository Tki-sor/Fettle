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

