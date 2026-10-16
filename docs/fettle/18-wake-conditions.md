# 18 · 唤醒条件补齐：方块/邻居更新 + capability 查询（均已实测）

边界文档 [09](09-be-sleeping-boundary.md) 列出的唤醒条件里，之前只实现了 `setChanged` + 心跳 + 一键暂停。
本轮补齐了**覆盖面最大的两条**。

## 18.1 方块 / 邻居更新

- 挂点：`Level#setBlock(BlockPos, BlockState, int)` 的 HEAD。
- 为了不让这个**极热**方法变慢，沉睡实例会被登记到一张**按位置索引**的表里（`SLEEPING: Map<Level, Map<Long, BlockEntity>>`，在实例入睡时登记、唤醒时注销）。
- `wakeAround(level, pos)` 只做 **7 次哈希查找**（自身 + 6 个邻居）；**表为空时只有一次判空**，所以试点关闭时开销可忽略。

## 18.2 capability 查询

- 挂点：`net.neoforged.neoforge.capabilities.BlockCapability#getCapability(Level, BlockPos, BlockState, BlockEntity, C)` —— 这是 **NeoForge 1.21.1 里所有 capability 解析的唯一入口**（`BlockCapabilityCache`、各类机器取邻居物品/能量处理器都会走到它；确认方式：`javap` 看 `BlockCapability` 的成员与 `BlockCapabilityCache.getCapability` 的调用链）。
- 该方法直接把 `BlockEntity` 作为参数传入，所以唤醒判断是**零额外查询**的。

## 18.3 实测（400 个空漏斗，配置白名单生效）

```
[Fettle] beSleeping enabled=true ... whitelist=[HopperBlockEntity] sleepRegistered=400
         wakes(change/block/capability)=0/0/0

# 在某个沉睡漏斗上方放一块石头
[Fettle] ... wakes(change/block/capability)=0/1/0        ← 方块更新唤醒 1 次

# 问它要物品 capability
[Fettle] capability query at 0, 100, 4000 on HopperBlockEntity -> VanillaHopperItemHandler
[Fettle] ... wakes(change/block/capability)=0/1/1        ← capability 查询唤醒 1 次
```

两条都**精确命中受影响的实例**（其余 399 个保持沉睡），且两个 mixin 均已应用（日志确认）。

## 18.4 唤醒条件现状

| 唤醒条件 | 状态 |
| --- | --- |
| `setChanged`（自身状态变化） | ✅ 已有（并计入自动拉黑） |
| 心跳（每 `maxSkipTicks` 强制 tick 一次） | ✅ 已有 |
| `/fettle idle wake` 一键暂停 | ✅ 已有 |
| **方块 / 邻居更新** | ✅ **本轮补齐** |
| **capability 查询** | ✅ **本轮补齐** |
| 区块重载 / 玩家进入渲染距离 / 交互（右键等） | ⛔ 仍未实现 |

## 18.5 复现

```bash
# 配置：beSleeping.enabled = true, classes = ["HopperBlockEntity"], observeTicks = 40
execute positioned 0 100 4000 run fettle debug hoppers 12
fettle idle sleeptest                                  # sleepRegistered=400
setblock 0 101 4000 minecraft:stone replace            # → 方块更新唤醒
fettle debug cap 0 100 4000                            # → capability 查询唤醒
```


### 18.6.1 验证完成（2026-10-16 深夜补充）：两条都测到了

新增 `/fettle debug tp <x> <y> <z>`（我们的指令不受权限限制）后，之前卡住的验证全部打通：

**玩家接近**（同一场景，先记录再动作）：

```
A1 玩家在相邻区块，16 个漏斗已睡：  sleepRegistered=468   wakes=0/0/0/0/480
A2 玩家传送回漏斗所在区块后：        sleepRegistered=436   wakes=0/0/0/0/768   ← proximity +288
```

→ 进入区块即唤醒该区块内的沉睡实例；离开后它们重新入睡（sleepRegistered 又回到几百）。

**玩家交互**（专门做了跨区块隔离，排除 proximity 干扰）：

```
位置：漏斗在 (14,100,4000)（区块 0），玩家站在 (16,100,4000)（区块 1，相邻）
B1 右键之前：  sleepRegistered=436   wakes=0/0/0/0/2368   ← interact=0
B2 右键之后：  sleepRegistered=436   wakes=0/0/0/1/2688   ← interact=+1，且只 +1
```

→ 对着沉睡漏斗右键，精确认领到一次交互唤醒；该次测试里 interact 此前一直是 0，
所以这 +1 是干净信号（proximity 同期因为其他沉睡实例仍在缓慢上涨，故不构成干扰项）。

**结论**：交互 / 接近 两条唤醒条件现在**已验证**；连同此前的方块更新、capability，
以及"区块重载天然安全"，边界文档 09 里列出的唤醒条件只剩**玩家渲染距离视觉**类尚未单独处理（客户端 tick 已明确不参与沉睡）。

