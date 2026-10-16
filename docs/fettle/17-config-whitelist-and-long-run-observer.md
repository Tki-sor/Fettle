# 17 · 沉睡试点升级为配置白名单 + 观察层长期采集（含自动拉黑验证）

## 17.1 配置驱动（无 JVM 参数、无需调试命令）

配置文件 `config/fettle-common.toml` 新增两个段（生成结果实测）：

```toml
[diagnostics]
    benchProbes = false
    debugPanelKeepAlive = false
    mixinLog = false

[observe]
    enabled = false
    reportIntervalMinutes = 5      # 每 N 分钟把快照追加到 fettle-observe.csv（0 = 不写）

[beSleeping]
    enabled = false
    classes = []                   # 简单名或全限定名都接受；空列表 = 什么都不睡
    observeTicks = 1200            # 连续多少 tick 无 setChanged 才允许入睡
    maxSkipTicks = 20              # 沉睡期间每跳 N 次强制 tick 一次（心跳）
    blacklistAfterWakes = 20       # 被唤醒这么多次 → 本次运行内拉黑该类
```

> 说明：原来的 `observe / benchProbes / debugPanelKeepAlive / mixinLog` 之前被误放进 `[recipe]` 段，本轮一并挪到 `[diagnostics]` / `[observe]`，配置结构现在是干净的。

## 17.2 实测：白名单从配置生效（**全程没发任何命令**）

把 `[observe] enabled=true, reportIntervalMinutes=1`、`[beSleeping] enabled=true, classes=["HopperBlockEntity"], observeTicks=40` 写进配置文件后启动：

```
[Fettle] beSleeping enabled=true suspended=false failed=false whitelist=[HopperBlockEntity] debug=- \
         observeTicks=40 maxSkipTicks=20 blacklistAfter=20 blacklisted=[] events=0
  HopperBlockEntity ticks=40800 skipped=481600 heartbeats=25200 wakes=0 observationResets=0
```

两次采样做差：`Δskipped/Δticks = (481600-290800)/(40800-30800) = 190800/10000 = **19.08**` —— 与 `maxSkipTicks=20` 的理论值（19 跳 : 1 心跳）一致，即**稳态跳过 95%**。
注意 `debug=-`：这个白名单完全来自配置文件，不是调试命令。

## 17.3 观察层长期采集：`fettle-observe.csv`

`[observe] reportIntervalMinutes = 1` 时，服务端每 1 分钟自动追加一次快照（已实测生成）：

```csv
epoch_seconds,class,ticks,changed,sendData,changedPerTick
1792149303,net.minecraft.world.level.block.entity.FurnaceBlockEntity,4648,0,0,0.000000
1792149303,net.minecraft.world.level.block.entity.BellBlockEntity,7131,0,0,0.000000
...
```

整合包作者可以**开几个小时**收集数据（`/fettle idle dump` 也可立即手动导出），再据 `changedPerTick` 决定白名单 —— 不用猜。
顺带确认了 ticker 层拦截的覆盖面：报告里同时出现 `FurnaceBlockEntity / BellBlockEntity / ChestBlockEntity / BedBlockEntity / CreativeMotorBlockEntity` 等**原版与 Create 混合**的类。

## 17.4 自动拉黑：实测触发并落日志

把 `blacklistAfterWakes` 调到 2，对 `HopperBlockEntity` 用反复 `/data merge` 人为制造 `setChanged`：

```
blacklisted=[HopperBlockEntity] events=1
  HopperBlockEntity ticks=1542 skipped=9978 heartbeats=510 wakes=2 observationResets=2

日志：[Fettle] BE sleeping: HopperBlockEntity removed from the whitelist for this run (woken 2 times)
```

拉黑后该类立即恢复正常 tick（不再被跳），**无需重启、不会崩**。
另外还有一条"始终不空闲"的拉黑规则：`observationResets >= blacklistAfterWakes` 且从未成功沉睡 → 同样拉黑（代码路径 `blacklistIfNotIdle`）。

## 17.5 行为门禁已扩展并通过

`tools/fettle_gate.py` 增加第 4 项断言：**白名单沉睡试点的稳态跳过比 ≥ 5**。

```
[gate] kinetic incremental: stress=[n=27 mismatches=0] ... afterReconcile=[n=3 mismatches=0]
[gate] collisionDifferential: fresh=[n=24 collisions=3 mismatches=0] stale_guarded=[n=24 ... mismatches=0 missed=0]
[gate] pilot delta ticks=8000 skipped=152000 ratio=19.00
[gate] recipe parity self-test: checked=1211 skipped=340 mismatches=0
[gate] PASS        (EXIT=0)
```

## 17.6 复现

```bash
# 1) 配置白名单沉睡（编辑 config/fettle-common.toml）
#    [observe]    enabled = true, reportIntervalMinutes = 1
#    [beSleeping] enabled = true, classes = ["HopperBlockEntity"], observeTicks = 40
# 2) 启动后无需任何命令
fettle idle sleeptest          # 期望 debug=- 且 whitelist=[HopperBlockEntity]
fettle idle report             # 观察快照
fettle idle dump               # 立即导出 CSV
fettle idle wake               # 一键暂停（所有实例恢复 tick）

# 3) 完整门禁
python tools/fettle_gate.py
```


---

## 17.7 追加：白名单支持通配与 mod 作用域（已实测）

`beSleeping.classes` 现在接受四种写法：

| 写法 | 含义 | 例 |
| --- | --- | --- |
| `HopperBlockEntity` | 简单类名 | 单个类 |
| `net.minecraft.world.level.block.entity.HopperBlockEntity` | 全限定名 | 单个类 |
| `*Hopper*` / `net.minecraft.*.entity.*` | 通配（`*` = 任意字符，`?` = 单字符） | 一批类 |
| `minecraft:*Hopper*` / `create:FactoryPanel*` / `create:*` | **mod 作用域**（modid:类名模式，或整个 mod） | 按 mod 圈定 |

mod 作用域解析分两层：先用 FML 的扫描结果取该 mod 的类名集合（反射，失败则跳过），再退回"包名/全限定名里含该 modid"的启发式。
匹配结果**按类缓存**，并在配置数组变化时自动失效——因为它在每个被 tick 的方块实体上都会被调用。

### 实测（配置里写 `classes = ["minecraft:*Hopper*"]`，启动后不发任何命令）

```
[Fettle] beSleeping enabled=true ... whitelist=[minecraft:*Hopper*] debug=- \
         observeTicks=40 maxSkipTicks=20 blacklisted=[] events=0
  HopperBlockEntity ticks=27600 skipped=232224 heartbeats=12000 wakes=0 observationResets=0
```

→ `modid:模式` 解析生效，`HopperBlockEntity` 被正确圈定并进入沉睡；心跳数 12,000 与 `maxSkipTicks=20` 一致。

### 仍未实现的唤醒条件（诚实列出）

当前唤醒条件：**`setChanged` + 心跳 + `/fettle idle wake` 一键暂停**。
边界文档里还列了"方块/邻居更新、红石变化、区块重载、玩家进入渲染距离、交互、capability 查询"——这些**尚未实现**，属于把试点推广到真实整合包前必须补齐的部分。

