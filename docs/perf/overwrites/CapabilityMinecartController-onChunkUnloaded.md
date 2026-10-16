# @Overwrite 详解：`CapabilityMinecartController.onChunkUnloaded` 与 `.tick`

> 关联 finding：`09-compat-loadtime.md` 第 2 条（High，区块卸载）与第 3 条（Low，tick）
> 目标：`com.simibubi.create.content.contraptions.minecart.capability.CapabilityMinecartController`
> 两个方法都**没有被 Create 自带 mixin 注入**（`create.mixins.json` 的 79 行里只有原版类），`@Overwrite` 不与 Create 冲突。

## 0. 结论先行（这两条的收益落差很大）

| 方法 | 真实热点 | 重写收益（估计） | 是否值得做 |
| --- | --- | --- | --- |
| `onChunkUnloaded` | **是**：每次区块卸载 O(该维度全部矿车) 全表扫描 | 一次批卸载（几百~几千区块）从 O(区块×矿车) 降到 O(矿车) 或 O(区块) | **值得** |
| `tick` | **基本不是**：见 §5。每 tick 一次全表扫描的真实绝对成本是微秒级 | 每 tick 省 1 个 `ArrayList`（多数情况下会被 JIT 标量替换）+ 每矿车约 2 次弱引用读 + 1 次 `isAlive()` | **不值得单独做** |

原 finding 把 `tick` 标为 High 是**过高**的：下面 §5 给出逐项成本核算。

## 1. 原方法完整源码（Create 6.0.10，逐行核对）

文件：`src/main/java/com/simibubi/create/content/contraptions/minecart/capability/CapabilityMinecartController.java`

相关字段（第 38–48 行）：

```java
	public static WorldAttached<Map<UUID, MinecartController>> loadedMinecartsByUUID;
	public static WorldAttached<Set<UUID>> loadedMinecartsWithCoupling;
	static WorldAttached<List<AbstractMinecart>> queuedAdditions;
	static WorldAttached<List<UUID>> queuedUnloads;

	static {
		loadedMinecartsByUUID = new WorldAttached<>($ -> new HashMap<>());
		loadedMinecartsWithCoupling = new WorldAttached<>($ -> new HashSet<>());
		queuedAdditions = new WorldAttached<>($ -> ObjectLists.synchronize(new ObjectArrayList<>()));
		queuedUnloads = new WorldAttached<>($ -> ObjectLists.synchronize(new ObjectArrayList<>()));
	}
```

目标方法 A：`tick`（第 50–102 行）

```java
	public static void tick(Level world) {
		Map<UUID, MinecartController> carts = loadedMinecartsByUUID.get(world);
		List<AbstractMinecart> queued = queuedAdditions.get(world);
		List<UUID> queuedRemovals = queuedUnloads.get(world);
		Set<UUID> cartsWithCoupling = loadedMinecartsWithCoupling.get(world);
		Set<UUID> keySet = carts.keySet();

		for (UUID removal : queuedRemovals) {
			keySet.remove(removal);
			cartsWithCoupling.remove(removal);
		}

		for (AbstractMinecart cart : queued) {
			UUID uniqueID = cart.getUUID();

			if (world.isClientSide && carts.containsKey(uniqueID)) {
				MinecartController minecartController = carts.get(uniqueID);
				if (minecartController != null) {
					AbstractMinecart minecartEntity = minecartController.cart();
					if (minecartEntity != null && minecartEntity.getId() != cart.getId())
						continue; // Away with you, Fake Entities!
				}
			}

			cartsWithCoupling.remove(uniqueID);

			MinecartController controller = cart.getData(AllAttachmentTypes.MINECART_CONTROLLER);
			if (controller != MinecartController.EMPTY) {
				carts.put(uniqueID, controller);
				if (controller.isLeadingCoupling())
					cartsWithCoupling.add(uniqueID);
				if (!world.isClientSide && controller != null)
					controller.sendData();
			}
		}

		queuedRemovals.clear();
		queued.clear();

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
	}
```

目标方法 B：`onChunkUnloaded`（第 113–128 行）

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

调用入口：`foundation/events/CommonEvents.java:114-117`（`ChunkEvent.Unload` → `onChunkUnloaded`）、`:133-144`（`LevelTickEvent.Post` 且非客户端 → `CapabilityMinecartController.tick(world)`）。

相关实现细节（已用 javap 反编译原版 1.21.1 确认）：

- `Entity.chunkPosition()` 的字节码是 `getfield chunkPosition; areturn` —— **只是返回字段，无分配**。
- `MinecartController.isPresent()`（`MinecartController.java:357-359`）= `weakRef.get() != null && cart().isAlive()`；`cart()`（:361-363）= `weakRef.get()`。

## 2. 为什么 `onChunkUnloaded` 值得重写

### 2.1 频次 × 成本

- **频次**：**每一个** `ChunkEvent.Unload` 都触发一次。区块卸载发生在：玩家飞行/乘列车移动、传送、视图距离缩小、维度切换、保存退出。既有「批量卸载」（传送 1000 格 → 一次卸载上百个区块），也有稳态的零散卸载（每秒几个）。
- **单次成本**：O(C)，C = 该维度 `loadedMinecartsByUUID` 里的矿车数。每次循环：hash 桶遍历 + `isPresent()`（2 次 `WeakReference.get()` + 1 次 `isAlive()`）+ `cart()` + `chunkPosition()` + `ChunkPos.equals`（2 个 int 比较）。
- **规模放大**：C 随「该维度已加载的矿车总数」增长。原版几乎为 0，但整合包里常见：大型矿车调度/自动化铁路网（几百辆）、常加载区块里的常驻车站（几十辆）。
  放大模型：一次批量卸载 K 个区块 × C 辆矿车。**K=500、C=200 → 10 万次迭代**，全部在服务端主线程、`ChunkEvent.Unload` 的同步路径里，会直接体现为 MSPT 尖峰。

### 2.2 一个隐藏的正确性隐患（顺手修）

第 122–124 行先调 `cart()` 再调 `cart.chunkPosition()`，第二次**没有 null 检查**。
虽然前置的 `isPresent()` 已经保证了非 null，但那是**跨两次 `weakRef.get()` 的**保证。改后写法用同一个局部变量消除这个隐患。

## 3. 改后（纯 Java，可粘贴的方法体）

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
			// 只取一次引用：原来 cart() 被调用两次（isPresent 内部 + 这里），
			// 且第二次没有 null 检查。
			AbstractMinecart cart = controller.cart();
			if (cart == null)
				continue;
			if (cart.chunkPosition()
				.equals(chunkPos))
				unloads.add(cart.getUUID());
		}
	}
```

**这只省了常数项，没有改变 O(C)。** 要真正改变复杂度需要空间索引。三条可选路线：

### 路线 1（增量索引，最稳但维护点多）

维护 `Level → (ChunkPos.toLong() → List<UUID>)` 的桶，在矿车内容变化时更新，`onChunkUnloaded` 只查一个桶。
代价：需要在 `tick` 的 queued 段、`onCartRemoved`、`MinecartController.tick` 的 `needsEntryRefresh` 入队点等**至少 5 个位置**同步维护。
**只要有一处漏维护就会漏卸载** → 死矿车残留在 `loadedMinecartsByUUID`，进而让 `CouplingHandler.forEachLoadedCoupling` 拿到过期数据。**脆弱度高**。

### 路线 2（tick 里按区块标记，卸载时只扫脏桶）— 推荐

在 `tick` 里已经遍历了全部矿车（见 §5），顺手把每个矿车按 `cart.chunkPosition().toLong()` 放进桶；桶在每个 `tick` 重建一次（或按 revision 懒重建）。
这样 `onChunkUnloaded` 变成 O(该区块的矿车数)，**复杂度上最优且改动集中**。
风险：两个方法必须**同时生效**（只装一个会让桶为空 → 漏卸载）。因此它们必须放进**同一个 `@Mixin` 类**，用一个 `@Unique` 静态字段共享状态。

### 路线 3（只做线性优化）

就是本节上面的版本。**建议先上这个 + 探针**，确认 `ChunkEvent.Unload` 栈确实痛之后再上路线 2。

## 4. Mixin 骨架（`onChunkUnloaded`，路线 3）

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

	// queuedUnloads 是 package-private static；@Shadow 对 package-private 成员有效，
	// 因为 mixin 类在应用时会被合并进目标类。字段名必须逐字一致。
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

说明：`loadedMinecartsByUUID` 是 `public` 字段，可以直接访问；`queuedUnloads` 是 package-private，用 `@Shadow` 是最干净的解法。
若你的 Mixin 版本对 package-private 静态字段的 `@Shadow` 有问题（**需实测**），退路是 `@Accessor`。

## 5. `tick`：为什么原 finding 标 High 过高（逐项核算）

原 finding 的三个指控逐项核对（第 89–101 行）：

| 指控 | 核对结果 | 真实成本 |
| --- | --- | --- |
| 「每 tick `new ArrayList`」 | 属实（第 89 行） | 1 个对象 + 1 个 `Object[]`。它**从不逃逸**出方法（只在方法内被遍历），C2 的逃逸分析 + 标量替换**可以完全消除**。即便不消除，约 40 字节/tick。 |
| 「遍历全部矿车只为清理死亡控制器」 | 属实 | 每迭代 = 1 次 `HashMap.Node` 访问 + `isPresent()`。约 5–15 ns/辆。**C=1000 辆 → 5–15 μs/tick**，相对 50 ms 预算约 **0.01%–0.03%**。 |
| 「entrySet 迭代器」 | 属实 | `HashMap.keySet()` 复用构造期初始化好的 `KeySet` 视图（**无分配**）；`iterator()` 每 tick 一个新 `HashIterator`，约几十字节，同样很可能被标量替换。 |

**结论**：`tick` 的实际成本是「每 tick 微秒级」。原 finding 的另外两点也不准确：

- `tick` **不调用** `chunkPosition()`（只有 `onChunkUnloaded` 调）；
- `loadedMinecartsByUUID` 里的条目对应**已加载实体**，C 不会无限增长。

### 如果仍然要重写 `tick`（不推荐单独做）

可以顺手消掉 `toRemove` 分配，但不改变 O(C)，且有一处**语义顺序差异**必须注意：

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

改成「边遍历边删」的写法：

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
		// 原实现删除的 UUID 一定来自 carts 的 key，所以 retainAll(carts.keySet())
		// 与原「统一清 cartsWithCoupling」等价。
		cartsWithCoupling.retainAll(keySet);
```

**注意**：`retainAll` 自身是 O(|cartsWithCoupling|)，coupling 数量远小于矿车数，可接受。
原实现是「先收集再统一删」，遍历期间 `cartsWithCoupling` 不变；新实现遍历期间同样不碰它，删完后补一轮 `retainAll`。**两者语义等价**，但如果你漏掉 `retainAll` 就会残留 coupling 标记。

## 6. 风险

| 风险 | 等级 | 说明 |
| --- | --- | --- |
| `@Overwrite` 语义漂移 | 中 | `onChunkUnloaded` 的改动是「等价重排 + null 检查」，风险低。`tick` 的 `toRemove` → 边遍历边删 改写涉及 `cartsWithCoupling` 的清理，漏掉 `retainAll` 会残留 coupling 标记 |
| 并发修改 | 低 | `onChunkUnloaded` 与 `tick` 都在服务端主线程（`ChunkEvent.Unload` 与 `LevelTickEvent.Post` 都是主线程派发）。`cart.chunkPosition()` 读的是实体自身字段，同样是主线程。 |
| `queuedUnloads` 是 `ObjectLists.synchronize` 包装 | 低 | 每次 `add` 都走同步包装（无竞争时开销很小）。批量卸载命中多辆车时会有若干次加锁，可选优化是非同步化（**需先确认无跨线程写入**） |
| 与 Create 自带 mixin 冲突 | **无** | 已核对 `create.mixins.json` 全部 79 行 |

## 7. 回归测试点

1. **矿车不残留**：铺轨道放 20 辆矿车，用 `/tp` 把玩家传走 2000 格（触发卸载），再传回。确认矿车全部正常显示、能推动、能耦合；退出重进存档后数量不变。
2. **耦合链不断**：把 3 辆矿车两两耦合，传走再传回，确认耦合仍在（`loadedMinecartsWithCoupling` 没被误删）。
3. **矿车死亡/销毁**：给一辆矿车 `/kill`，确认它从 `loadedMinecartsByUUID` 消失，且耦合的另一端掉落耦合器物品。
4. **跨维度**：在下界放矿车，在主世界活动触发卸载，确认 `WorldAttached` 的按维度隔离未破坏。
5. **常加载区块**：用 `/forceload` 固定住 1 辆矿车，反复传送玩家，确认它从未被误卸载。
6. **卸载压测**：`/forceload remove all` 后飞行一圈，用 Spark 看 `ChunkEvent.Unload` 是否还在 top-20。
7. **（若做路线 2）增量索引一致性**：连续做「放置矿车 / 推走 / 杀死 / 传走」各 100 次，断言桶里 UUID 总数 == `carts.size()` 且桶内 UUID 全部存在于 carts。

## 8. 收益

- **`onChunkUnloaded`（路线 3，估计）**：每次调用省一次多余的 `cart()`、把 `queuedUnloads.get(level)` 从循环内提到循环外、并加一个空表短路。**绝对收益很小**。真正的收益来自路线 2，量级为「一次批量卸载省几十万次迭代」，**需实测**。
- **`tick`（估计）**：每 tick 省 1 个 `ArrayList`。**在 JIT 后基本不可观测**。若你的 profiler 显示它进入 top-50，说明 C 已经极大（上万），此时应该上路线 2，而不是微优化 `tick` 自身。

> 诚实结论：**这条 finding 里真正值得做的是 `onChunkUnloaded` 的空间索引（路线 2），而不是 `tick` 的分配消除。** 只打算花一天的话：先做路线 3 + 探针；profiler 证明确实痛再做路线 2。