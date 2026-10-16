# @Overwrite 详解：`MountedStorageManager.tick`

> 关联 finding：`03-contraptions-trains.md` 第 3 条（High）
> 目标：`com.simibubi.create.content.contraptions.MountedStorageManager`
> 本文件给的是**完整可粘贴**的替换实现（推荐用 `@Inject` 版，见第 4 节）。

## 1. 原方法完整源码（Create 6.0.10，行号已逐行核对）

文件：`src/main/java/com/simibubi/create/content/contraptions/MountedStorageManager.java`，第 190–216 行。

```java
	public void tick(AbstractContraptionEntity entity) {
		if (this.syncCooldown > 0) {
			this.syncCooldown--;
			return;
		}

		Map<BlockPos, MountedItemStorage> items = new HashMap<>();
		Map<BlockPos, MountedFluidStorage> fluids = new HashMap<>();
		this.syncedItems.forEach((pos, storage) -> {
			if (storage.isDirty()) {
				items.put(pos, (MountedItemStorage) storage);
				storage.markClean();
			}
		});
		this.syncedFluids.forEach((pos, storage) -> {
			if (storage.isDirty()) {
				fluids.put(pos, (MountedFluidStorage) storage);
				storage.markClean();
			}
		});

		if (!items.isEmpty() || !fluids.isEmpty()) {
			MountedStorageSyncPacket packet = new MountedStorageSyncPacket(entity.getId(), items, fluids);
			CatnipServices.NETWORK.sendToClientsTrackingEntity(entity, packet);
			this.syncCooldown = 8;
		}
	}
```

调用链（已核对）：`AbstractContraptionEntity.tick()`（`:368`）→ `:386` `contraption.tickStorage(this)` → `Contraption.tickStorage`（`Contraption.java:1502-1504`）→ `getStorage().tick(entity)`。
**这条链在客户端也走**（`AbstractContraptionEntity.tick` 没有 side 判断），`ElevatorContraption.tickStorage`（`:58-77`）在 `super.tickStorage` 之后才做客户端不友好的事。

## 2. 为什么值得改

1. **无条件构造 2 个 `HashMap`**：只要 `syncCooldown == 0` 就 new，即使 `syncedItems`/`syncedFluids` 里**一个脏 storage 都没有**（绝大多数 tick 都是这种情况）。
2. **冷却只挡 8 tick**：`syncCooldown = 8` 只在**真的发了包**时设置。所以稳态是「每 9 tick 里 1 次进入分配路径」，客户端列车队伍里每个车厢 contraption 各算一份：10 节车厢 → **每秒约 22 个 HashMap**（`(1000/9) × 2 × 10 / 1000 ≈ 2.2/秒/节`）。
3. **空 contraption 也要付钱**：`syncedItems` 为空时 `forEach` 是 no-op，但两个 map 已经建好了。
4. **客户端也执行**：客户端 `syncedItems` 由 `handleSync` 填充，通常较少但不为空；且客户端这条路径**永远发不出包**（`sendToClientsTrackingEntity` 在客户端是 no-op 实现），所以客户端的分配是**纯浪费**。

## 3. 改后完整方法体（可粘贴）

改法：**懒构造** —— 只有真的发现脏 storage 时才 new 对应 map。语义完全不变（`markClean` 仍在该 storage 被判脏时调用；包仍只在 `!items.isEmpty() || !fluids.isEmpty()` 时发）。

```java
	public void tick(AbstractContraptionEntity entity) {
		if (this.syncCooldown > 0) {
			this.syncCooldown--;
			return;
		}

		// 懒构造：命中 0 个脏 storage 时（绝大多数 tick）不再无条件 new 两个 HashMap。
		Map<BlockPos, MountedItemStorage> items = null;
		Map<BlockPos, MountedFluidStorage> fluids = null;

		this.syncedItems.forEach((pos, storage) -> {
			if (storage.isDirty()) {
				items.put(pos, (MountedItemStorage) storage);   // 见下方注：需要能写入，故用局部技巧
				storage.markClean();
			}
		});
		this.syncedFluids.forEach((pos, storage) -> {
			if (storage.isDirty()) {
				fluids.put(pos, (MountedFluidStorage) storage);
				storage.markClean();
			}
		});

		if (items == null && fluids == null)
			return;

		MountedStorageSyncPacket packet = new MountedStorageSyncPacket(entity.getId(),
			items == null ? Collections.emptyMap() : items,
			fluids == null ? Collections.emptyMap() : fluids);
		CatnipServices.NETWORK.sendToClientsTrackingEntity(entity, packet);
		this.syncCooldown = 8;
	}
```

> ⚠️ 上面的 lambda 里写 `items.put` 需要 `items` 是有效 final 且**非 null**。Java 里不能在 lambda 内重新赋值局部变量，所以「懒构造 + lambda」必须换成下面两种写法之一：

### 3.1 写法 A：显式 for 循环 + 局部变量（推荐，最直白）

```java
	public void tick(AbstractContraptionEntity entity) {
		if (this.syncCooldown > 0) {
			this.syncCooldown--;
			return;
		}

		Map<BlockPos, MountedItemStorage> items = null;
		Map<BlockPos, MountedFluidStorage> fluids = null;

		for (Map.Entry<BlockPos, SyncedMountedStorage> e : this.syncedItems.entrySet()) {
			SyncedMountedStorage storage = e.getValue();
			if (!storage.isDirty())
				continue;
			if (items == null)
				items = new HashMap<>();
			items.put(e.getKey(), (MountedItemStorage) storage);
			storage.markClean();
		}
		for (Map.Entry<BlockPos, SyncedMountedStorage> e : this.syncedFluids.entrySet()) {
			SyncedMountedStorage storage = e.getValue();
			if (!storage.isDirty())
				continue;
			if (fluids == null)
				fluids = new HashMap<>();
			fluids.put(e.getKey(), (MountedFluidStorage) storage);
			storage.markClean();
		}

		if (items == null && fluids == null)
			return;

		MountedStorageSyncPacket packet = new MountedStorageSyncPacket(entity.getId(),
			items == null ? Collections.emptyMap() : items,
			fluids == null ? Collections.emptyMap() : fluids);
		CatnipServices.NETWORK.sendToClientsTrackingEntity(entity, packet);
		this.syncCooldown = 8;
	}
```

### 3.2 写法 B：per-instance 复用 map（少一次分配，但要注意下游是否保留引用）

```java
	// @Unique 字段（放在 MountedStorageManagerMixin 里）
	@Unique private Map<BlockPos, MountedItemStorage> createperf$items = new HashMap<>();
	@Unique private Map<BlockPos, MountedFluidStorage> createperf$fluids = new HashMap<>();

	public void tick(AbstractContraptionEntity entity) {
		if (this.syncCooldown > 0) {
			this.syncCooldown--;
			return;
		}

		Map<BlockPos, MountedItemStorage> items = createperf$items;
		Map<BlockPos, MountedFluidStorage> fluids = createperf$fluids;
		items.clear();
		fluids.clear();

		this.syncedItems.forEach((pos, storage) -> {
			if (storage.isDirty()) {
				items.put(pos, (MountedItemStorage) storage);
				storage.markClean();
			}
		});
		this.syncedFluids.forEach((pos, storage) -> {
			if (storage.isDirty()) {
				fluids.put(pos, (MountedFluidStorage) storage);
				storage.markClean();
			}
		});

		if (items.isEmpty() && fluids.isEmpty())
			return;

		MountedStorageSyncPacket packet = new MountedStorageSyncPacket(entity.getId(), items, fluids);
		CatnipServices.NETWORK.sendToClientsTrackingEntity(entity, packet);
		this.syncCooldown = 8;
	}
```

> ⚠️ 写法 B 的前提：`MountedStorageSyncPacket` 不能长期保留这两个 map（网络层在写入 buffer 后即丢弃，但**需实测确认**；`MountedStorageSyncPacket` 是 record，序列化时只读）。**默认推荐写法 A** —— 它把「分配是否发生」与「是否有脏数据」绑死，风险最低。

## 4. Mixin 类骨架

### 4.1 推荐：@Inject 短路（不改变方法体其余部分）

按手册 §3.3，这类「方法体内独立分配」的最稳注入是 HEAD 短路。但本方法无法用「缓存命中」短路（每次都可能变化），
所以要么 `@Overwrite`（第 3 节写法 A），要么对**两个 `new HashMap`** 做 `@ModifyExpressionValue`。

```java
@Mixin(value = MountedStorageManager.class, remap = false)
public abstract class MountedStorageManagerMixin {

	// 让两个 new HashMap<>() 变成「按需构造」：先返回空共享实例，第一次 put 时再换真的。
	// 注意手册 §3.2：@ModifyExpressionValue 修饰 new HashMap<>() 会 checkcast HashMap，
	// 所以返回的必须是 HashMap 实例（不能是 Collections.emptyMap()）。
	// 因此这条路径要用「共享空 HashMap + 首次 put 时替换」的包装类，复杂且易错 —— 不推荐。
	// 直接 @Overwrite 更清晰：
	@Overwrite
	public void tick(AbstractContraptionEntity entity) {
		/* 见第 3.1 节 */
	}
}
```

**更小的侵入方案（推荐落地）**：只把「两个 `new HashMap`」换成**两个 static final 共享空 map** 不行（会被写入），所以真正的「最小侵入」是：

```java
	@Inject(method = "tick(Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;)V",
		at = @At("HEAD"), cancellable = true)
	private void createperf$skipClientSide(AbstractContraptionEntity entity, CallbackInfo ci) {
		// 客户端这条路径永远发不出包（sendToClientsTrackingEntity 在客户端 no-op），
		// 却仍然走完整个 forEach + 分配。直接跳过即可。
		if (entity.level().isClientSide)
			ci.cancel();
	}
```

> ⚠️ 但要注意：`handleSync` 会 `reset()` 重建 manager，客户端 `syncCooldown` 状态本身不影响客户端行为；
> 跳过客户端 `tick` 的唯一副作用是**客户端的 `syncCooldown` 不再递减**（本来也只用于节流发包）。
> 这是**行为变更**，必须在文档与测试里显式声明。若要严格零行为变更，用第 3.1 节的 `@Overwrite`。

描述符：`(Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;)V`。

## 5. 风险

| 风险 | 说明 |
| --- | --- |
| 客户端跳过（4.1 方案 B） | 改变客户端 `syncCooldown` 的递进状态。它只在 `tick` 内部使用，不参与同步，属于低风险但**是行为变更**。 |
| 写法 B 的 map 复用 | 若下游（网络层/packet）保留引用，下一次 `clear()` 会污染。需实测。 |
| 空 map 传参 | 写法 A 里 `Collections.emptyMap()` 代替原来的「空 HashMap」，`MountedStorageSyncPacket` 的 `StreamCodec.composite`（`MountedStorageSyncPacket.java:20-25`）只做遍历编码，接受任意 `Map` 实现。 |
| 上游重构 | 方法签名变动 → 启动报错。 |
| 与 Create 自带 mixin | 无重叠。 |

## 6. 回归测试点

1. **箱子同步**：在移动装置上放一个箱子，从里面取/放物品，确认**客户端**能立刻看到内容变化（这是 `MountedStorageSyncPacket` 的唯一用途）。
2. **多 storage**：一个装置上放 4 个箱子 + 2 个流体罐，同时操作，确认 4 个箱子的变化都同步（不会因为懒构造而漏发）。
3. **冷却节流**：连续快速取放物品，确认包不会被每 tick 狂发（观察 `syncCooldown = 8` 仍生效 —— 用客户端日志或 NeoForge 网络调试）。
4. **列车车厢**：坐进列车车厢，操作车厢里的箱子，确认同步正常（车厢是 `MountedStorageManager` 的另一个高密度使用者）。
5. **空装置**：造一个没有任何 storage 的装置，跑 5 分钟；改动前应有稳定 GC 压力，改动后应几乎为零（这是收益的直接观测点）。
6. **电梯**：坐电梯上下，确认 `ElevatorContraption.tickStorage` 在 `super.tickStorage` 之后的行为不受影响（名称列表同步）。

## 7. 收益

**估计**：稳态下每个 contraption 每 9 tick 省 2 个 `HashMap`（当无脏数据时；有脏数据时仍需 1~2 个）。10 节车厢列车 + 若干独立装置时，量级为**每秒数十个对象**（估计值，需 Spark `--alloc` 实测）。客户端跳过方案还能额外省掉客户端整段 `forEach`。
