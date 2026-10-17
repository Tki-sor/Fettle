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

## 25.6 F1 ② `PumpBlockEntity#distributePressureTo`：**决定不做**（附理由）

原分析里的第二处 BFS。核对后判定**不值得改动**，理由是实测与结构两方面的：

1. **触发频率极低**：调用链是 `onSpeedChanged`（泵转速绝对值变化）→ `updatePressureChange` → 下一 tick 的
   `distributePressureTo`。稳态转速不变时**根本不执行**；
2. **frontier 有硬上界**：`maxDistance = FluidPropagator.getPumpRange()`（默认 16），`distance + 1 >= maxDistance`
   即停止扩张 → 搜索范围是半径 16 的球，前沿宽度最多几十到一百量级，
   而 `remove(0)` 的代价正比于前沿宽度 → 单次只有几十微秒量级；
3. **它不在 tick 热路径上**：即使优化，对 MSPT 的贡献也是 0（只在玩家改管道/转速变化时的一次性开销）；
4. 它顺带能去掉的 `Pair<Integer, BlockPos>` 装箱属于**分配消除**类——我们已经用 A8/①②证明
   HotSpot 的逃逸分析会把这类短命分配消掉（[22](22-allocation-optimisations.md)）；
5. 而它的重写需要一整段与 `pipeGraph`/`validFaces` 强耦合的粘贴代码（见
   [docs/perf/overwrites/PumpBlockEntity-distributePressureTo.md](../perf/overwrites/PumpBlockEntity-distributePressureTo.md)，300+ 行），
   风险/收益比明显不划算。

**结论**：F1 三处里，① 与 ③ 已做并入库；② 明确记录为「已评估、决定不做」，
若将来它在 profiler 里真的冒头（例如某个把泵转速切得很频繁的装置），再回来做也不迟。
