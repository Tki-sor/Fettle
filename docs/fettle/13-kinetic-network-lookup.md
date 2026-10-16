# 13 · 🅑-2 动力学网络：把 O(M) 的方块实体查询变成每 tick 一次

## 13.1 问题

`KineticNetwork#calculateStress` / `calculateCapacity` 对**每个成员**都做一次校验：

```java
if (be.getLevel().getBlockEntity(be.getBlockPos()) != be) { iterator.remove(); continue; }
presentStress += getActualStressOf(be);
```

加法本身很便宜，**`level.getBlockEntity(pos)` 才是大头**（区块 + section 查找）。而 `updateStress()` 会在每次发电机变速、每次成员变更时被调用 —— 同一个 tick 内，同一批位置被反复校验，结果却完全相同。

## 13.2 做法

`@Redirect` 掉这两个方法里的 `Level#getBlockEntity(BlockPos)`，改走**每 tick 的记忆化**（`LevelBlockEntityCache`：`(level, pos)` → BE，tick 变化即清空）。

- **比较结果完全相同**：外层仍是 `!= be` 判断，只是取值来自缓存；
- **零行为变更**：不跳过任何校验，只是不重复查同一格；
- 计数：`realLookups`（真实查询）/ `cacheHits`（命中）。

## 13.3 实测（真实动力学网络：200 根传动轴 + 1 个创造马达 = **201 成员**）

场景由新增的 `/fettle debug kinetics <数量>` 搭建（放置一条轴 + 一个创造马达作为旋转源；**没有源时网络成员表是空的**，根本测不到东西——这是本轮踩到的第一个坑）。
基准：`/fettle micro kineticStress <迭代数>`，对真实网络成员反复调用真实的 `updateStressFor`。

| 模式 | us/op（2000 次/组） | 真实方块实体查询 |
| --- | --- | --- |
| **记忆化 on** | **6.59 / 8.10** | **201**（每成员每 tick 一次） |
| 记忆化 off（原路径） | 30.04 / 36.46 | **442,200** |

→ **约 4.5–5 倍**，且**方块实体查询 442,200 → 201（−99.95%）**；单成员扫描成本从 ~150 ns 降到 ~33 ns。
每次扫描的成本随成员数线性增长：201 成员时原路径单次 **30 µs**；几千成员的基地会更夸张，而发电机每次变速都会触发一次。

## 13.4 还没做的部分（🅑-2 的真正目标）

这一步砍掉的是**查询成本**，没有砍掉 **O(M) 的遍历本身**。要把遍历也消掉，需要**增量维护**应力总和：

- 做法：`updateStressFor(be, stress)` 时只更新该成员的增量（−旧贡献 + 新贡献），O(1)；
- **风险**：成员的**转速**变化如果不伴随一次 `updateStressFor`，增量总和会与真实值漂移 → 应力读数错误 → 机器错误停转。Create 里 `updateStressFor` 只从 `GeneratingKineticBlockEntity`（发电机变速）调用，普通成员的转速变化路径不完全覆盖；
- **安全设计**（下次做）：增量为主 + **每 N 次或每 N tick 做一次全量对账**，把漂移窗口限制在很短时间内，并在对账时记录差异（可作为自检指标）。

## 13.5 复现

```bash
fettle debug kinetics 200          # 放置 200 根轴 + 创造马达
fettle feature kineticValidityCache true|false
fettle micro kineticStress 2000
# 输出：members=201 us/op=6.59 realLookups=201 cacheHits=441999  (off: us/op=30.0 realLookups=442200)
```

