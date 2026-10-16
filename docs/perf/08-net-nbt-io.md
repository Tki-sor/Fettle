# 网络 / NBT / 配置 / IO / 蓝图 性能问题

> 覆盖范围：`com.simibubi.create.content.schematics.**`（含 `cannon/`、`client/`、`packet/`）、
> `com.simibubi.create.impl.registry.SimpleRegistryImpl`、`com.simibubi.create.api.stress.BlockStressValues`、
> `content/kinetics/TorquePropagator`、`content/redstone/link/RedstoneLinkNetworkHandler`、
> `foundation/recipe/RecipeFinder`、`foundation/recipe/trie/RecipeTrieFinder`
> 调查依据：Create 6.0.10 / MC 1.21.1 源码逐行核对；行号以本仓库为准
> 公共约定见 [01-mixin-playbook.md](01-mixin-playbook.md)
>
> 本文所有代码均为**静态推演的结果，未在开发环境编译验证**。收益数字都是**估计**。
> 本领域**没有 `@Overwrite` 候选**（最高风险的做法在这里都不划算），因此 `docs/perf/overwrites/` 下没有对应文件。

## 摘要

- **N1 [Medium]** `MaterialChecklist.getRequiredAmount` 每次调用 `new ItemStack(item).getMaxDamage()`；
  生成材料清单时每个物品调 2 次（`:108/:139` 与 `:185/:217`）。
- **N2 [Medium]** `SchematicItem.loadSchematic` 每次调用都「磁盘读 + GZIP 解压 + NBT 解析 + `StructureTemplate.load`」，
  客户端预览一次 `setupRenderer()` 还会建 3 个 `SchematicLevel`；另有 `NbtAccounter.create(0x20000000L)`（**512 MiB**、无限定的解压炸弹面）。
  这是本领域最值得做缓存的一条。
- **N3 [Medium]** `ServerSchematicLoader.tick` 每 tick 无条件读配置 + 遍历 `activeUploads`（每个 key 双查找）；
  `handleNewUpload`/`tryDeleteOldestSchematic` 在**服务端线程**上做 2~3 次完整目录扫描 + `min(comparingLong(...))`，
  并把 `IOException` 包成 `IllegalStateException` 当控制流用（`:326-333`）。
- **N4 [Medium]** `SchematicInstances.getHash` 在 `clearHash` 之后退化为「整份组件补丁的 `hashCode`」；
  而 `SchematicSyncPacket.handle` 每收到一次同步就改 4 个组件 + `clearHash`（客户端每 10 tick 可能同步一次）
  → 蓝图缓存 key 变化 → 重读磁盘 + 重建 `SchematicLevel`。
- **N5 [Low-Medium]** `ClientSchematicLoader.continueUpload` 每 `PACKET_DELAY = 10` tick 分配一个
  `maxSchematicPacketSize`（默认 1024 B）的 `byte[]`，末尾还要 `Arrays.copyOf`；`SchematicUploadPacket` 的 codec 再拷一次。
- **N6 [Low-Medium]** `SimpleRegistryImpl.get` 是 `synchronized` 的（`:56`、`:77`），被 `BlockStressValues.getImpact/getCapacity` 调用；
  调用点在动能网络重建（`KineticNetwork.add/addSilently`）与**物品 tooltip**（`KineticStats`）上。
- **N7 [Low]** 4 处日志字符串拼接（`ServerSchematicLoader.java:71,248`、`TorquePropagator.java:18,23`、`RedstoneLinkNetworkHandler.java:70,75`），
  全部在**世界加载/卸载**或**上传超时**等冷路径上。
- **N8 [Low]** `RecipeFinder`:`27` 与 `RecipeTrieFinder`:`19` 的静态 Guava 缓存没有 `maximumSize`、
  世界卸载不清理。**修正**：现有全部调用点用的都是 **static final 的 key 对象**（`SawBlockEntity.cuttingRecipesKey`、
  `MechanicalMixerBlockEntity.shapelessOrMixingRecipesKey`、`MechanicalPressBlockEntity.compressingRecipesKey`），
  因此条目数**有界**（≤3）；风险在「未来改成 per-instance key 时会无界增长」，属设计脆弱而非当下泄漏。

---

### N1. `MaterialChecklist.getRequiredAmount` 每次调用 `new ItemStack(item)` — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/schematics/cannon/MaterialChecklist.java:230-235`（关键行 `:233`）；调用点 `:108`、`:139`（`createWrittenBook`）与 `:185`、`:217`（`createWrittenClipboard`） |
| 调用频次 | 每次**生成材料清单**（`createWrittenBook`/`createWrittenClipboard`，由玩家放置蓝图到蓝图炮/剪贴板触发）对每个 key 物品调 1~2 次 → `O(物品种类数)`，不是 per-tick。`new ItemStack` 只在 `damageRequired.containsKey(item)` 为真时发生（即「按耐久消耗计算的物品」）。 |
| 复杂度 / 成本 | 每次 1 个 `ItemStack` 对象 + `getMaxDamage()` 的组件查询。1.21 组件化后 `ItemStack(ItemLike)` 还会构造 `PatchedDataComponentMap`（无自定义组件时它共享空 patch 单例，不一定再分配 map → **需实测**）。 |
| 注入方式 | `@Inject(HEAD, cancellable = true)`（3 行的 getter，`@Inject` 比 `@Overwrite` 抗改动；手册 §3.3） |
| 目标成员 | `com.simibubi.create.content.schematics.cannon.MaterialChecklist#getRequiredAmount(Lnet/minecraft/world/item/Item;)I` |
| 需新增状态 | `@Unique static` 的「物品 → 最大耐久」缓存（`IdentityHashMap<Item,Integer>`）。**上界 = 物品注册表大小**（几千），无失效需求（`MAX_DAMAGE` 组件对 item 是常量），可长期驻留；只在服务端主线程写。 |
| 风险 | 低（单点无状态 + 只读缓存） |
| 脆弱度 | 依赖字段名 `required`、`damageRequired` 与 `damageRequired.getInt(item)` 的 fastutil 方法名；上游换字段名 → 启动期报错 |
| 置信度 | 行号与调用点：**确定**。`new ItemStack(item)` 的具体分配量：**需实测** |

**改前**

```java
// MaterialChecklist.java:230-235
	public int getRequiredAmount(Item item) {
		int amount = required.getOrDefault(item, 0);
		if (damageRequired.containsKey(item))
			amount += (int) Math.ceil(damageRequired.getInt(item) / (float) new ItemStack(item).getMaxDamage());  // :233
		return amount;
	}
```

**为什么慢**

- `new ItemStack(item)` 只是为了问 `getMaxDamage()`——一个对给定 `Item` **恒定不变**的值。
- 生成清单是 `O(物品种类数)` × 2 次调用的循环（`:107-140` 与 `:184-219` 各遍历一次 `keys`），
  每项都重新构造并丢弃一个 `ItemStack`。一个大型蓝图（几十到上百种方块）就是数百次无谓分配。
- 绝对量不大（数百个对象），所以是 Medium 而不是 High；但修复成本也极低。

**改后**

```java
// 纯 Java：把「物品 → 最大耐久」做一次缓存
	private static final Map<Item, Integer> createperf$maxDamage = new IdentityHashMap<>();

	private static int createperf$maxDamageOf(Item item) {
		Integer cached = createperf$maxDamage.get(item);
		if (cached != null)
			return cached;
		int maxDamage = new ItemStack(item).getMaxDamage();   // 每个 Item 只走一次
		createperf$maxDamage.put(item, maxDamage);
		return maxDamage;
	}

	public int getRequiredAmount(Item item) {
		int amount = required.getOrDefault(item, 0);
		if (damageRequired.containsKey(item))
			amount += (int) Math.ceil(damageRequired.getInt(item) / (float) createperf$maxDamageOf(item));
		return amount;
	}
```

> 若 1.21.1 的 `Item` 暴露了等价的只读访问器（例如 `item.components().getOrDefault(DataComponents.MAX_DAMAGE, 0)`，
> **需核对**），可以直接用它，连缓存都不需要。

**Mixin 骨架**

```java
package com.example.createperf.mixin.schematics;

import java.util.IdentityHashMap;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.content.schematics.cannon.MaterialChecklist;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

@Mixin(value = MaterialChecklist.class, remap = false)
public abstract class MaterialChecklistMixin {

	@Unique
	private static final Map<Item, Integer> createperf$maxDamage = new IdentityHashMap<>();

	// 目标中是 public 字段（MaterialChecklist.java:38-39），声明必须完全一致
	@Shadow
	public Object2IntMap<Item> required;

	@Shadow
	public Object2IntMap<Item> damageRequired;

	@Inject(method = "getRequiredAmount(Lnet/minecraft/world/item/Item;)I",
			at = @At("HEAD"), cancellable = true, remap = false)
	private void createperf$fastRequiredAmount(Item item, CallbackInfoReturnable<Integer> cir) {
		int amount = required.getInt(item);
		if (damageRequired.containsKey(item)) {
			Integer cached = createperf$maxDamage.get(item);
			int maxDamage;
			if (cached != null)
				maxDamage = cached;
			else {
				maxDamage = new ItemStack(item).getMaxDamage();
				createperf$maxDamage.put(item, maxDamage);
			}
			amount += (int) Math.ceil(damageRequired.getInt(item) / (float) maxDamage);
		}
		cir.setReturnValue(amount);
	}
}
```

字段已核对：`public Object2IntMap<Item> required = new Object2IntArrayMap<>();`（`MaterialChecklist.java:38`）与
`public Object2IntMap<Item> damageRequired = new Object2IntArrayMap<>();`（`:39`）。
`Object2IntArrayMap.getInt(item)` 对缺失 key 返回 0，与原 `getOrDefault(item, 0)` 等价。

**验证方法**

- 探针：`@Unique static` 计数器统计 `createperf$maxDamage` 的 miss 次数；生成 3 次清单后应停在「不同物品数」上不再增长。
- 分配：JFR `ObjectAllocationSample`，过滤 `net.minecraft.world.item.ItemStack`。
- 功能：生成的成书/剪贴板中的数量必须与改前**逐项相同**（尤其是需要按耐久折算的物品，例如各种工具/机械零件）。

**收益估计**（估计）

- 量级：每次生成清单从「O(物品数) 次 `ItemStack` 分配」降到「O(不同物品数) 次，且全进程只一次」。
  绝对量是**数百个短命对象/次**，属于中小收益（不是 tick 级热点）。

**回归测试点**

1. 蓝图炮写入的材料清单（成书）里，所有物品的数量与改前一致。
2. 剪贴板版本（`createWrittenClipboard`）与成书版本的数量一致。
3. 按耐久折算的物品（`damageRequired`）在跨存档/跨会话后仍正确（缓存常驻，但 `MAX_DAMAGE` 是物品常量，不受存档影响）。
4. 装了会在运行期改变物品组件的 mod 时不崩（`MAX_DAMAGE` 由物品定义，理论上不变；若该 mod 改了，缓存会读到旧值 → 需实测确认）。

---

### N2. `SchematicItem.loadSchematic` 每次调用都读盘 + 解压 + 解析 NBT（+ 512 MiB 配额）— [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/schematics/SchematicItem.java:101-133`（读盘 `:124-127`，配额 `:126`）<br>调用点：`SchematicInstances.java:51`、`SchematicPrinter.java:101`、`client/SchematicHandler.java:140`（其后 `:145-147` 建 3 个 `SchematicLevel`）、`cannon/SchematicannonBlockEntity.java:467`（经 `initializePrinter`）、`packet/SchematicPlacePacket.java:37` |
| 调用频次 | 每个调用点各不同，但**都是「每次操作」而非缓存**：<br>• 客户端预览：`SchematicHandler.init`:`126`→`setupRenderer`（手中蓝图的 `SCHEMATIC_FILE` 变化时）与 `deploy()`:`381`；<br>• 蓝图炮：`initializePrinter`（`:451`）在 `!printer.isLoaded()` 时调用（`:341-344`、`:723-727`），**每次从 STOPPED 回到 RUNNING 都会重新读盘**；<br>• 创造模式放置：`SchematicPlacePacket.handle`:`37`，每次放置；<br>• 部署器：`SchematicInstances.get` → `loadWorld`（缓存未命中时）。 |
| 复杂度 / 成本 | 一次调用 = `Files.newInputStream` + `GZIPInputStream` 解压 + `NbtIo.read`（配额 512 MiB）+ `StructureTemplate.load`（O(方块数)：构 `palette` 哈希表 + 每方块一个 `StructureBlockInfo`）。客户端预览还要再 `placeInWorld` **3 次**（`:155`，`w`/`wMirroredFB`/`wMirroredLR`）。 |
| 注入方式 | `@Inject(HEAD, cancellable = true)`（缓存命中直接 `setReturnValue`，可行性见手册 §3.3）+ `@ModifyExpressionValue`（收紧 `NbtAccounter.create` 的配额）。**不推荐** `@Overwrite`（收益相同、风险更高）。 |
| 目标成员 | `com.simibubi.create.content.schematics.SchematicItem#loadSchematic(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;`；配额调用点 `net.minecraft.nbt.NbtAccounter#create(J)Lnet/minecraft/nbt/NbtAccounter;` |
| 需新增状态 | `@Unique static final ConcurrentHashMap<Path, Entry>`（Entry = mtime + size + `StructureTemplate`）。**失效时机**：每次命中前用 `Files.getLastModifiedTime` + `Files.size` 校验，不一致则重读；文件不存在则删条目并交回原逻辑。**并发**：集成服与客户端是两个线程，必须用 `ConcurrentHashMap`（不能用 `HashMap`）。 |
| 风险 | 中（缓存生命周期 + 文件变更检测 + 线程安全；返回**共享的** `StructureTemplate` 实例需要确认调用方只读） |
| 脆弱度 | 依赖 `loadSchematic` 的方法签名与「路径由 `SCHEMATIC_OWNER`/`SCHEMATIC_FILE` + 客户端/服务端选择目录」这一规则；我们的 HEAD 注入必须**复刻**这段路径推导，上游改路径规则 → 我们会用错误的 key 查缓存（错误结果）而不是启动报错。**因此缓存项必须做 mtime+size 校验**，且新增/变更路径规则时人工复核。 |
| 置信度 | 行号/调用点/配额数值：**确定**（`0x20000000L` = 536,870,912 B）。`NbtAccounter` 的默认配额常量（`DEFAULT_NBT_QUOTA = 2097152`、`UNCOMPRESSED_NBT_QUOTA = 104857600`）来自反编译核对（版本可能与 1.21.1 略有差异，**需实测**）。修复收益：**需实测**。 |

**改前**

```java
// SchematicItem.java:101-133
	public static StructureTemplate loadSchematic(Level level, ItemStack blueprint) {
		StructureTemplate t = new StructureTemplate();
		String owner = blueprint.get(AllDataComponents.SCHEMATIC_OWNER);
		String schematic = blueprint.get(AllDataComponents.SCHEMATIC_FILE);

		if (owner == null || schematic == null || !schematic.endsWith(".nbt"))
			return t;

		Path dir;
		Path file;
		if (!level.isClientSide()) {
			dir = CreatePaths.UPLOADED_SCHEMATICS_DIR;
			file = Paths.get(owner, schematic);
		} else {
			dir = CreatePaths.SCHEMATICS_DIR;
			file = Paths.get(schematic);
		}

		Path path = dir.resolve(file).normalize();
		if (!path.startsWith(dir))
			return t;

		try (DataInputStream stream = new DataInputStream(new BufferedInputStream(
			new GZIPInputStream(Files.newInputStream(path, StandardOpenOption.READ))))) {
			CompoundTag nbt = NbtIo.read(stream, NbtAccounter.create(0x20000000L));   // :126  512 MiB 配额
			t.load(level.holderLookup(Registries.BLOCK), nbt);
		} catch (IOException e) {
			LOGGER.warn("Failed to read schematic", e);
		}

		return t;
	}
```

**为什么慢**

- **完全没有缓存**：同一份蓝图文件在同一秒里可以被解析多次。最糟的是蓝图炮：`initializePrinter` 在
  `!printer.isLoaded()` 时触发，而 `state == STOPPED` 会 `resetPrinter()`（`:331-335`）→ 缺材料/停机/重启的循环里会**反复**读盘 + 解压 + 解析。
- **客户端预览更贵**：`setupRenderer` 除了 `loadSchematic`，还会把整份结构 `placeInWorld` 到 **3 个不同的**
  `SchematicLevel`（`:145-147`、`:155`），即 3 次全量方块写入 + 3 份内存中的方块副本。
- **配额过大（安全问题，不只是性能）**：`NbtAccounter.create(0x20000000L)` = **512 MiB**。
  `NbtAccounter` 只按「已计数字节」截断（`accountBytes`），并有 `maxDepth = 512` 的深度保护，
  但 512 MiB 的预算意味着一个高度可压缩的文件（gzip ~1000:1）可以在**解压炸开**时把服务端/客户端堆打满。
  要注意：`NbtAccounterException` 继承 `NbtException` → `RuntimeException`
  （`net.minecraft.nbt.NbtAccounterException`），而 `loadSchematic` 只 `catch (IOException e)`（`:128`）
  → **配额被触发时会以未检查异常逃逸到调用方**（蓝图炮 tick / 网络包处理 / 部署器 tick），不是优雅降级。

**改后**

```java
// 纯 Java 形态：按「规范化路径 + mtime + size」缓存已解析的 StructureTemplate
	private static final Map<Path, CachedSchematic> createperf$cache = new ConcurrentHashMap<>();

	private record CachedSchematic(long modified, long size, StructureTemplate template) {}

	public static StructureTemplate loadSchematic(Level level, ItemStack blueprint) {
		// ... 与原实现完全相同的路径推导（owner/schematic 校验、dir/ file、normalize、startsWith）...
		Path path = dir.resolve(file).normalize();
		if (!path.startsWith(dir))
			return new StructureTemplate();

		try {
			long modified = Files.getLastModifiedTime(path).toMillis();
			long size = Files.size(path);
			CachedSchematic cached = createperf$cache.get(path);
			if (cached != null && cached.modified() == modified && cached.size() == size)
				return cached.template();
			// miss：走原来的读盘/解压/解析，然后填充缓存
			StructureTemplate t = createperf$readAndParse(level, path);
			createperf$cache.put(path, new CachedSchematic(modified, size, t));
			return t;
		} catch (IOException e) {
			createperf$cache.remove(path);
			LOGGER.warn("Failed to read schematic", e);
			return new StructureTemplate();
		}
	}
```

Mixin 实现（**不复制解析逻辑**，用 HEAD 命中 + RETURN 回填）：

```java
	@Unique private static final ConcurrentHashMap<String, Object[]> createperf$cache = new ConcurrentHashMap<>();
	// Object[] = { Long modified, Long size, StructureTemplate template }

	@Inject(method = "loadSchematic", at = @At("HEAD"), cancellable = true, remap = false)
	private static void createperf$cacheHit(Level level, ItemStack blueprint, CallbackInfoReturnable<StructureTemplate> cir) {
		String owner = blueprint.get(AllDataComponents.SCHEMATIC_OWNER);
		String schematic = blueprint.get(AllDataComponents.SCHEMATIC_FILE);
		if (owner == null || schematic == null || !schematic.endsWith(".nbt"))
			return;                                  // 交回原逻辑
		boolean client = level.isClientSide();
		Path dir = client ? CreatePaths.SCHEMATICS_DIR : CreatePaths.UPLOADED_SCHEMATICS_DIR;
		Path path = dir.resolve(client ? Paths.get(schematic) : Paths.get(owner, schematic)).normalize();
		if (!path.startsWith(dir))
			return;
		String key = path.toString();
		try {
			Object[] entry = createperf$cache.get(key);
			if (entry == null)
				return;
			long modified = Files.getLastModifiedTime(path).toMillis();
			long size = Files.size(path);
			if (((Long) entry[0]) != modified || ((Long) entry[1]) != size) {
				createperf$cache.remove(key);
				return;
			}
			cir.setReturnValue((StructureTemplate) entry[2]);
		} catch (IOException e) {
			createperf$cache.remove(key);             // 文件没了/读不到 → 交回原逻辑（它会 warn）
		}
	}

	@Inject(method = "loadSchematic", at = @At("RETURN"), remap = false)
	private static void createperf$cacheFill(Level level, ItemStack blueprint, CallbackInfoReturnable<StructureTemplate> cir) {
		StructureTemplate t = cir.getReturnValue();
		if (t == null || t.getSize().equals(Vec3i.ZERO))
			return;                                   // 失败/空模板不进缓存
		// 与 HEAD 相同的路径推导，然后 stat + put（见上）
		// createperf$cache.put(key, new Object[] { modified, size, t });
	}
```

**配额收紧**（独立的一条，建议一起做）：

```java
	/** 把 512 MiB 的配额压到固定上界（示例：64 MiB）。 */
	@ModifyExpressionValue(method = "loadSchematic", remap = false,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/NbtAccounter;create(J)Lnet/minecraft/nbt/NbtAccounter;"))
	private static NbtAccounter createperf$boundedQuota(NbtAccounter original) {
		return NbtAccounter.create(0x4000000L);        // 64 MiB
	}
```

⚠️ **必须同时记录行为变更**：配额调小后，一个合法但很大的蓝图会从「能加载」变成
「抛未检查的 `NbtAccounterException`」并逃逸出 `loadSchematic`（原实现只 catch `IOException`）。
若要更稳妥，改成「按文件大小定配额」：HEAD 注入里已经 stat 过 size，把它存进
`@Unique static ThreadLocal<Long>`，配额取 `min(128 MiB, max(8 MiB, size * 300))`。
**更好的做法是让上游删掉 512 MiB 常量**（见文末 U1）。

**验证方法**

- **缓存命中率**：`@Unique static AtomicLong` 统计 hit/miss，蓝图炮反复启停时 miss 应只发生在文件真正变化之后。
- **正确性**：修改磁盘上的 `.nbt` 文件（mtime/size 变化）后，下一次加载必须拿到**新**内容（这是缓存唯一的功能风险）。
- **线程**：单机（集成服 + 客户端）+ 多人专用服各测一遍；`defaultRequire = 1` 只保证注入成功，不保证并发正确。
- **配额**：用一个大蓝图（或人为 gzip 炸弹）确认不再出现 512 MiB 级别的堆增长；
  并确认失败路径改为「报错但不崩服」（若保持原 512 MiB 则此条不适用）。
- `/spark profiler --timeout 60`，过滤栈里的 `GZIPInputStream`/`Inflater`/`NbtIo`/`StructureTemplate.load`。

**收益估计**（估计）

- 缓存命中一次省掉「磁盘读 + GZIP 解压 + NBT 解析 + `StructureTemplate.load`」整个链条；
  对一个几百 KB 的蓝图，**量级 = 每次省下 1 次文件读 + 1 次解压 + O(方块数) 的解析**（毫秒级，具体需实测）。
- 客户端预览首次仍需付 3 次 `placeInWorld` 的代价（本 finding 不动这部分）；改动只消除**重复的磁盘/解析**成本。
- 配额收紧的价值是**稳定性**（避免一次恶意文件把堆打满），不是吞吐。

**回归测试点**

1. 蓝图炮：放入蓝图 → 缺材料停机 → 补材料继续，反复 5 次，打印数量/位置与改前一致。
2. 客户端预览：手中蓝图的旋转/镜像切换（会重建 `SchematicLevel`）显示正确。
3. 创造模式直接放置（`SchematicPlacePacket`）后存档内容正确。
4. 部署器 + 已部署蓝图（`SchematicInstances.get`）行为不变。
5. **文件变更检测**：替换磁盘上的同名 `.nbt`（长度相同、mtime 变化；再试长度变化），两次都必须加载新内容。
6. 路径逃逸（`owner = "../.."`）仍被 `path.startsWith(dir)` 拒绝。
7. 服务端上传流程（`ServerSchematicLoader` → `SchematicItem.create` → `writeSize` → `loadSchematic`）走通，`SCHEMATIC_BOUNDS` 正确。

---

### N3. `ServerSchematicLoader.tick` 每 tick 读配置 + 遍历；上传路径在服务端线程做多次目录扫描 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/schematics/ServerSchematicLoader.java`：`tick()` `:64-81`、`handleNewUpload` `:88-152`（`Files.list` 计数 `:128-130`，第二次 `Files.list` + `min` `:133-137`）、`tryDeleteOldestSchematic` `:312-324`、`getLastModifiedTime` `:326-333` |
| 调用频次 | `tick()` **每服务端 tick**：`CommonEvents.onServerTick`（`foundation/events/CommonEvents.java:103-105`，`ServerTickEvent.Post`）→ `Create.SCHEMATIC_RECEIVER.tick()`（`Create.java:98`，类型 `ServerSchematicLoader`）。`handleNewUpload`/`tryDeleteOldestSchematic` 只在玩家发起上传/使用蓝图与笔时执行。 |
| 复杂度 / 成本 | `tick()`：1 次 `getConfig().schematicIdleTimeout.get()`（`AllConfigs.server()` 的字段读取）+ 对 `activeUploads` 的 `keySet()` 迭代，每个 key 再 `get(upload)`（**双查找**）。**常见情况 `activeUploads` 为空 → 实际成本 ≈ 0**（这是原始 finding 里最需要修正的一点）。<br>`handleNewUpload`：`Files.deleteIfExists` + `Files.list(playerPath).count()`（全目录扫描）+ 若超限再来一次 `Files.list` + `filter` + `min(Comparator.comparingLong(...))`（再全扫 + 每文件一次 `stat`）。<br>`tryDeleteOldestSchematic`：`Files.list(dir).toList()` + `stream().min(comparingLong(this::getLastModifiedTime))`（每文件一次 `stat`）。 |
| 注入方式 | tick() 部分：`@Inject(HEAD, cancellable = true)`（`activeUploads` 为空时直接 `ci.cancel()`，跳过配置读取与遍历）。IO 部分：**不可 Mixin**（见下） |
| 目标成员 | `com.simibubi.create.content.schematics.ServerSchematicLoader#tick()V` |
| 需新增状态 | 无（只用 `@Accessor` 读 `activeUploads`） |
| 风险 | 低（tick 短路无副作用；被跳过的配置读取不改变任何状态） |
| 脆弱度 | 依赖私有字段 `activeUploads` 的名字（用 `@Accessor`）；上游把它换成别的容器/名字 → 启动期报错 |
| 置信度 | 行号/调用链：**确定**。`activeUploads` 常为空：**确定**（字段就是 `new HashMap<>()`，只有上传期间才填充）。tick 的真实开销：**确定是「可忽略」**。IO 部分的收益量级：**需实测**（取决于目录里有多少文件） |

**改前**

```java
// ServerSchematicLoader.java:64-81 —— 每服务端 tick
	public void tick() {
		// Detect Timed out Uploads
		int timeout = getConfig().schematicIdleTimeout.get();     // :66  无条件读配置
		for (String upload : activeUploads.keySet()) {            // :67
			SchematicUploadEntry entry = activeUploads.get(upload);   // :68  双查找
			if (entry.idleTime++ > timeout) {
				Create.LOGGER.warn("Schematic Upload timed out: " + upload);   // :71  字符串拼接
				deadEntries.add(upload);
			}
		}
		for (String toRemove : deadEntries)
			this.cancelUpload(toRemove);
		deadEntries.clear();
	}

// ServerSchematicLoader.java:126-141 —— 服务端线程上的目录扫描
			long count;
			try (Stream<Path> list = Files.list(playerPath)) {
				count = list.count();                              // :129  扫描 1
			}
			if (count >= getConfig().maxSchematics.get()) {
				Stream<Path> list2 = Files.list(playerPath);
				Optional<Path> lastFilePath = list2.filter(f -> !Files.isDirectory(f))
					.min(Comparator.comparingLong(f -> f.toFile().lastModified()));   // :135  扫描 2 + 每文件 stat
				list2.close();
				if (lastFilePath.isPresent())
					Files.deleteIfExists(lastFilePath.get());
			}

// ServerSchematicLoader.java:312-333 —— 同一模式的第二处
	private boolean tryDeleteOldestSchematic(Path dir) {
		try (Stream<Path> stream = Files.list(dir)) {
			List<Path> files = stream.toList();                    // :314  扫描
			if (files.size() < getConfig().maxSchematics.get())
				return true;
			Optional<Path> oldest = files.stream().min(Comparator.comparingLong(this::getLastModifiedTime));  // :317
			Files.delete(oldest.orElseThrow());                    // :318  orElseThrow 当控制流
			return true;
		} catch (IOException | IllegalStateException e) {          // :320  捕获自己抛的 ISE
			Create.LOGGER.error("Error deleting oldest schematic", e);
			return false;
		}
	}

	private long getLastModifiedTime(Path file) {
		try {
			return Files.getLastModifiedTime(file).toMillis();
		} catch (IOException e) {
			Create.LOGGER.error("Error getting modification time for file {}", file.getFileName(), e);
			throw new IllegalStateException(e);                     // :331  异常当控制流
		}
	}
```

**为什么慢**

- **先说清量级**：`tick()` 在 `activeUploads` 为空时几乎不花钱（一次配置字段读取 + 一个空 `HashMap` 的 `keySet()` 迭代）。
  原始 finding 说「无条件做配置读取 + 全 map 遍历」，字面上对，但它每 tick 的成本是常数且极小，
  真正的价值是「不在每 tick 做任何不必要的事」这个卫生问题 → **这一条的实际严重度是 Low**。
- 上传/删除路径才是 Medium 的部分，但它是**每个玩家操作一次**而不是每 tick；问题在**性质**上：
  块式 `Files.list`（2~3 次全目录扫描 + 每文件 `stat`）跑在**服务端主线程**上，
  一个塞了几千个蓝图的 `schematics/` 目录会让服务端卡住一个 tick 到几十毫秒
  （`maxSchematics` 默认 10，但玩家可以直接往文件夹里丢文件，目录条目数不受该配置限制）。
- `getLastModifiedTime` 把 `IOException` 包成 `IllegalStateException`，再由 `tryDeleteOldestSchematic` 捕获（`:320`）
  是「异常当控制流」：每个异常都会 `fillInStackTrace`（昂贵），而且错误日志会刷两遍。

**改后**

tick() 的安全短路（纯 Java 形态）：

```java
	public void tick() {
		if (activeUploads.isEmpty())      // 常见情况：不读配置、不遍历、不分配
			return;
		... // 原逻辑
	}
```

IO 部分（**不改语义**的纯 Java 形态，供上游参考；本 mod 不建议 Mixin）：

```java
// 一次遍历同时算出「数量」与「最旧文件」，并把 stat 失败当作"跳过该文件"而不是异常
	private void createperf$trimOldest(Path dir, int maxSchematics) {
		Path oldest = null;
		long oldestTime = Long.MAX_VALUE;
		int count = 0;
		try (Stream<Path> stream = Files.list(dir)) {
			for (Path p : (Iterable<Path>) stream::iterator) {
				count++;
				try {
					long t = Files.getLastModifiedTime(p).toMillis();
					if (t < oldestTime) {
						oldestTime = t;
						oldest = p;
					}
				} catch (IOException ignored) {
					// 跳过读不到 mtime 的条目，不再用异常当控制流
				}
			}
		} catch (IOException e) {
			Create.LOGGER.error("Error listing schematics in {}", dir, e);
			return;
		}
		if (count >= maxSchematics && oldest != null)
			try {
				Files.deleteIfExists(oldest);
			} catch (IOException e) {
				Create.LOGGER.error("Error deleting oldest schematic {}", oldest, e);
			}
	}
```

**Mixin 骨架**（只做 tick() 短路；IO 部分见文末上游候选）

```java
@Mixin(value = com.simibubi.create.content.schematics.ServerSchematicLoader.class, remap = false)
public abstract class ServerSchematicLoaderMixin {

	@Shadow
	@Final
	private Map<String, ServerSchematicLoader.SchematicUploadEntry> activeUploads;

	@Inject(method = "tick()V", at = @At("HEAD"), cancellable = true, remap = false)
	private void createperf$skipWhenIdle(CallbackInfo ci) {
		if (activeUploads.isEmpty())
			ci.cancel();   // 无进行中的上传 → 无需读配置、无需遍历、无需清 deadEntries
	}
}
```

> `activeUploads` 是 `private final`（`:38`、`:59`）。`@Shadow @Final` 一个 final 实例字段是 Mixin 常规写法；
> 若你的环境报 "variable might not have been initialized"，改用接口 mixin：
> `@Mixin(ServerSchematicLoader.class) interface ...Accessor { @Accessor("activeUploads") Map<String, SchematicUploadEntry> createperf$getActiveUploads(); }`
> 并在注入器里 `((...Accessor) this).createperf$getActiveUploads().isEmpty()`。

**验证方法**

- 探针：`@Unique` 计数器统计 `tick` 的「实际执行次数 / 短路次数」，开/关 mixin 各跑 600 tick。
- 功能：发起一次真实上传（含超时场景：`schematicIdleTimeout` 最小档 100），验证超时清理、完成上传、取消上传三条路径。
- `/spark profiler` 观察 `ServerSchematicLoader.tick` 的占比（预计本来就接近 0）。

**收益估计**（估计）

- tick() 短路：**量级 = 每 tick 省 1 次配置字段读取 + 1 次空 `HashMap` 迭代 + 1 次 `ObjectArrayList.clear()`（`:80`）**。绝对值可忽略。
- IO 部分（若上游照改）：把 2~3 次目录扫描合并为 1 次，并省掉一次 `Stream` 分配与若干异常构造；
  **量级 = 每次删除操作省 1 次全目录遍历**。

**回归测试点**

1. 上传超时：停手后确认超时连接被清理、日志只有一次 warning。
2. 正常上传完整 `.nbt`，蓝图炮里拿到正确的蓝图。
3. `maxSchematics` 打满时删最旧：确认删的是 mtime 最旧的那个。
4. 目录里有子目录/不可读文件时不崩（`filter(f -> !Files.isDirectory(f))` 的语义）。
5. 取消上传（`cancelUpload`）与 `shutdown()`（`:83-86`）正常关闭流。

### N4. 蓝图哈希在每次部署/旋转后重算整份组件补丁 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/schematics/SchematicInstances.java:76-82`（`getHash` 回退计算）、`content/schematics/packet/SchematicSyncPacket.java:54`（`clearHash`）、`content/schematics/client/SchematicHandler.java:104-107,137-140` |
| 调用频次 | 每次部署/移动/旋转/镜像蓝图（`SchematicSyncPacket.handle` 会 `clearHash`），随后任意一次 `getHash` 触发全量哈希；客户端每 tick 比较 `SCHEMATIC_FILE` |
| 复杂度 / 成本 | `schematic.getComponentsPatch().hashCode()` 遍历蓝图的**全部数据组件**（含边界、owner）；哈希变化又让 `SchematicLevel` 缓存条目重键 → 触发完整重载（含 `placeInWorld` 的整份世界拷贝） |
| 注入方式 | `@Inject` 在 `clearHash` 处改为仅在文件/owner 变化时清除；或在 `getHash` 用 revision 组件短路 |
| 目标成员 | `SchematicInstances#getHash` / `#loadWorld`；`SchematicSyncPacket#handle` |
| 需新增状态 | `@Unique` 单调递增 revision 组件，替代整份 patch 哈希 |
| 风险 | 中：哈希是缓存键，语义变化会让缓存命错（渲染错误蓝图） |
| 脆弱度 | 中：依赖 `AllDataComponents.SCHEMATIC_HASH` 与 `SCHEMATIC_FILE` 的用法 |
| 置信度 | 高概率（`:76-82` 与 `SchematicSyncPacket.java:54` 来自报告） |

**Mixin 骨架**

```java
@Mixin(value = SchematicInstances.class, remap = false)
public abstract class SchematicInstancesMixin {
    @Inject(method = "getHash", at = @At("HEAD"), cancellable = true)
    private static void createperf$revisionHash(ItemStack schematic, CallbackInfoReturnable<Integer> cir) {
        // 有 revision 组件就直接返回，避免遍历整份组件补丁
    }
}
```

**验证方法**：探针统计 `getComponentsPatch().hashCode()` 次数与 `SchematicLevel` 重载次数；行为回归：部署/旋转/镜像后预览与实际放置一致；同名不同内容的蓝图不串用缓存。
**收益估计**：每次操作从"遍历全部组件 + 缓存重键重载" → O(1)（估计）。
**回归测试点**：蓝图旋转/镜像；多蓝图切换；服务器-客户端哈希一致。

---

### N5. 蓝图上传每个包周期都分配一个 maxPacketSize 数组并再次拷贝 — [Low-Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/schematics/client/ClientSchematicLoader.java:127-147`；编码侧 `content/schematics/packet/SchematicUploadPacket.java:21-27` |
| 调用频次 | 每 `PACKET_DELAY = 10` tick × 每个进行中的上传 |
| 复杂度 / 成本 | 每周期 `new byte[maxPacketSize]`（配置可调，通常较大），尾部不足时再 `Arrays.copyOf(data, status)`；随后 `ByteBufCodecs.byteArray` 再拷贝一次进 `FriendlyByteBuf`；接收端在服务端线程直接写盘 |
| 注入方式 | `@Inject(HEAD)` 重写该段以复用缓冲（NEWARRAY 无法用 `@Redirect`） |
| 目标成员 | `ClientSchematicLoader#tick`（上传推进段） |
| 需新增状态 | `@Unique byte[] createperf$buffer`（每 loader；客户端主线程） |
| 风险 | 低 |
| 脆弱度 | 低 |
| 置信度 | 高概率（`:127-147` 来自报告） |

**验证方法**：JFR 观察大数组分配；行为回归：大蓝图上传完整、断线重连、并发多个上传。
**收益估计**：每 10 tick 一次大数组分配 + 一次拷贝 → 复用（估计）。
**回归测试点**：上传完整性；`maxSchematicPacketSize` 极值；取消上传。

---

### N6. `SimpleRegistryImpl.get` 是 `synchronized`，且被每 actor 每 tick / 每帧调用 — [Low-Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `impl/registry/SimpleRegistryImpl.java:77-99`（`public synchronized V get(K)`；`:56-60` 是另一个重载） |
| 调用频次 | `content/contraptions/AbstractContraptionEntity.java:448` `tickActors`（每 actor 每 tick）、`ContraptionEntityRenderer.java:158` 与 `ContraptionVisual.java:180`（每 actor 每帧）、`api/stress/BlockStressValues.java:25,30`（每次动力网络重算），另有约 30 处 |
| 复杂度 / 成本 | 每次查询取**全局共享对象**的监视器 + 两次 `IdentityHashMap` 探测（`containsKey` + `get`）；服务端线程、渲染线程、mod 初始化/资源重载线程共用一个锁 |
| 注入方式 | 方法级 `synchronized` **无法用 `@Inject` 去掉** → 必须 `@Overwrite` `get(K)`（配 `@Shadow`）；保守替代是在最热调用点 `@Redirect` 成自带缓存 |
| 目标成员 | `SimpleRegistryImpl#get(K)` |
| 需新增状态 | 若要快照：`@Unique volatile` 不可变快照 map |
| 风险 | 中：去掉锁会失去"并行 mod 初始化"保护（上游注释明确该锁的用途） |
| 脆弱度 | 中：依赖内部字段名 |
| 置信度 | 确定（`:77` 的 `public synchronized V get(K object)` 与双查找已由 Lead 逐行核对） |

**验证方法**：`tickActors` 与渲染路径探针计数；行为回归：大量 mod 的并行初始化不出现注册表读竞态；装置 actor 解析与渲染正常。
**收益估计**：每次查询去掉一次监视器获取 + 一次哈希探测（估计）。
**回归测试点**：启动期并行注册；movement behaviour 解析；应力换算。

---

### N7. 半热路径上的日志字符串拼接 — [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `content/schematics/ServerSchematicLoader.java:71,248`；`content/kinetics/TorquePropagator.java:18,23`；`content/redstone/link/RedstoneLinkNetworkHandler.java:70,75` |
| 调用频次 | 世界加载/卸载时的网络空间准备与移除（TorquePropagator、RedstoneLink），以及蓝图上传超时/异常 |
| 复杂度 / 成本 | 直接用 `"..." + value` 拼接，日志级别关闭时字符串仍会被构造 |
| 注入方式 | `@ModifyExpressionValue` 改参数化日志，或 `@Overwrite` 该日志行 |
| 目标成员 | 上述各方法（单行） |
| 需新增状态 | 无 |
| 风险 | 低 |
| 脆弱度 | 低 |
| 置信度 | 高概率（行号来自报告） |

**验证方法**：确认日志内容与级别不变。
**收益估计**：半热路径无谓字符串构造归零（估计）。
**回归测试点**：上述日志的输出内容/级别。

---

### N8. `RecipeFinder` / `RecipeTrieFinder` 的静态 Guava 缓存无上限、世界卸载不清理 — [Low]（**建议上游修**）

| 项 | 内容 |
| --- | --- |
| 位置 | `foundation/recipe/RecipeFinder.java:27`、`foundation/recipe/trie/RecipeTrieFinder.java:19` |
| 调用频次 | 每次配方查找（盆、混合器、压机、合成器等） |
| 复杂度 / 成本 | 缓存键按方块实体类型构造，实践中规模有界，但**没有 `maximumSize`，也没有世界卸载时的失效**；多人/多次切档场景下会长期驻留 |
| 注入方式 | 技术上可 `@Overwrite` 成 Caffeine 带 `maximumSize`，但"何时失效"属上游语义 |
| 风险 | 高（改错会跨存档串用配方缓存） |
| 置信度 | 高概率（行号来自报告） |

**建议**：提 issue 让上游加 `expireAfterAccess` 或按 level 维度分键，而不是自己 Mixin（见 [11-upstream-issues.md](11-upstream-issues.md)）。

---

## 交叉引用（本文不展开）

- **`SimpleRegistryImpl` 的 `synchronized`** 在 [10-crosscutting-java.md](10-crosscutting-java.md) 的「synchronized 热路径」反模式下有统一分析（本文 N6 给的是 Mixin 方案与风险）。
- **`TickBasedCache.size()/asMap()` 强制 `cleanUp`**（被 `TrainMapSync` 每包调用）→ [09-compat-loadtime.md](09-compat-loadtime.md)。
- **流体压力序列化的 NBT 结构**（`PipeConnection` 用 `ListTag<FloatTag>` 编码压力）→ [05-fluids.md](05-fluids.md)。

## 不可 Mixin / 建议提给上游的候选

| 条目 | 理由 |
| --- | --- |
| `RecipeFinder`/`RecipeTrieFinder` 的无上限静态缓存（N8） | 缓存生命周期属上游设计；Mixin 改失效时机极易造成跨存档串用 |
| `SchematicItem.loadSchematic` 的重复读盘（N2） | Mixin 可以加缓存，但"按文件 + mtime/哈希"的失效策略最好由上游在 `SchematicInstances` 里统一实现（客户端预览也走同一路径） |
| `NbtAccounter.create(0x20000000L)`（N2 附注，约 512MB 且无 component 配额） | 安全加固项：应由上游按文件大小/组件数设限 |
| `SimpleRegistryImpl` 的锁（N6） | 去掉 `synchronized` 等于移除上游刻意设置的"并行 mod 初始化"保护，属设计决策 |

## 快速收益清单（本领域，按性价比）

1. **`ServerSchematicLoader.tick` 空表短路（N3）** —— 一行级修复：每 tick 省一次配置读取 + 空 map 迭代。
2. **蓝图哈希改 revision（N4）** —— 消除部署/旋转后的整份组件遍历与蓝图缓存重载。
3. **`SchematicItem.loadSchematic` 记忆化（N2）** —— 换蓝图/炮台状态机重启都不必重新读盘 + gunzip + NBT 解析。
4. **上传缓冲复用（N5）** —— 复用一个大数组，去掉每 10 tick 的分配与尾部拷贝。
5. **日志参数化（N7）** —— 顺手清理，零风险。

> **写作说明**：本文件由子代理起草（N1–N3 完整）后因进程失败中断，N4–N8 与收尾章节由 Lead 补齐，分段标记已清除。置信度按来源逐条标注：N6 的 `synchronized` 已由 Lead 逐行核对，其余引用报告行号。



