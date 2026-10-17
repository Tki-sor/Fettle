# 29 · C7 自动白名单：候选列表可用，**自动采纳被实测证明不安全**（默认关）

## 29.1 交付物一：`/fettle idle candidates <minTicks>`（可用）

观察层 `BehaviourProbe` 早就在统计每个类的 `ticks / changed / sendData`，缺的只是把人写白名单这件事变成"可粘贴的数据"。新增：

```
fettle idle report        -> observed classes=10 never-changed=8
fettle idle candidates 200 -> sleep candidates (ticks>=200, changed=0, sendData=0): 8
```

排序按 tick 数降序，输出全限定名，可直接粘进 `beSleeping.whitelist`。
**这半边是安全的、也应该用**：人看一眼名单、自己决定。

## 29.2 交付物二：`beSleeping.autoWhitelist`（实现了，但实测证明不能开）

做法：`targeted()` 里加一条数据驱动通路 —— 若 `autoWhitelist` 打开且该类 `ticks >= autoWhitelistMinTicks && changed == 0 && sendData == 0`，
就自动采纳（每类只记一条日志），其余保持不变（自动拉黑、唤醒条件、`beSleeping.enabled` 仍须单独打开）。

### 实测（hopper 场景，空人工白名单，等待 1200 tick 后）

```
[Fettle] BE sleeping: ...MechanicalCrafterBlockEntity adopted from observation (>= 1200 ticks, no setChanged, no sendData)
[Fettle] BE sleeping: ...BellBlockEntity adopted from observation (...)
[Fettle] BE sleeping: ...FurnaceBlockEntity adopted from observation (...)
beSleeping enabled=true ... sleepRegistered=3411 wakes(change/block/capability/interact/proximity)=0/0/0/0/0
```

机制本身**工作正常**（3411 个实例自动进入沉睡、零误唤醒）。但采纳名单里有 **`FurnaceBlockEntity`（原版熔炉）和 `BellBlockEntity`（钟）**，
这就是致命问题：

### 为什么不能开：`changed == 0` ≠ "不做任何工作"

沉睡实例**每 N 个跳过 tick 才真正 tick 一次**（心跳，默认 20）。对于**按时间推进状态**的方块：

- 熔炉的燃烧/熔炼进度是**按 tick 累加**的，过程中**并不调用 `setChanged`** → 观察层看到 `changed=0`，判定"空闲"，采纳；
- 一旦沉睡，它的进度变成 **1/20 速度**（只有心跳那一 tick 推进），而且**没有任何唤醒条件会触发**（没人改状态、没人交互）；
- 结果：**熔炉悄悄慢 20 倍**。这是**静默的游戏行为破坏**，比崩溃更糟。

钟、以及任何"内部计时器/进度条"型方块同理。原分析里"**绝不对 random-tick / 绝对时间依赖 / 计时器型方块下手**"这条约束，
**无法从 `ticks/changed/sendData` 这三个计数器推断出来**——这正是不该用观察数据自动决策的原因。

## 29.3 决定

| 项 | 处置 |
| --- | --- |
| `/fettle idle candidates` | ✅ **保留**（数据驱动的**候选名单**，人来拍板） |
| `beSleeping.autoWhitelist` | ⚠️ **保留实现但默认 false**，配置注释写明上述风险与实测证据 |
| 把自动采纳设为默认 | ❌ **不做** |

## 29.4 这一轮"更激进"的总账

| 候选 | 结局 |
| --- | --- |
| C1 网格接碰撞过滤 | ⛔ 差分 3/3 漏检，回滚 |
| C2 容量侧增量 | ⚠️ 前提不成立（规模差 200 倍），默认关 |
| **C7 自动白名单** | ⚠️ **实测会静默拖慢计时器型方块，默认关**；留下可用的 `candidates` 命令 |
| C4（复查附带） | ✅ 真 bug 修复（空输入） |

**结论**：机械动力里"看起来对称/看起来数据充分"的激进优化，三条里两条错、一条危险；
真正兑现的进步来自**正确性复查**与**已有的验证过的算法项**。这也说明我们坚持"默认关 + 差分/实测"的纪律是对的。
