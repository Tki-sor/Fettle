# 25 · 流体传播 BFS（F1 ③）：`FluidPropagator` 两处出队改成 ArrayDeque

## 25.1 改了什么

`FluidPropagator#propagateChangedPipe` 与 `#resetAffectedFluidNetworks` 的 BFS 都用 `List.remove(0)` 出队
（每次 O(frontier) 的 arraycopy）。两个方法都是 **public static**，所以用 `@Inject(HEAD, cancellable)` 整体重写，
换 `ArrayDeque.pollFirst()`；`PipeConnection#flow` 是包私有字段，用 `@Accessor` 取（`PipeConnectionAccessor`）；
`Pair<Integer,BlockPos>` 的 `Integer` 装箱换成新的 `PosDist` record。
开关 `create.fluidPropagatorDeque`（默认 true，可 `/fettle feature fluidPropagatorDeque false` 运行时对比）。

## 25.2 行为一致性：逐格哈希完全相同

新场景 `/fettle debug pipenet <r>` 造 **1089 根管道**的网格（33×33，互相连通），
新基准 `/fettle debug fluidpropagate <x> <y> <z> <n>` 反射调用真实的 `propagateChangedPipe` n 次，
并对整个网络的**可观测状态**做 FNV 哈希：每根管道的 `hasAnyPressure()`、每个方向的
`getConnection()!=null` / `hasFlow()` / `hasNetwork()`（遍历本身用 public API 走一遍网络）。

6 次测量（3 轮 ON/OFF 交错，每次 200 次调用）全部得到：

```
pipes=1089  noPressure=1089  hash=0x88445592608014ff
```

→ 被 wipe 的管道集合、以及每根管道的连接/流动/网络状态**逐项一致**。

## 25.3 性能：方向有利，但在这个拓扑上幅度小且与噪声重叠

| 轮次 | deque ON（us/call） | vanilla OFF（us/call） |
| --- | --- | --- |
| 1 | 1042.9 | 1290.8 |
| 2 | 1005.3 | 1246.2 |
| 3 | 975.6 | **944.1** |
| 均值 | **1007.9** | 1160.4 |

平均快 **约 13%**，但第三轮 OFF 反而更快——**不能称其为确定的收益**。原因很清楚：
网格的**前沿很窄**（2D 网格的 BFS frontier ≈ 周长 ≈ O(√n) ≈ 33），
所以 `remove(0)` 每次只搬约 33 个引用；总耗时（约 1 ms）几乎全在 `getPipe`/`getBlockState` 世界查询上。

这也解释了为什么 F1 ①（5000 格水体，frontier 很宽）能看到稳定的 1.5×，而这里只有小幅/噪声级别：
**`remove(0)` 的代价与 frontier 宽度成正比**，窄前沿的网络（树状/网格状）几乎不受影响。

算法上 `pollFirst()` 是 O(1)、`remove(0)` 是 O(frontier)，所以这个改动**永远不会更差**，只是收益取决于拓扑。

## 25.4 决定与状态

| 项 | 状态 |
| --- | --- |
| 行为一致性 | ✅ 6 次运行哈希逐格相同 |
| APPLY | ✅ `MixinApplyError=0` |
| 性能 | ⚠️ 小且与噪声重叠（均值 -13%，单轮出现反转）；宽前沿拓扑才明显 |
| 默认值 | **开**（算法上不可能更差 + 一致性已证） |

## 25.5 复现

```bash
fettle debug pipenet 16            # 在当前位置造 33x33 管道网格
fettle debug fluidpropagate 0 100 0 200
fettle feature fluidPropagatorDeque false   # 再跑一次对比
```
