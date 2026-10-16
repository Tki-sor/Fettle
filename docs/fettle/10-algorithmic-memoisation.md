# 10 · 激进路线第二步：算法级记忆化（🅑-1）

## 10.1 做了什么

`Train#findCollidingTrain` 的结构是：

```
for (Train other : 所有列车)              // T
  for (Carriage c : other.carriages)       // C
     start2 = c.leading.getPosition(graph)    // ← 这里
     end2   = c.trailing.getPosition(graph)   // ← 和这里
```

即 **O(T²·C) 次 `TravellingPoint#getPosition`**，每次都要算 `t = position/edge.getLength()`、做插值与法线计算，并**分配若干 `Vec3`**。而答案只在"点动了"之后才变。

新增 `TravellingPointPositionMixin`：在 `getPositionWithOffset` 上做**精确键记忆化**——

- 缓存键 = `(server tick, graph 身份, edge 身份, position, offset, flip, upsideDown)`；
- `@Inject(HEAD)` 命中则直接返回缓存；`@Inject(RETURN)` 写入缓存；
- **跨 tick 一律失效**（由新增的 `ServerTickClock` 驱动，在 `tickServer` 头部自增），所以"同一 tick 内轨道被改动"最多影响该 tick 剩余部分，下一 tick 立刻恢复精确值；
- 缓存放在**目标对象自身的 `@Unique` 字段**上（不是 Map）→ 命中路径零分配。

## 10.2 实测（同 JVM，交替 2 组）

`/fettle micro travelPointPosition 3000000` —— 模拟一个 tick 内对同一点反复取世界位置：

| 模式 | ns/op | 返回值 |
| --- | --- | --- |
| **记忆化 on** | **5.5 / 2.4** | x=3.000 |
| 记忆化 off（原路径） | 32.9 / 30.1 | x=3.000 |

→ **快 6–13 倍**，返回值完全一致（两种模式输出都是 `x=3.000`）。

## 10.3 与"换算法"的关系（重要）

记忆化解决的是**每次比较的成本**，没有减少**比较的次数**：`findCollidingTrain` 仍是 O(T²·C) 次两两比较。
完整方案还需要 **🅑-1b：按"图 + 粗粒度格子"建车厢空间索引**（每 tick 重建一次 O(C)，之后每次查询只测附近格子里的车厢），才能把 T² 降下来。

> 现有代码里已有一个近似空间过滤：`min(dist(start2,start), dist(end2,start)) > maxAssemblyLength² → continue Trains`。
> 用它作为**保守半径**做格子划分，就能在不改变语义的前提下把候选集缩到常数级。这一步还没做。

## 10.4 目前的账（全项目）

| 项 | 实测 | 置信度 | 状态 |
| --- | --- | --- | --- |
| 🅐 预编译位图索引 | 每次配方查找 **−85%**（23.4→3.5–4.1 µs） | 高（交替 2 组 + 1211 条配方零偏差） | **已落地** |
| 🅑-1 位置记忆化 | **6–13×**（30–33 → 2.4–5.5 ns/次） | 高（交替 2 组，返回值一致） | **已落地** |
| B8 面板 5t | 面板段 **−72%**，总 tick −0.4~−1.2 ms（4500 面板） | 高（双向重复） | 已落地 |
| A15 地址正则缓存 | **~4×** | 高 | 已落地 |
| A1 TrackGraph | −14~20 ns/次 | 中 | 已落地 |
| A8 BeltBlock | 未测 | — | 待测 |
| 🅑-1b 车厢空间索引 | 未实现 | — | **下一步** |
| 🅑-2 动力学网络增量维护 | 未实现 | — | 待做 |
| 🅔 BE 沉睡 | 边界已定义，未实现 | — | 待做（先做观察层） |
| 🅕 AppCDS / JVM 参数 | 未测 | — | 可随时做 |

## 10.5 复现方式

```bash
fettle feature travelPositionCache true|false ; fettle micro travelPointPosition 3000000
fettle feature bitsetIndex true|false          ; fettle micro getRecipeFor 200000
fettle feature packageAddressCache true|false  ; fettle micro matchAddress 3000000
fettle feature trackGraph true|false           ; fettle micro trackGraphMiss 3000000
```

## 10.6 环境备注

本轮期间 dev 服务端出现**间歇性静默退出**（日志停在模组列表之后、无异常、无 "Stopping server"，进程直接消失；重跑恢复）。长基准建议：分批执行 + 每步前检查服务端存活。

