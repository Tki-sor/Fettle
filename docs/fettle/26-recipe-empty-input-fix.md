# 26 · 复查已发布优化时发现的正确性缺口：空输入的 `getRecipeFor`

## 26.1 缺口是什么

复查（`docs/fettle/24-more-aggressive.md` 的 C4）发现：我们的配方索引只 Hook 了 3 参
`RecipeManager#getRecipeFor(RecipeType, RecipeInput, Level)`，并且**在被索引的配方类型上无条件取消原方法**。
而原版在 4 参实现的最前面就有一个早退：**输入为空时直接返回 `Optional.empty()`**，根本不看任何配方。

也就是说：对于一个**空输入**，我们有可能返回一个原版会拒绝的配方（只要存在某个能匹配空输入的配方），
而我们的 parity 自检一直是拿"暴力扫描 + `matches`"当基准，**恰好也复刻不了这个早退**，所以此前检查不出。

## 26.2 修法（零成本、语义对齐）

在快速路径的最前面补上与原版一致的守卫，命中时**不取消**原方法，交还给原版：

```java
if (input.isEmpty()) {
    return;   // 原版对空输入直接返回空，快速路径不许猜
}
```

## 26.3 永久回归测试（进 parity 自检，随门禁一起跑）

`RecipeParityCheck` 新增空输入断言：构造 `CraftingInput.of(0, 0, List.of())` 与
`CraftingInput.of(1, 1, [EMPTY])` 两种空输入，调用快速路径，**任何非空结果都记为 mismatch**。
日志行扩展为 `checked=… skipped=… emptyInputs=2 mismatches=0`。

## 26.4 验证

见 26.5 的门禁输出：`emptyInputs=2` 且 `mismatches=0`，整体 `[gate] PASS`。

## 26.5 门禁输出

`python tools/fettle_gate.py` 原样输出：

```
[gate] server up
[gate] > fettle debug kinetictest 3
[Fettle] kinetic incremental: members=201 stress=[n=27 mismatches=0] speedViaNetwork=[n=15 mismatches=10] rawSpeedWrite=[n=27 mismatches=18] afterReconcile=[n=3 mismatches=0] incremental=27 reconciles=1 syncs=26 speedAdjusts=0
[gate] > fettle debug trains 3
[Fettle] collisionDifferential: fleet=6 fresh=[n=24 collisions=3 mismatches=0] stale_raw=[n=24 collisions=3 mismatches=3 missed=3] stale_guarded=[n=24 collisions=3 mismatches=0 missed=0] index=builds=72 indexed=432 queries=120 kept=18 keptMoved=24 dropped=678
[gate] > fettle idle sleeptest
[gate] pilot delta ticks=8000 skipped=152400 ratio=19.05
[Fettle] recipe parity self-test: checked=1211 skipped=340 emptyInputs=2 mismatches=0
[gate] PASS
```

（注意 `emptyInputs=2`：两种空输入各测一次，都未被快速路径误判成有配方；
`stale_raw` 的 3 个 mismatch 是**故意保留的反例**，用来证明差分测试确实能抓到问题。）

