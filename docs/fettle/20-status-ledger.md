# 20 · 状态台账（唯一权威口径）

> 对外表述一律以此表为准。三档标注：
> **✅ 已验证**（有 A/B 或差分证据，可复现）· **⚠️ 无收益/未证实**（实测过但拿不到收益）· **🧪 未验证**（已实现但未测到）。
> 当前 **没有 🧪 项**。

| 项目 | 状态 | 默认 | 证据 / 数字 |
| --- | --- | --- | --- |
| 配方 bitset 预筛（`getRecipeFor`） | ✅ 已验证 | 开 | 23.4 µs → 3.5–4.1 µs（**−85%**）；parity `checked=1211 mismatches=0`，门禁每次复跑 |
| `getAllRecipesFor` 列表缓存 | ✅ 已验证 | 开 | docs 05 / 08 |
| 动能网络校验查询 memo | ✅ 已验证 | 开 | 442,200 → 201 次查询（**4.5–5×**） |
| 动能增量应力 O(1) + 每 tick 对账 | ✅ 已验证 | 开 | 6.612 → 0.031 µs/op（**213×**）；`afterReconcile` 0 mismatch |
| 列车碰撞候选过滤（+移动守卫） | ✅ 已验证 | 开 | 候选 **−94%**；差分 `fresh`/`stale_guarded` **0 mismatch**（`stale_raw` 3 missed 作为反例保留） |
| TravellingPoint 位置 memo | ✅ 已验证 | 开 | 30–33 → 2.4–5.5 ns/op（**6–13×**） |
| `TrackGraph#getConnectionsFrom` 分配 | ✅ 已验证 | 开 | docs 05 / 10 |
| 包裹地址正则缓存 | ✅ 已验证 | 开 | 552–567 → 136–145 ns/op（**~4×**） |
| **`BeltBlock#hasShaftTowards`（A8）** | ⚠️ **无收益（本轮实测）** | 开 | 原版 **9.14 / 12.09 / 11.49** ns/op vs Fettle **10.17 / 11.15** ns/op（200–400 万次/轮）→ 差值小于轮间抖动；`Optional` 被逃逸分析标量化。见 [21](21-a8-belt-shaft.md) |
| ① 车载存储同步快速路径（`MountedStorageManager#tick`） | ⚠️ **稳态无收益** | 开 | 冷态 8.50 ns/op & 51.3 B/op → C2 后 1.17 ns/op & **0.0 B/op**；逃逸分析把两个 HashMap 消掉了。见 [22](22-allocation-optimisations.md) |
| ② `SmartBlockEntity#tick` 迭代器/lambda | ⚠️ **前提不成立** | — | 实测 **0.0 B/op**，运行时没有可见分配，未改代码。见 [22](22-allocation-optimisations.md) |
| 流体 BFS 出队（F1） | ✅ **已验证**（真实 5000 格水体 ~1.5x；accepted/leftover/顺序敏感哈希逐格一致） | **开** | ArrayDeque 替换 ArrayList.remove(0)：纯队列 519x；稳态 360 vs 540-572 us/call；六次运行 accepted=439、leftover=442、hash 全同；APPLY ✅。整机 MSPT 未测（装置泵不转）。见 [23](23-fluid-search-deque.md) |
| 面板聚合节流 | ⚠️ **未证实** | **关（1）** | 脚手架场景面板段 −72%；自然激活客户端场景 0.5210 vs 0.5302 ms **无差异**。见 [19](19-client-spark-test.md) |
| 沉睡试点：观察层 | ✅ 已验证 | 关（`observe.enabled`） | 逐类 tick/setChanged/sync 计数 + `fettle-observe.csv` 长期采集 |
| 沉睡试点：白名单沉睡 + 心跳 | ✅ 已验证 | 关（`beSleeping.enabled`，白名单空） | 稳态 **19:1**（95% 跳过），实测 19.08 / 19.10 |
| 沉睡试点：唤醒条件 | ✅ 已验证 | 随试点 | `setChanged` / 心跳 / 一键暂停 / **方块+邻居更新** / **capability 查询** / **玩家交互** / **玩家接近**（18.3、18.6.1） |
| 沉睡试点：区块重载 | ✅ 天然安全 | — | BE 重载即新实例，沉睡状态是 `@Unique` 实例字段 |
| 沉睡试点：客户端 tick | ✅ 明确不参与 | — | `shouldTick` 对 `isClientSide()` 直接放行 |

## 20.1 关于两个 ⚠️ 项的处理决定

- **面板节流**：默认已改回 **1（原版）**=功能关闭；文档 05/07 的数字加了警示块，README 状态表同步改为"未证实"。
- **A8 皮带轴查找**：行为与原版**完全等价**（只是把 `Optional.map.orElse` 换成 `instanceof`），因此**保留开启**，
  但**不作为性能卖点**；若未来 Create 改动导致这个 `@Overwrite` 冲突，可以直接删掉该 mixin，无功能损失。

## 20.2 复现入口

```bash
python tools/fettle_gate.py                 # 行为门禁：parity + 碰撞/动能差分 + 沉睡比 → [gate] PASS
# A8 微基准（需分别以 create.beltShaftLookup=true/false 各启动一次）
fettle micro beltShaft 2000000
```
