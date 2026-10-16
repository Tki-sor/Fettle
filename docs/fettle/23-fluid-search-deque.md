# 23 · 流体 BFS 出队（F1）：**数据结构收益 519×，但实现未通过 mixin 应用门禁，未发布**

## 23.1 结论先说

- **收益是真的、而且很大**：把"用 `ArrayList.remove(0)` 出队"换成 `ArrayDeque.pollFirst()`，
  在 n=50000 的 frontier 上，出队总耗时 **94.89 ms → 0.183 ms（≈519×）**（微基准 `fluidSearchQueue`）。
- **实现没通过我们的发布门禁**：第一版 mixin 在目标类加载时
  `MixinApplyError: Mixin [fluids.FluidManipulationSearchMixin] from phase [DEFAULT] FAILED during APPLY`
  → 触发它的 `/setblock ... create:hose_pulley` 直接抛 `MixinTransformerError`。
  **已按策略回滚**（mixin 文件、mixin 列表项、配置开关、HotFlag 全部移除），回滚后实测
  `MixinApplyError=0`、服务器正常 `Done (` 启动。

## 23.2 为什么会被卡住（原因已定位到具体成员）

重写 `FluidManipulationBehaviour#search` 必须读目标类的这几个成员：

| 成员 | 可见性 | 我们的第一版做法 | 问题 |
| --- | --- | --- | --- |
| `rootPos` | **包私有** | `@Shadow` | Mixin 对**包私有**成员要求 mixin 与目标同类包；我们不在同一包 → APPLY 失败（最可能的直接原因） |
| `maxBlocks()` / `maxRange()` / `canDrainInfinitely(Fluid)` | protected | `@Shadow` | 一般可用，但需一并复核 |
| `getWorld()` | public（继承） | `@Shadow` | 无问题 |
| `searchedPerTick` | `static final` 编译期常量 | 直接硬编码 1024 | 无问题（但属于**脆弱点**：Create 改这个常量我们不会感知） |

**正确的修法**（下一步，范围明确）：为 `rootPos` 写一个 `@Accessor` 访问器 mixin
（`@Accessor` 不受可见性限制），必要时把三个 protected 方法改成 `@Invoker`；
并且**必须先确认 APPLY 成功**（导出字节码 + 启动无 MixinApplyError）再谈性能。

## 23.3 已经拿到的、可复现的一半

微基准 `fluidSearchQueue`（新增，纯数据结构对比，不依赖游戏场景）：

```
fettle micro fluidSearchQueue 50000
→ fluidSearchQueue: n=50000 ArrayList.remove(0)=94.89 ms  ArrayDeque.poll=0.183 ms  speedup=519x
```

它证明的是"该处 O(n²) → O(n) 的替换值得做"，但**不等于端到端 tick 收益**——端到端要有
真实软管滑轮 + 大水体场景，而那需要在 mixin 能正确 APPLY 之后才能测（本次就卡在这一步）。

## 23.4 现状与后续

| 项 | 状态 |
| --- | --- |
| 数据结构收益 | ✅ 已实测（519×） |
| mixin 实现 | ⛔ 未发布（APPLY 失败，已回滚） |
| 端到端 tick 收益 | 🧪 未测（依赖上一条） |
| 下一步 | ① 用 `@Accessor` 取 `rootPos`；② 先验证 APPLY（启动无 MixinApplyError）；③ 再搭软管滑轮 + 水体场景做 on/off 的 tick A/B |

同类候选 ② `PumpBlockEntity#distributePressureTo` 与 ③ `FluidPropagator` 的两处 BFS 风险更低
（无包私有字段依赖、n 受 `mechanicalPumpRange=16` 限制），可以作为下一次的先头部队。
