# @Overwrite 详解：`AllCreativeModeTabs.RegistrateDisplayItemsGenerator.applyOrderings`

> 关联 finding：`09-compat-loadtime.md` 第 10 条（Medium）
> 目标：`com.simibubi.create.AllCreativeModeTabs$RegistrateDisplayItemsGenerator`（内部类）
> 该方法是 `private static`，没有被 Create 自带 mixin 注入（已核对 `create.mixins.json` 全部 79 行）。

## 1. 原方法完整源码（Create 6.0.10，逐行核对）

文件：`src/main/java/com/simibubi/create/AllCreativeModeTabs.java`，第 324–343 行：

```java
	private static void applyOrderings(List<Item> items, List<ItemOrdering> orderings) {
		for (ItemOrdering ordering : orderings) {
			int anchorIndex = items.indexOf(ordering.anchor());
			if (anchorIndex != -1) {
				Item item = ordering.item();
				int itemIndex = items.indexOf(item);
				if (itemIndex != -1) {
					items.remove(itemIndex);
					if (itemIndex < anchorIndex) {
						anchorIndex--;
					}
				}
				if (ordering.type() == ItemOrdering.Type.AFTER) {
					items.add(anchorIndex + 1, item);
				} else {
					items.add(anchorIndex, item);
				}
			}
		}
	}
```

调用入口（第 274–292 行）：

```java
		@Override
		public void accept(ItemDisplayParameters parameters, Output output) {
			Predicate<Item> exclusionPredicate = makeExclusionPredicate();
			List<ItemOrdering> orderings = makeOrderings();
			Function<Item, ItemStack> stackFunc = makeStackFunc();
			Function<Item, TabVisibility> visibilityFunc = makeVisibilityFunc();

			List<Item> items = new LinkedList<>();
			if (addItems) {
				items.addAll(collectItems(exclusionPredicate.or(IS_ITEM_3D_PREDICATE.negate())));
			}
			items.addAll(collectBlocks(exclusionPredicate));
			if (addItems) {
				items.addAll(collectItems(exclusionPredicate.or(IS_ITEM_3D_PREDICATE)));
			}

			applyOrderings(items, orderings);
			outputAll(output, items, stackFunc, visibilityFunc);
		}
```

### 1.1 实际规模（已核对，修正原 finding 的估算）

- `makeOrderings()`（第 165–191 行）产生的 ordering 是**常数条**，不是随规模增长：
  `simpleBeforeOrderings` 有 2 条、`simpleAfterOrderings` 有 1 条（都是 `Map.of` 字面量），再加 `PackageStyles.STANDARD_BOXES` 里 namespace 为 `create` 的箱子各一条。
  **结论：orderings 的数量是 O(1)（个位数~十几个），不是 O(300)。**
- `items` 的初始规模：`collectBlocks` + 最多两次 `collectItems`，每次内部都调 `Create.registrate().getAll(Registries.BLOCK/ITEM)`（第 296、312 行）把**全部** Create 方块/物品物化成一个列表。
  Create 6.0.10 的注册对象总量在**千级**（方块+物品），所以 `items.size()` 量级为 **10^3**。

## 2. 为什么慢（诚实版本）

设 n = `items.size()`（10^3 量级），k = orderings 数量（个位数~十几）：

| 步骤 | 复杂度 | n=1000, k=15 时的绝对量 |
| --- | --- | --- |
| `items.indexOf(anchor)` | O(n)，每元素一次 equals | 15 × ~1000 = **1.5 万次 equals** |
| `items.indexOf(item)` | O(n) | 同上 |
| `items.remove(itemIndex)` | O(n) `System.arraycopy`（memmove） | 15 × ~1000 个元素移动 |
| `items.add(anchorIndex, item)` | O(n) memmove | 同上 |

**总开销：约 3 万次 `Item.equals` + 约 3 万次元素移动。**
在一个每次打开创造模式物品栏（或每次搜索刷新）才跑一次的路径上，这是**几十微秒到几百微秒**量级 —— 相对该方法的其余部分（`getAll` 物化 10^3 个 `RegistryEntry`、每个物品跑 `IS_ITEM_3D_PREDICATE` 即 `ItemRenderer.getModel(...).isGui3d()`）**是小头**。

### 规模放大场景

- 大型整合包把 Create 的注册量从 10^3 推到 **10^4** 时，indexOf/memmove 成本 ×10 → 约 30 万次 equals + 30 万次移动，此时开始可见。
- 这个方法在**每次打开创造栏 + 每次搜索文本变化刷新**时都会执行（NeoForge 的 `CreativeModeTab` 搜索页会重新跑 `DisplayItemsGenerator`）。

### 原 finding 的高估之处

- 原 finding 写「O(orderings×items)，n≈800+」——**结论方向对**，隐含 orderings 也可能很大；实际 orderings 是 O(1)。
- 原 finding 写「先 materialise 全部 Create 方块/物品（:294-322 两次 `registrate().getAll`）」——**属实且更值得关注**：`accept` 每次调用都会走 `collectBlocks` 与最多两次 `collectItems`，也就是 2~3 次全量物化。这是本条 finding 里**真正的大头**，但 `getAll` 是 Registrate 的 API（不在 Create 源码里），`@Overwrite` 无法缓存它 —— 见 §5。

## 3. 改后（纯 Java，可粘贴的方法体）

用「首轮构建 Item → index 映射，后续在映射上维护」把 indexOf 从 O(n) 降到 O(1)，**同时保持完全相同的语义**（含「item 不在列表里时仍然追加」这一默认行为）。

```java
	private static void applyOrderings(List<Item> items, List<ItemOrdering> orderings) {
		if (orderings.isEmpty() || items.isEmpty())
			return;

		// 只在需要时才建索引：orderings 为常数条，n 可能上千。
		Object2IntOpenHashMap<Item> index = new Object2IntOpenHashMap<>(Math.max(items.size() * 2, 16));
		index.defaultReturnValue(-1);
		for (int i = 0; i < items.size(); i++)
			index.putIfAbsent(items.get(i), i);

		for (ItemOrdering ordering : orderings) {
			int anchorIndex = index.getInt(ordering.anchor());
			if (anchorIndex == -1)
				continue;

			Item item = ordering.item();
			int itemIndex = index.getInt(item);
			if (itemIndex != -1) {
				items.remove(itemIndex);
				if (itemIndex < anchorIndex)
					anchorIndex--;
			}

			if (ordering.type() == ItemOrdering.Type.AFTER) {
				items.add(anchorIndex + 1, item);
			} else {
				items.add(anchorIndex, item);
			}

			// 位移后必须重建索引：一次 add/remove 会让 i >= min(itemIndex, anchorIndex) 的条目全部错位。
			// 为了不引入"部分重建"的复杂性与错位 bug，这里整体重建；
			// k 是 O(1)，所以总代价仍是 O(k*n)，但每轮的 n 只花在 n 次 put 上（no equals 链）。
			index.clear();
			for (int i = 0; i < items.size(); i++)
				index.putIfAbsent(items.get(i), i);
		}
	}
```

### 3.1 为什么上面这个版本**并不比原版快多少**（重要）

上面为了「绝不引入错位 bug」，每轮 ordering 都整体重建索引 —— 那本身就是 O(n)。
真正省下的只有 `indexOf` 的 `equals` 链（换成哈希查找），**memmove 与重建都在**。
要真正降复杂度，必须**只在必要时局部修正**，写法如下（更复杂，但确实是 O(n + k·移动量)）：

```java
	private static void applyOrderings(List<Item> items, List<ItemOrdering> orderings) {
		if (orderings.isEmpty() || items.isEmpty())
			return;

		// item -> 它在 items 里的当前位置。只维护"已知位置"，允许短暂过期。
		Object2IntOpenHashMap<Item> index = new Object2IntOpenHashMap<>(Math.max(items.size() * 2, 16));
		index.defaultReturnValue(-1);
		for (int i = 0; i < items.size(); i++)
			index.putIfAbsent(items.get(i), i);

		for (ItemOrdering ordering : orderings) {
			int anchorIndex = createperf$resolve(items, index, ordering.anchor());
			if (anchorIndex == -1)
				continue;

			Item item = ordering.item();
			int itemIndex = createperf$resolve(items, index, item);
			if (itemIndex != -1) {
				items.remove(itemIndex);
				if (itemIndex < anchorIndex)
					anchorIndex--;
			}

			int target = ordering.type() == ItemOrdering.Type.AFTER ? anchorIndex + 1 : anchorIndex;
			items.add(target, item);

			// 只有 [min(target,itemIndex), end) 这一段的位置变了。
			// 与其精确重算，不如把"可能过期"的条目直接标脏，交给 resolve 惰性修正。
			int from = itemIndex == -1 ? target : Math.min(target, itemIndex);
			for (int i = from; i < items.size(); i++)
				index.put(items.get(i), i);
		}
	}

	private static int createperf$resolve(List<Item> items, Object2IntOpenHashMap<Item> index, Item item) {
		int cached = index.getInt(item);
		if (cached >= 0 && cached < items.size() && items.get(cached) == item)
			return cached;                 // 快路径：缓存仍然正确
		int actual = items.indexOf(item); // 慢路径：缓存过期，回退到线性查找并修正
		if (actual >= 0)
			index.put(item, actual);
		return actual;
	}
```

**评价**：这段代码把一个几十微秒级的问题复杂化了。**对于 k=O(1)、n=10^3 的实际规模，我不推荐 `@Overwrite`**。
如果你确实要做，`§3.1` 的惰性修正版是正确形态；但请先用探针量出 `applyOrderings` 的真实耗时（大概率 < 1 ms），再决定是否值得引入这段复杂度。

### 3.2 真正值得做的：把 `getAll` 的物化结果缓存一层

如果 profiler 显示 `RegistrateDisplayItemsGenerator.accept` 很贵，瓶颈几乎一定在：

1. `Create.registrate().getAll(Registries.BLOCK)` / `getAll(Registries.ITEM)` 各一次（第 296、312 行），每次把它们**全部**物化成列表；
2. `IS_ITEM_3D_PREDICATE`（第 91–96 行）对每个物品调 `ItemRenderer.getModel(new ItemStack(item), null, null, 0).isGui3d()` —— 每次 `accept` 都新建 `ItemStack` 并查模型。

这两项都**不在本方法的职责范围内**，且 1 依赖 Registrate API。可考虑的注入点是：

- 对 `AllCreativeModeTabs$RegistrateDisplayItemsGenerator` 的 `makeExclusionPredicate/makeStackFunc/makeVisibilityFunc` 做缓存（它们每次 `accept` 都重建 Map 与 lambda，见第 108–272 行）；
- 对 `collectBlocks` 做结果缓存（键 = `tabFilter + exclusionPredicate`，但 `exclusionPredicate` 每次是**新对象**，无法作键 → 需要按 `tabFilter` 缓存，并保证 `TagDependentIngredientItem.shouldHide()` 在会话内不变）。

这些属于「需要新增状态 + 失效时机」的 Medium 改动，**不在本 overwrite 文件范围内**，如果你要做，请单开一个 `overwrites/AllCreativeModeTabs-collectBlocks.md`。

## 4. Mixin 骨架（仅 `applyOrderings`）

```java
package com.example.createperf.mixin.loadtime;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import com.simibubi.create.AllCreativeModeTabs;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.item.Item;

@Mixin(targets = "com.simibubi.create.AllCreativeModeTabs$RegistrateDisplayItemsGenerator", remap = false)
public abstract class RegistrateDisplayItemsGeneratorMixin {

	@Overwrite
	private static void applyOrderings(List<Item> items, List<ItemOrdering> orderings) {
		// 见 §3 / §3.1 的方法体
	}
}
```

注意：

- 目标是**内部类**，必须用 `@Mixin(targets = "...$RegistrateDisplayItemsGenerator")`，不能用 `value = X.class`（因为它是 `private static class`，不是 `public`）。
  实际上 `value` 也可以写内部类的 `class` 字面量，但当内部类是 private 时，用 `targets` 字符串更稳。
- `ItemOrdering` 也是**私有内部 record**（第 351–364 行）。`@Overwrite` 的方法签名里引用它需要：
  (a) 你的 mixin 类里**声明一个同名同结构的** `record`？不行 —— 方法描述符里的类型必须与目标类一致。
  (b) 正确做法：用 `@Mixin` 的 `@Shadow` 无法声明 record 类型；实务上**用 `Object` 接收**会导致描述符不匹配。

  **因此：**`@Overwrite` 在这个方法上有一个**真实的签名障碍** —— 参数类型 `List<ItemOrdering>` 里的 `ItemOrdering` 是 private 内部 record，外部代码无法合法引用。
  可行的绕法：把 mixin 类**放在 `com.simibubi.create` 包下**（这样 `ItemOrdering` 的 `record` 组件访问器可见？不 —— `private` 外部类不可见）→ **仍然不行**。
  → **结论：`applyOrderings` 实际上不适合 `@Overwrite`。** 参见 §5 的替代注入点。

## 5. 替代注入点（真正可落地的做法）

### 5.1 用 `@Inject` + `@Redirect` 接管 `List.indexOf` —— 不可行

```java
// 想这样：把 items.indexOf(x) 换成自己的 O(1) 查找。
// 问题：indexOf 在 Object 上，Redirect 会命中整个方法里**所有** List.indexOf 调用，
// 且无法区分 items 与 orderings；而且没有跨调用的状态可挂。
```

### 5.2 用 `@Inject` 在 `accept` 内联替换 —— 可行但侵入大

对 `accept`（第 274–292 行）做 `@Inject(at = @At("HEAD"), cancellable = true)`，用你自己的实现**完整替换** accept 的逻辑。
这样你不需要引用 `ItemOrdering`（因为你不再调用 `applyOrderings`），但你需要：

- 复制 `collectBlocks/collectItems/makeOrderings/makeStackFunc/makeVisibilityFunc/outputAll` 的全部逻辑（约 200 行），
- 或对它们做 `@Invoker` 暴露（`makeOrderings` 的返回类型又是 private record → 同样卡住）。

→ **复杂度/风险远大于收益。不建议。**

### 5.3 建议：不做 Mixin，改为向上游提 issue

本条 finding 的**最优解是上游改一行 API 可见性**（把 `applyOrderings` 与 `ItemOrdering` 从 `private` 提升为 `package-private` 或 `public`），之后单侧 mod 就能安全地 `@Overwrite`。
在 `docs/perf/09-compat-loadtime.md` 的「不可 Mixin / 建议提给上游」一节里登记为候选。

## 6. 风险

| 风险 | 等级 | 说明 |
| --- | --- | --- |
| 私有内部类型导致 `@Overwrite` 不可写 | **高（阻塞）** | `ItemOrdering` 是 `private static record`，外部 mixin 类无法引用其类型 → 描述符无法书写 |
| 语义漂移（若绕过类型问题） | 中 | 原实现里 `item` 不在列表时**仍然**被 `add(anchorIndex, item)` 插入（`itemIndex == -1` 也往下走）。任何重写必须保留这个行为 |
| 顺序变化 | 中 | `LinkedList` 的 `add(index, e)` 在中间位置是 O(n)，原实现依赖它；换成 `ArrayList` 或 fastutil 列表会改变 `outputAll` 的迭代顺序吗？**不会**（顺序由列表内容决定），但会改变 `List.remove(int)` 的语义吗？**不会**。可以换，但没必要 |
| 与 Create 自带 mixin 冲突 | **无** | 已核对 |

## 7. 回归测试点

1. **物品顺序逐项一致**：打开 Create 的两个创造标签页（base / palettes），把物品栏内容截图或导出 ID 序列，与加固前的序列**逐项对比**。
   - `Empty Blaze Burner` 应在 `Blaze Burner` 之前（`simpleBeforeOrderings`）；
   - `Vertical Gearbox` 应在 `Gearbox` 之后（`simpleAfterOrderings`）；
   - `Schedule` 应在 `Track Station` 之前；
   - `PackageStyles.STANDARD_BOXES` 里 namespace 为 `create` 的箱子应在 `Packager` 之后。
2. **搜索页**：在创造栏搜索框输入 `box` / `gearbox` / `schedule`，确认结果顺序与加固前一致（搜索页会重新跑 `accept`）。
3. **创造栏多次打开**：反复开关创造栏 20 次，确认无异常、无重复项、无缺失项。
4. **（若改了缓存）Installed mod 变化**：装/卸一个会给 Create 标签页加物品的 mod，确认列表更新（缓存失效）。

## 8. 收益

- **`applyOrderings` 本身（估计）**：k=O(1)、n≈10^3 时，绝对量级是 **1.5 万~3 万次 `Item.equals`**。**这是微秒级**，且 `Item.equals` 是引用比较（`Item` 是注册单例，`Object.equals` 即同一性比较）→ 实际上比哈希查找还便宜。
  **因此：把这个方法的 O(k·n) 改成 O(k+n) 在 n=10^3 时几乎不可观测。**
- **本条 finding 的真实价值在于它指出的模式**（每次 `accept` 全量物化 + 每物品查模型），而不是这个方法本身。

> 诚实结论：**不做这个 `@Overwrite`。** 把它登记为「上游应提升可见性 + 缓存 collectBlocks」的候选，节省你的风险预算。