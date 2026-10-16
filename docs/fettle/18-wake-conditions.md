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

