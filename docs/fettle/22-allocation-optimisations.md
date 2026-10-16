# 22 · 分配消除类优化实测（① ② + A8）：**稳态收益为零**

本轮按计划做了 ① `MountedStorageManager#tick` 与 ② `SmartBlockEntity#tick`，加上之前的 A8，
三个都属于"消除分配"这一类。结论很一致，值得记下来当经验：

## 22.1 ① `MountedStorageManager#tick`（每 8 tick 两个 HashMap）

先纠正原分析里的一条：**不是"每车厢每 tick"**。字节码显示方法开头就是 `syncCooldown > 0 → return`，
只有冷却结束（发送后设为 **8**）才会走到两个 `new HashMap`，而且两个 map 会被塞进
`MountedStorageSyncPacket` 交给网络层 → **不能池化复用**（会与网络发送竞争）。

因此实现的是**行为等价的安全提前返回**：`syncedItems` 与 `syncedFluids` 都为空时直接返回
（原版在这个分支里也只是分配两个 map + 两次 `isEmpty`，什么也不做）。
开关 `create.mountedStorageFastPath`（默认开，可 `/fettle feature mountedStorageFastPath false` 运行时对比）。

微基准 `mountedStorageTick`（500 万次，`ThreadMXBean#getThreadAllocatedBytes` 同时测分配）：

| 状态 | ns/op | B/op |
| --- | --- | --- |
| 快速路径**开** | 2.10 / 2.57 | 0.2 / 0.0 |
| 快速路径**关**（原版），**首次**（未 C2） | **8.50** | **51.3** |
| 快速路径**关**（原版），**热身后** | **1.17** | **0.0** |

→ 原版那两个 `HashMap` 在方法被 C2 编译后**被逃逸分析标量化**（空 map 不外逃、只被 `isEmpty` 读），
稳态与原版**没有差别**；只有在 C2 编译之前（或纯 C1/无 JIT 环境）才有 51 B/op 的差别。

## 22.2 ② `SmartBlockEntity#tick`（原分析称每 BE 每 tick 迭代器/lambda）

微基准 `smartBETick`（200 万次，真实放置的 `ShaftBlockEntity`）：

```
smartBETick: 19.28 ns/op, 0.0 B/op
smartBETick: 11.43 ns/op, 0.0 B/op
```

**B/op = 0.0** → 这条"分配"在运行时**根本不存在**（lambda 被缓存/标量化，`forEachBehaviour` 的迭代也不产生可见分配）。
**前提不成立，无需修改代码。**

## 22.3 合起来的一般结论

| 项目 | 优化内容 | 稳态结果 |
| --- | --- | --- |
| A8 皮带轴查找 | `Optional.map.orElse` → `instanceof` | 无差异（9.14/12.09/11.49 vs 10.17/11.15 ns/op） |
| ① 车载存储同步 | 跳过两个空 HashMap | 无差异（C2 后原版也是 0 B/op） |
| ② 方块实体 tick | 迭代器/lambda | **前提不成立**（0 B/op） |

**所以：在 HotSpot 下，"消除短命分配"这类微优化在稳态基本拿不到收益——逃逸分析已经把它们消掉了。**
真正有效的是**算法级/索引/记忆化**改动，这一点与我们已验证的清单完全吻合：

- 配方 bitset 预筛（−85%）、动能增量应力（213×）、动能查询 memo（4.5–5×）、
  列车碰撞候选过滤（−94%）、TravellingPoint memo（6–13×）、包裹地址正则缓存（~4×）。

**方法论收获**：判断"分配类优化值不值得做"，先量 `ThreadMXBean#getThreadAllocatedBytes`（B/op），
再看稳态 ns/op——只看冷态数字会把逃逸分析已经消掉的分配当成收益。

## 22.4 复现

```bash
./gradlew runServer
fettle micro mountedStorageTick 5000000                    # 开着（默认）
fettle feature mountedStorageFastPath false
fettle micro mountedStorageTick 5000000                    # 第一次 vs 第二次，差别就在 C2
fettle micro smartBETick 2000000                           # B/op = 0
```
