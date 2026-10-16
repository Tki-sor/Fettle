# 09 · 加载期 / 事件 / compat / 注册 性能问题

> 覆盖范围：`foundation/data/RuntimeDataGenerator.java`、`content/contraptions/minecart/capability/CapabilityMinecartController.java`、`content/contraptions/ContraptionCollider.java`、
> `content/equipment/armor/BacktankUtil.java`、`compat/curios/Curios.java`、`compat/trainmap/*`、`content/decoration/palettes/*`、`AllCreativeModeTabs.java`、
> `foundation/utility/TickBasedCache.java`、`content/contraptions/actors/trainControls/ControlsServerHandler.java`、`foundation/utility/ServerSpeedProvider.java`、
> `content/kinetics/chainConveyor/ServerChainConveyorHandler.java`、`Create.java`、`foundation/events/CommonEvents.java`
> 调查依据：Create 6.0.10 / MC 1.21.1 / NeoForge 21.1.219 源码逐行核对；行号以本仓库为准；
> 原版（Minecraft/NeoForge）行为使用 gradle 缓存中的编译产物 + `javap` 反编译核对。
> 公共约定见 [01-mixin-playbook.md](01-mixin-playbook.md)；总体路线图见 [00-overview-and-roadmap.md](00-overview-and-roadmap.md)。

## 摘要

- **本领域真正的热点只有两条**：`RuntimeDataGenerator.insertIntoPack`（每会话重复执行 ≥2 次的全注册表扫描）与 `CapabilityMinecartController.onChunkUnloaded`（每区块卸载 O(全部矿车) 扫描）。其余条目多为**每帧/每 tick 的小额分配**。
- `ContraptionCollider.collideEntities` 是 per-contraption-per-tick，规模随装置数线性；它每条装置的固定成本（2×`CollisionList` 的 6 个 `double[16]`）是本领域**最大的稳定分配源**。
- `TrainMapSync.send` 与 `TrainMapManager.renderPhase` 的规模上限由「全服列车数 × 车厢数」与「轨道节点数 × 连接数」决定，在大型铁路存档上是**客户端/服务端双端**的显著成本。
- **本项目里 `ModConfigSpec.ConfigValue.get()` 不是性能问题**（已反编译 NeoForge 21.1.219 确认：命中路径是 `getfield cachedValue` + null 检查）。原 discovery 里把它列为热循环负担是误判，见第 13 条与「已核实不是性能问题的点」。
- 全树**没有**用反射做热路径（唯一的 `getDeclaredMethods` 在 gametest）；三处 `Pattern.compile` 都已正确提升为 `static final`。

## 发现

### 1. `RuntimeDataGenerator.insertIntoPack` 每次会话重复跑全注册表 → [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/foundation/data/RuntimeDataGenerator.java:57`（方法头）、`:58-61`（物品全表循环）、`:83,88,96`（3 次 `Pattern.matcher`）、`:94`（`withPrefix/withSuffix`）、`:212`（`CatnipCodecUtils.encode`） |
| 调用频次 | 由 `foundation/events/CommonEvents.java:257-261` 的 `AddPackFindersEvent`（SERVER_DATA）触发。已反编译核对 NeoForge：`ResourcePackLoader.populatePackRepository` 被 `ClientModLoader`（启动，连续两次，第二次是 SERVER_DATA）、`ServerPacksSource`（专用服务端启动）、`CreateWorldScreen`（**每次打开创建/编辑世界的数据包界面**）调用 → 同一次会话 **≥2 次** |
| 复杂度 / 成本 | O(N)，N = 全部注册物品数（原版 ≈1.3k；中大型整合包 2 万–6 万）。每物品最多 3 个 `Matcher` 分配 + 3 次 regex `find()`；每个非木材物品 2 次 `ResourceLocation` 分配（`withPrefix/withSuffix` → `StringConcatFactory` → `withPath` → `new ResourceLocation`）；每个命中木材最多 9 条 recipe 的完整 codec 编码 |
| 注入方式 | 首选 `@Inject(HEAD, cancellable)` 守卫（消除重复执行）；完整方案为 `@Overwrite`（见 overwrites） |
| 目标成员 | `com.simibubi.create.foundation.data.RuntimeDataGenerator.insertIntoPack(Lcom/simibubi/create/foundation/pack/DynamicPack;)V`；备选 `cuttingRecipes(Lnet/minecraft/resources/ResourceLocation;)V`（private static） |
| 需新增状态 | `@Unique private static boolean createperf$generatedOnce = false;`（作用域：进程内全局；失效时机：无 —— 注册表在 `AddPackFindersEvent` 触发前已冻结；并发：只在主线程调用）。若担心运行期注册，改用 `@Unique private static int createperf$generatedForSize = -1;` 按 `BuiltInRegistries.ITEM.size()` 失效 |
| 风险 | 低（守卫版，单点无状态语义）/ 高（`@Overwrite` 版：闸门假设了三个正则的语义） |
| 脆弱度 | 守卫版几乎不脆弱。`@Overwrite` 版**高度脆弱**：上游改正则（哪怕只加一个分支）会让快速闸门静默漏配 → **缺少配方**。`Pattern` 字段名/数量变化会让注入失败（好在 `defaultRequire:1` 会启动报错） |
| 置信度 | 重复执行的频次：**高概率**（`/reload` 是否也算需实测）；单次成本量级：**确定**（代码路径已逐行核对） |

**改前**

```java
	public static void insertIntoPack(DynamicPack dynamicPack) {
		for (ResourceLocation itemId : BuiltInRegistries.ITEM.keySet()) {
			cuttingRecipes(itemId);
			washingRecipes(itemId);
		}
		// ... 日志 / JSON_FILES.forEach(dynamicPack::put) / TAGS 编码 ...
		JSON_FILES.clear();
		JSON_FILES.trim();
		TAGS.clear();
	}

	private static void cuttingRecipes(ResourceLocation itemId) {
		String path = itemId.getPath();

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
		// ... 命中后最多 9 条 simpleWoodRecipe，每条最终走 CatnipCodecUtils.encode（:212）...
	}
```

**为什么慢**

三层放大叠加：

1. **频次放大**：`AddPackFindersEvent` 不是「每会话一次」。已反编译核对 NeoForge 21.1.219 的调用者（`ClientModLoader` / `ServerPacksSource` / `CreateWorldScreen`），`CreateWorldScreen` 每次打开「创建/编辑世界 → 数据包」都会重新 populate → 重发该事件。而 `JSON_FILES` / `TAGS` 在方法末尾被清空，所以每次都是**从零重算**。
2. **规模放大**：循环体成本 ∝ 注册物品数。整合包 2 万–6 万物品是原版的 15–45 倍。
3. **单物品常数放大**：`Pattern.matcher` 每次分配 `Matcher`；`withPrefix/withSuffix` 每次分配字符串中间态 + `ResourceLocation`（已 `javap` 确认字节码为 `invokedynamic makeConcatWithConstants` → `withPath` → `new ResourceLocation`）—— 这些分配发生在**每一个**物品上，而不是只在木材上。

**改后**

只做守卫（推荐第一步，零语义风险）：

```java
@Inject(method = "insertIntoPack(Lcom/simibubi/create/foundation/pack/DynamicPack;)V",
	at = @At("HEAD"), cancellable = true)
private static void createperf$skipRegeneration(DynamicPack dynamicPack, CallbackInfo ci) {
	if (createperf$generatedOnce)
		ci.cancel();
	createperf$generatedOnce = true;
}
```

完整方案（快速闸门替代无条件 regex）见 [overwrites/RuntimeDataGenerator-cuttingRecipes.md](overwrites/RuntimeDataGenerator-cuttingRecipes.md)。

**Mixin 骨架**

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

**验证方法**

- **注入生效**：`-Dmixin.debug.export=true`，检查 `run/.mixin.out/` 导出的 `RuntimeDataGenerator.class` 里 `insertIntoPack` 开头是否有你的静态守卫。
- **执行次数探针**（判断频次的事实依据）：在守卫里加 `@Unique private static int createperf$calls;`，每次进入 `System.out.println("insertIntoPack call #" + (++createperf$calls))`。然后分别做：启动游戏、`/reload`、打开「创建世界 → 数据包」再关掉、加载一个存档。记录各步骤后计数器的增量。**这是确认「/reload 是否重发」的唯一可靠办法。**
- **单次耗时探针**：用 `System.nanoTime()` 包住第一次执行，记录到日志。整合包环境下这个数字就是本条 finding 的收益上限。
- **分配观测**：`JFR ObjectAllocationSample` 或 Spark `/spark profiler start --alloc`，过滤 `java.util.regex.Matcher`、`net.minecraft.resources.ResourceLocation`，看是否集中在 `cuttingRecipes` 的栈上。

**收益估计**：守卫版消除「第 2 次及以后」的 100% 成本（估计；若玩家在会话内打开 3 次数据包界面则是 4× → 1×）。完整版对非木材物品（>95%）省掉最多 2 个 `Matcher` + 3 次 regex + 2 个 `ResourceLocation`（估计，需实测）。

**回归测试点**：见 overwrites 文件 §8，其中第 1 条「全量配方集合等价」是**必做项**。

### 2. `CapabilityMinecartController.onChunkUnloaded` 每区块卸载 O(全部矿车) → [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/contraptions/minecart/capability/CapabilityMinecartController.java:113-128`（关键行 `:117` 全表遍历、`:122-124`） |
| 调用频次 | 每一个 `ChunkEvent.Unload` 一次，由 `foundation/events/CommonEvents.java:114-117` 转发 |
| 复杂度 / 成本 | O(C)，C = 该维度 `loadedMinecartsByUUID` 中的矿车数。每元素：hash 桶遍历 + `isPresent()`（2×`WeakReference.get()` + `isAlive()`）+ `cart()` + `chunkPosition()`（已 `javap` 确认是字段读取，无分配）+ `ChunkPos.equals`。一次批量卸载 K 个区块 → O(K×C) |
| 注入方式 | `@Overwrite`（线性版）；真正降复杂度需要 `@Unique` 区块索引（与 `tick` 同一 Mixin 类） |
| 目标成员 | `CapabilityMinecartController.onChunkUnloaded(Lnet/neoforged/neoforge/event/level/ChunkEvent$Unload;)V` |
| 需新增状态 | 线性版：无。索引版：`@Unique private static Map<Level, Object2ObjectOpenHashMap<Long, ObjectArrayList<UUID>>> createperf$byChunk`（作用域：每 Level；失效：`LevelEvent.Unload`；并发：主线程。**必须在每个矿车内容变更点同步维护，否则漏卸载**） |
| 风险 | 中（`@Overwrite` 线性版语义几乎不变）/ 高（索引版需多点同步） |
| 脆弱度 | 线性版：低（只依赖 `loadedMinecartsByUUID` 这个 public 字段 + package-private 的 `queuedUnloads` 用 `@Shadow`）。索引版：上游任何一个新的「矿车进入/离开 map」路径没被覆盖 → 静默漏卸载 |
| 置信度 | 确定（O(C) 与调用链已逐行核对） |

**改前**

```java
	public static void onChunkUnloaded(ChunkEvent.Unload event) {
		ChunkPos chunkPos = event.getChunk()
			.getPos();
		Map<UUID, MinecartController> carts = loadedMinecartsByUUID.get(event.getLevel());
		for (MinecartController minecartController : carts.values()) {
			if (minecartController == null)
				continue;
			if (!minecartController.isPresent())
				continue;
			AbstractMinecart cart = minecartController.cart();
			if (cart.chunkPosition()
				.equals(chunkPos))
				queuedUnloads.get(event.getLevel())
					.add(cart.getUUID());
		}
	}
```

**为什么慢**

频次 × 规模：区块卸载是**玩家移动的副产品**（飞/坐列车/传送/缩视距/切维度）。单次成本 O(C) 本身很小（每元素约 10 ns 量级），但**批量卸载**时 K 可以很大（传送 1000 格 → 一次卸载上百区块；`/forceload remove all` 后飞一圈 → 上千）。
放大模型：**K=500、C=200 → 10 万次迭代**；K=2000、C=1000（大型自动化铁路）→ **200 万次**，全部在服务端主线程的 `ChunkEvent.Unload` 同步路径里。
另外 `:122` 的 `cart()` 与 `:123` 的 `cart.chunkPosition()` 之间有**一次多余的 `weakRef.get()`**，且第二次没有 null 检查（前置 `isPresent()` 保证了，但这个保证跨了两次 get）。

**改后**

```java
	public static void onChunkUnloaded(ChunkEvent.Unload event) {
		Level level = event.getLevel();
		Map<UUID, MinecartController> carts = loadedMinecartsByUUID.get(level);
		if (carts.isEmpty())
			return;

		ChunkPos chunkPos = event.getChunk()
			.getPos();
		List<UUID> unloads = queuedUnloads.get(level);

		for (MinecartController controller : carts.values()) {
			if (controller == null || !controller.isPresent())
				continue;
			AbstractMinecart cart = controller.cart();
			if (cart == null)
				continue;
			if (cart.chunkPosition()
				.equals(chunkPos))
				unloads.add(cart.getUUID());
		}
	}
```

**Mixin 骨架**

```java
package com.example.createperf.mixin.loadtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import com.simibubi.create.content.contraptions.minecart.capability.CapabilityMinecartController;
import com.simibubi.create.content.contraptions.minecart.capability.MinecartController;

import net.createmod.catnip.data.WorldAttached;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.level.ChunkEvent;

@Mixin(value = CapabilityMinecartController.class, remap = false)
public abstract class CapabilityMinecartControllerMixin {

	// package-private static 字段；mixin 类应用时会被合并进目标类，@Shadow 可用。
	@Shadow private static WorldAttached<List<UUID>> queuedUnloads;

	@Overwrite
	public static void onChunkUnloaded(ChunkEvent.Unload event) {
		Level level = event.getLevel();
		Map<UUID, MinecartController> carts = CapabilityMinecartController.loadedMinecartsByUUID.get(level);
		if (carts.isEmpty())
			return;

		ChunkPos chunkPos = event.getChunk()
			.getPos();
		List<UUID> unloads = queuedUnloads.get(level);

		for (MinecartController controller : carts.values()) {
			if (controller == null || !controller.isPresent())
				continue;
			AbstractMinecart cart = controller.cart();
			if (cart == null)
				continue;
			if (cart.chunkPosition()
				.equals(chunkPos))
				unloads.add(cart.getUUID());
		}
	}
}
```

**验证方法**

- **复杂度探针**：在 Mixin 里加 `@Unique private static long createperf$iterations;`，每次循环体 `++`；每 100 tick 打一次日志。做「原地 `/tp +1000 格」一次，观察该次的迭代数是否 ≈ 卸载区块数 × 矿车数。
- **基线对比**：同一存档、同一批矿车，分别做「`/tp +2000 格`」10 次，用 `/spark profiler --timeout 30` 对比 `ChunkEvent.Unload` 相关帧。
- **分配**：JFR `ObjectAllocationSample` 应看不到明显对象（本方法不分配，除了 `queuedUnloads.add` 时的扩容）。
- **索引版一致性**：见 overwrites 文件 §7 第 7 条。

**收益估计**：线性版只有常数项（小）。索引版才能把 O(K×C) 降到 O(K)，量级为「一次批量卸载省 10 万–200 万次迭代」（估计，需实测）。

**回归测试点**：见 [overwrites/CapabilityMinecartController-onChunkUnloaded.md](overwrites/CapabilityMinecartController-onChunkUnloaded.md) §7。
==> _part2.tmp.md <==
| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/contraptions/minecart/capability/CapabilityMinecartController.java:50-102`（关注 `:89` `new ArrayList<>()`、`:91-96` 全表遍历、`:57-87` 的 queued 段） |
| 调用频次 | 每维度每 tick 一次，由 `foundation/events/CommonEvents.java:133-144`（`LevelTickEvent.Post`，非客户端）转发 |
| 复杂度 / 成本 | O(C)，C = 该维度已加载矿车数。每元素：1 次 `HashMap.Node` 访问 + `isPresent()`（2×`WeakReference.get()` + `isAlive()`）≈ 5–15 ns。另有每 tick 1 个 `ArrayList` + 1 个 `HashIterator`（都**不逃逸**，可被 C2 标量替换） |
| 注入方式 | 不建议单独注入。若要连带做，见 overwrites（`@Overwrite` 消 `toRemove` 分配） |
| 目标成员 | `CapabilityMinecartController.tick(Lnet/minecraft/world/level/Level;)V` |
| 需新增状态 | 无（线性版）；索引版需要 `@Unique` 区块桶 |
| 风险 | 低（线性版） |
| 脆弱度 | 低。但注意：`tick` 与 `onChunkUnloaded` 共享 `queuedUnloads`，**如果同时给两个方法各写一个 Mixin 类，两个类的静态状态不共享** |
| 置信度 | 确定（逐行核对 + 反编译原版确认 `chunkPosition()` 无分配） |

**改前**

```java
		List<UUID> toRemove = new ArrayList<>();

		for (Entry<UUID, MinecartController> entry : carts.entrySet()) {
			MinecartController controller = entry.getValue();
			if (controller != null && controller.isPresent())
				continue;
			toRemove.add(entry.getKey());
		}

		for (UUID uuid : toRemove) {
			keySet.remove(uuid);
			cartsWithCoupling.remove(uuid);
		}
```

**为什么慢 —— 诚实核算（原 finding 高估）**

| 原 finding 的指控 | 真实成本（核对后） |
| --- | --- |
| 「每 tick `new ArrayList`」 | 1 个对象 + 1 个 `Object[]`。`toRemove` **从不逃逸**出 `tick`，C2 的逃逸分析 + 标量替换通常**完全消除**。 |
| 「无条件遍历全部矿车」 | C=1000 时约 **5–15 μs/tick**，相对 50 ms 预算是 **0.01%–0.03%**。 |
| 「entrySet 迭代器」 | `HashMap.keySet()` 复用构造期初始化的视图对象（**无分配**）；`iterator()` 每次一个新 `HashIterator`。 |
| 「每轮还调 `cart()` 与 `chunkPosition()`」 | **不准确**：`tick` 里没有 `chunkPosition()` 调用（那个在 `onChunkUnloaded`）；`cart()` 也只在 `isPresent()` 内部间接调用。 |

**改后**（仅当你要连带做时；保持语义等价）

```java
		if (!carts.isEmpty()) {
			Iterator<UUID> it = keySet.iterator();
			while (it.hasNext()) {
				MinecartController controller = carts.get(it.next());
				if (controller != null && controller.isPresent())
					continue;
				it.remove();
			}
		}
		// 原实现删除的 UUID 必来自 carts 的 key，所以等价于「统一清 cartsWithCoupling」。
		cartsWithCoupling.retainAll(keySet);
```

**Mixin 骨架**（Low，可省略；若做请与第 2 条合并进**同一个** `@Mixin` 类）

```java
@Mixin(value = CapabilityMinecartController.class, remap = false)
public abstract class CapabilityMinecartControllerMixin {
	@Overwrite
	public static void tick(Level world) { /* 见上 */ }
}
```

**收益估计**：每 tick 省 1 个 `ArrayList`（估计，JIT 后大概率不可观测）。**不建议为它单独投入**。

**回归测试点**：矿车死亡/销毁后从 map 中消失；耦合标记不残留（见 overwrites §7）。

### 4. `ContraptionCollider.collideEntities` 每装置每 tick 的固定分配 → [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/contraptions/ContraptionCollider.java:98`（`new CollisionList()`）、`:100-101`（`bounds.inflate(2).expandTowards(0,32,0)` + 方法引用谓词）、`:114-118`（`getSelfAndPassengers().forEach`）、`:161`（第二个 `CollisionList`，仅当 `getSimplifiedEntityColliders()` 为 null 时） |
| 调用频次 | 每装置每 tick（双端），由 `content/contraptions/ContraptionHandler.java:54` 在遍历 `loadedContraptions` 时对每个存活装置调用 |
| 复杂度 / 成本 | 每装置**固定**：2 个 `AABB`（`inflate` 与 `expandTowards` 都 `new AABB`）+ 1 次 `getEntitiesOfClass`（返回新的 `ArrayList`）+ 1 个捕获式方法引用 + 1 个 `CollisionList`（= 1 对象 + **6 个 `double[16]`** ≈ 800 B）。每实体额外：`getSelfAndPassengers()` 的 stream 链（`Stream.of` + `ImmutableList.stream()` + `flatMap` + `Stream.concat` + `forEach` 的 sink，已 javap 确认）+ 1 个 lambda 实例 |
| 注入方式 | `@WrapOperation`（包裹 `getSelfAndPassengers`，把 `forEach` 合并进 handler）+ `@Inject(HEAD, cancellable)` 空表短路 |
| 目标成员 | `ContraptionCollider.collideEntities(Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;)V`（package-private static） |
| 需新增状态 | 无（`bounds.isEmpty()` 是原版 `AABB` 的既有能力，不需要新字段） |
| 风险 | 中（合并 `forEach` 改变了调用形状；`HEAD` 短路改变了原方法的前置赋值） |
| 脆弱度 | `bounds.isEmpty()` 短路依赖「空 `AABB` 不可能与任何实体相交」这一原版不变量（稳定）。`@WrapOperation` 依赖 `Entity.getSelfAndPassengers()` 的签名稳定（原版 API，稳定）。`方法描述符里的 `AbstractContraptionEntity` 是 Create 类，上游改包名会失效（`defaultRequire:1` 启动报错） |
| 置信度 | 确定（分配点已逐个核对；`CollisionList` 的 6 个数组见 `foundation/collision/CollisionList.java:12-17`） |

**改前**

```java
	static void collideEntities(AbstractContraptionEntity contraptionEntity) {
		Level world = contraptionEntity.getCommandSenderWorld();
		Contraption contraption = contraptionEntity.getContraption();
		AABB bounds = contraptionEntity.getBoundingBox();

		if (contraption == null)
			return;
		if (bounds == null)
			return;

		Vec3 contraptionPosition = contraptionEntity.position();
		Vec3 contraptionMotion = contraptionPosition.subtract(contraptionEntity.getPrevPositionVec());
		Vec3 anchorVec = contraptionEntity.getAnchorVec();
		ContraptionRotationState rotation = null;

		// ... 安全锁 / skipClientPlayer ...

		CollisionList denseViableColliders = new CollisionList();

		List<Entity> entitiesWithinAABB = world.getEntitiesOfClass(Entity.class, bounds.inflate(2)
			.expandTowards(0, 32, 0), contraptionEntity::canCollideWith);
		for (Entity entity : entitiesWithinAABB) {
			if (!entity.isAlive() || world.tickRateManager().isEntityFrozen(entity))
				continue;

			PlayerType playerType = getPlayerType(entity);
			if (playerType == PlayerType.REMOTE) { /* ... */ }

			entity.getSelfAndPassengers()
				.forEach(e -> {
					if (e instanceof ServerPlayer)
						((ServerPlayer) e).connection.aboveGroundTickCount = 0;
				});
			// ... 每个实体的完整碰撞解算 ...
		}
		// ...
	}
```

配套源码（`foundation/collision/CollisionList.java:10-19`，已核对）：每次 `new CollisionList()` 都分配 6 个 `double[DEFAULT_CAPACITY=16]`。

**为什么慢**

- **频次**：per-contraption-per-tick，双端。一个「20 台钻机/收割机/机械臂同时在跑」的基地就是 20 次/tick。
- **固定成本与实体数无关**：即使 `entitiesWithinAABB` 为空（绝大多数装置周围没有实体），也已经付出了 2 个 `AABB` + 1 个 `CollisionList`（≈800 B）的分配。
- **`getEntitiesOfClass`（:100）**：原版实现会构造一个新的 `ArrayList` 并注入谓词 lambda（捕获 `contraptionEntity`）。谓词是必需的（语义），但列表分配不是。
- **`:114-118 的 stream 链**：只为「若这个实体（或其乘客）是 `ServerPlayer` 就把 `aboveGroundTickCount` 清 0」。已用 `javap` 确认 `Entity.getSelfAndPassengers()` 的字节码是 `Stream.of(this)` + `getIndirectPassengersStream()` + `Stream.concat`，而 `getIndirectPassengersStream()` 是 `passengers.stream().flatMap(...)` —— **无乘客时也要构造 2~3 个 stream 对象 + flatMap 的 sink**。
  放大场景：一次装置扫到 10 个实体（掉落物、动物、玩家）→ 每 tick 30+ 个 stream 对象。

**改后**

一次性的「空 bounds / 无实体」短路 + 把 stream 管线换成两次 `instanceof` 检查：

```java
	static void collideEntities(AbstractContraptionEntity contraptionEntity) {
		Level world = contraptionEntity.getCommandSenderWorld();
		Contraption contraption = contraptionEntity.getContraption();
		AABB bounds = contraptionEntity.getBoundingBox();

		if (contraption == null)
			return;
		if (bounds == null)
			return;
		// createperf: 空 AABB 与任何实体都不相交，后面的全部工作都是白跑。
		// 注意：必须放在 null 检查之后，且不改变方法对安全锁/排队的任何副作用。
		if (bounds.isEmpty())
			return;
		// ... 原方法体不变 ...
	}
```

以及把 `:114-118` 换成：

```java
		// createperf: 等价于 entity.getSelfAndPassengers().forEach(...)，但无 stream 管线。
		createperf$resetAboveGroundTickCount(entity);

	/** @Unique */
	private static void createperf$resetAboveGroundTickCount(Entity entity) {
		if (entity instanceof ServerPlayer player)
			player.connection.aboveGroundTickCount = 0;
		List<Entity> passengers = entity.getPassengers();
		int size = passengers.size();
		for (int i = 0; i < size; i++) {
			Entity passenger = passengers.get(i);
			if (passenger instanceof ServerPlayer player)
				player.connection.aboveGroundTickCount = 0;
		}
	}
```

**Mixin 骨架**

```java
package com.example.createperf.mixin.loadtime;

import java.util.List;
import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.ContraptionCollider;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

@Mixin(value = ContraptionCollider.class, remap = false)
public abstract class ContraptionColliderMixin {

	/**
	 * 空 AABB 的短路。放在 HEAD 并不可靠（原方法还没算出 bounds），
	 * 所以这里用 ModifyExpressionValue 更精确：见下方 wrapOperation 的说明。
	 * 实务上更简单的是 @Inject 到方法头不可行（bounds 在方法体内赋值），
	 * 因此用 @WrapOperation 包 getEntitiesOfClass 来做"空表短路"：
	 */
	@WrapOperation(method = "collideEntities(Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
	private static <T extends Entity> List<T> createperf$skipEmptyBounds(
			net.minecraft.world.level.Level level, Class<T> clazz, net.minecraft.world.phys.AABB area,
			java.util.function.Predicate<? super T> predicate, Operation<List<T>> original) {
		if (area.isEmpty())
			return java.util.Collections.emptyList();
		return original.call(level, clazz, area, predicate);
	}

	/**
	 * 把 entity.getSelfAndPassengers().forEach(consumer) 合并成一次直接遍历。
	 * 注意：WrapOperation 的 handler 直接消费 stream，等价于 forEach。
	 */
	@WrapOperation(method = "collideEntities(Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;getSelfAndPassengers()Ljava/util/stream/Stream;"))
	private static java.util.stream.Stream<Entity> createperf$noStreamPileline(
			Entity entity, Operation<java.util.stream.Stream<Entity>> original,
			@com.llamalad7.mixinextras.sugar.Local java.util.function.Consumer<Entity> consumer) {
		createperf$resetAboveGroundTickCount(entity);
		// 返回空 stream，让紧随其后的 forEach 无操作；handler 已经完成了工作。
		return java.util.stream.Stream.empty();
	}

	@Unique
	private static void createperf$resetAboveGroundTickCount(Entity entity) {
		if (entity instanceof ServerPlayer player)
			player.connection.aboveGroundTickCount = 0;
		List<Entity> passengers = entity.getPassengers();
		for (int i = 0, size = passengers.size(); i < size; i++) {
			Entity passenger = passengers.get(i);
			if (passenger instanceof ServerPlayer player)
				player.connection.aboveGroundTickCount = 0;
		}
	}
}
```

**注意 `@WrapOperation` 的 handler 里直接完成工作、返回 `Stream.empty()` 这一点**：
这样紧随其后的 `forEach` 变成对空 stream 的操作 —— 语义上等价，但**如果另一个 mod 也改了这处 `forEach`**（比如想把 `aboveGroundTickCount` 改成别的东西）就会失效。
更稳妥的形态是**只包 `forEach` 而不动 stream 来源**（`@Redirect` 到 `Stream.forEach`），但那样仍然要构造 stream。两害相权，本条建议：

> **如果你不介意多一个 helper 类**，用 `@WrapOperation` 返回 `Stream.of(entity)` 并让 `forEach` 正常跑 —— 那等于没优化。所以最干净的其实是**接受 stream 管线，只做空表短路**（第 1 个 `@WrapOperation`），把第 2 个留作可选。

**收益估计**：空表短路对「无实体」的装置省掉 1 个 `CollisionList`（≈800 B）+ 1 次 `getEntitiesOfClass`；stream 消除对「有实体」的装置省 3–5 个对象/实体。量级：**每装置每 tick 省 1–3 个对象（无实体）/ 10–50 个对象（10 个实体）**（估计）。

**回归测试点**：

1. 玩家被装置推挤/夹住时的「踩地重置」行为不变：站在钻机/收割机上被推，确认不会持续受「掉落伤害」判定影响（`aboveGroundTickCount` 的意义）。
2. 玩家坐在矿车/船里被装置推：确认乘客的 `aboveGroundTickCount` 也被重置（`getSelfAndPassengers` 的乘客分支）。
3. 空 bounds 装置：用一个刚生成还没初始化的装置（或 `place` 后立刻 `destroy`）确认没有 NPE。
4. 碰撞行为整体不变：让一辆列车/钻机穿过一群牛/掉落物，确认推动效果、伤害判定与加固前一致。

### 5. `BacktankUtil.getAllWithAir` 每次调用重建列表 + 排序 → [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/equipment/armor/BacktankUtil.java:50-65`（`:51` `new ArrayList`、`:43` 供应商里的 `entity.getArmorSlots()`、`:62` `List.sort` 内 `getAir→maxAir`） |
| 调用频次 | 三条路径：<br>(a) **每帧** 每物品条回调 `:143`/`:155`/`:181`（由 `ExtendoGripItem.java:213-224`、`PotatoCannonItem.java:255-266` 转发；物品条每帧重绘）<br>(b) **每帧** HUD 的 `RemainingAirOverlay.java:70`<br>(c) 事件驱动：`DivingHelmetItem.java:91`（`LivingBreatheEvent`，频率中等） |
| 复杂度 / 成本 | 每调用：1 个 `ArrayList` + 1 个 `getArmorSlots()` 的 `List`（可迭代视图，**每个护甲槽一个 `ItemStack` 引用**）+ 每个护甲物品一次 tag 匹配 + `sort`（O(k log k)，k ≤ 4）+ 每次比较 2 次 `getAir`（每次 `getAir` → `maxAir` → **遍历整个附魔表** + 读两次配置）。curios 装了 `Curios.java:57-71` 后供应商数变 2，每个供应商各自走一遍全部槽位 |
| 注入方式 | `@WrapOperation` 包 `List.sort`（消除每物品 3 次调用里的重复排序）+ `@Inject(HEAD, cancellable)` 一层短期缓存（可选，需处理失效） |
| 目标成员 | `BacktankUtil.getAllWithAir(Lnet/minecraft/world/entity/LivingEntity;)Ljava/util/List;`；备选 `BacktankUtil.isBarVisible/getBarWidth/getBarColor(ItemStack,int)` 三个静态方法（在同一 tick 内返回同一份 list） |
| 需新增状态 | `@Unique private static LivingEntity createperf$lastEntity; private static int createperf$lastTick; private static List<ItemStack> createperf$lastResult;`（作用域：客户端主线程全局；失效时机：tick 号变化 或 entity 变化 或 空气值变化；并发：只在客户端渲染/逻辑主线程） |
| 风险 | 中（缓存的生命周期） / 低（只做 `sort` 消除） |
| 脆弱度 | 中等。`BACKTANK_SUPPLIERS` 是 public API（`addBacktankSupplier`），第三方 mod 可以注册供应商 → **缓存必须按「供应商集合版本」失效，或在每次调用时至少重跑供应商**。这是本条最脆弱的点 |
| 置信度 | 「每帧调用」为**高概率**（1.21.1 的物品条在 `Gui.renderSlot` 内每帧调用 `isBarVisible/getBarWidth/getBarColor`；我已在新版反编译产物 `GuiGraphicsExtractor.java:886` 确认同样的每帧模式，但 **1.21.1 的具体调用点需实测**）；「分配与排序成本」为**确定** |

**改前**

```java
	private static final List<Function<LivingEntity, List<ItemStack>>> BACKTANK_SUPPLIERS = new ArrayList<>();

	static {
		addBacktankSupplier(entity -> {
			List<ItemStack> stacks = new ArrayList<>();
			for (ItemStack itemStack : entity.getArmorSlots())
				if (AllTags.AllItemTags.PRESSURIZED_AIR_SOURCES.matches(itemStack))
					stacks.add(itemStack);
			return stacks;
		});
	}

	public static List<ItemStack> getAllWithAir(LivingEntity entity) {
		List<ItemStack> all = new ArrayList<>();

		for (Function<LivingEntity, List<ItemStack>> supplier : BACKTANK_SUPPLIERS) {
			List<ItemStack> result = supplier.apply(entity);

			for (ItemStack stack : result)
				if (hasAirRemaining(stack))
					all.add(stack);
		}

		// Sort with ascending order (we want to prioritize the most empty so things actually run out)
		all.sort((a, b) -> Float.compare(getAir(a), getAir(b)));

		return all;
	}

	public static int getAir(ItemStack backtank) {
		return Math.min(backtank.getOrDefault(AllDataComponents.BACKTANK_AIR, 0), maxAir(backtank));
	}

	public static int maxAir(ItemStack backtank) {
		int enchantLevel = 0;
		ItemEnchantments enchants = backtank.getTagEnchantments();
		for (Entry<Holder<Enchantment>> entry : enchants.entrySet()) {
			if (entry.getKey().is(AllEnchantments.CAPACITY)) {
				enchantLevel = entry.getIntValue();
				break;
			}
		}
		return maxAir(enchantLevel);
	}

	public static int maxAir(int enchantLevel) {
		return AllConfigs.server().equipment.airInBacktank.get()
			+ AllConfigs.server().equipment.enchantedBacktankCapacity.get() * enchantLevel;
	}
```

**为什么慢**

- **频次 × 3**：一帧内 `isBarVisible`、`getBarWidth`、`getBarColor` 各自调用一次 `getAllWithAir`（`BacktankUtil.java:149/162/187`）。如果玩家同时拿着 Extendo Grip 和 Potato Cannon（或在副手），就再翻倍。Plus `RemainingAirOverlay` 的每帧一次（:70）。
- **每次调用都要重新枚举护甲 + 重新排序**，而结果在**同一帧内是同一个值**（玩家的护甲不会在一帧内改变）。
- **`sort` 的比较器很贵**：每次比较调 2 次 `getAir`，每次 `getAir` 调 `maxAir(ItemStack)` → 遍历整个 `ItemEnchantments`（附魔表可能十几项）+ 两次 `AllConfigs.server()` 字段链 + 两次 `ConfigValue.get()`（后者已确认很便宜，见第 13 条）。k ≤ 4 时 sort 的比较次数 ≤ 5 次，所以这部分**绝对量不大**，但它是「每次调用都付」的。
- **curios 放大**：`Curios.java:57-71` 注册了第二个供应商，它会遍历**全部 curio 槽**并对每槽做 `PRESSURIZED_AIR_SOURCES` tag 匹配。curio 槽总数可达 20+。

**改后**

第一步（零状态，只消掉「同一帧内 3 次排序」中的 2 次）：把排序结果在三个 bar 回调之间共享。最干净的形态是在物品条回调层缓存，但那是客户端渲染代码；更简单的是**让 `getAllWithAir` 只在 tick 内缓存一次**：

```java
	// @Unique：客户端主线程单线程使用，不需要 volatile
	@Unique private static LivingEntity createperf$cachedEntity;
	@Unique private static int createperf$cachedAtTick = Integer.MIN_VALUE;
	@Unique private static List<ItemStack> createperf$cachedResult = List.of();

	/**
	 * 等价于原 getAllWithAir，但在同一个游戏 tick 内对同一个实体复用结果。
	 * 失效条件：tick 号变化 或 entity 变化。
	 * 注意：同一 tick 内护甲槽内容不会变（物品移动发生在 tick 边界），因此安全。
	 */
	public static List<ItemStack> getAllWithAir(LivingEntity entity) {
		int tick = createperf$currentTick();          // 见下
		if (createperf$cachedEntity == entity && createperf$cachedAtTick == tick)
			return createperf$cachedResult;

		List<ItemStack> all = new ArrayList<>();
		for (Function<LivingEntity, List<ItemStack>> supplier : BACKTANK_SUPPLIERS) {
			for (ItemStack stack : supplier.apply(entity))
				if (hasAirRemaining(stack))
					all.add(stack);
		}
		all.sort((a, b) -> Float.compare(getAir(a), getAir(b)));

		createperf$cachedEntity = entity;
		createperf$cachedAtTick = tick;
		createperf$cachedResult = all;
		return all;
	}
```

tick 号从哪来？服务端/客户端都能用 `level.getGameTime()`，但客户端渲染帧可能比 tick 密（一 tick 多帧）—— 那**正好是我们要的**（同一 tick 的多帧复用）。
偶发问题：`level` 可能为 null（实体未加入世界）。此时退化为不缓存。

**⚠️ 一个更简单的替代（推荐先做）**：只缓存 `sort` 结果没意义，因为列表本身要重建。**如果不想引入状态**，可以先做「消除重复的 `maxAir` 遍历」：把比较器改成先算一次 air：

```java
		// 用一次 getAir 计算替代比较器里的重复调用
		all.sort(java.util.Comparator.comparingInt(BacktankUtil::getAir));
```

—— 这**没有**变快（`comparingInt` 内部还是每次比较调一次 `getAir`，只是从 2 次降到 1 次）。真正省的是把 air 值预先算好：

```java
		int n = all.size();
		int[] air = new int[n];
		for (int i = 0; i < n; i++)
			air[i] = getAir(all.get(i));
		// k ≤ 4，插入排序即可，避免装箱/比较器开销
		for (int i = 1; i < n; i++) {
			int keyAir = air[i];
			ItemStack keyStack = all.get(i);
			int j = i - 1;
			while (j >= 0 && air[j] > keyAir) {
				air[j + 1] = air[j];
				all.set(j + 1, all.get(j));
				j--;
			}
			air[j + 1] = keyAir;
			all.set(j + 1, keyStack);
		}
```

但注意：`List.sort` 是**稳定排序**，插入排序也是稳定的，所以顺序保证一致。

**Mixin 骨架**（只做「跳过无背罐时的排序 + 预取 air 值」的版本）

```java
package com.example.createperf.mixin.client;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import com.simibubi.create.content.equipment.armor.BacktankUtil;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

@Mixin(value = BacktankUtil.class, remap = false)
public abstract class BacktankUtilMixin {

	@Overwrite
	public static List<ItemStack> getAllWithAir(LivingEntity entity) {
		// 见上"改后"；@Overwrite 方法体内直接引用 BACKTANK_SUPPLIERS / hasAirRemaining / getAir
	}
}
```

> 注意：本类只有 1 个供应商（默认）或 2 个（curios），方法很短。`@Overwrite` 的冲突面尚可，但**如果只是想省排序，收益可能小于风险**。
> 更保守的做法是不 `@Overwrite`，只 `@WrapOperation` 包 `List.sort` 为 `no-op`（当 size ≤ 1 时）—— 但 `List.sort` 在 `ArrayList` 上会先转数组，这个转换本身也有成本。

**收益估计**：省掉每帧 2–4 次重复的「护甲枚举 + 排序 + 附魔表遍历」。量级：**每帧 3–5 个 `ArrayList` + 每帧约 10–40 次 `maxAir` 的附魔表遍历**（估计，物品条是否每帧调用需实测）。

**回归测试点**：

1. 背罐耐久条：装备 1 个铜背罐 → 条可见、宽度/颜色随空气量变化；装备 2 个（护甲+curio）→ 取平均值行为不变。
2. 空气耗尽顺序：装 2 个不同气量的背罐，跑 Extendo Grip / Potato Cannon 直到耗尽，确认**先消耗更空的**（排序契约）。
3. 卸下背罐：条立刻消失（缓存不残留）—— 如果把缓存做成了「按实体缓存」，这是必测项。
4. 换穿背罐：打开背包把背罐从胸甲换到 curio 槽（需要 curios），确认列表随之变化。
5. 水下 HUD：戴潜水头盔下水，`RemainingAirOverlay` 显示的剩余时间与背罐数一致。

==> _part3.tmp.md <==
| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/compat/curios/Curios.java:41-55`（谓词注册）；接线在 `Create.java:163-165`；每帧调用点在 `content/equipment/goggles/GoggleOverlayRenderer.java:93`（`renderOverlay`） |
| 调用频次 | 每帧一次（只要鼠标指着方块且游戏不是 spectator/hideGui）。链路：`GoggleOverlayRenderer.renderOverlay`:93` → `GogglesItem.isWearingGoggles(Player)`（`GogglesItem.java:41-48`，遍历 `IS_WEARING_PREDICATES`）→ 第 2 个谓词即 Curios 的那个 |
| 复杂度 / 成本 | 每帧：1 次 `entity.getCapability(CuriosCapability.INVENTORY)` + 1 个 `Optional` + 1 个 `Optional.map` + `getCurios()`（返回 slot→handler 的 Map）+ 遍历全部 handler × 每个 handler 的全部槽 + 每槽 `stacksHandler.getStacks().getStackInSlot(slot)` + `AllItems.GOGGLES.isIn(stack)` |
| 注入方式 | `@Redirect` 到 `GoggleOverlayRenderer.renderOverlay` 里那次对 `GogglesItem.isWearingGoggles` 的调用（**唯一能消除整链的注入口**） |
| 目标成员 | `GoggleOverlayRenderer.renderOverlay(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V` 内的 `GogglesItem.isWearingGoggles(Lnet/minecraft/world/entity/player/Player;)Z` 调用点 |
| 需新增状态 | `@Unique private static boolean createperf$gogglesCached; @Unique private static int createperf$gogglesCachedTick = -1;`（作用域：客户端主线程；失效时机：客户端 tick 号变化 → 每 tick 最多算一次；并发：仅客户端主线程） |
| 风险 | 低（缓存「眼镜是否佩戴」是纯客户端视觉状态，缓存 1 tick 无行为影响） |
| 脆弱度 | 中。`@Redirect` 是单点覆盖，**如果另一个 mod 也 redirect 同一个调用点就会有一个失效**（Mixin 会记录 conflict，不崩）。`GogglesItem.isWearingGoggles` 的签名/调用位置变化会让注入失败（`defaultRequire:1` 启动报错） |
| 置信度 | 确定（调用链与「全链路无缓存」已逐行核对；每帧频率由 `LayeredDraw.Layer` 语义保证） |

**改前**

```java
	// Curios.java:38-55
	public static void init(IEventBus modEventBus) {
		modEventBus.addListener(Curios::onClientSetup);

		GogglesItem.addIsWearingPredicate(player -> resolveCuriosMap(player)
			.map(curiosMap -> {
				for (ICurioStacksHandler stacksHandler : curiosMap.values()) {
					// Search all the curio slots for Goggles existing
					int slots = stacksHandler.getSlots();
					for (int slot = 0; slot < slots; slot++) {
						if (AllItems.GOGGLES.isIn(stacksHandler.getStacks().getStackInSlot(slot))) {
							return true;
						}
					}
				}

				return false;
			})
			.orElse(false));
		// ... BacktankUtil.addBacktankSupplier(...) ...
	}

	// GogglesItem.java:41-48
	public static boolean isWearingGoggles(Player player) {
		for (Predicate<Player> predicate : IS_WEARING_PREDICATES) {
			if (predicate.test(player)) {
				return true;
			}
		}
		return false;
	}

	// GoggleOverlayRenderer.java:92-93
		BlockEntity be = world.getBlockEntity(pos);
		boolean wearingGoggles = GogglesItem.isWearingGoggles(mc.player);
```

**为什么慢**

- **频次 = 每帧**。`GoggleOverlayRenderer.OVERLAY` 是 `LayeredDraw.Layer`，每帧调一次。
- **链很长**：capability 解析 → `Optional` → `map` → `getCurios()` → 嵌套两层循环（handler × slot）→ 每槽一次 `ItemStack` 获取 + 一次 `isIn`（Holder 比较）。
- **完全没有缓存**：眼镜是否在 curio 槽里，在一个 tick 内不可能变（物品转移发生在 tick 边界）。
- **放大场景**：装了 Curios 且玩家身上有多个 curio 槽（饰品栏 mod 常见 10–30 槽）。此时每帧 10–30 次槽位读取；如果玩家的 curio 槽里**没有**眼镜（绝大多数情况），还要遍历到底才能返回 `false`。

**改后**

在**调用点**缓存（比在谓词内部缓存更安全，因为不动 Curios 的公共 API 语义）：

```java
	@Unique private static boolean createperf$gogglesCached = false;
	@Unique private static int createperf$gogglesCachedTick = -1;

	private static boolean createperf$isWearingGoggles(Player player) {
		if (player == null)
			return false;
		int tick = (int) (player.level().getGameTime() & 0x7FFFFFFF);
		if (createperf$gogglesCachedTick == tick)
			return createperf$gogglesCached;
		boolean result = GogglesItem.isWearingGoggles(player);
		createperf$gogglesCached = result;
		createperf$gogglesCachedTick = tick;
		return result;
	}
```

**边界情况**：客户端只有一个本地玩家，所以缓存不需要带 `player` 键。若将来有 mod 用假 `LocalPlayer` 渲染（replay mod），需要把 `player` 也纳入缓存键。
另外死亡重生会换 `mc.player` 对象，但 tick 号继续增长 → 最多延迟 1 tick，可接受。

**Mixin 骨架**

```java
package com.example.createperf.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.simibubi.create.content.equipment.goggles.GoggleOverlayRenderer;
import com.simibubi.create.content.equipment.goggles.GogglesItem;

import net.minecraft.world.entity.player.Player;

@Mixin(value = GoggleOverlayRenderer.class, remap = false)
public abstract class GoggleOverlayRendererMixin {

	@Unique private static boolean createperf$gogglesCached = false;
	@Unique private static int createperf$gogglesCachedTick = -1;

	@Redirect(method = "renderOverlay(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
		at = @At(value = "INVOKE",
			target = "Lcom/simibubi/create/content/equipment/goggles/GogglesItem;isWearingGoggles(Lnet/minecraft/world/entity/player/Player;)Z"))
	private static boolean createperf$cachedGoggles(Player player) {
		if (player == null)
			return false;
		int tick = (int) (player.level().getGameTime() & 0x7FFFFFFF);
		if (createperf$gogglesCachedTick == tick)
			return createperf$gogglesCached;
		boolean result = GogglesItem.isWearingGoggles(player);
		createperf$gogglesCached = result;
		createperf$gogglesCachedTick = tick;
		return result;
	}
}
```

本 mixin 必须放进 `createperf.mixins.json` 的 `"client"` 数组（`GoggleOverlayRenderer` 是客户端专有类，放 `"mixins"` 会让专用服务端崩在类加载期）。

**收益估计**：每帧从「遍历全部 curio 槽」降到「每 tick 一次」。量级：**每帧省 10–30 次槽位读取 + 10–30 次 `isIn` + 1 次 capability 解析 + 1 个 Optional**（估计，具体槽数取决于饰品 mod）。

**回归测试点**：

1. 把护目镜放进 curio 槽 → 指着 Create 机器确认 goggle tooltip 出现（唯一的用户可见行为）。
2. 把护目镜从 curio 槽拿出 → 1 tick 内 tooltip 消失。
3. 护目镜戴在头盔槽（原版路径，谓词 1）→ 行为不变；两条路径都要测。
4. 打开/关闭 curio 界面 → 不崩。
5. 死亡重生后仍正确。

### 7. `TrainMapSync.send` 只要有玩家开图就序列化**全部**列车 → [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/compat/trainmap/TrainMapSync.java:167-182`（`send`）、`:184-328`（`createEntry`，关键行 `:188-191` 的 `Float[]` + `Arrays.fill`、`:246-252` 的逐元素装箱、`:261` 的 `owner.getName().getString()`）；调度在 `:159-165` |
| 调用频次 | `ServerTickEvent.Post`（`CommonEvents.java:109`）→ 每 5 tick 一次 light、每 10 tick 一次 full（`:161-164`）。**前置条件**：`requestingPlayers.size() != 0`（有任一玩家开着列车地图） |
| 复杂度 / 成本 | 每轮 O(全部列车 × 车厢数)：每列车 1 个 `Float[车厢×6]`（`Arrays.fill` 逐元素装箱 `Float.valueOf(0)`）+ 1 个 `ArrayList`（dimensions）+ 每车厢 6 次 float→Float 装箱 + 两次 `TravellingPoint.getPosition(graph)`。同一包再按请求者逐个 `sendToClient`（`:175-181`） |
| 注入方式 | `@Inject(HEAD, cancellable)` 空集合短路；按维度/距离过滤属于**同步模型改动**，见「不可 Mixin」一节 |
| 目标成员 | `TrainMapSync.send(Lnet/minecraft/server/MinecraftServer;Z)V` |
| 需新增状态 | 短路版：无 |
| 风险 | 低（空集合短路） |
| 脆弱度 | 低。`Create.RAILWAYS.trains` 是 public 字段 |
| 置信度 | 确定（代码路径与频次已逐行核对）。「每玩家一次序列化」这一点需实测 `CatnipServices.NETWORK.sendToClient` 是否复用 buffer |

**改前**

```java
	public static void serverTick(ServerTickEvent event) {
		ticks++;
		if (ticks % fullPacketInterval == 0)
			send(event.getServer(), false);
		else if (ticks % lightPacketInterval == 0)
			send(event.getServer(), true);
	}

	public static void send(MinecraftServer minecraftServer, boolean light) {
		if (requestingPlayers.size() == 0)
			return;

		TrainMapSyncPacket packet = new TrainMapSyncPacket(light);
		for (Train train : Create.RAILWAYS.trains.values())
			packet.add(train.id, createEntry(minecraftServer, train));

		for (WeakReference<ServerPlayer> weakReference : requestingPlayers.asMap()
			.values()) {
			ServerPlayer player = weakReference.get();
			if (player == null)
				continue;
			CatnipServices.NETWORK.sendToClient(player, packet);
		}
	}

	private static TrainMapSyncEntry createEntry(MinecraftServer minecraftServer, Train train) {
		TrainMapSyncEntry entry = new TrainMapSyncEntry();
		entry.positions = new Float[train.carriages.size() * 6];
		entry.dimensions = new ArrayList<>();
		Arrays.fill(entry.positions, Float.valueOf(0));
		// ... 每个车厢：两次 TravellingPoint.getPosition + 6 次 Float 装箱（:246-252）...
		// ... :257-262 owner.getName().getString() ...
	}
```

**为什么慢**

- **频次**：12 次/秒（5 tick light）+ 2 次/秒（10 tick full）。
- **规模放大**：单轮成本 ∝ **全服列车数 × 车厢数**，与「开图玩家的位置」完全无关。300 列车 × 5 车厢 → 单轮 1500 个车厢位置计算 + **9000 次 float→Float 装箱** + 300 次 `owner.getName().getString()`（若 owner 不为 null）。12 次/秒 → 约 10.8 万次装箱/秒。
- **已澄清**：`:161-164` 的 if/else-if 保证第 10 tick **只发 full 不发 light**，所以原 finding 没有「同一 tick 重复发包」的问题。
- **没有按维度过滤**：`createEntry` 为**所有**维度的列车算位置，跨维度的在 `dimensions` 里放 `null`，客户端再过滤。

**改后**

```java
	// @Inject(HEAD, cancellable)
	if (Create.RAILWAYS.trains.isEmpty())
		ci.cancel();
```

**Mixin 骨架**

```java
package com.example.createperf.mixin.loadtime;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.Create;
import com.simibubi.create.compat.trainmap.TrainMapSync;

import net.minecraft.server.MinecraftServer;

@Mixin(value = TrainMapSync.class, remap = false)
public abstract class TrainMapSyncMixin {

	@Inject(method = "send(Lnet/minecraft/server/MinecraftServer;Z)V",
		at = @At("HEAD"), cancellable = true)
	private static void createperf$skipWhenNoTrains(MinecraftServer server, boolean light, CallbackInfo ci) {
		if (Create.RAILWAYS.trains.isEmpty())
			ci.cancel();
	}
}
```

这条**只对「服务器还没有任何列车」的空服有效**，收益有限但绝对安全。按维度/距离过滤需要改 `TrainMapSyncPacket` 的结构（客户端 codec 同步改）→ 见「不可 Mixin」一节。

**收益估计**：无列车时空转从「每 5/10 tick 构造一个 `TrainMapSyncPacket`」降到 0（估计）。有列车时的实际收益需实测。

**回归测试点**：

1. 开列车地图，确认列车图标位置/朝向/颜色/信号状态与加固前一致。
2. 两个客户端同时开图，确认双方信息都正常（逐玩家发送路径）。
3. 关图后 20 tick（`TickBasedCache` 超时）内不再收到包。
4. 跨维度列车显示为「不可用」而不是错误位置。

### 8. `TrainMapRenderer.getOrCreateAt` / `TrainMapManager.renderPhase` 的每像素键分配 → [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/compat/trainmap/TrainMapRenderer.java:148-157`（`:152` `maps.compute`、`:156` `Couple.create`）；像素循环在 `compat/trainmap/TrainMapManager.java:496-710`（`renderPhase`；`alphaAt` 调用点在 `:534,599,606,635,671,682-684,687`）。**修正原 discovery 的行号**：`renderPhase` 在 `TrainMapManager`（不是 `TrainMapRenderer`），实际范围 496–710，原写的 503–612 不准确 |
| 调用频次 | `redrawAll`（`:460-494`）在 `TrainMapManager.tick`（`:62-68`）里当 `trackingVersion` / `trackingDim` / `trackingTheme` 变化时触发。`tick` 由 `JourneyTrainMap.java:55` / `XaeroTrainMap.java:38` / `FTBChunksTrainMap.java:46` 每帧调用 |
| 复杂度 / 成本 | `redrawAll` 三轮 `renderPhase`（PHASE_BACKGROUND/STRAIGHTS/CURVES，`:488-489`），每轮遍历 `CreateClient.RAILWAYS.trackNetworks` 的全部节点与连接。每个被绘制的像素最多 3 次 `alphaAt` + 1–2 次 `setPixel`，每次 `alphaAt/setPixel/getPixel/is` 都经过 `getOrCreateAt` → `toSectionKey` → `Couple.create(Integer, Integer)` + `Object2ObjectOpenHashMap.compute` |
| 注入方式 | `@Inject(HEAD, cancellable)` 到 `TrainMapRenderer.toSectionKey`（同段记忆化）—— **这是能真正生效的注入口** |
| 目标成员 | `TrainMapRenderer.toSectionKey(II)Lnet/createmod/catnip/data/Couple;`（第 155-157 行，单行方法） |
| 需新增状态 | `@Unique private int createperf$lastSectionX = Integer.MIN_VALUE; @Unique private int createperf$lastSectionZ = Integer.MIN_VALUE; @Unique private Couple<Integer> createperf$lastSectionKey;`（作用域：`TrainMapRenderer` 的单例实例；失效：无需（纯函数记忆化）；并发：客户端渲染线程单线程） |
| 风险 | 低（一个小函数的记忆化） |
| 脆弱度 | 低。`toSectionKey` 是 private 单行方法，逻辑稳定。`Couple` 是否不可变**需实测**（若可变则不能复用实例）；从用法看它只被当 map key 与 `getFirst/getSecond` 读取 |
| 置信度 | 分配点**确定**（源码里的 `Couple.create` 与 int 装箱）。**「这些分配有多贵」需实测**：C2 的逃逸分析很可能把这些短命对象完全消掉 |

**改前**

```java
	public TrainMapInstance getOrCreateAt(int xCoord, int zCoord) {
		Couple<Integer> sectionKey = toSectionKey(xCoord, zCoord);
		if (previouslyAccessed != null && previouslyAccessed.sectionKey.equals(sectionKey))
			return previouslyAccessed;
		return maps.compute(sectionKey, (key, instance) -> instance == null ? new TrainMapInstance(key) : instance);
	}

	public Couple<Integer> toSectionKey(int xCoord, int zCoord) {
		return Couple.create(Mth.floor(xCoord / (float) WIDTH), Mth.floor(zCoord / (float) HEIGHT));
	}

	public void setPixel(int xCoord, int zCoord, int color) {
		TrainMapInstance instance = getOrCreateAt(xCoord, zCoord);
		xCoord = Mth.positiveModulo(xCoord, WIDTH);
		zCoord = Mth.positiveModulo(zCoord, HEIGHT);
		instance.getImage().setPixelRGBA(xCoord, zCoord, color);
	}

	public int alphaAt(int x, int z) {
		int pixel = getPixel(x, z);
		return ((pixel & 0xFFFFFF) != 0) ? ((pixel >>> 24) & 0xFF) : 0;
	}
```

**为什么慢**

- **频次**：`trackingVersion` 每次变化（列车移动/信号变化都会 bump）就整张重画。
- **单像素成本**：`Mth.floor(float)` 得到 2 个 int，装箱成 2 个 `Integer`（仅 −128..127 有缓存，128×128 的世界地图必然溢出缓存）→ `Couple.create` → 1 个 `Couple`。即 **每次像素操作 ≥3 个对象**。
- **规模放大**：一条横跨 1000 像素的直线轨道 → 三轮里每轮 1000 次 `alphaAt`（:635）+ 1000 次 `setPixel` → 约 6000 个对象。50 条线路 → **30 万个对象/次重画**（**但这只是静态计数；JIT 很可能消除了大部分**）。
- **附带核对**：`mapYtoAlpha`（:734-737）每像素调 `Minecraft.getInstance().level.getMinBuildHeight()`。已 `javap` 确认两者都是纯字段/接口读取，**不是热点**。要做 `@Overwrite` 时提到 `redrawAll` 一次是免费收益，但单独看它不值得改。

**改后**

```java
	@Unique private int createperf$lastSectionX = Integer.MIN_VALUE;
	@Unique private int createperf$lastSectionZ = Integer.MIN_VALUE;
	@Unique private Couple<Integer> createperf$lastSectionKey;

	public Couple<Integer> toSectionKey(int xCoord, int zCoord) {
		int sx = Mth.floor(xCoord / (float) WIDTH);
		int sz = Mth.floor(zCoord / (float) HEIGHT);
		if (sx == createperf$lastSectionX && sz == createperf$lastSectionZ
			&& createperf$lastSectionKey != null)
			return createperf$lastSectionKey;
		Couple<Integer> key = Couple.create(sx, sz);
		createperf$lastSectionX = sx;
		createperf$lastSectionZ = sz;
		createperf$lastSectionKey = key;
		return key;
	}
```

**为什么这条有效**：`renderPhase` 的像素循环里，连续若干次 `alphaAt/setPixel/getPixel` 通常落在**同一个 128×128 段**（`:534,599,606` 的 xi/zi 邻域、`:635` 的沿轨步进）。记忆化后这些调用不再分配；跨段时 miss 一次，成本与原来相同。

**Mixin 骨架**

```java
package com.example.createperf.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.compat.trainmap.TrainMapRenderer;

import net.createmod.catnip.data.Couple;
import net.minecraft.util.Mth;

@Mixin(value = TrainMapRenderer.class, remap = false)
public abstract class TrainMapRendererMixin {

	@Unique private int createperf$lastSectionX = Integer.MIN_VALUE;
	@Unique private int createperf$lastSectionZ = Integer.MIN_VALUE;
	@Unique private Couple<Integer> createperf$lastSectionKey;

	@Inject(method = "toSectionKey(II)Lnet/createmod/catnip/data/Couple;",
		at = @At("HEAD"), cancellable = true)
	private void createperf$memoizeSectionKey(int xCoord, int zCoord, CallbackInfoReturnable<Couple<Integer>> cir) {
		int sx = Mth.floor(xCoord / (float) TrainMapRenderer.WIDTH);
		int sz = Mth.floor(zCoord / (float) TrainMapRenderer.HEIGHT);
		if (sx == createperf$lastSectionX && sz == createperf$lastSectionZ && createperf$lastSectionKey != null) {
			cir.setReturnValue(createperf$lastSectionKey);
			return;
		}
		Couple<Integer> key = Couple.create(sx, sz);
		createperf$lastSectionX = sx;
		createperf$lastSectionZ = sz;
		createperf$lastSectionKey = key;
		cir.setReturnValue(key);
	}
}
```

注意：`toSectionKey` 是**实例方法**，所以 handler 是非 static，`@Unique` 字段也是非 static（`TrainMapRenderer` 是单例 `INSTANCE`）。
本 mixin 里没有引用客户端专有类（`TrainMapRenderer` 自身引用了 `NativeImage`，但这是目标类）——**仍需放进 `"client"` 数组**，因为 `TrainMapRenderer` 会加载 `com.mojang.blaze3d.platform.NativeImage`。

**收益估计**：把每像素的 `Couple` 分配从「每次调用 1 个」降到「每段 1 个」。量级：每次重画省 O(像素操作数) 个对象（静态计数下 50 条线路约 30 万个）。**真实收益必须用 JFR 实测**：如果 `Couple` 根本不出现在 `ObjectAllocationSample` 里，说明 JIT 已经处理了，这条应降级为「不做」。

**回归测试点**：

1. 开列车地图（JourneyMap / XAERO / FTBChunks 任一），确认轨道线条与加固前视觉一致。**特别注意段边界接缝**（记忆化写错会把像素画到错误的段）。
2. 跨段长直线：铺一条横跨 3 个以上 128 像素段的直轨，确认线条连续无错位。
3. 缩放/平移地图：改变视角让 `trackingDim` / `trackingTheme` 变化，确认整张重画正确。
4. 段边界处的车站/信号图标出现在正确位置。
5. 关图再开：确认 `finishDrawing` 的 `untouched` 回收逻辑不受影响。

==> _part4.tmp.md <==
| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/decoration/palettes/PalettesVariantEntry.java:34-72`（`:34` 图案循环、`:69` partial 循环）；由 `AllPaletteStoneTypes.register`（`AllPaletteStoneTypes.java:87-95`）在 `AllPaletteBlocks` 静态块（`AllPaletteBlocks.java:116-118`）里触发 |
| 调用频次 | **一次**（类初始化）。`AllPaletteBlocks` 在 `Create.onCtor`（`Create.java:123`）被引用 → 启动期一次 |
| 复杂度 / 成本 | **已核对的真实规模**（原 discovery 写的「7 石材×6 图案」不准确）：`AllPaletteStoneTypes` 有 **14 个枚举值**（`:23-62`：7 个 VANILLA_RANGE + 7 个 STANDARD_RANGE），每个都有 6 个图案（`VANILLA_RANGE`/`STANDARD_RANGE`，`PaletteBlockPattern.java:64-66`）。<br>→ 14×6 = **84 个方块** + 84 个物品；有 partial 的图案：`CUT`（ALL_PARTIALS=3）、`POLISHED`（FOR_POLISHED=3）、`BRICKS`（3）、`SMALL_BRICKS`（3）→ 14×4×3 = **168 个 partial 方块/物品**。<br>→ **每个 builder 链**：`initialProperties`（克隆 `Properties`）+ `pickaxeOnly` tag + `blockstate` 数据生成 lambda + `item()` 子 builder + 掉落表 + `recipe` lambda（`:60-63`，内含 stonecutting + `addRecipes`）+ 部分图案的 `connectedTextures` behaviour（`:55-63`） |
| 注入方式 | **不可 Mixin（不建议）**。唯一能"跳过"的方式是 `@Inject(HEAD, cancellable)` 取消 `AllPaletteStoneTypes.register`，但那会**缺少全部石材变体方块**（Content missing，功能破坏） |
| 目标成员 | `PalettesVariantEntry.<init>(Ljava/lang/String;Lcom/simibubi/create/content/decoration/palettes/AllPaletteStoneTypes;)V`；或 `AllPaletteStoneTypes.register(Lcom/simibubi/create/foundation/data/CreateRegistrate;)V` |
| 需新增状态 | 无（不推荐做） |
| 风险 | **高**（取消注册 = 内容缺失）；`@Overwrite` 构造器则要完整重写 200+ 行 builder 链且必须与 Registrate 的 registration 时序一致 |
| 脆弱度 | 极高：`REGISTRATE.block(...)` 的返回类型与调用顺序如果被上游改动，`@Overwrite` 立刻编译失败；而任何"延迟注册"的方案都要求 `RegisterEvent` 之前完成注册，否则注册表冻结后注册会抛异常 |
| 置信度 | 确定（规模已逐行数出：14 枚举 × 6 图案，partial 数量已核对 `PaletteBlockPartial.java:44-45` 与 `PaletteBlockPattern.java:42-62`）。"约 100ms"这个数字**需实测** |

**改前**（关键结构）

```java
	public PalettesVariantEntry(String name, AllPaletteStoneTypes paletteStoneVariants) {
		ImmutableList.Builder<BlockEntry<? extends Block>> registeredBlocks = ImmutableList.builder();
		ImmutableList.Builder<BlockEntry<? extends Block>> registeredPartials = ImmutableList.builder();
		NonNullSupplier<Block> baseBlock = paletteStoneVariants.baseBlock;

		for (PaletteBlockPattern pattern : paletteStoneVariants.variantTypes) {
			BlockBuilder<? extends Block, CreateRegistrate> builder =
				REGISTRATE.block(pattern.createName(name), pattern.getBlockFactory())
					.initialProperties(baseBlock)
					.transform(pickaxeOnly())
					.blockstate(pattern.getBlockStateGenerator()
						.apply(pattern)
						.apply(name)::accept);

			ItemBuilder<BlockItem, ? extends BlockBuilder<? extends Block, CreateRegistrate>> itemBuilder =
				builder.item();

			// ... tags / addLayer / connectedTextures ...
			builder.recipe((c, p) -> {
				p.stonecutting(DataIngredient.tag(paletteStoneVariants.materialTag), RecipeCategory.BUILDING_BLOCKS, c);
				pattern.addRecipes(baseBlock, c, p);
			});

			itemBuilder.register();
			BlockEntry<? extends Block> block = builder.register();
			registeredBlocks.add(block);

			for (PaletteBlockPartial<? extends Block> partialBlock : pattern.getPartials())
				registeredPartials.add(partialBlock.create(name, pattern, block, paletteStoneVariants)
					.register());
		}
		// ... 两个 addDataGenerator ...
	}
```

**为什么慢**

纯粹的**启动期类初始化成本**，不影响运行时 MSPT/帧率。成本来源：

1. **方块的 `Properties` 克隆**：每个 `initialProperties(baseBlock)` 都要从基准方块复制一份 `BlockBehaviour.Properties`（含 mapColor/destroyTime/soundType 等字段），× 252 个方块。
2. **每个 builder 挂 3–6 个数据生成 lambda + 掉落表**：这些 lambda 在**运行时**（`DataGenerator` 阶段）才执行，但**构造 lambda 对象与把它们注册进 `ProviderType` 的 map** 发生在启动期。
3. **连接纹理 behaviour**（`:58`，`pattern.createCTBehaviour(name)`）：为 LAYERED/PILLAR 等图案构造 `HorizontalCTBehaviour`/`RotatedPillarCTBehaviour` 并注册。
4. **`REGISTRATE.addDataGenerator(ProviderType.RECIPE/ITEM_TAGS, ...)`（`:74-79`）** 每个石材类型两次 → 28 次。

规模放大：这个成本是**常数**（不随存档/玩家/整合包规模变化），只随「Create 自己的石材类型数」变化。
它的意义是**启动时间**与**内存**（每个 `BlockBuilder` 都持有若干 lambda 与 `RegistryEntry`），对服务器 MSPT **零影响**。

**改后**

不建议改。若确实要优化启动时间，正确方向是**上游把调色板方块改成数据生成（datagen）产出的资源包**，而不是运行时 builder 链 —— 这是架构改动。

**Mixin 骨架**：不提供（见「不可 Mixin」一节）。

**收益估计**：改成"不注册"能省下全部（估计百毫秒量级），但代价是**内容缺失**，不可接受。**不建议做。**

**回归测试点**（如果上游改了，验证时要覆盖）：

1. 创造模式 palettes 标签页里 14 种石材 × 6 图案 + 各自 partial 全部存在且可放置。
2. 连接纹理（layered / pillar）视觉正确。
3. stonecutting 配方齐全（每种石材能切出全部变体）。
4. 挖掘掉落正确（pickaxeOnly tag）。

### 10. `AllCreativeModeTabs.applyOrderings` 的 O(orderings×items) 移位 → [Medium] **实际不可 Mixin（签名阻塞）**

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/AllCreativeModeTabs.java:324-343`（`:326,329` 两次 `List.indexOf`、`:331,337,339` 的 `remove/add(index)`）；物化在 `:294-322`（`:296` 与 `:312` 两次 `Create.registrate().getAll(...)`） |
| 调用频次 | 每次 `RegistrateDisplayItemsGenerator.accept`（`:274-292`）。即**每次打开创造模式物品栏 + 每次搜索框刷新**（NeoForge 的搜索页会重跑 `DisplayItemsGenerator`） |
| 复杂度 / 成本 | **修正原 discovery 的估算**：`makeOrderings`（`:165-191`）只产生**常数条** ordering（`simpleBeforeOrderings` 2 条 + `simpleAfterOrderings` 1 条 + `PackageStyles.STANDARD_BOXES` 里 namespace 为 create 的若干条）。<br>`items` 的规模 n 是 Create 注册的方块+物品总数（**千级**）。<br>→ 总成本 ≈ k×2n 次 `Item.equals`（引用比较）+ k×n 次数组元素移动，k 为**个位数~十几**，n≈10^3 → **约 3 万次比较/移动**，绝对量级是**微秒~几十微秒**。<br>`createperf` 判定：**这是本领域里最被高估的一条**。 |
| 注入方式 | **不可 Mixin**。`ItemOrdering` 是 `private static record`（`:351-364`），`@Overwrite` 的签名 `applyOrderings(List<Item>, List<ItemOrdering>)` 里的类型无法从外部 mixin 类合法引用 → **描述符写不出来**。真正可做的只有 `@Inject(HEAD, cancellable)` 完整替换 `accept`（需复制 200+ 行） |
| 目标成员 | 从外部**不可达**（private 内部类 + private record） |
| 需新增状态 | 无 |
| 风险 | 若不绕过类型问题：**无法施工**。若走 `@Inject` 替换 `accept`：高（200+ 行复制，任何上游改动都会漂移） |
| 脆弱度 | 极高 |
| 置信度 | 确定（类型可见性、k 与 n 的量级都已逐行核对） |

**改前**

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

	private record ItemOrdering(Item item, Item anchor, Type type) {
		public static ItemOrdering before(Item item, Item anchor) { ... }
		public static ItemOrdering after(Item item, Item anchor) { ... }
		enum Type { BEFORE, AFTER; }
	}
```

**为什么"不算快"**

- `Item.equals` 是 `Object.equals`（`Item` 没有覆写 `equals`，它就是注册单例）→ 即同一性比较，**比 HashMap 查找还便宜**。
- 数组元素移动用 `System.arraycopy`（memmove），k 次 × n 元素 ≈ 1.5 万元素移动，纳秒级。
- 相比之下，同一次 `accept` 里的 `Create.registrate().getAll(Registries.BLOCK)` / `getAll(Registries.ITEM)` 物化 10^3 个 `RegistryEntry`、以及每个物品跑 `IS_ITEM_3D_PREDICATE`（`:91-96`，调 `ItemRenderer.getModel(new ItemStack(item), null, null, 0).isGui3d()`）才是大头。

**改后**

如果上游把 `ItemOrdering` 提升为 package-private/public，推荐写法（把 indexOf 换成一次性索引）：

```java
	private static void applyOrderings(List<Item> items, List<ItemOrdering> orderings) {
		if (orderings.isEmpty() || items.isEmpty())
			return;
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
			items.add(ordering.type() == ItemOrdering.Type.AFTER ? anchorIndex + 1 : anchorIndex, item);
			// 位移后索引失效，整体重建（k 是常数，所以仍是 O(k*n)，但每次 n 只是 n 次 put）
			index.clear();
			for (int i = 0; i < items.size(); i++)
				index.putIfAbsent(items.get(i), i);
		}
	}
```

更完整的讨论（含"惰性修正版"为什么反而更复杂）见 [overwrites/AllCreativeModeTabs-applyOrderings.md](overwrites/AllCreativeModeTabs-applyOrderings.md)。

**Mixin 骨架**：正是因为签名阻塞，**本 finding 不给可编译的骨架**。若上游提升可见性后，骨架形如：

```java
@Mixin(targets = "com.simibubi.create.AllCreativeModeTabs$RegistrateDisplayItemsGenerator", remap = false)
public abstract class RegistrateDisplayItemsGeneratorMixin {
	@Overwrite
	private static void applyOrderings(List<Item> items, List<ItemOrdering> orderings) { /* 见上 */ }
}
```

**收益估计**：把 3 万次比较降到 3 万次哈希 put —— **几乎不可观测**（估计）。**建议不做**，登记为上游候选。

**回归测试点**：见 overwrites 文件 §7（物品顺序逐项对比）。

### 11. `TickBasedCache.size()/asMap()` 强制 `cleanUp()` → [Low-Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/foundation/utility/TickBasedCache.java:122-126`（`size()`）、`:133-137`（`asMap()`）、`:139-150`（`cleanUp()`，三趟 lambda + 一个 `HashSet`）；`getIfPresent` 在 `:50-63`（双 map 查找 + `MutableInt`） |
| 调用频次 | (a) `TrainMapSync.java:168` 的 `requestingPlayers.size()` —— 每 5/10 tick（有列车时）；(b) `TrainMapSync.java:175` 的 `requestingPlayers.asMap()` —— 同上；(c) `ChainConveyorInteractionHandler.java:74`（每帧，鼠标指向链式传送机时）；(d) `ChainPackageInteractionHandler.java:23`；(e) `AddressEditBoxHelper.java:62`（**每帧**，打开有地址输入框的界面时）；(f) `LogisticallyLinkedBehaviour.java:79`（每次调用，还额外 `new LinkedList<>(cache.asMap().values())`） |
| 复杂度 / 成本 | `size()` 与 `asMap()` 都先调 `cleanUp()`：O(E) 遍历 `timestamps` + 1 个 `HashSet` + 三趟 lambda（`timestamps.forEach`、`outdated.forEach(map::remove)`、`outdated.forEach(timestamps::remove)`）。`getIfPresent`：1 次 `HashMap.get` + 可能的 `remove` + 1 次 `ConcurrentHashMap.get` |
| 注入方式 | `@Overwrite`（小方法，4 行）；或 `@Inject(HEAD, cancellable)` 直接返回 `map.size()`（对 `size()` 更合适） |
| 目标成员 | `TickBasedCache.size()J`、`TickBasedCache.asMap()Ljava/util/concurrent/ConcurrentMap;` |
| 需新增状态 | 无（若只想让 `size()` 变 O(1)：直接用 `map.size()`，因为它就是 `timestamps.size()` 的上界） |
| 风险 | 低（`size()` 语义从「清理后的精确值」变成「含过期项的近似值」——**这是行为变更**，需要在文档里显式声明） |
| 脆弱度 | 低。类与方法都稳定 |
| 置信度 | 确定（逐行核对） |

**改前**

```java
	@Override
	public long size() {
		cleanUp();
		return timestamps.size();
	}

	@Override
	public ConcurrentMap<K, V> asMap() {
		cleanUp();
		return map;
	}

	@Override
	public void cleanUp() {
		Set<K> outdated = new HashSet<>();
		timestamps.forEach((k, v) -> {
			if (v.intValue() < ticks() - ticksUntilTimeout)
				outdated.add(k);
			if (resetTimerOnAccess)
				v.setValue(ticks());
		});
		outdated.forEach(map::remove);
		outdated.forEach(timestamps::remove);
	}

	@Override
	public V getIfPresent(Object key) {
		MutableInt timestamp = timestamps.get(key);
		if (timestamp == null)
			return null;
		if (timestamp.intValue() < ticks() - ticksUntilTimeout) {
			timestamps.remove(key);
			map.remove(key);
			return null;
		}
		if (resetTimerOnAccess)
			timestamp.setValue(ticks());
		return map.get(key);
	}
```

**为什么慢**

- **`size()` 与 `asMap()` 是"读度量"操作，却要付全表代价。** 这是 API 设计问题（缓存实现自 Guava `Cache` 接口，`cleanUp()` 在 Guava 里是"显式维护"，通常是 no-op）。
- **放大场景**：`AddressEditBoxHelper` 在**每帧**调用 `asMap()` —— 当玩家打开打包机/蛙港的地址输入界面，且服务器里有很多附近的告示板（`NEARBY_CLIPBOARDS`），每帧都做一次 O(E) 清理 + `HashSet` 分配。
- `getIfPresent` 需要查两张 map（`timestamps` + `map`），并且每次 `put` 都新建一个 `MutableInt`（`:84-86`）。

**改后**

最小改动（`size()` 变 O(1)，但语义变为"近似值"）：

```java
	@Override
	public long size() {
		// 原实现先 cleanUp()（O(E)）再返回 timestamps.size()。
		// map 与 timestamps 的键集合在 put/invalidate 下同步，只有超时项会滞后；
		// 因此 map.size() 是"上界近似值"，对"是否有任何条目"的用途完全够。
		// **行为变更**：不再因为调用 size() 而顺带清理过期项。
		return map.size();
	}
```

`asMap()` 的清理**语义更难去掉**，因为 `cleanUp()` 在 `resetTimerOnAccess=true` 时还会**顺手刷新所有条目的时间戳**（`:145-146`）—— 去掉会改变超时行为。
对 `asMap()` 只建议：**在调用点改成不依赖清理的写法**。例如 `TrainMapSync.java:168`：

```java
	// 原：if (requestingPlayers.size() == 0) return;
	// 新：直接判断底层 map 是否为空，不触发 cleanUp。
	// 注意：requestingPlayers 声明为 Cache<...>，需要把类型改成 TickBasedCache 才能拿到 O(1) 方法。
	if (requestingPlayers instanceof TickBasedCache<UUID, WeakReference<ServerPlayer>> tbc && tbc.isEmptyFast())
		return;
```

**Mixin 骨架**（只覆盖 `size()`）

```java
package com.example.createperf.mixin.loadtime;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.foundation.utility.TickBasedCache;

import java.util.concurrent.ConcurrentHashMap;

@Mixin(value = TickBasedCache.class, remap = false)
public abstract class TickBasedCacheMixin<K, V> {

	@Shadow private ConcurrentHashMap<K, V> map;

	@Inject(method = "size()J", at = @At("HEAD"), cancellable = true)
	private void createperf$sizeWithoutCleanup(CallbackInfoReturnable<Long> cir) {
		cir.setReturnValue((long) map.size());
	}
}
```

**注意**：`TickBasedCache` 是**泛型类**，`@Mixin` 类上的类型参数 `<K, V>` 必须与目标一致（Mixin 支持泛型混入）。`@Shadow` 的字段名 `map` 必须逐字一致。

**收益估计**：`size()` 每次从 O(E) 降到 O(1)。量级：`TrainMapSync` 每 5/10 tick 省一次 O(E)（E = 开图玩家数，很小）；`AddressEditBoxHelper` 的每帧 `asMap()` 才是主要受益点，**但本改动不覆盖它**。**总体收益小**。

**回归测试点**：

1. 列车地图：开图 → 确认仍能收到包（`requestingPlayers.size() != 0` 仍成立）。
2. 超时：关图后 20 tick 内不再收包（`getIfPresent` 的超时逻辑未被本改动触及，应不受影响）。
3. 地址输入框：打开打包机/蛙港的地址编辑界面，确认附近告示板地址仍出现在建议列表（`asMap()` 未改，应不受影响）。
4. 若改了 `asMap()`：确认超时行为不变（这是最容易被破坏的语义）。

### 12. `ControlsServerHandler.tick` 每 tick stream 装箱 → [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/contraptions/actors/trainControls/ControlsServerHandler.java:52-54`（`list.stream().map(...).toList()`）；`:45` 的 `world.getPlayerByUUID` |
| 调用频次 | 每 tick（由 `foundation/events/CommonEvents.java:141` 转发），但**只对 `receivedInputs` 里的上下文**（即正在操作列车控制器的玩家）。空服/无人操作时 map 为空，循环体不执行 |
| 复杂度 / 成本 | 每上下文：1 个 `Stream` + 1 个 `map` 中间流 + 1 个 `toList()` 的 `ArrayList` + 每个按键一次 `Integer` 装箱（`ManuallyPressedKey.getSecond()` 已经是 `Integer`，所以 map 本身不装箱，但 `toList` 会新建列表。**修正原 discovery**：原写的"装箱成 List<Integer>"里，装箱已经在 `ManuallyPressedKey` 构造时发生过（`:89` `new ManuallyPressedKey(activated)`，`activated` 是 `Integer`），stream 这里只是**收集**，不新增装箱 |
| 注入方式 | `@WrapOperation` 包 `Stream.toList` → 返回复用的列表 + `@Inject(HEAD, cancellable)` 空 map 短路 |
| 目标成员 | `ControlsServerHandler.tick(Lnet/minecraft/world/level/LevelAccessor;)V` |
| 需新增状态 | `@Unique private static final List<Integer> createperf$scratch = new ArrayList<>();`（作用域：静态单线程主线程；失效：每上下文开头 clear；并发：服务端主线程） |
| 风险 | 中（复用列表如果被 `control()` 保存引用就会串味 → **必须确认 `AbstractContraptionEntity.control` 不保留该 list**） |
| 脆弱度 | 中。`toList()` 返回的是**不可变列表**（`Stream.toList()` 的规范保证），替换成可变列表会改变契约 —— 若 `control()` 的某个实现依赖不可变性，就会出问题。**需实测** |
| 置信度 | 确定（源码逐行核对）；「实际影响」为**很低**（只在玩家操作列车控制器时才有内容） |

**改前**

```java
			if (!ctx.entity.control(ctx.controlsLocalPos, list.stream()
				.map(ManuallyPressedKey::getSecond)
				.toList(), player)) {
				ctx.entity.stopControlling(ctx.controlsLocalPos);
			}
```

**为什么慢**

严格说**不慢**：频次是「每 tick × 正在操作控制器的玩家数」，而那通常 ≤ 2；每次成本是 3 个对象（Stream、中间流、ArrayList）。
真正值得注意的是 `:45` 的 `world.getPlayerByUUID`——服务端 `ServerLevel` 的这个方法会走 `getEntities().get(uuid)`（**已 javap 确认是接口调用**，底层是 `EntityLookup` 的 uuid→entity 索引，O(1)），所以也不贵。
本条属于**原 discovery 里过度收录**的条目之一。

**改后**

```java
		// 复用 scratch，避免每 tick 新建 stream + 列表
		createperf$scratch.clear();
		for (ManuallyPressedKey key : list)
			createperf$scratch.add(key.getSecond());
		if (!ctx.entity.control(ctx.controlsLocalPos, List.copyOf(createperf$scratch), player)) {
			ctx.entity.stopControlling(ctx.controlsLocalPos);
		}
```

注意 `List.copyOf` 又分配了一个不可变列表 —— 那就没省下什么。**要么确认 `control()` 不保留引用从而直接传 `scratch`，要么不做**。

**Mixin 骨架**（Low，可省略）

```java
@Mixin(value = ControlsServerHandler.class, remap = false)
public abstract class ControlsServerHandlerMixin {
	@Inject(method = "tick(Lnet/minecraft/world/level/LevelAccessor;)V", at = @At("HEAD"), cancellable = true)
	private static void createperf$skipWhenEmpty(LevelAccessor world, CallbackInfo ci) {
		if (ControlsServerHandler.receivedInputs.get(world).isEmpty())
			ci.cancel();
	}
}
```

**收益估计**：只有 `receivedInputs` 非空时才有效；每次调用省 3 个对象 + 1 次 stream 管线（估计）。**收益极小**。

**回归测试点**：

1. 坐上列车控制器，按 W/A/S/D，确认列车响应与加固前一致（输入映射不能丢）。

==> _part5.tmp.md <==
| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/foundation/utility/ServerSpeedProvider.java:24-29`（`serverTick` 调 `getSyncInterval()`）、`:43-45`（`getSyncInterval` 读 `AllConfigs.server().tickrateSyncTimer.get()`）；同类模式：`compat/trainmap/TrainMapManager.java:64-65`、`:98`、`foundation/events/ClientEvents.java:273`（`ItemTooltipEvent`，每个 tooltip 一次） |
| 调用频次 | `ServerSpeedProvider.serverTick` 每 tick 一次（`CommonEvents.java:107`）；`TrainMapManager.tick` 每帧一次（有地图打开时）；`ClientEvents.addToItemTooltip` 每个 tooltip 一次 |
| 复杂度 / 成本 | **已用 javap 反编译 NeoForge 21.1.219 的 `ModConfigSpec$ConfigValue.class` 确认**：`get()` 的字节码是<br>`getfield cachedValue; ifnonnull L; getRaw(); putfield cachedValue; L: getfield cachedValue; areturn`<br>→ **首次之后就是一次字段读 + 一次 null 检查**。调用方再把 `Integer` 拆箱成 `int`（`Integer.intValue()`）。`cachedValue` 在 `ModConfigSpec.resetCaches` 时失效（已确认存在该方法）→ 重载配置后仍能读到新值，**不存在"缓存导致配置不生效"的问题** |
| 注入方式 | **不建议**。没有可消除的分配、没有可跳过的分支；`@Overwrite` 只会把 2 条指令换成 1 条字段读，收益不可观测 |
| 目标成员 | 不适用 |
| 需新增状态 | 不适用（若真要"缓存"，按手册 §7 是反模式：把配置值缓存进静态字段而不监听重载 → 用户改配置不生效） |
| 风险 | 不适用 |
| 脆弱度 | 不适用 |
| 置信度 | 确定（反编译结论） |

**改前**

```java
	public static void serverTick() {
		serverTimer++;
		if (serverTimer > getSyncInterval()) {
			CatnipServices.NETWORK.sendToAllClients(Packet.INSTANCE);
			serverTimer = 0;
		}
	}

	public static Integer getSyncInterval() {
		return AllConfigs.server().tickrateSyncTimer.get();
	}
```

**为什么"看起来慢"但实际不慢**

- 原 discovery 写「每 tick 读 `ModConfig ConfigValue` + 自动拆箱」。拆箱确实存在（`Integer → int`），但它是 `Integer.intValue()`（或 JIT 后的 `getfield value`），**不是分配**。
- 每 tick 一次这样的调用 ≈ 2–5 ns。相对 50 ms 预算约 **0.00001%**。
- `AllConfigs.client()`/server()` 本身是静态字段读取（`AllConfigs.java:31-41`，已核对），也不慢。

**改后**

不改。如果你确实想要"零虚调用"，唯一正确的做法是**注册一个配置重载回调**把值缓存成基本类型字段（`AllConfigs.onReload` 已经在做类似的事，见 `AllConfigs.java:81-87`），但那是**上游该做的事**，而且收益仍然是 0.00001% 量级。

**Mixin 骨架**：不提供。

**收益估计**：不可观测（估计）。**列在此处是为了明确「不要做」。**

**回归测试点**：不适用。

### 14. `ServerChainConveyorHandler.tick` 每 10 tick 无条件广播 → [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/kinetics/chainConveyor/ServerChainConveyorHandler.java:32-55`（`:50-53` 的 `ticks % 10 != 0 && before == after` 判断 + 无条件 `sync()`）；`sync()` 在 `:57-59` |
| 调用频次 | 每 tick（`ServerTickEvent.Post` → `CommonEvents.java:110`）；`sync()` 每 10 tick 一次 |
| 复杂度 / 成本 | 每 tick：遍历 `hangingPlayers`（Object2IntOpenHashMap，通常为空或个位数）+ 2 次 `size()`。每 10 tick：一次 `sendToAllClients`，包体是 `hangingPlayers.keySet()`（**活的视图**，不是快照）；服务端编码时会遍历它 |
| 注入方式 | `@Inject(HEAD, cancellable)` 在 `sync()` 里加「空集合短路」 |
| 目标成员 | `ServerChainConveyorHandler.sync()V`（public static） |
| 需新增状态 | 无 |
| 风险 | **低，但有语义变更**：原本"每 10 tick 必有一次全量同步"是一个**纠错机制**（客户端若丢包/状态漂移，10 tick 内会被纠正）。加上短路后，只有『集合为空』才跳过 —— 从"非空集合"状态变成"空集合"的那一次仍会同步（因为 `:50` 的条件会在 `before != after` 时放行）。所以**只有持续为空时才跳过**，纠错语义基本保留 |
| 脆弱度 | 低 |
| 置信度 | 确定（逐行核对）；「原本那一次广播的成本」为**估计**（一个空 HashSet 的 UUID 列表编码，很小） |

**改前**

```java
	public static void tick() {
		ticks++;

		int before = hangingPlayers.size();

		ObjectIterator<Object2IntMap.Entry<UUID>> iterator = hangingPlayers.object2IntEntrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Integer> entry = iterator.next();
			int newTTL = entry.getValue() - 1;
			if (newTTL <= 0) {
				iterator.remove();
			} else {
				entry.setValue(newTTL);
			}
		}

		int after = hangingPlayers.size();

		if (ticks % 10 != 0 && before == after)
			return;

		sync();
	}

	public static void sync() {
		CatnipServices.NETWORK.sendToAllClients(new ClientboundChainConveyorRidingPacket(hangingPlayers.keySet()));
	}
```

**为什么慢（弱）**

- 每 10 tick 一次广播，包体是 `hangingPlayers` 的「当前挂在链式传送机上的玩家」列表。绝大多数服务器这个集合**长期为空**（没有玩家挂在链子上），但仍然每 10 tick 发一次空包。
- 成本：一次包对象分配 + 一次广播给所有玩家（每个玩家一次空列表编码）。
- **放大场景**：玩家数多的服务器（50+ 玩家）→ 每秒 2 次 × 50 = 100 次空包编码。仍然很小，但属于"纯浪费"。
- **活视图问题**：`hangingPlayers.keySet()` 是**活视图**。`sendToAllClients` 如果是异步编码（网络线程），主线程的 `tick` 可能同时改 `hangingPlayers`，导致 `ConcurrentModificationException` 或漏/多编。**这一点需实测**（如果发送是同步编码就没事）。

**改后**

```java
		int after = hangingPlayers.size();

		if (ticks % 10 != 0 && before == after)
			return;
		// createperf: 集合为空时没有可同步的内容。
		// 注意：这条在"从非空变为空"的那一次也会跳过（因为 after == 0），
		// 但那是正确的 —— 客户端收到空列表与不收到是等价的（它本来就要清空）。
		if (after == 0)
			return;

		sync();
```

**⚠️ 需要确认**：客户端 `PlayerSkyhookRenderer.updatePlayerList`（`ClientboundChainConveyorRidingPacket.java:34`）在**没有收到包**时是否会保留上一次的列表。
如果客户端依赖"定期收到空列表来清空自己"，那么从非空变空的那一次**必须**发出去。
更安全的写法是只在 `before == 0 && after == 0` 时跳过（即"从来没有过任何玩家"的纯空闲状态）：

```java
		if (ticks % 10 != 0 && before == after)
			return;
		if (before == 0 && after == 0)
			return;   // 从未有过挂链玩家，且现在也没有 → 无需同步
		sync();
```

但这样在"曾经有过、现在为空"的稳态下仍然每 10 tick 发空包。要彻底解决需要客户端侧配合（"我不再需要纠错"的信号），属于上游改动。

**Mixin 骨架**

```java
package com.example.createperf.mixin.loadtime;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.kinetics.chainConveyor.ServerChainConveyorHandler;

@Mixin(value = ServerChainConveyorHandler.class, remap = false)
public abstract class ServerChainConveyorHandlerMixin {

	@Inject(method = "sync()V", at = @At("HEAD"), cancellable = true)
	private static void createperf$skipEmptySync(CallbackInfo ci) {
		if (ServerChainConveyorHandler.hangingPlayers.isEmpty())
			ci.cancel();
	}
}
```

**这条短路必须放在 `sync()` 里**（而不是 `tick` 里），因为 `sync()` 还被 `:24`（`handleTTLPacket` 里玩家数变化）与 `:29`（`handleStopRidingPacket` 里移除成功）调用。
在 `sync()` 里判断"集合为空就不发"对这两条路径**也正确**（集合为空时确实没人挂在链上）。

**收益估计**：空闲服务器每秒省 2 次广播 + 2 个包对象（估计）。玩家多时收益按玩家数线性放大。**量级极小。**

**回归测试点**：

1. 玩家挂在链式传送机上：确认自己和其他玩家能看到正确的挂链姿态（`PlayerSkyhookRenderer`）。
2. **从挂着到松手**：松手的那一 tick 必须让所有客户端把该玩家的挂链姿态清掉。**这是最容易因短路而破坏的行为**（如果客户端只在收到包时更新，那么"空集合"的那一次必须发）。
3. 两个玩家先后挂链/松手，确认姿态不串。
4. 断线重连后挂链姿态正确。

### 15. `Create.registrate()` 的 StackWalker 守卫在启动期被调数百次 → [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/Create.java:213-217`（`registrate()`），栈遍历在 `:73`（`STACK_WALKER` 的创建）与 `:214`（`getCallerClass().getPackageName()`） |
| 调用频次 | **启动期**数百~上千次（例如 `PaletteBlockPartial.java:58` 的 `Create.registrate()` 在每个 partial 方块的构造里被调用 → 仅调色板就 168 次；加上全部其它注册） |
| 复杂度 / 成本 | 每次调用：`StackWalker.getCallerClass()`（构造一个 `StackFrame` 并读 `Class`，通常只走几帧）+ `Class.getPackageName()`（可能触发 `Package` 查找或返回缓存的 name）+ `String.startsWith` |
| 注入方式 | `@Inject(HEAD, cancellable)` 直接返回 `REGISTRATE`（需 `@Shadow @Final` 拿 `REGISTRATE`）。**不建议**：见下 |
| 目标成员 | `Create.registrate()Lcom/simibubi/create/foundation/data/CreateRegistrate;` |
| 需新增状态 | 无 |
| 风险 | 中（**这是 Create 刻意设置的设计契约**：`throw new UnsupportedOperationException("Other mods are not permitted to use create's registrate instance.")`）。删掉它允许第三方 mod 拿 Create 的 registrate 实例，可能造成注册冲突/崩溃 |
| 脆弱度 | 低（方法签名稳定），但**语义上是破坏设计意图** |
| 置信度 | 确定（源码 + 反编译无关，纯 Java 调用） |

**改前**

```java
	private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

	public static CreateRegistrate registrate() {
		if (!STACK_WALKER.getCallerClass().getPackageName().startsWith("com.simibubi.create"))
			throw new UnsupportedOperationException("Other mods are not permitted to use create's registrate instance.");
		return REGISTRATE;
	}
```

**为什么"看起来慢"但实际不慢**

- `StackWalker.getCallerClass()` 在 Java 9+ 是**经过优化的**：它使用 `StackWalker.StackFrame` 的批量获取，并且 `Option.RETAIN_CLASS_REFERENCE` 下只走所需的帧（默认放弃前若干帧）。单次成本通常是**几百纳秒到几微秒**。
- 调到几百次 → 总成本 **< 1 ms**（估计），且只发生在启动期。
- 关键：**这不是热路径**（`registrate()` 不会在 tick/帧循环里被调用；我 grep 过全部 30 处调用点，都在注册/构造阶段）。

**改后**

不改。

**Mixin 骨架**：不提供（理由：破坏了 Create 的显式防滥用保护，且收益 < 1 ms）。

**收益估计**：启动期 < 1 ms（估计）。**建议不做**，登记为「上游可以改成 `CallerSensitive` 或直接用 `StackWalker` 缓存」的候选。

**回归测试点**：不适用。


## 不可 Mixin / 建议提给上游的候选

> 本节按 `_TEMPLATE.md` 规则 6 单列；Lead 汇总进 `11-upstream-issues.md`。已在 `11-upstream-issues.md` 登记过的条目在括号里标注对应编号，**不重复举例**。

| # | 位置 | 为什么不能/不该用 Mixin | 建议的上游改法 |
| --- | --- | --- | --- |
| U1 | `AllCreativeModeTabs.java:324-343`（`applyOrderings`）+ `:351-364`（`ItemOrdering`） | 参数类型 `ItemOrdering` 是 `private static record`，外部 mixin 无法书写方法描述符 → `@Overwrite` 写不出来；`@Inject` 替换 `accept` 需复制 200+ 行 | 把 `applyOrderings` 与 `ItemOrdering` 提升为 `public`（或至少 package-private），并让 `registrate().getAll(...)` 的结果在 `accept` 内被缓存 |
| U2 | `compat/trainmap/TrainMapSync.java:167-182`（`send`） | 按维度/距离过滤需要改 `TrainMapSyncPacket` 的结构；客户端 codec（`:61-73`）必须同步改 → 单侧 mod 会与官方客户端不兼容。已在 `11-upstream-issues.md` **C4** 登记 | 服务端按「请求者所在维度 + 可视半径」过滤列车后再打包；或让包体按维度分片 |
| U3 | `foundation/utility/TickBasedCache.java:122,133-137,140-150` | `size()`/asMap()` 强制 `cleanUp()` 是**接口实现的设计问题**；且 `asMap()` 在 `resetTimerOnAccess=true` 时还会顺手刷新全部时间戳，去掉会改语义。已在 `11-upstream-issues.md` **C5** 登记。**Mixin 只建议改 `size()`（O(1) 近似值），不动 `asMap()`** | 让 `size()` 返回 `map.size()`；把清理做成按 tick 的惰性批量（如每 N tick 清一次）而不是每次度量都清 |
| U4 | `content/decoration/palettes/PalettesVariantEntry.java:29-83` | 构造器里 252 个方块/物品的 Registrate builder 链；跳过注册会造成内容缺失。已在 `11-upstream-issues.md` **B1** 登记（原文"7 石材"应为 14，"≈300+" 应为 **100 个方块 + 100 个物品**，其中 16 个 partial 方块/物品——本文件第 9 条已修正） | 把调色板方块改为 datagen 产出的资源包，或延迟到 `RegisterEvent` 时批量注册 |
| U5 | `Create.java:213-217`（`registrate()` 的 StackWalker 守卫） | 这是**刻意的防滥用设计**，删掉等于改契约。已在 `11-upstream-issues.md` **B3** 登记 | 保留守卫；若在意启动成本，改用 `MethodHandles.lookup().lookupClass()` 或维护一个"已授权包名"白名单 |
| U6 | `content/kinetics/chainConveyor/ServerChainConveyorHandler.java:53`（每 10 tick 无条件 `sync()`） | 纯服务端改动可以 Mixin（见第 14 条），但"客户端是否依赖定期空包来清空姿态"需要双端协同确认。真正干净的修法是客户端在 `PlayerSkyhookRenderer` 里加"已清空"状态 | 只在挂链集合**发生变化**时同步；给客户端加"列表版本号"避免丢包后永久错位 |
| U7 | `content/equipment/armor/BacktankUtil.java:50-65`（`BACKTANK_SUPPLIERS` 是 public 可扩展 API） | 缓存策略必须处理"第三方 mod 在运行期注册供应商"的情形，否则缓存失效会漏读。已在 `11-upstream-issues.md` **A3** 一栏提到 `TickBasedCache` 的并发问题，本条是另一处 | 上游把「供应商结果」缓存到实体（`LivingEntity` 的 data/attachment），或提供 `getAllWithAirCached(entity, tick)` |
| U8 | `compat/trainmap/TrainMapManager.java:734-741`（`mapYtoAlpha` 每像素调 `Minecraft.getInstance().level.getMinBuildHeight()`） | 单独看不是热点（已 javap 确认是字段/接口读），但如果要做 `renderPhase` 的 `@Overwrite`，把 `minBuildHeight` 提到 `redrawAll` 一次是免费收益 | 上游把 `minY` 作为参数传入 `renderPhase`（纯粹的内联优化） |
| U9 | `foundation/events/CommonEvents.java:281-312`（44 行手写 `registerCapabilities` 调用） | 不是性能问题（一次性），但**是维护负担**：新增方块实体必须手动加一行，漏了就在运行期静默失去能力。Mixin 无法改善 | 上游改成注解扫描或 `RegisterCapabilitiesEvent` 上的自动注册 |

## 已核实不是性能问题的点

> 单列这一节的目的：**避免后人重复扫**。每条都给出了核实方式。

| # | 位置 | 核实结论与依据 |
| --- | --- | --- |
| N1 | `infrastructure/config/AllConfigs.java:25` | 用 `EnumMap`（已读第 25 行）。`byType` 是 O(1) 的枚举键查找 |
| N2 | `Mods` 的 24 次 `getModFileById`、`infrastructure/RemapHelper.java` 的上百次 `put` | 都是**一次性类初始化**（`Mods` 是枚举 + 静态字段；`RemapHelper` 在 `FMLCommonSetupEvent` 前跑一次）。不在任何 tick/帧路径上 |
| N3 | `foundation/events/CommonEvents.java:180-185`（`addReloadListeners` 注册 3 个监听器） | 注册本身是一次性的；被注册的 `RecipeFinder.LISTENER` / `RecipeTrieFinder.LISTENER` / `BeltHelper.LISTENER` 在每次 `/reload` 时只是**清缓存**（`/reload` 不是热路径） |
| N4 | `content/kinetics/base/BlockEntityBehaviour` / IBE 的 `getTicker` 每次新建 `SmartBlockEntityTicker` | **只在方块实体加载时调用一次**（`BlockEntityTicker` 的工厂语义），不是每 tick。每 tick 调用的是返回的 ticker 实例本身 |
| N5 | `logistics/GlobalLogisticsManager.tick`、`redstone/link/controller/LinkedControllerServerHandler.tick` | 只遍历**自己维护的小 map**（每个物流管理器/控制器各一个），规模与"全服实体数"无关 |
| N6 | 全树反射 | 已 grep：`com.simibubi.create` 包下**唯一**的反射点是 `infrastructure/gametest/CreateTestFunction.java:54` 的 `Class::getDeclaredMethods`（gametest 专用，不在生产路径）。"Create 用反射拖慢性能"的直觉在本项目**不成立** |
| N7 | `foundation/data/RuntimeDataGenerator.java:44,46,48`、`foundation/utility/PojavChecker.java:25` | 三处 `Pattern.compile` **已经**正确提升为 `static final`（已核对源码行）。真正的问题是每次 `Matcher` 的分配，不是正则编译 |
| N8 | `foundation/utility/ServerSpeedProvider.java:24,43`（及同类） | 已反编译 NeoForge 21.1.219 的 `ModConfigSpec$ConfigValue.get()`：命中路径是 `getfield cachedValue` + null 检查 + 调用方拆箱。**不是分配，不是虚调用热点**，每 tick 约 2–5 ns。详见第 13 条 |
| N9 | `content/contraptions/minecart/capability/CapabilityMinecartController.java:57-87` 的 `queuedUnloads` / `queuedAdditions` 用 `ObjectLists.synchronize` 包装 | 每次 `add` / `remove` / `clear` 都走 `synchronized`。在**无竞争**时 JVM 的偏向锁/轻量级锁路径成本很低（几十 ns 量级）。它确实是"不必要的同步"，但**不是当前的瓶颈**；改成非同步前必须先证明没有跨线程访问（**需实测**） |
| N10 | `AllCreativeModeTabs.java:324-343` 的 `applyOrderings` | 见第 10 条：k 是常数（个位数~十几），n≈10^3，`Item.equals` 是同一性比较 → 整个方法的绝对成本是**微秒级**。原 discovery 的"O(orderings×items) 很大"隐含了 orderings 也很大，实际不是 |
| N11 | `foundation/events/CommonEvents.java:109,137,138,140,141` 的单行 tick 转发 | 这些静态转发方法调用本身（`ContraptionHandler.tick` / `CapabilityMinecartController.tick` / `CouplingPhysics.tick` / …）是 O(1) 虚调用；成本在**被调方**，转发层不是问题 |
| N12 | `content/equipment/armor/BacktankUtil.java:107-122` 的 `maxAir` 读 `AllConfigs.server()...get()` | 同上（N8）。附魔表遍历在 `maxAir(ItemStack)` 里，k ≤ 4 个背罐时总次数很小；真正的问题是**重复调用**（见第 5 条），不是单次成本 |

---

## 本领域发现的「值得上报但不是性能问题」的点

- `content/contraptions/minecart/CouplingHandler.java:57,60,63`：遍历 `cartsWithCoupling` 时用 `return` 而非 `continue` —— **遍历被提前终止**。这是纯 bug（已在 `11-upstream-issues.md` **A1** 登记），与性能无关，但在你为本文件第 2/3 条写 Mixin 时会碰到同一个集合，**建议顺手提 issue**。
- `foundation/utility/TickBasedCache.java:33`：`map` 是 `ConcurrentHashMap` 而 `timestamps` 是普通 `HashMap` —— 并发读写下 `timestamps` 可能损坏（已在 `11-upstream-issues.md` **A3** 登记）。本文件第 11 条的 Mixin（`size()` 直接读 `map`）**不会**引入新的并发问题，因为它只读 `ConcurrentHashMap`。
