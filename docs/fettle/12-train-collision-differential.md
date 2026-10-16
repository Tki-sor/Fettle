# 12 · 真车差分测试：**方案 B 确实有问题（3/3 漏检），已做出精确版并验证 0 差异**

你问"测一下看看会不会真有问题"——会。我用**真实的 `Train` 对象 + 真实的 `Train#findCollidingTrain`** 做了差分测试，把问题复现出来了，并顺手把修复也验证了。

## 12.1 测试怎么做的（可复用）

新增 `/fettle debug trains <迭代数>`（`TrainDebugScenarios`）：

1. 在合成直线轨道上**构造真车**：`TrackNode`/`TrackEdge`/`TravellingPoint`/`CarriageBogey`/`Carriage`/`new Train(...)`，临时注册进该维度的 `Create.RAILWAYS.sided(level).trains`，测完移除；
2. 同一场景调**两次真方法**：索引关（= 原版参照）与索引开，比较返回的列车是否一致；
3. 三个阶段：
   - **fresh**：查询前立即重建快照（模拟"该 tick 的第一次查询"）；
   - **stale_raw**：快照在"目标列车远离时"建立 → 同一 tick 内把列车**移进碰撞范围** → 再查（方案 B 的原始策略）；
   - **stale_guarded**：同一场景，但打开"移动校验"。

## 12.2 结果（连跑两次，数字完全相同）

```
collisionDifferential: fleet=6
  fresh        [n=24  collisions=3  mismatches=0]
  stale_raw    [n=24  collisions=3  mismatches=3  missed=3]     ← 方案 B
  stale_guarded[n=24  collisions=3  mismatches=0  missed=0]     ← 加校验
  index=builds=72 indexed=432 queries=120 kept=18 keptMoved=24 dropped=678
```

| 阶段 | 场景数 | 参照路径发现的碰撞 | 与参照不一致 | 漏掉的碰撞 |
| --- | --- | --- | --- | --- |
| **fresh**（快照新鲜） | 24 | 3 | **0** | 0 |
| **stale_raw**（方案 B） | 24 | 3 | **3** | **3** |
| **stale_guarded** | 24 | 3 | **0** | **0** |

**结论**：
- 快照新鲜时，候选过滤与**原版逐字等价**（24 组零差异，且 3 次真碰撞都照样找到）；
- **方案 B 的缺陷是真的、可复现的**：同一 tick 内其它列车移动进范围后，**3 次碰撞全部被漏检**（该 tick 内检测不到，最坏情况是撞车判定晚一个 tick / 或高速对向列车短暂互穿）；
- 加一行**廉价的"移动校验"**（只读每节车厢的 `(edge, position)` 字段，不调用 `getPosition`）后，**差异归零**，同时仍然砍掉绝大部分候选。

## 12.3 工作量对比（确定性指标）

120 次查询 × 6 列车 = **720 次"列车级候选评估"**：

| 策略 | 实际交给原版循环的候选 | 降幅 |
| --- | --- | --- |
| 原版（无索引） | 720 | — |
| **索引 + 移动校验（推荐）** | **42**（kept=18 + keptMoved=24） | **−94%** |
| 索引 + 无校验（方案 B） | 18 | −97.5%（但有漏检） |

也就是说：**校验只花了 24 次列车（每查询 0.2 列车），就把 3/3 的漏检问题彻底消掉**——这个买卖明显划算。

## 12.4 处置建议

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `trainCollisionIndex` | **true** | 启用候选过滤（配合 🅑-1 的位置记忆化） |
| `trainCollisionIndexVerifyMoved` | **true** | 移动校验：**与原版逐字等价**（本测试 0 差异） |
| （可选）设为 false | — | 原始方案 B：最快（−97.5%），代价是"移动进范围的列车在当 tick 可能漏检"，已在文档中标注 |

运行时可直接切：`/fettle feature trainCollisionIndexVerifyMoved false|true`

## 12.5 这条测试本身作为回归门禁

`/fettle debug trains <n>` 可以随时复跑，用来在后续改动（比如以后换成"增量维护"的索引）时确认**候选过滤始终与原版逐字一致**。建议把它和配方一致性自检（`/fettle selftest`）一起当作固定回归项。

## 12.6 复现

```bash
fettle feature trainCollisionIndex true
fettle debug trains 3
# => fresh 0 差异 / stale_raw 3 漏检 / stale_guarded 0 差异
```

