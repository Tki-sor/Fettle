# 30 · 让"该动的会动"：观察不够，必须**运行时自检**

## 30.1 先回答问题：能不能保证？

三种做法，只有第三种是**可证明**的：

| 做法 | 能不能保证 | 为什么 |
| --- | --- | --- |
| 用观察数据自动决策（C7） | ❌ **不能** | `changed/sendData` 只看"有没有主动报告变化"，熔炉推进进度时不报告（[29](29-auto-whitelist-verdict.md) 实测采纳了熔炉） |
| 人工白名单 | ✅ 能，但**靠人的判断** | 名单是人审过的；风险转移给人，不是消除 |
| **运行时自检**（本次实现） | ✅ **能，且可自动撤销** | 不预测、不假设：**每个心跳 tick 后核对状态是否变过**，变了就说明"跳过的 tick 不是空操作"，立刻拉黑并唤醒 |

换句话说：**无法事先知道某个类会不会做计时工作，但可以在它真的做了的时候马上发现并恢复**。

## 30.2 实现：心跳状态自检（`beSleeping.heartbeatCheck`，默认 true）

沉睡实例每 `maxSkipTicks`（默认 20）个跳过 tick 会真正 tick 一次（心跳）。在这个心跳 tick 上：

```java
net.minecraft.nbt.CompoundTag tag = be.saveWithoutMetadata(be.getLevel().registryAccess());
int hash = tag.toString().hashCode();
Integer previous = HEARTBEAT_STATE.put(name, hash);
if (previous != null && previous != hash && BLACKLIST.add(name)) {
    // 状态在"被跳过的 tick"之间还在动 => 它在做按 tick 推进的工作
    HEARTBEAT_STATE.remove(name);
    Fettle.LOGGER.info("[Fettle] BE sleeping: {} blacklisted - its state moved across heartbeats ...", name);
}
```

- **每类**每心跳只采样一次（不是每实例），成本可以忽略；
- 命中后走既有 `BLACKLIST` 通路：`targeted()` 变 false → 现有实例在下一 tick 被 `wake(be)` 唤醒；
- 熔炉的 `BurnTime/CookTime` 都在序列化状态里，**心跳前后必然不同** → 40 tick 内就会被拉黑；
- 拉黑是**永久**的（本次运行内），不会来回横跳。

## 30.3 诚实的状态：**已实现、未验证（🧪）**

这轮的验证没能跑通，如实记录：

| 尝试 | 结果 |
| --- | --- |
| `/fettle idle start <class>` | ❌ 该子命令不存在（正确写法是 `idle sleep <class> [observe] [maxSkip]`） |
| `/fettle idle sleep FurnaceBlockEntity 40 20` | ❌ 参数被拒（`word()` 不接受点号，简单名也报 Incorrect argument），**未定位到可用写法** |
| 走 `autoWhitelist` 路径 | ⚠️ 上一轮（[29](29-auto-whitelist-verdict.md)）成功采纳 3 类并注册 3411 个沉睡实例；本轮重试 `adoptions=0`，未能再次触发 |

所以：**机制在代码里、能编译、默认开，但没有任何一次"它真的触发了拉黑"的运行记录**。
按本项目的口径，它必须标 🧪 未验证，不能算数。

## 30.4 顺带查清的一件事：熔炉不熔炼**不是**我们造成的

搭熔炉场景时发现熔炉不产出（`CookTime` 在涨、`CookTimeTotal=0`、无铁锭）。做了 A/B：

| 状态 | 70 秒后 |
| --- | --- |
| `recipeIndex = false`（原版） | `CookTime=1403`，铁锭 **0** |
| `recipeIndex = true`（我们的索引） | `CookTime=1402`，铁锭 **0** |

两态**完全一致** → 这是场景/NBT 摆放问题（该熔炉的 recipe 没被匹配上），
**不是配方索引的回归**。这条必须记下来：否则以后看到"熔炉不工作"很容易误判成我们的优化搞坏了它。

## 30.5 关于"改熔炉/钟的算法"

- 原版熔炉每 tick 的配方查询在 1.21 已有 `RecipeManager.CachedCheck`（同输入重复查询被缓存），**改它收益很小**；
- 画像数据里真正的大头是 Create 自己的方块实体（`MechanicalCrafterBlockEntity` ticks=135 万、`changed/tick=0`），
  **该被优化的是这些，而不是原版熔炉/钟**；
- 因此正确路线是：**用 `heartbeatCheck` 让"沉睡"这件事变安全**（本节的机制），而不是去重写原版机器。

## 30.6 下一步（把 🧪 变成 ✅ 的最小路径）

1. 修 `/fettle idle sleep` 的参数定义（改用 `StringArgumentType.string()` / `greedyString()`，允许 FQN）；
2. 用 `debugTarget` 直接指定 `FurnaceBlockEntity` 并复现"沉睡→心跳→状态变化→拉黑"的日志；
3. 再跑一次 C7 的对照：`heartbeatCheck=false` 应无拉黑日志、`=true` 应出现且**只出现一次**；
4. 过了这两条，才能把 `heartbeatCheck` 从 🧪 升为 ✅，并考虑把 `autoWhitelist` 的安全性结论一并更新。

## 30.7 把 20–40 tick 的损失**降到 0**：把状态检查提前到观察期

30.2 的心跳自检是在"**已经睡着之后**"才发现问题，所以必然会丢掉最多 `maxSkipTicks`（默认 20）个 tick。
更好的做法是把同一个检查搬到**观察期**——那时实例还在**正常 tick**，所以拒掉它**一个 tick 都不丢**：

```java
// 观察期每个 tick（每类采样一次）：
int hash = be.saveWithoutMetadata(level.registryAccess()).toString().hashCode();
if (previous != null && previous != hash && BLACKLIST.add(name)) {
    // 状态在"正常 tick"期间自己在动 => 它做按 tick 推进的工作 => 永不沉睡
    log("... state moves while it is still being ticked (per-tick work, never slept, no tick lost)");
}
```

于是流程变成：

| 场景 | 结果 | 损失 |
| --- | --- | --- |
| 熔炉**没在烧**时观察 | 状态静止 → 允许沉睡 | 0（它本来就没做事） |
| 玩家投煤 → `setChanged` | 既有唤醒条件立刻唤醒 + 重新观察 | 0 |
| 熔炉**正在烧**时观察 | 每 tick `BurnTime/CookTime` 在变 → **观察期就被拉黑** | **0**（从未睡过） |
| 计时工作**在无任何唤醒事件**下开始 | 只有心跳兜底能发现 | ≤ 20 tick，一次，之后永久拉黑 |

所以常见形态（熔炉/钟/一切"有人在用就会变状态"的机器）**损失为 0**；
剩下那条"静默开始计时工作"的路径本来就极少见（任何状态变化都会触发唤醒条件），而且只损失一次。

### 还能再进一步（未做）

把心跳节奏做成**指数启动**：第 1、2、4、8… 个跳过 tick 就验证一次，验证多次都没问题才放宽到 20。
这样即便走的是兜底路径，初始损失也只有 **1 tick**。

### 状态：仍是 🧪（代码已入库，端到端验证没跑完）

`observeCheck` 已实现并通过编译；但本轮的验证脚本在服务器**尚未启动完成**时就跑了（RCON 端口未监听，
世界因为前面几个场景累积了大量强制加载区块，启动已超过 2 分钟），因此**还没有"日志里出现拉黑行"的运行记录**。
验证方法同 30.6：让一个**正在燃烧**的熔炉被观察到，日志应出现
`state moves while it is still being ticked`，且 `FurnaceBlockEntity` 从未出现在 `sleepRegistered` 里。
