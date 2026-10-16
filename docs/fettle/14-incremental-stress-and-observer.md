# 14 · 🅑-2 增量应力 + 🅔 观察层（两件都做了）

## 14.1 🅑-2 增量应力：**更新路径 213×，且做了差分验证**

### 做法
`KineticNetwork#updateStressFor(be, stress)` 原本每次都重算整个网络的应力和（并对每个成员做一次方块实体校验）。改成：

- 维护**running sum**：`−旧贡献 + 新贡献`，O(1)；
- **每个服务端 tick 做一次全量对账**（`updateStress()` 本来就做全量精确扫描并会清理失效成员），对账后重新播种 running sum；
- `updateStress/updateCapacity/updateNetwork` 仍走原版全量扫描，返回时把 running sum 重新播种；
- 开关：`create.kineticIncrementalStress`（默认 true，可运行时切）。

### 实测（真实网络：200 根轴 + 创造马达 = **201 成员**，2000 次 `updateStressFor`）

| 模式 | us/op | 说明 |
| --- | --- | --- |
| 增量 **off**（原版路径） | **6.612** | 每次重算 201 个成员 |
| 增量 **on** | **0.031** | O(1) 记账 |

→ **约 213 倍**。（该基准测的是"应力值未变化"的情况，这是最常见的情形：发电机重算 `calculateStressApplied()` 但值没变。）

### 差分验证（`/fettle debug kinetictest 3`）

把 running sum 与**原版全量扫描**逐次比对：

```
kinetic incremental: members=201
  stress=[n=27  mismatches=0]
  speedChanged=[n=15  mismatches=10]
  afterReconcile=[n=3  mismatches=0]
  incremental=27 reconciles=1 syncs=26
```

| 阶段 | 检查次数 | 与精确值不一致 |
| --- | --- | --- |
| 仅改应力（同 tick） | 27 | **0** |
| **改了转速但没有触发 updateStressFor**（同 tick） | 15 | **10** ← 已知窗口 |
| 对账之后 | 3 | **0** |

**诚实结论**：增量求和无法感知"转速变了但没人调用 updateStressFor"——这种情形下缓存总和最多**错一个 tick**，下个 tick 的对账会纠正（实测对账后 0 差异）。
**要完全消除这个窗口**，需要再挂一个 `KineticBlockEntity#updateFromNetwork` 的钩子，在转速变化时按 `Δ(stress × |speed|)` 做 O(1) 修正。这是下一步的收尾项。

## 14.2 🅔 观察层：零行为变更，已跑出第一份画像

按 [09 的边界文档](09-be-sleeping-boundary.md)"先观察后沉睡"，落地了观察层（**只统计，不跳过任何 tick**）：

| 统计项 | 采集点 |
| --- | --- |
| 每个 BE 类的 **tick 次数** | `SmartBlockEntity#tick` HEAD |
| 每个 BE 类的 **setChanged() 次数**（持久状态变化） | `BlockEntity#setChanged` HEAD |
| 每个 BE 类的 **客户端同步次数** | `SmartBlockEntity#sendData` HEAD |

**门禁（全部走配置，无 JVM 参数）**：配置 `observe` 默认 **false**；开启方式为配置文件 `observe = true` 或运行时 `/fettle feature observe true`（测量/观察类 mixin 始终应用，靠 handler 内的一个 volatile 标志自关，因此不需要重启、也没有 JVM 参数）。未开启时实测 `observed classes=0`。

### 首份实测（`/fettle idle report`，观察 25 秒）

```
observed classes=3 never-changed=3
  MechanicalCrafterBlockEntity    ticks=1351792  changed/tick=0.0000  sendData/tick=0.0000
  BracketedKineticBlockEntity     ticks=214208   changed/tick=0.0000  sendData/tick=0.0000
  CreativeMotorBlockEntity        ticks=536      changed/tick=0.0000  sendData/tick=0.0000
```

解读：这套测试场景里 3000 个机械合成器、400 多根轴在 25 秒内共 tick 了 **156 万次**，却**一次状态变化、一次同步都没有**——正是"空转 BE"的典型形态。
（真实基地里这些类的 `changed/tick` 会 > 0，观察层的价值就在于把"哪些类在真空中烧 tick"用数据指出来，而不是拍脑袋。）

## 14.3 两个"下一步"（都还差最后一段）

1. **🅑-2 收尾**：挂 `updateFromNetwork` 钩子，把"转速变化"也纳入 O(1) 记账 → 目标是把差分测试的 `speedChanged` 阶段也变成 **0 差异**；
2. **🅔 试点**：挑**一个**`changed/tick == 0` 的类，按边界文档实现"观察→沉睡→唤醒→回退→自动拉黑"，并跑 `runGameTestServer` 行为门禁；唤醒条件必须全实现（方块更新/红石/区块重载/玩家靠近/交互/capability 查询）。

## 14.4 复现

```bash
# 场景
fettle debug kinetics 400                 # 400 根轴 + 创造马达
# 🅑-2
fettle feature kineticIncrementalStress true|false
fettle micro kineticStress 2000           # 6.612 -> 0.031 us/op
fettle debug kinetictest 3                # 差分：0 / 10（转速窗口）/ 0
# 🅔（需以 -Dfettle.observe=true 启动）
fettle feature observe true
fettle idle report
```

