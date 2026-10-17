# 27 · C1 尝试：把空间网格接进真实列车碰撞过滤 —— **我们的差分测试把它否掉了**

`24-more-aggressive.md` 把 C1 排在第一位：`SpatialBuckets` 早已存在、微基准原型实测
距离判断 **160,000 → 1,999（−98.8%）**、耗时 **13.7×**，唯独没接进真实路径。我去接了。

## 27.1 做法

- 给 `SpatialBuckets` 加盒子 API：`addBox(min..max, id)` / `collectBoxes(x,y,z,radius,out)`（返回 -1 表示缓冲区不足，
  让调用方退回线性路径，绝不静默丢候选）；
- `TrainCollisionIndex`：`ensureBuilt` 里按源顺序给每列车编号、把盒子灌进网格；`filter` 用网格取候选，
  **仍然对每个候选跑原来的精确 `boxDistanceSqr` 与 moved 守卫**，并按编号排序还原源顺序；
- 保留线性路径作为回退，并给计数器加了 `tested=` / `fellBack=`（这组计数器就是后来发现问题的关键）。

## 27.2 第一次跑：门禁绿了，但**新路径一次都没被用到**

```
index=builds=72 indexed=432 queries=120 kept=18 keptMoved=24 dropped=678 tested=0 fellBack=120
[gate] PASS
```

原因：网格格子边长取的是"最大列车盒子尺寸"，而查询半径是服务器配置 `maxAssemblyLength`（128）→
`maxAssemblyLength > 4 × gridCell`，**120 次查询全部回退**。

**教训（值得单独记住）**：门禁 PASS 而计数器显示新代码零执行，这种绿是**无意义的绿**。
加计数器不是为了好看，是为了能分辨"通过了"和"根本没跑"。

## 27.3 第二次跑：格子边长改用查询半径 → 差分立刻抓住

```
index=builds=72 indexed=432 queries=120 kept=18 keptMoved=0 dropped=3 tested=21 fellBack=0
collisionDifferential: fresh=[mismatches=0] stale_raw=[mismatches=3 missed=3] stale_guarded=[mismatches=3 missed=3]
[gate] FAIL  - collision guarded phase diverged
```

`tested=21` 说明网格路径真的跑起来了；接着 **`stale_guarded` 3/3 漏检**（原版有碰撞、我们没有）。
计数器 `keptMoved` 从 24 掉到 0，一眼就能看出**moved 守卫再也没被触发过**。

## 27.4 根因：网格与 moved 守卫在语义上互斥

守卫存在的前提正是：**某列车的快照盒子在范围外，但它在这个 tick 内又移进了范围**。
网格只能枚举"快照盒子在半径内"的格子 —— 那类列车**根本不会被枚举到**，守卫自然没机会救它。
所以只要保留"每 tick 一次快照 + moved 守卫"（方案 B，[12](12-train-collision-differential.md) 已经证明去掉守卫会漏检），
网格过滤在真实路径上就是**不安全的**；原型那 13.7× 的前提是"快照期间没有东西移动"。

## 27.5 处置：回滚，并把这件事记下来

- `git checkout` 回滚 `TrainCollisionIndex` / `TrainCollisionMixin` / `SpatialBuckets` 三处改动；
- 回滚后门禁恢复：`stale_guarded=[mismatches=0 missed=0]`、`keptMoved=24`、`[gate] PASS` ✓；
- C1 在台账里记为 **⛔ 已评估、实测不可行（差分 3/3 漏检），已回滚**。

## 27.6 如果想真正拿到这 13.7×（未做，记录思路）

唯一安全的形态需要一个**全局"本 tick 有没有列车移动过"信号**（例如注入 `Train#tick`/车厢 tick 置一个标志）：
全部静止时走网格（停车场的列车是大头场景），**一旦有东西动过就整 tick 回退线性**。
这样既不破坏守卫语义，也能在"停着的车队"上拿到收益。代价是新增一个 tick 钩子 + 再一轮差分验证。
