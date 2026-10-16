# Overwrite 施工图：`StockKeeperRequestScreen.maxCraftable`

> 目标方法：`com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen#maxCraftable(Lcom/simibubi/create/content/logistics/stockTicker/CraftableBigItemStack;Lcom/simibubi/create/content/logistics/packager/InventorySummary;Ljava/util/function/Function;I)Lnet/createmod/catnip/data/Pair;`（`private`）
> 对应发现：[07-client-render-ui.md](../07-client-render-ui.md) 发现 8（Medium）
> 公共约定见 [01-mixin-playbook.md](../01-mixin-playbook.md)

## 1. 范围声明：只做两处替换，不动对象别名

读源码后必须警告：这个方法**有破坏性 mutation 与跨配料的对象别名**，整段「优雅重写」会改行为：

- `:1659-1664` 新建的 `BigItemStack` 被同时放进 `valid` 与 `alreadyCreated`；
- 后续配料的 `:1653-1658` 会把 `alreadyCreated` 里的**同一个对象**加进自己的 `valid`；
- `resolveIngredientAmounts`（`:1735-1768`）会**就地**递减这些 `BigItemStack.count`（`:1751`），并新建 `BigItemStack` 放进结果列表；
- `removeLeastEssentialItemStack`（`:1706-1733`）会用 `list.remove(chosen)` 从所有配料列表里删除同一个对象。

也就是说 `validEntriesByIngredient` 里的对象是**共享且会被消耗**的。因此本文件只做两处**保持对象图完全不变**的替换：

1. 把 `alreadyCreated` 的线性扫描（`:1653-1658`）换成「按 `ItemStack.hashItemAndComponents` 分桶」的索引查找（同一个对象、同样的加入顺序）；
2. 把排序比较器里每次比较都调两次 `summary.getCountOf`（`:1670-1671`）换成「排序前一次性算好、用身份表查」的方式（排序结果与稳定性不变）。

`getOrderForItem`（`:948-953`）的 `O(itemsToOrder)` 扫描**不**在本文件范围（它也被 `:1581`、`:1711` 调用，且 `itemsToOrder` 频繁变动，缓存的失效面更大，收益远小于上面两处）。

## 2. 原方法完整源码（`StockKeeperRequestScreen.java:1638-1704`，逐字摘录）

```java
	private Pair<Integer, List<List<BigItemStack>>> maxCraftable(CraftableBigItemStack cbis, InventorySummary summary,
																 Function<ItemStack, Integer> countModifier, int newTypeLimit) {
		List<Ingredient> ingredients = cbis.getIngredients();
		List<List<BigItemStack>> validEntriesByIngredient = new ArrayList<>();
		List<BigItemStack> alreadyCreated = new ArrayList<>();

		for (Ingredient ingredient : ingredients) {
			if (ingredient.isEmpty())
				continue;
			List<BigItemStack> valid = new ArrayList<>();
			for (List<BigItemStack> list : summary.getItemMap()
				.values())
				Entries:for (BigItemStack entry : list) {
					if (!ingredient.test(entry.stack))
						continue;
					for (BigItemStack visitedStack : alreadyCreated) {
						if (!ItemStack.isSameItemSameComponents(visitedStack.stack, entry.stack))
							continue;
						valid.add(visitedStack);
						continue Entries;
					}
					BigItemStack asBis = new BigItemStack(entry.stack,
						summary.getCountOf(entry.stack) + countModifier.apply(entry.stack));
					if (asBis.count > 0) {
						valid.add(asBis);
						alreadyCreated.add(asBis);
					}
				}

			if (valid.isEmpty())
				return Pair.of(0, List.of());

			Collections.sort(valid,
				(bis1, bis2) -> -Integer.compare(summary.getCountOf(bis1.stack), summary.getCountOf(bis2.stack)));
			validEntriesByIngredient.add(valid);
		}

		// Used new items may have to be trimmed
		if (newTypeLimit != -1) {
			int toRemove = (int) validEntriesByIngredient.stream()
				.flatMap(l -> l.stream())
				.filter(entry -> getOrderForItem(entry.stack) == null)
				.distinct()
				.count() - newTypeLimit;

			for (int i = 0; i < toRemove; i++)
				removeLeastEssentialItemStack(validEntriesByIngredient);
		}

		// Ingredients with shared items must divide counts
		validEntriesByIngredient = resolveIngredientAmounts(validEntriesByIngredient);

		// Determine the bottlenecking ingredient
		int minCount = Integer.MAX_VALUE;
		for (List<BigItemStack> list : validEntriesByIngredient) {
			int sum = 0;
			for (BigItemStack entry : list)
				sum += entry.count;
			minCount = Math.min(sum, minCount);
		}

		if (minCount == 0)
			return Pair.of(0, List.of());

		int outputCount = cbis.getOutputCount(blockEntity.getLevel());
		return Pair.of(minCount * outputCount, validEntriesByIngredient);
	}
```

## 3. 改后完整可粘贴方法体

```java
	@Overwrite
	private Pair<Integer, List<List<BigItemStack>>> maxCraftable(CraftableBigItemStack cbis, InventorySummary summary,
																 Function<ItemStack, Integer> countModifier, int newTypeLimit) {
		List<Ingredient> ingredients = cbis.getIngredients();
		List<List<BigItemStack>> validEntriesByIngredient = new ArrayList<>();
		List<BigItemStack> alreadyCreated = new ArrayList<>();

		// (1) 用 hash 分桶替代 alreadyCreated 的线性扫描；桶内仍用 isSameItemSameComponents 兜碰撞。
		//     键用 ItemStack.hashItemAndComponents（本仓库已在 ClipboardEntry.java:109 使用，API 确认存在）。
		Map<Integer, List<BigItemStack>> createdIndex = new HashMap<>();

		for (Ingredient ingredient : ingredients) {
			if (ingredient.isEmpty())
				continue;
			List<BigItemStack> valid = new ArrayList<>();
			for (List<BigItemStack> list : summary.getItemMap()
				.values())
				Entries:for (BigItemStack entry : list) {
					if (!ingredient.test(entry.stack))
						continue;

					int hash = ItemStack.hashItemAndComponents(entry.stack);
					List<BigItemStack> bucket = createdIndex.get(hash);
					if (bucket != null) {
						for (BigItemStack visitedStack : bucket) {
							if (!ItemStack.isSameItemSameComponents(visitedStack.stack, entry.stack))
								continue;
							valid.add(visitedStack);
							continue Entries;
						}
					}

					BigItemStack asBis = new BigItemStack(entry.stack,
						summary.getCountOf(entry.stack) + countModifier.apply(entry.stack));
					if (asBis.count > 0) {
						valid.add(asBis);
						alreadyCreated.add(asBis);
						createdIndex.computeIfAbsent(hash, $ -> new ArrayList<>(2)).add(asBis);
					}
				}

			if (valid.isEmpty())
				return Pair.of(0, List.of());

			// (2) 排序键只算一次（原比较器每次比较算两次 summary.getCountOf，O(|valid| log |valid|) 次）
			//     用身份表（BigItemStack 未覆写 equals，语义等同身份）保存，排序结果与稳定性不变：
			//     Collections.sort 是稳定排序，且比较关系 -Integer.compare(count) 与原来逐位相同。
			Map<BigItemStack, Integer> sortKeys = new IdentityHashMap<>(valid.size() * 2);
			for (BigItemStack bis : valid)
				sortKeys.put(bis, summary.getCountOf(bis.stack));

			Collections.sort(valid,
				(bis1, bis2) -> -Integer.compare(sortKeys.get(bis1), sortKeys.get(bis2)));
			validEntriesByIngredient.add(valid);
		}

		// ===== 以下与原实现逐字节相同 =====
		// Used new items may have to be trimmed
		if (newTypeLimit != -1) {
			int toRemove = (int) validEntriesByIngredient.stream()
				.flatMap(l -> l.stream())
				.filter(entry -> getOrderForItem(entry.stack) == null)
				.distinct()
				.count() - newTypeLimit;

			for (int i = 0; i < toRemove; i++)
				removeLeastEssentialItemStack(validEntriesByIngredient);
		}

		// Ingredients with shared items must divide counts
		validEntriesByIngredient = resolveIngredientAmounts(validEntriesByIngredient);

		// Determine the bottlenecking ingredient
		int minCount = Integer.MAX_VALUE;
		for (List<BigItemStack> list : validEntriesByIngredient) {
			int sum = 0;
			for (BigItemStack entry : list)
				sum += entry.count;
			minCount = Math.min(sum, minCount);
		}

		if (minCount == 0)
			return Pair.of(0, List.of());

		int outputCount = cbis.getOutputCount(blockEntity.getLevel());
		return Pair.of(minCount * outputCount, validEntriesByIngredient);
	}
```

**为什么这两处是等价的**

- **分桶**：原逻辑是「在 `alreadyCreated` 里找第一个 `isSameItemSameComponents` 匹配的对象」；新逻辑是「在 `hashItemAndComponents` 相同的桶里找第一个匹配的对象」。两个真正相等的 stack 必然同 hash（`hashItemAndComponents` 就是按 item + components 计算的，`ClipboardEntry.java:109` 即用它做 equals 契约的一部分），因此匹配到的**元素集合与顺序**一致；桶内仍用 `isSameItemSameComponents` 逐项校验，所以 hash 碰撞不会引入错误。
- **排序键**：比较函数从「每次比较算 2 次 `getCountOf`」变成「排序前算 |valid| 次，比较时查表」。`Collections.sort` 是**稳定**排序，比较关系（`-Integer.compare(count)`，即计数降序）逐位相同 ⇒ 排序结果逐位相同。注意 `getCountOf` 读的是 `summary`（外部数据），排序期间不会变化（本方法内没有对 `summary` 的写入）。
- 两处替换都不改变「哪个对象进 `valid`/`alreadyCreated`、以什么顺序进」⇒ 后续 `resolveIngredientAmounts` / `removeLeastEssentialItemStack` 的破坏性 mutation 与别名关系完全保持。

## 4. 需要的 import / `@Unique`

```java
package com.example.createperf.mixin.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.stockTicker.CraftableBigItemStack;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen;

import net.createmod.catnip.data.Pair;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

@Mixin(value = StockKeeperRequestScreen.class, remap = false)
public abstract class StockKeeperRequestScreenMixin {
    // 第 3 节的方法体
}
```

- **无需 `@Unique` 字段**（两张索引表都是方法内局部变量，随调用结束被 GC）——这是选择「两处替换」而不是「缓存 `getOrderForItem`」的另一个好处：没有生命周期/失效/并发问题。
- `@Overwrite` 一个 `private` 方法时，`blockEntity`（`:1614`、`:1702` 用到）等同类字段可以直接引用（mixin 类在字节码层面被合并进目标类）。

## 5. `@Overwrite` 风险

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| **别名/mutation 语义** | 本方法后半段的 `resolveIngredientAmounts` 会就地改 `count`；任何对象图变化都会改变合成数量 | 第 3 节只替换两处、保持对象图；验证要求逐项比对返回值 |
| `ItemStack.hashItemAndComponents` 的可用性 | 已在本仓库 `ClipboardEntry.java:109` 使用（NeoForge 1.21.1 提供） | 若上游/版本变动导致不可用，退回「只用 `Item` 分桶 + 桶内线性扫描」（仍能把 300 条目缩到每物品几项） |
| 排序键身份表 | `BigItemStack` 未覆写 `equals`（本仓库 `BigItemStack` 是普通类），`IdentityHashMap` 与默认 equals 语义一致 | 若上游给 `BigItemStack` 加了 `equals`，需改用 `HashMap` 并确认同一对象不会被重复放入 `valid` |
| 与 `getOrderForItem` 的耦合 | 本文件不改它，`:1679`/`:1711` 的 `O(itemsToOrder)` 仍在 | 单独评估（见发现 8 的说明） |
| 上游重构 | 签名变化会在启动期报错 | 期望行为 |

## 6. 回归测试点

1. **返回值逐项比对**：同一搜索字符串 + 同一库存快照下，比较 `Pair.getFirst()`（整数）与 `Pair.getSecond()` 中每个 `BigItemStack` 的 `stack` 身份/内容与 `count`，以及列表顺序——必须完全一致。场景覆盖：单配料、多配料共享物品、`newTypeLimit != -1` 的修剪、`valid.isEmpty()` 提前返回。
2. `canRequestCraftingPackage`（`:1631-1635`）：`usedItems.getCountOf(ordered.stack) != ordered.count` 的置位时机不变。
3. `resolveIngredientAmounts` 的「共享配料均分」结果不变（这是最容易因对象图变化而出错的地方）。
4. 搜索框连续输入 10 个字符时不出现明显卡顿；`/spark profiler` 中 `maxCraftable` 占比下降。
5. 边界：空库存、配方配料为空（`ingredient.isEmpty()`）、库存中同一物品多个变体（不同 components）。
