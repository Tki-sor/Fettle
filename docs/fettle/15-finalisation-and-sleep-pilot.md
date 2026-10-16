# 15 · 收尾：转速窗口的结论 + 🅔 沉睡试点实测

## 15.1 🅑-2 收尾："关掉转速窗口"这条路被数据否掉了

按计划挂了 `KineticBlockEntity#setSpeed` 钩子（转速变化 → 按 `Δ(stress×|speed|)` 做 O(1) 修正），差分测试结果：

| 阶段 | 检查次数 | 与精确值不一致 |
| --- | --- | --- |
| 仅改应力（同 tick） | 27 | **0** |
| 经 `setSpeed` 改转速（同 tick） | 15 | **10** |
| 直接写 `speed` 字段（绕过 setter） | 27 | **18** |
| 对账之后 | 3 | **0** |

钩子确实触发了（`speedAdjusts=15`），但**不一致比例没有下降**。查源码找到原因：
`KineticBlockEntity` 内部有 **三处直接给 `speed` 赋值**（第 151/329/490 行），完全绕过 setter —— **任何 setter 钩子都看不到这些写入。**

**处置（基于数据）**：**删掉这个无效钩子**，不再携带没有收益的复杂度。结论写清楚：
- 增量求和能保证**应力变化路径完全精确**；
- **转速变化路径**存在**最多 1 个 tick** 的窗口，由**每 tick 一次对账**兜住（实测对账后 0 差异）；
- 要彻底关掉必须拦截**字段直接写入**（字节码级 field-write redirect），代价与收益不成比例 → **不建议做**。

## 15.2 🅔 沉睡试点：机制全部跑通，而且拦住了一个不该睡的类

### 契约实现（与 [09](09-be-sleeping-boundary.md) 一致）

| 契约 | 实现 |
| --- | --- |
| 默认关闭 | 只有显式 `/fettle debug sleep <类> <观察tick> <最大跳过>` 才生效 |
| 逐类白名单 | 只影响被点名的**单个类** |
| 先观察 | 每个实例必须连续 tick `observeTicks` 次且**期间没有 setChanged** 才允许沉睡；一旦变化立刻清零重数 |
| 心跳 | 每连续跳过 `maxSkipTicks` 次强制 tick 一次 |
| 唤醒 | `setChanged` 立即唤醒该实例（计 `wakes`） |
| 一键回退 | `/fettle idle wake` 立即全部唤醒并停止跳过 |
| 自动拉黑 | 跳过路径任何异常 → 立即禁用整个试点（计 `blacklist`），只记日志 |

### 实测（对传动轴类 `BracketedKineticBlockEntity`，本世界 400+ 根；observe=40、maxSkip=10、跑 24 秒）

```
beSleeping enabled=false target=- observeTicks=40 maxSkipTicks=10
  ticks=158400 skipped=36000 heartbeats=3600 wakes=0 blacklist=0
```

- **跳过生效**：36,000 次 tick 被跳过；
- **心跳精确**：36,000 / 10 = **3,600** 次心跳，与设计完全一致；
- **零异常、零拉黑**；`/fettle idle wake` 之后 `enabled=false`，恢复原状。

### 最重要的发现：试点自己给出了否决

同一场景的观察画像：

```
observed classes=3 never-changed=1
  MechanicalCrafterBlockEntity   ticks=355602  changed/tick=0.0000
  BracketedKineticBlockEntity    ticks=55648   changed/tick=0.0288   ← 试点选它
  CreativeMotorBlockEntity       ticks=140     changed/tick=0.0286
```

传动轴的 `changed/tick = 0.0288`（约每 35 tick 就 `setChanged` 一次），按"连续 40 tick 无变化才允许沉睡"的规则，**它几乎永远无法进入沉睡**——实测 158,400 次 tick 只跳过了 36,000 次（约 19%），正是"偶尔睡一下就被唤醒"。

**这正是我们想要的行为**：试点机制**没有**把一个仍然在改状态的类当空转类睡掉。
本世界里真正满足 `changed/tick == 0` 的是 `MechanicalCrafterBlockEntity`（测试场景里的空转合成器）——那才是下一步该试点的对象。

## 15.3 下一步（明确收尾项）

1. **换一个满足条件的类跑试点**（本世界里的空转合成器），验证"长时间沉睡 + 心跳 + 唤醒"的完整行为，并跑 `runGameTestServer` 行为门禁；
2. **覆盖非 Create 的 BE**：目前只能在 `SmartBlockEntity#tick` 上拦截（Create 自家 BE）。要覆盖第三方 mod，需要在 `LevelChunk$BoundTickingBlockEntity` 的 ticker 调用处拦截（路径已确认，未落地）；
3. 观察层可长期开启，先筛出"真正常态空转"的类再决定白名单。

## 15.4 复现

```bash
# 以 -Dfettle.observe=true 启动（或 env FETTLE_OBSERVE=true）
fettle feature observe true
fettle idle report                                  # 每类 ticks / changed per tick / sync per tick
fettle debug sleep BracketedKineticBlockEntity 40 10
fettle idle sleeptest                               # ticks/skipped/heartbeats/wakes/blacklist
fettle idle wake                                    # 一键回退
```

