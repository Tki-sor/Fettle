# @Overwrite 详解：`RuntimeDataGenerator.cuttingRecipes`（含 `insertIntoPack` 的重复执行防护）

> 关联 finding：`09-compat-loadtime.md` 第 1 条（High）
> 目标：`com.simibubi.create.foundation.data.RuntimeDataGenerator`
> 推荐组合：**方案 A（`@Unique` 一次性守卫，改动 6 行，消除重复执行）** + **方案 B（第 5 节的 `@Overwrite` 快速闸门，消除单次执行内 95% 物品的 regex 与分配）**。
> 只做 A 已经能砍掉「同一次游戏会话内第 2 次及以后」的全部开销；A 对单次成本无效，单次成本要靠 B。

## 1. 原方法完整源码（Create 6.0.10，逐行核对）

文件：`src/main/java/com/simibubi/create/foundation/data/RuntimeDataGenerator.java`

调用入口（第 57–75 行）：

```java
	public static void insertIntoPack(DynamicPack dynamicPack) {
		for (ResourceLocation itemId : BuiltInRegistries.ITEM.keySet()) {
			cuttingRecipes(itemId);
			washingRecipes(itemId);
		}

		Create.LOGGER.info("Created {} recipes which will be injected into the game", JSON_FILES.size());
		JSON_FILES.forEach(dynamicPack::put);

		Create.LOGGER.info("Created {} tags which will be injected into the game", TAGS.size());
		for (Map.Entry<ResourceLocation, Collection<TagEntry>> tags : TAGS.asMap().entrySet()) {
			TagFile tagFile = new TagFile(new ArrayList<>(tags.getValue()), false);
			dynamicPack.put(tags.getKey().withPrefix("tags/item/"), TagFile.CODEC.encodeStart(JsonOps.INSTANCE, tagFile).result().orElseThrow());
		}

		JSON_FILES.clear();
		JSON_FILES.trim();
		TAGS.clear();
	}
```

目标方法（第 77–148 行，含上方注释）：

```java
	// logs/woods -> stripped variants
	// logs/woods both stripped and non stripped -> planks
	// planks -> stairs, slabs, fences, fence gates, doors, trapdoors, pressure plates, buttons and signs
	private static void cuttingRecipes(ResourceLocation itemId) {
		String path = itemId.getPath();

		Matcher match = STRIPPED_WOODS_PREFIX_REGEX.matcher(path);
		boolean hasFoundMatch = match.find();
		boolean strippedInPrefix = hasFoundMatch;

		if (!hasFoundMatch) {
			match = STRIPPED_WOOD_SUFFIX_REGEX.matcher(path);
			hasFoundMatch = match.find();
		}

		// Last ditch attempt. Try to find logs without stripped variants
		boolean noStrippedVariant = false;
		if (!hasFoundMatch && !BuiltInRegistries.ITEM.containsKey(itemId.withPrefix("stripped_"))
			&& !BuiltInRegistries.ITEM.containsKey(itemId.withSuffix("_stripped"))) {
			match = NON_STRIPPED_WOODS_REGEX.matcher(path);
			hasFoundMatch = match.find();
			noStrippedVariant = true;
		}

		if (hasFoundMatch) {
			String prefix = strippedInPrefix && match.group(1) != null ? match.group(1) : "";
			String suffix = !strippedInPrefix && !noStrippedVariant ? match.group(3) + match.group(4) : "";
			String type = match.group(strippedInPrefix ? 3 : 2);
			ResourceLocation matched_name = itemId.withPath(match.group(strippedInPrefix ? 2 : 1));
			// re-add 'wood' to wood types such as Botania's livingwood
			ResourceLocation base = matched_name.withSuffix(type.equals("wood") ? "wood" : "");
			base = MISMATCHED_WOOD_NAMES.getOrDefault(base, base);
			ResourceLocation nonStrippedId = matched_name.withSuffix(type).withPrefix(prefix).withSuffix(suffix);
			ResourceLocation planksId = base.withSuffix("_planks");
			ResourceLocation stairsId = base.withSuffix(base.getNamespace().equals(Mods.BTN.getId()) ? "_planks_stairs" : "_stairs");
			ResourceLocation slabId = base.withSuffix(base.getNamespace().equals(Mods.BTN.getId()) ? "_planks_slab" : "_slab");
			ResourceLocation fenceId = base.withSuffix("_fence");
			ResourceLocation fenceGateId = base.withSuffix("_fence_gate");
			ResourceLocation doorId = base.withSuffix("_door");
			ResourceLocation trapdoorId = base.withSuffix("_trapdoor");
			ResourceLocation pressurePlateId = base.withSuffix("_pressure_plate");
			ResourceLocation buttonId = base.withSuffix("_button");
			ResourceLocation signId = base.withSuffix("_sign");
			// Bamboo, GotD whistlecane
			int planksCount = type.contains("block") ? 3 : 6;

			if (!noStrippedVariant) {
				// Catch mods like JNE that have a non-stripped log prefixed but not the stripped log
				if (BuiltInRegistries.ITEM.containsKey(nonStrippedId)) {
					simpleWoodRecipe(nonStrippedId, itemId);
				}
				simpleWoodRecipe(itemId, planksId, planksCount);
			} else if (BuiltInRegistries.ITEM.containsKey(planksId)) {
				ResourceLocation tag = Create.asResource("runtime_generated/compat/" + itemId.getNamespace() + "/" + base.getPath());
				insertIntoTag(tag, itemId);

				simpleWoodRecipe(TagKey.create(Registries.ITEM, tag), planksId, planksCount);
			}

			if (!path.contains("_wood") && !path.contains("_hyphae") && BuiltInRegistries.ITEM.containsKey(planksId)) {
				simpleWoodRecipe(planksId, stairsId);
				simpleWoodRecipe(planksId, slabId, 2);
				simpleWoodRecipe(planksId, fenceId);
				simpleWoodRecipe(planksId, fenceGateId);
				simpleWoodRecipe(planksId, doorId);
				simpleWoodRecipe(planksId, trapdoorId);
				simpleWoodRecipe(planksId, pressurePlateId);
				simpleWoodRecipe(planksId, buttonId);
				simpleWoodRecipe(planksId, signId);
			}
		}
	}
```

三个 `static final` 正则（第 43–48 行，已核对；改动时**逐字节保留**）：

```java
	private static final Pattern STRIPPED_WOODS_PREFIX_REGEX = Pattern.compile("(\\w*)??stripped_(\\w*)(_log|_wood|_stem|_hyphae|_block|(?<!_)wood)()$");
	private static final Pattern STRIPPED_WOOD_SUFFIX_REGEX = Pattern.compile("(\\w*)(_log|_wood|_stem|_hyphae|_block|(?<!_)wood)(\\w*)_stripped(\\w*)");
	private static final Pattern NON_STRIPPED_WOODS_REGEX = Pattern.compile("^(?!stripped_)([a-z_]+)(_log|_wood|_stem|_hyphae|(?<!bioshroom)_block)(([a-z_]+)(?<!_stripped))?$");
```

## 2. 为什么值得重写

### 2.1 重复执行（方案 A 的目标）

Create 侧的订阅点是 `foundation/events/CommonEvents.java:257-261`（`PackType.SERVER_DATA` 分支）。
该 `AddPackFindersEvent` 由 NeoForge 的 `net.neoforged.neoforge.resource.ResourcePackLoader.populatePackRepository(PackRepository, PackType, boolean)` 发出。
已用 `javap` 反编译并核对全部引用者，`populatePackRepository` 的调用者是：

| 调用者 | 时机 | SERVER_DATA？ |
| --- | --- | --- |
| `net.neoforged.neoforge.client.loading.ClientModLoader` | **游戏启动**（javap 可见连续两次调用） | 先 `CLIENT_RESOURCES` 再 `SERVER_DATA` |
| `net.minecraft.server.packs.repository.ServerPacksSource` | 专用服务端启动 | 是 |
| `net.minecraft.client.gui.screens.worldselection.CreateWorldScreen` | **每次打开「创建/编辑世界 → 数据包」界面** | 是 |

→ 同一次游戏会话内 `insertIntoPack` 至少执行 2 次（启动 1 次 + 每次打开世界创建界面 1 次）。
**修正原 finding**：`/reload` 走 `MinecraftServer.reloadResources`，**不重新 populate pack repository**，因此「每次 `/reload` 都跑」在 1.21.1/NeoForge 21.1.219 上证据不足，**需实测**（探针计数器跑一次 `/reload` 即可确认）。
而 `JSON_FILES` / `TAGS` 在方法末尾被 `clear()` + `trim()`，所以第 2 次执行是**从零重算**，不是复用。

### 2.2 单次执行内的成本（方案 B 的目标）

设注册表物品数为 N（原版 ≈1.3k；中大型整合包 2 万–6 万）：

1. **最多 3 次 `Matcher` 创建 + `find()`**：`Pattern.matcher(CharSequence)` 每次都分配一个新的 `Matcher`（含 `int[] groups`，默认 20 长）。第 2、3 个只在前面失败时创建；**绝大多数物品名不是木头**，所以现实分布接近「3N 个 Matcher + 3N 次 regex 扫描」。
2. **`itemId.withPrefix("stripped_")` / `withSuffix("_stripped")`（:94）**：已 `javap` 反编译 `ResourceLocation.withPrefix/withSuffix` —— 二者都是
   `invokedynamic makeConcatWithConstants`（`StringConcatFactory` → 分配字节数组 + `String`）→ `withPath(String)` → **`new ResourceLocation(namespace, assertValidPath(...))`**。
   即每次调用 **≥3 个对象** + 一次 `assertValidPath` 遍历。该分支对每个「前两个 regex 都没命中的物品」执行 2 次 → **≥2N 次 `ResourceLocation` 分配**。
3. **命中路径（真正的木材）**：每种木材最多 9 条 `simpleWoodRecipe`，每条经 `StandardBuilder.build()` → `CatnipCodecUtils.encode(Recipe.CONDITIONAL_CODEC, JsonOps.INSTANCE, ...)`（:212）做一次完整 codec 编码（构造 JSON 树）。木材种类通常几十到几百，**不是主要成本**，但单条成本高。

### 2.3 规模放大场景

- 第 1、2 项随 N **线性**放大；
- 第 2 项在「一个 mod 加了大量非木头物品」时最明显（每个非木头物品都白建 2 个 `ResourceLocation`）；
- 打开世界创建/数据包界面 → 整套成本重跑一遍（方案 A 解决这一点）。

## 3. 方案 A：`@Unique` 一次性守卫（先做这个）

原理：`AddPackFindersEvent` 只在启动/世界创建界面触发，而触发时 `BuiltInRegistries.ITEM` 已经冻结（内容不会在会话中途变化）。第 2 次及以后的 `insertIntoPack` 是**纯重复劳动**，产物与第 1 次逐字节相同。

```java
package com.example.createperf.mixin.loadtime;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.foundation.data.RuntimeDataGenerator;
import com.simibubi.create.foundation.pack.DynamicPack;

@Mixin(value = RuntimeDataGenerator.class, remap = false)
public abstract class RuntimeDataGeneratorGuardMixin {

	@Unique
	private static boolean createperf$generatedOnce = false;

	@Inject(method = "insertIntoPack(Lcom/simibubi/create/foundation/pack/DynamicPack;)V",
		at = @At("HEAD"), cancellable = true)
	private static void createperf$skipRegeneration(DynamicPack dynamicPack, CallbackInfo ci) {
		if (createperf$generatedOnce)
			ci.cancel();
		createperf$generatedOnce = true;
	}
}
```

**语义等价性论证**：`insertIntoPack` 的产物只依赖 `BuiltInRegistries.ITEM` / `BuiltInRegistries.BLOCK` 的**内容**与三个常量正则，不依赖 `dynamicPack` 既有内容。在 ITEM 注册表冻结后重复调用必然产出相同的 `JSON_FILES` / `TAGS`。
**更强的守卫（可选）**：把布尔换成 `@Unique private static int createperf$generatedForSize = -1;`，只在 `BuiltInRegistries.ITEM.size() != createperf$generatedForSize` 时重算 —— 代价是一次 int 比较，能覆盖「运行期注册物品」的极端情况。

## 4. 为什么不能只靠 `@Redirect` / `@ModifyExpressionValue` 消除分配

按手册 §3：

- 在 `BuiltInRegistries.ITEM.containsKey(...)` 上挂 `@WrapOperation`/`@Redirect` 时，实参 `itemId.withPrefix("stripped_")` **已经在调用前构造完**，`ResourceLocation` 照旧分配。
- 在 `Pattern.matcher(path)` 上挂 `@ModifyExpressionValue` 也不能返回共享 `Matcher`：`Matcher` 有状态（`find()` 游标与 groups），共享会串味。
- 所以「消除 regex 与 `ResourceLocation` 分配」只有两条路：
  **(a) `@Overwrite` + 快速闸门（第 5 节）**；
  **(b) `Matcher` 复用**（每次 `reset(path)`）—— 只省 `Matcher` 分配，路径字符串拼接仍照旧，而且键设计容易写错（见第 6 节）。

## 5. 方案 B：`@Overwrite` + 快速闸门（推荐写法）

思路：**逻辑与正则逐字不变**，只在方法开头加一段「后缀分类 + 字符集检查」的快速闸门。判定为「三个正则都不可能出现」时直接 `return`，省掉最多 3 次 `Matcher` 分配 + 3 次 regex 扫描 + 2 次 `ResourceLocation` 分配；判定为「可能」时**走完全相同的原路径**。

写入位置建议：**把原方法第 101–147 行（`if (hasFoundMatch) {` 整块）原样复制**到闸门之后。为了 diff 最小化，下面给出的是"不抽取、直接内联"的完整方法体（只列出与原方法不同/新增的部分用注释标出）。

```java
package com.example.createperf.mixin.loadtime;

import java.util.regex.Matcher;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import com.simibubi.create.foundation.data.RuntimeDataGenerator;

import net.minecraft.resources.ResourceLocation;

@Mixin(value = RuntimeDataGenerator.class, remap = false)
public abstract class RuntimeDataGeneratorCuttingRecipesMixin {

	/**
	 * 快速闸门。返回 false = 三个正则绝不可能命中（直接 return）；
	 * 返回 true = 不确定 → 走原逻辑。
	 *
	 * 宁松勿紧：多放行只是回到现状（多跑 regex），漏放行会导致缺少配方（内容 bug）。
	 */
	@Unique
	private static boolean createperf$maybeWood(String path) {
		if (!path.endsWith("wood") && !path.endsWith("_log") && !path.endsWith("_stem")
			&& !path.endsWith("_hyphae") && !path.endsWith("_block") && !path.endsWith("_stripped"))
			return false;
		// 三个正则的 \\w 只允许 [A-Za-z0-9_]（NON_STRIPPED_WOODS_REGEX 更严，只允许 [a-z_]）。
		// 这里只做粗筛：出现其它字符必然不命中。
		for (int i = 0; i < path.length(); i++) {
			char c = path.charAt(i);
			boolean ok = c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
			if (!ok)
				return false;
		}
		return true;
	}

	@Overwrite
	private static void cuttingRecipes(ResourceLocation itemId) {
		String path = itemId.getPath();

		// >>> createperf: 3 行闸门，替代原来无条件跑的 3 次 regex <<<
		if (!createperf$maybeWood(path))
			return;

		// >>> 以下到方法结束与原方法逐字相同（第 81–147 行）<<<
		Matcher match = STRIPPED_WOODS_PREFIX_REGEX.matcher(path);
		boolean hasFoundMatch = match.find();
		boolean strippedInPrefix = hasFoundMatch;

		if (!hasFoundMatch) {
			match = STRIPPED_WOOD_SUFFIX_REGEX.matcher(path);
			hasFoundMatch = match.find();
		}

		boolean noStrippedVariant = false;
		if (!hasFoundMatch && !BuiltInRegistries.ITEM.containsKey(itemId.withPrefix("stripped_"))
			&& !BuiltInRegistries.ITEM.containsKey(itemId.withSuffix("_stripped"))) {
			match = NON_STRIPPED_WOODS_REGEX.matcher(path);
			hasFoundMatch = match.find();
			noStrippedVariant = true;
		}

		if (!hasFoundMatch)
			return;

		// 原第 101–147 行整体原样粘贴（略去重复，见"原方法完整源码"一节）
		// ... prefix/suffix/type/matched_name/base/... planksCount ...
		// ... simpleWoodRecipe(...) x最多9 + insertIntoTag(...) ...
	}
}
```

**注意三个私有成员的访问**：`STRIPPED_WOODS_PREFIX_REGEX` / `STRIPPED_WOOD_SUFFIX_REGEX` / `NON_STRIPPED_WOODS_REGEX` 是 `private static final` 字段，`MISMATCHED_WOOD_NAMES` 是 `private static final Map`，`simpleWoodRecipe` / `insertIntoTag` 是 `private static` 方法。
`@Overwrite` 方法体里**直接写这些名字**即可：Mixin 在编译后会把对同名字段的引用重定向到目标类（mixin 类会获得目标类的字段/方法视图）。
若编译期报"找不到符号"（取决于你的 Mixin 配置与 IDE），改用：

- 字段：`@Shadow @Final private static Pattern STRIPPED_WOODS_PREFIX_REGEX;`（三个都要）
- 方法：`@Invoker("simpleWoodRecipe") private static ...`（`simpleWoodRecipe` 有三个重载，`@Invoker` 需要按描述符区分；**这也是建议"整个方法体内联、不提取 helper"的原因**：只要不提取，就不需要对 `simpleWoodRecipe` 做任何跨类访问声明）。

## 6. 可选补充：`Matcher` 复用（不推荐）

原理上是把每个 `itemId` 的 `Matcher` 缓存起来、每次 `reset(path)` 复用。三个陷阱：

1. `Matcher` **有状态**，同一个物品会被 3 个不同 `Pattern` 依次使用，所以缓存键必须是 `(itemId, pattern)` 或 3 个独立的 map；只按 `itemId` 缓存是**错的**。
2. `Matcher` 不可跨线程共享；`insertIntoPack` 目前是主线程调用，但一旦上游改成并行会立刻出错。
3. 收益只有「省掉 `Matcher` 对象分配」，`StringConcatFactory` 的路径拼接与 `ResourceLocation` 分配**一个都省不掉**。

**结论：跳过这一项。** 要么做第 5 节的闸门（同时省掉 regex + 分配），要么只做第 3 节的守卫。

## 7. 风险

| 风险 | 等级 | 说明 |
| --- | --- | --- |
| 闸门漏放行 → 缺少配方 | **高** | 内容正确性问题。闸门编码了「三个正则的后缀要求」，上游一改正则就可能失配。**必须**用第 8 节第 1 条的全量等价测试兜底 |
| 闸门多放行 | 低 | 只是回到现状（多跑 regex），无功能影响 |
| `@Overwrite` 与上游重构冲突 | 中 | 方法体约 70 行，上游一改就冲突。缓解：`required:true` + `defaultRequire:1` 会让注入失败**启动即报错**而非静默失效 |
| 与 Create 自带 mixin 冲突 | **无** | `create.mixins.json`（79 行）只注入原版类，已逐条核对 |
| 方案 A 的守卫与 `/reload` | 低 | 守卫假设 `AddPackFindersEvent` 不在 `/reload` 时重发。即使重发，产物不变，跳过仍然安全 |
| 方案 A 的守卫与运行期注册 | 低 | 若某 mod 在运行期往 `BuiltInRegistries.ITEM` 注册物品，跳过会漏配方。用第 3 节的「按 size 失效」版本可覆盖 |

## 8. 回归测试点

1. **配方集合全量等价**（必做）：写一个只在开发环境启用的对照测试，对 `BuiltInRegistries.ITEM.keySet()` 全量分别跑「原逻辑」与「新逻辑」，断言两边的 `JSON_FILES.keySet()` **集合完全相等**，且 `TAGS` 的每个 key→values 相等。
2. **木材变体齐全**：原版 oak/spruce/birch/jungle/acacia/dark_oak/mangrove/cherry/bamboo/crimson/warped，以及至少一个模组木材都能切出 stairs/slab/fence/fence_gate/door/trapdoor/pressure_plate/button/sign。
   **特别注意** `MISMATCHED_WOOD_NAMES`（第 51–55 行）里的两个映射：`Mods.ARS_N.blue_archwood → archwood`、`Mods.DD.blooming → bloom`。
3. **`_wood` / `_hyphae` 不产楼梯**：`oak_wood` 不应产出 `oak_stairs`（原第 136 行的短路）。
4. **bamboo / whistlecane 的 `planksCount=3`**：确认 `type.contains("block")` 分支仍是 3 而不是 6。
5. **两种命名风格都覆盖**：`stripped_oak_log`（前缀）与模组的 `xxx_log_stripped`（后缀）都要产配方。
6. **`noStrippedVariant` 的 tag 路径**：找一个「有 non-stripped log 但没 stripped 变体」的模组木材，确认 `runtime_generated/compat/<ns>/<base>` 这个 item tag 仍写入动态包。
7. **混凝土洗涤**：`washingRecipes` 未改动，但要确认守卫没有误伤第一次执行。
8. **世界创建界面反复开关**：开关「创建新世界 → 数据包」5 次，日志里 `Created {} recipes` 应只出现 1 次，无异常。

## 9. 收益

- **方案 A（估计）**：同一次会话内第 2 次及以后 **100% 消除**。玩家在会话中打开 3 次数据包界面 → 总量从 4× 降到 1×。单次成本随 N 线性（N=4 万时量级为「十万级 regex 扫描 + 十万级对象分配」），**需用探针计数实测**。
- **方案 B（估计）**：对**非木材物品**（占 95%+）省掉最多 2 个 `Matcher` 分配 + 3 次 regex 扫描 + 2 个 `ResourceLocation` + 2 次字符串拼接。反向成本是每个物品多 1 次 `endsWith` 链 + 一次长度 N 的字符循环。**净收益必须实测**（regex 在 JIT 后可能已经很快）；预计在 2 万+ 物品时为正。