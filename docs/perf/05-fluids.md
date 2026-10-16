# 流体 性能问题

> 覆盖范围：`com.simibubi.create.content.fluids.**`（`PumpBlockEntity`、`FluidPropagator`、`FluidTransportBehaviour`、
> `PipeConnection`、`FluidNetwork`、`tank/FluidTankBlockEntity`、`transfer/FluidManipulationBehaviour`）+ `foundation/fluid/SmartFluidTank`
> 调查依据：Create 6.0.10 / MC 1.21.1 源码逐行核对；行号以本仓库为准
> 公共约定见 [01-mixin-playbook.md](01-mixin-playbook.md)
>
> 本文所有代码均为**静态推演的结果，未在开发环境编译验证**。落地时必须用
> `-Dmixin.debug.verbose=true -Dmixin.debug.export=true` 导出核对（见手册 §6）。所有收益数字都是**估计**。

## 摘要

- **F1 [High]** 三处 BFS 用 `ArrayList.remove(0)` 出队 → 每次 O(n) 移位、整体 O(n²)。其中最贵的是
  `FluidManipulationBehaviour.search`：**每 tick、每个软管滑轮最多出队 1024 个元素**，在
  `hosePulleyBlockThreshold = 10000`（`CFluids.java:15`）的规模下，每 tick 的引用搬移量在 10^6 量级；另两处（泵压力重算）只在转速变化时触发。
- **F1 附带**：`PumpBlockEntity`/`FluidPropagator` 的队列元素是 `Pair<Integer, BlockPos>`，每次入队都 `Integer` 装箱。
- **F2 [High]** `FluidTankBlockEntity.onFluidStackChanged` 每次流体变化遍历**整个罐体体积**，并对**每一格**调用
  `level.updateNeighbourForOutputSignal`（3×3×3 罐体 = 27 格 × 6 方向 = 162 次 `neighborChanged`/次），
  而活跃输送期间 `FluidNetwork` 每 tick 都会 `fill` 一次。
- **F3 [High→实际 Medium]** 压力同步是「每次 `addPressure` 一次 `sendData()`」，且压力被编码成 `ListTag + 2 个 FloatTag`；
  但 `sendData()` 只是 `ServerChunkCache.blockChanged`（**延迟的脏标记，不是立刻发包**），且 `distributePressureTo` 只在泵转速变化时运行。**改 NBT 编码会破坏与未装本 mod 的服务端/客户端的协议兼容** → 主体归入「不可 Mixin」。
- **F4 [High→实际 Low-Medium]** `FluidTransportBehaviour.tick` 每连接每 tick 新建捕获 lambda（最多 6/管道/tick），
  并在 lambda 内重复调用 `blockEntity.getBlockState()`（一次 level 查询）。
- **F5 [Medium]** `FluidNetwork.tick` 每 tick 分配 1 个 `IdentityHashMap`（还有 `getOrDefault` 的装箱）+ 最多 2 个 `ArrayList` 防御拷贝；
  真正的大头是 `:116` 的 `new BlockFace`（每 tick 最多 `16 × frontier × 6` 次）。

---

### F1. 三处 BFS 用 `ArrayList.remove(0)` 出队（O(n²)），并伴随 `Integer` 装箱 — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | ① `src/main/java/com/simibubi/create/content/fluids/transfer/FluidManipulationBehaviour.java:159`（`frontier.remove(0)`，方法 `:149-205`）<br>② `src/main/java/com/simibubi/create/content/fluids/pump/PumpBlockEntity.java:146`（方法 `:119-228`，`ArrayList` 建于 `:140`）<br>③ `src/main/java/com/simibubi/create/content/fluids/FluidPropagator.java:58` 与 `:106`（方法 `:49-98` / `:100-135`，`ArrayList` 建于 `:50` / `:101`） |
| 调用频次 | ① **每 tick**：`FluidDrainingBehaviour.tick()`（`FluidDrainingBehaviour.java:243-245`）只要 `frontier` 非空就调 `continueSearch()` → `search(...)`（`:278`）；上限 `searchedPerTick = 1024`（`:54`）。每个软管滑轮各一份 frontier。<br>②③ **每次压力重算**：`PumpBlockEntity.onSpeedChanged` → `updatePressureChange()`（`:99-110`）→ `tick()`（`:77-82`）→ `distributePressureTo`；只在泵转速**绝对值变化**时触发，不是每 tick。 |
| 复杂度 / 成本 | `ArrayList.remove(0)` = `System.arraycopy(elementData, 1, elementData, 0, --size)`，单次 O(n)。① 每 tick 出队 ≤1024 次，若 frontier 有 n 个元素，单 tick 搬移约 `1024 × n/2` 个引用；n = 10000 时约 5×10^6 次引用搬移。②③ 整体 O(n²)，但 n 受 `mechanicalPumpRange = 16`（`CFluids.java:12`）限制，量级远小于 ①。<br>装箱：②③ 的元素是 `Pair<Integer, BlockPos>`（`PumpBlockEntity.java:140`），每次入队一次 `Integer.valueOf`（`distance + 1`）、每次出队一次拆箱；① 用的是 record `BlockPosEntry`（`:40`）**没有**装箱。 |
| 注入方式 | `@Overwrite`（三处的 frontier 都是**局部变量**或**方法参数**，无法用 `@ModifyExpressionValue`/`@Redirect` 安全替换容器类型；详见各 overwrite 文档 §0） |
| 目标成员 | `com.simibubi.create.content.fluids.transfer.FluidManipulationBehaviour#search(Lnet/minecraft/world/level/material/Fluid;Ljava/util/List;Ljava/util/Set;Ljava/util/function/BiConsumer;Z)Lnet/minecraft/world/level/material/Fluid;`<br>`com.simibubi.create.content.fluids.pump.PumpBlockEntity#distributePressureTo(Lnet/minecraft/core/Direction;)V`<br>`com.simibubi.create.content.fluids.FluidPropagator#propagateChangedPipe(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V`<br>`com.simibubi.create.content.fluids.FluidPropagator#resetAffectedFluidNetworks(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)V` |
| 需新增状态 | ① 一个 `@Unique` 字段 `ArrayDeque<BlockPosEntry>`（per-instance，作用域=该行为实例，用完即清，无失效问题，主线程独占）。②③ **无需新增字段**（全部局部变量）；另需一个普通 record 类 `CreateperfPosDist(BlockPos, int)`。 |
| 风险 | ① 高（必须把剩余 frontier 按序写回，且要覆盖 `ChunkNotLoadedException` 的抛出路径）；②③ 低-中（纯 BFS，无外部状态） |
| 脆弱度 | ① 依赖 `searchedPerTick`（`static final` 常量，无法 `@Shadow`，只能硬编码 1024）、`rootPos`（包私有）、`maxBlocks/maxRange/canDrainInfinitely` 与 `getWorld()` 的签名；调用方 `FluidDrainingBehaviour` 用 `!frontier.isEmpty()` 判断搜索是否结束（`:63/:243/:301/:359`），任何「剩余元素丢失」都是静默功能 bug。<br>②③ 依赖 `hasReachedValidEndpoint`（private）、`isFront`/`searchForEndpointRecursively`（protected）、`PumpFluidTransferBehaviour` 类型名。 |
| 置信度 | 复杂度与分配量：**确定**（源码逐行核对）。收益百分比：**需实测**。 |

**改前**

```java
// FluidManipulationBehaviour.java:149-205 —— 每 tick 热路径（最多出队 1024 个）
	for (i = 0; i < searchedPerTick && !frontier.isEmpty()
		&& (visited.size() <= maxBlocks || !canDrainInfinitely(fluid)); i++) {
		BlockPosEntry entry = frontier.remove(0);                     // :159  ← O(n) 移位
		...
		frontier.add(new BlockPosEntry(offsetPos, entry.distance + 1)); // :200
	}

// PumpBlockEntity.java:140-146 —— 只在泵转速变化时
	List<Pair<Integer, BlockPos>> frontier = new ArrayList<>();        // :140
	frontier.add(Pair.of(1, start.getConnectedPos()));                 // :143  Integer 装箱
	while (!frontier.isEmpty()) {
		Pair<Integer, BlockPos> entry = frontier.remove(0);            // :146  O(n) 移位 + 拆箱
		...
		frontier.add(Pair.of(distance + 1, connectedPos));             // :197  又一次装箱
	}

// FluidPropagator.java:50-58（同一 BFS 形态）与 :101-106（List<BlockPos> 版本）
	List<Pair<Integer, BlockPos>> frontier = new ArrayList<>();
	while (!frontier.isEmpty()) {
		Pair<Integer, BlockPos> pair = frontier.remove(0);              // :58
	}
	List<BlockPos> frontier = new ArrayList<>();
	while (!frontier.isEmpty()) {
		BlockPos pos = frontier.remove(0);                             // :106
	}
```

**为什么慢**

- `ArrayList.remove(0)` 的实现是 `System.arraycopy` 把后面 `n-1` 个引用整体前移。单次 O(n)，在「边出队边入队」的 BFS 里构成 O(n²)。
- ① 的规模最大：`searchedPerTick = 1024`（`FluidManipulationBehaviour.java:54`）意味着**每 tick 每个软管滑轮**都要出队 1024 次；
  `hosePulleyBlockThreshold` 默认 10000 → frontier 可以长期停在数千到上万。这个循环每 tick 都跑，是真正的 tick 级热路径。
  另外 `frontier` 是**跨 tick 保留**的字段（`:56`），所以 n 不会每次归零，O(n²) 会持续整个抽干过程。
- ②③ 是「一次性压力重算」，n 受 `mechanicalPumpRange = 16` 限制，但一个密集的分支网络（例如 16 格半径内的管道迷宫）仍可让 n 达到数百到数千；
  由于每次转速变化都会重算，在「齿轮频繁启停/离合器」场景下会反复触发。
- 装箱：②③ 每入队一个元素就 `Integer.valueOf(distance + 1)`。`distance` 通常 < 128 落在 Integer 缓存内，**分配不是问题**，
  真正的问题是 `Pair` 对象本身（每元素 1 个对象 + 1 个数组槽）与拆箱分支；① 用 record `BlockPosEntry` 免了装箱，但每扩展一个邻居仍分配 1 个 record（最多 6/出队）。

**改后**

核心改动就是把「`List` + `remove(0)`」换成「`ArrayDeque` + `addLast`/`pollFirst`」，两者都是 FIFO，遍历顺序逐元素等价。
纯 Java 形式：

```java
// ① FluidManipulationBehaviour.search —— frontier 是方法参数且被调用方当判据，必须写回
ArrayDeque<BlockPosEntry> queue = createperf$queue;
queue.clear();
if (!frontier.isEmpty()) {
	queue.addAll(frontier);
	frontier.clear();
}
try {
	for (int i = 0; i < 1024 && !queue.isEmpty() && (visited.size() <= maxBlocks || !canDrainInfinitely(fluid)); i++) {
		BlockPosEntry entry = queue.pollFirst();          // O(1)
		...
		queue.addLast(new BlockPosEntry(offsetPos, entry.distance + 1));
	}
} finally {
	if (!queue.isEmpty()) {                              // 覆盖 ChunkNotLoadedException 的两条抛出路径
		frontier.addAll(queue);
		queue.clear();
	}
}

// ② ③ —— 局部变量，直接用 ArrayDeque；坐标+距离用 record 去掉装箱
ArrayDeque<CreateperfPosDist> frontier = new ArrayDeque<>();
frontier.addLast(new CreateperfPosDist(start.getConnectedPos(), 1));
while (!frontier.isEmpty()) {
	CreateperfPosDist entry = frontier.pollFirst();      // O(1)
	int distance = entry.distance();                     // 无拆箱
	BlockPos currentPos = entry.pos();
	...
	frontier.addLast(new CreateperfPosDist(connectedPos, distance + 1));  // 无装箱
}
```

完整可粘贴方法体见：
- [overwrites/FluidManipulationBehaviour-search.md](overwrites/FluidManipulationBehaviour-search.md)
- [overwrites/PumpBlockEntity-distributePressureTo.md](overwrites/PumpBlockEntity-distributePressureTo.md)
- [overwrites/FluidPropagator-BFS.md](overwrites/FluidPropagator-BFS.md)（含 `CreateperfPosDist` record 与 `PipeConnectionAccessor`）

**Mixin 骨架**

```java
// ① 推荐用 @Inject(HEAD, cancellable) 而不是 @Overwrite（手册 §2 优先级 3 > 5）
@Mixin(value = com.simibubi.create.content.fluids.transfer.FluidManipulationBehaviour.class, remap = false)
public abstract class FluidManipulationBehaviourMixin {
	@Unique private final ArrayDeque<FluidManipulationBehaviour.BlockPosEntry> createperf$queue = new ArrayDeque<>();

	@Shadow BlockPos rootPos;
	@Shadow protected abstract Level getWorld();
	@Shadow protected abstract int maxBlocks();
	@Shadow protected abstract int maxRange();
	@Shadow protected abstract boolean canDrainInfinitely(Fluid fluid);

	@Inject(method = "search(Lnet/minecraft/world/level/material/Fluid;Ljava/util/List;Ljava/util/Set;Ljava/util/function/BiConsumer;Z)Lnet/minecraft/world/level/material/Fluid;",
			at = @At("HEAD"), cancellable = true, remap = false)
	private void createperf$fastSearch(Fluid fluid, List<FluidManipulationBehaviour.BlockPosEntry> frontier,
			Set<BlockPos> visited, BiConsumer<BlockPos, Integer> add, boolean searchDownward, CallbackInfo ci)
		throws FluidManipulationBehaviour.ChunkNotLoadedException {
		// ... 见 overwrites/FluidManipulationBehaviour-search.md §2.1，最后 ci.setReturnValue(fluid);
	}
}

// ②③ 纯静态/局部方法，直接 @Overwrite
@Mixin(value = com.simibubi.create.content.fluids.pump.PumpBlockEntity.class, remap = false)
public abstract class PumpBlockEntityMixin {
	@Shadow protected abstract boolean isFront(Direction side);
	@Shadow protected abstract boolean searchForEndpointRecursively(
		Map<BlockPos, Pair<Integer, Map<Direction, Boolean>>> pipeGraph, Set<BlockFace> targets,
		Map<Integer, Set<BlockFace>> validFaces, BlockFace currentFace, boolean pull);

	@Overwrite(remap = false)
	protected void distributePressureTo(Direction side) { /* 见 overwrite 文档 */ }
}

@Mixin(value = com.simibubi.create.content.fluids.FluidPropagator.class, remap = false)
public abstract class FluidPropagatorMixin {
	@Overwrite(remap = false)
	public static void propagateChangedPipe(LevelAccessor world, BlockPos pipePos, BlockState pipeState) { /* ... */ }

	@Overwrite(remap = false)
	public static void resetAffectedFluidNetworks(Level world, BlockPos start, Direction side) { /* ... */ }
}
```

**验证方法**

- **注入是否生效**：`-Dmixin.debug.verbose=true -Dmixin.debug.export=true`，检查 `run/.mixin.out/` 下
  `FluidManipulationBehaviour.class` / `PumpBlockEntity.class` / `FluidPropagator.class` 是否含你的 `createperf$` 成员。
- **探针**：在 `search` 里加 `@Unique static long` 计数器统计「出队次数」与 `System.nanoTime()` 累计耗时，每 100 tick 打日志；
  开/关 mixin 对比。重点看 frontier 较大（>1000）时的单 tick 耗时。
- **分配量**：JFR `ObjectAllocationSample` 或 `/spark profiler start --alloc`，确认 `java.lang.Integer`（来自 `Pair.of`）与
  `catnip` 的 `Pair`/`BlockPosEntry` 样本下降。
- **端到端**：同一存档（一个横跨 4 区块的大水池 + 软管滑轮 + 泵），`/spark profiler --timeout 60` + `/spark tps`，前后各 3 次取中位数。
- **正确性回归**：见每条 overwrite 文档的 §4；F1 的正确性风险远高于收益，**必须先跑功能回归再谈收益**。

**收益估计**（估计）

- ① 复杂度从 `1024 × n/2` 引用搬移降到 `1024` 次 O(1) 操作：n = 5000~10000 时，单 tick 从约 2.5×10^6~5×10^6 次引用搬移
  降到常数级；**单软管滑轮每 tick 量级：0.5~2 ms → 数十 µs**（估计，取决于 n 与 CPU）。
- ②③ 只是把 O(n²) 降为 O(n)，n 受 `mechanicalPumpRange` 限制，绝对收益小；**量级：每次压力重算省数百到数千次引用搬移 + 等量 `Integer` 装箱**。
- **注意**：①②③ 的价值主要在「世界规模放大时不让它变成瓶颈」，单机小基地几乎测不出差异。

**回归测试点**

见各 overwrite 文档 §4。汇总（必须全过）：
1. 软管滑轮抽干跨 4 区块的水池，中途卸载其中一个区块（触发 `ChunkNotLoadedException`）后继续抽干，不能漏搜/重复搜。
2. 达到 `hosePulleyBlockThreshold` 后的 infinite 模式行为不变。
3. 泵压力传播：`mechanicalPumpRange` = 1/16/64 三档 + 多分支网络，压力分配值与改前逐一比对。
4. `resetAffectedFluidNetworks` 的方向限制与 `flow.inbound` 过滤不变。
5. 存档/世界重载后 frontier 的断点续搜（`LastPos`/`Infinite` NBT）行为不变。

---

### F2. `FluidTankBlockEntity.onFluidStackChanged` 每次流体变化遍历整个罐体并逐格广播邻居 — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/fluids/tank/FluidTankBlockEntity.java:167-207`；关键行 `:177-194`（三重循环 + `:187` 的 `level.updateNeighbourForOutputSignal` 在 `:189` 的亮度比较**之前**）。`width`/`height` = 多块尺寸（`:58-59`，单块为 1×1×1）。回调注册在 `:96`。 |
| 调用频次 | `SmartFluidTank.onContentsChanged()` / `setFluid()` → `this::onFluidStackChanged`（`foundation/fluid/SmartFluidTank.java:17-26`，注册于 `FluidTankBlockEntity.java:96`）。活跃输送期间 `FluidNetwork.tick()` 的 EXECUTE 分支每 tick 对目标 handler 调一次 `fill`（`FluidNetwork.java:251`）→ **约每 tick 一次**。 |
| 复杂度 / 成本 | 每调用一次 = `width × height × width` 次（`ConnectivityHandler.partAt` = 一次 `level.getBlockEntity` + 类型检查，`ConnectivityHandler.java:376-382`）+ 同次数的 `level.updateNeighbourForOutputSignal(pos, block)`。3×3×3 = **27 次 blockEntity 查询 + 27×6 = 162 次 `neighborChanged`**（每次充放）。单块罐体退化为 1 次。 |
| 注入方式 | **首选** `@Inject(HEAD)` + `@WrapOperation`（不需 Overwrite）；**备选** `@Overwrite`（连 27 次 `partAt` 一起省掉） |
| 目标成员 | `com.simibubi.create.content.fluids.tank.FluidTankBlockEntity#onFluidStackChanged(Lnet/neoforged/neoforge/fluids/FluidStack;)V`；@WrapOperation 的目标调用点 `net.minecraft.world.level.Level#updateNeighbourForOutputSignal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V` |
| 需新增状态 | `@Unique int createperf$lastComparatorLevel`、`@Unique FluidType createperf$lastFluidType`、`@Unique boolean createperf$signalDirty`（per-instance BE，随 BE 回收；失效时机＝红石等级或流体类型变化时自动刷新；不跨线程） |
| 风险 | 中（需要缓存生命周期 + 一处行为变更声明；**不要**按原始 finding 把 `updateNeighbourForOutputSignal` 挪到亮度比较之后） |
| 脆弱度 | 依赖 `Level.updateNeighbourForOutputSignal(BlockPos, Block)` 的精确调用点与描述符；上游换写法 → `defaultRequire = 1` 启动即报错。`@Unique` 字段名与 `getFillState()`/`hasLevel()` 的公开签名无关（这两个都是 public）。 |
| 置信度 | 行号/调用链：**确定**。省下的 `neighborChanged` 次数：**确定**（27×6）。该项对 TPS 的实际贡献：**需实测**。 |

**改前**

```java
// FluidTankBlockEntity.java:167-207
	protected void onFluidStackChanged(FluidStack newFluidStack) {
		if (!hasLevel())
			return;

		FluidType attributes = newFluidStack.getFluid()
			.getFluidType();
		int luminosity = (int) (attributes.getLightLevel(newFluidStack) / 1.2f);
		boolean reversed = attributes.isLighterThanAir();
		int maxY = (int) ((getFillState() * height) + 1);

		for (int yOffset = 0; yOffset < height; yOffset++) {          // :177
			boolean isBright = reversed ? (height - yOffset <= maxY) : (yOffset < maxY);
			int actualLuminosity = isBright ? luminosity : luminosity > 0 ? 1 : 0;

			for (int xOffset = 0; xOffset < width; xOffset++) {
				for (int zOffset = 0; zOffset < width; zOffset++) {
					BlockPos pos = this.worldPosition.offset(xOffset, yOffset, zOffset);
					FluidTankBlockEntity tankAt = ConnectivityHandler.partAt(getType(), level, pos);
					if (tankAt == null)
						continue;
					level.updateNeighbourForOutputSignal(pos, tankAt.getBlockState()   // :187 每格一次
						.getBlock());
					if (tankAt.luminosity == actualLuminosity)                      // :189 亮度比较在广播之后
						continue;
					tankAt.setLuminosity(actualLuminosity);
				}
			}
		}

		if (!level.isClientSide) {
			setChanged();
			sendData();
		}
		... // isVirtual() 分支
	}
```

**为什么慢**

- **体积放大**：单位是 `width² × height`，不是常数。3×3×3 罐体每次充放要做 27 次 `level.getBlockEntity` 级别的查询
  和 **162 次 `Block.neighborChanged`**（每格 6 个方向）；`updateNeighbourForOutputSignal` 会先查 `getBlockState(pos)`
  再遍历 6 个方向调用 `level.neighborChanged(...)`，其中贴着比较器的方向还会触发比较器重新调度 tick。
- **频次**：活跃输送时每 tick 一次（见上表调用链）。一个基地里若并列 10 个 3×3×3 罐体持续充放，就是 1620 次 `neighborChanged`/tick。
- **对比原版**：原版单个容器（箱子/熔炉）流体/物品变化只对**自身**调一次 `updateNeighbourForOutputSignal`；
  Create 为了「任意一格外表面贴比较器都能读到 controller 的填充比例」（`FluidTankBlock.java:350-354`）而按体积放大。

> ⚠️ **原始 finding 的修法是错的**：把 `:187` 挪到 `:189` 之后会让比较器**不再随充放更新**，
> 因为比较器读的是 `fractionToRedstoneLevel(getFillState())`（**填充比例**），与 `luminosity` 无关。
> 同理，`maxY = (int)(getFillState()*height+1)` 的变化点（`f = k/height`）与红石等级的变化点（`f ≈ k/14`）**不重合**
> （例：`height=3`，`f: 0.3333→0.3334` 时 maxY 1→2，而红石等级都是 5），
> 所以任何跳过大循环的方案都必须同时跟踪 `maxY`（见 overwrite 文档 §2.2）。

**改后**

纯逻辑（不改任何行为的版本）：只在「**红石等级**或**流体类型**发生变化」时才广播邻居信号；
体积遍历与 `setLuminosity` 照常执行，因此光照语义**完全不变**：

```java
	// HEAD：判断这次是否需要广播
	int level = ComparatorUtil.fractionToRedstoneLevel(getFillState());
	boolean signalDirty = level != createperf$lastComparatorLevel || type != createperf$lastFluidType;
	if (signalDirty) {
		createperf$lastComparatorLevel = level;
		createperf$lastFluidType = type;
	}

	// 循环体里的那一行改成：
	if (signalDirty)
		level.updateNeighbourForOutputSignal(pos, tankAt.getBlockState().getBlock());
```

效果：红石等级未变时，27 次 `updateNeighbourForOutputSignal`（= 162 次 `neighborChanged`）全部省掉。
量级估算：3×3×3 罐体 27000 mB，红石等级 15 级 → 约 1800 mB/级；泵以 32 mB/tick 注入时**约 56 tick 才需要广播一次**
→ 该循环的广播开销降到约 1/56。

**Mixin 骨架**（首选形式；完整可粘贴见 overwrite 文档 §2.1）

```java
@Mixin(value = com.simibubi.create.content.fluids.tank.FluidTankBlockEntity.class, remap = false)
public abstract class FluidTankBlockEntityMixin {
	@Unique private int createperf$lastComparatorLevel = Integer.MIN_VALUE;
	@Unique private FluidType createperf$lastFluidType;
	@Unique private boolean createperf$signalDirty;

	@Inject(method = "onFluidStackChanged(Lnet/neoforged/neoforge/fluids/FluidStack;)V",
			at = @At("HEAD"), remap = false)
	private void createperf$decideSignalUpdate(FluidStack newFluidStack, CallbackInfo ci) {
		FluidTankBlockEntity self = (FluidTankBlockEntity) (Object) this;
		if (!self.hasLevel())
			return;
		FluidType type = newFluidStack.getFluid().getFluidType();
		int level = ComparatorUtil.fractionToRedstoneLevel(self.getFillState());
		createperf$signalDirty = level != createperf$lastComparatorLevel || type != createperf$lastFluidType;
		if (createperf$signalDirty) {
			createperf$lastComparatorLevel = level;
			createperf$lastFluidType = type;
		}
	}

	@WrapOperation(method = "onFluidStackChanged(Lnet/neoforged/neoforge/fluids/FluidStack;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/Level;updateNeighbourForOutputSignal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V"),
			remap = false)
	private void createperf$skipRedundantSignalUpdate(Level level, BlockPos pos, Block block, Operation<Void> original) {
		if (createperf$signalDirty)
			original.call(level, pos, block);
	}
}
```

**行为变更声明**：红石等级与流体类型都未变时，罐体不再向 6 邻居发送 `neighborChanged`。
对原版比较器无影响；对第三方方块若有「靠罐体流体变化触发 `neighborChanged`」的依赖会失效。必须写进 changelog。

**验证方法**

- **比较器**（第一验收项）：1×1×1 与 3×3×3 罐体各面贴比较器，用恒定速率泵注水/抽水，
  比较器输出必须逐级平滑变化；用 `/debug` 或 `/data get block <pos>` 对比前后。
  3×3×3 的**中下层外侧面**也要各贴一个（它们的信号都取自 controller）。
- **光照**：发光流体在 1/2/3 尺寸下注满、倒空，`getLightEmission`（`FluidTankBlock.java:115-123`）与客户端光照随填充高度变化；
  重点看 `f = 1/3、2/3` 边界（`maxY` 跳变但红石等级可能不变的一侧）。
- **探针**：`@Unique` 计数器统计每 tick 实际执行的 `updateNeighbourForOutputSignal` 次数，开/关 mixin 对比；
  预期开启后与红石等级变化次数同阶。
- **分配/调用采样**：`/spark profiler --timeout 60`，观察 `BlockState.updateNeighbourForOutputSignal`、
  `LevelChunk.blockChanged`、`Block.neighborChanged` 的占比。

**收益估计**（估计）

- 3×3×3 罐体：每次充放的 `neighborChanged` 从 162 次降到「红石等级变化时才 162 次」，
  活跃注水场景下**每 tick 的邻居通知次数降 1~2 个数量级**（取决于泵速率与罐体容量）。
- 1×1×1 罐体：只有 6 次 → 收益很小；**这不是给单罐体用的优化**。
- 该 finding 的价值随「罐体尺寸 × 持续充放的罐体数量」放大。

**回归测试点**

见 [overwrites/FluidTankBlockEntity-onFluidStackChanged.md](overwrites/FluidTankBlockEntity-onFluidStackChanged.md) §4。
关键三条：(1) 3×3×3 各面比较器逐级更新；(2) maxY 边界处的光照刷新；(3) `removeController` 成型/解体后的亮度与比较器。

---

### F3. 压力变更逐管道 `sendData()` + `ListTag/FloatTag` 压力编码 — [High→Medium]（**主体不可 Mixin**）

> **严重度修正**：原始 finding 标 High。逐行核对后：`sendData()` 不是「立刻发一个包」，而是
> `SyncedBlockEntity.sendData()` → `serverLevel.getChunkSource().blockChanged(getBlockPos())`（`SyncedBlockEntity.java:59-62`），
> 即**一个延迟的脏标记**（真正的包在区块广播阶段才组装）。加上 `distributePressureTo` 只在泵转速变化时运行，
> 所以「每根管道每个压力更新一次 sendData」的实际成本远低于 High。**可 Mixin 的只有一个小项**，
> 主体（NBT 编码格式）改了就破坏协议兼容 → 归入文末「不可 Mixin / 建议提给上游」。

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/fluids/PipeConnection.java:212-236`（`serializeNBT`，压力 `:216-221`，`FluidStack.saveOptional` `:229`）<br>`src/main/java/com/simibubi/create/content/fluids/FluidTransportBehaviour.java:206-213`（`addPressure`，`blockEntity.sendData()` 在 `:212`）<br>反序列化 `PipeConnection.java:242-249`（要求 `Tag.TAG_FLOAT` 的 2 元素 `ListTag`）<br>调用方 `PumpBlockEntity.java:224` |
| 调用频次 | `addPressure` 的唯一调用点是 `PumpBlockEntity.distributePressureTo`（`:224`）→ 每次**压力重算**对 `validFaces` 里每个 `BlockFace` 调一次。序列化发生在 `FluidTransportBehaviour.write`（`:165-174`）里，对 `interfaces` 的每个连接调一次 `serializeNBT`（最多 6 面/管道），由 `sendData()` 标脏后在区块广播时触发。 |
| 复杂度 / 成本 | 每次 `addPressure`：1 次 `Couple.mapWithContext`（**高概率**新建 `Couple` + 2 个 `Float` 装箱，catnip 源码不在本仓库，需实测）+ 1 次 `sendData()`（chunk 查询 + 标脏）。<br>每次 `serializeNBT`（有压力时）：1 个 `CompoundTag` + 1 个 `ListTag`（内部 `ArrayList` 扩容）+ 2 个 `FloatTag`；有流动时再加 `FluidStack.saveOptional`。6 面 = 最多 6×(2+4) 个对象。 |
| 注入方式 | 压力 NBT 编码：**不可 Mixin**（见文末）。可 Mixin 的部分：`@Inject(HEAD, cancellable)` 短路 `FluidTransportBehaviour.addPressure` 里的 `sendData()`（低收益，不推荐）；或 `@Inject(HEAD)` 在 `FluidTransportBehaviour.write` 里跳过无变化的连接（风险高，不建议） |
| 目标成员 | `com.simibubi.create.content.fluids.PipeConnection#serializeNBT(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V`；`com.simibubi.create.content.fluids.FluidTransportBehaviour#addPressure(Lnet/minecraft/core/Direction;ZF)V`；`com.simibubi.create.content.fluids.FluidTransportBehaviour#write(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V` |
| 需新增状态 | 若做 `write` 跳过：需要「上次同步的压力值」缓存（per-connection），失效时机＝`sendData` 被调用或数值变化 → **生命周期复杂**，是这条 finding 里最危险的方向 |
| 风险 | 高（协议兼容 + 缓存生命周期） |
| 脆弱度 | 依赖 `wire format`：`"Pressure"` 必须是 `ListTag(TAG_FLOAT)` 且恰好 2 个元素，否则未装本 mod 的客户端/服务端 `getList("Pressure", Tag.TAG_FLOAT)` 会读到空列表 → 压力归零（**静默行为错误**，不报错） |
| 置信度 | 行号与调用链：**确定**。`Couple.mapWithContext` 是否真的分配：**高概率（需实测）**。收益：**需实测** |

**改前**

```java
// FluidTransportBehaviour.java:206-213 —— 唯一调用点 PumpBlockEntity.java:224
	public void addPressure(Direction side, boolean inbound, float pressure) {
		createConnectionData();
		if (!interfaces.containsKey(side))
			return;
		interfaces.get(side)
			.addPressure(inbound, pressure);
		blockEntity.sendData();          // :212  每个连接一次（实际只是 ServerChunkCache.blockChanged 标脏）
	}

// PipeConnection.java:310-312
	public void addPressure(boolean inbound, float pressure) {
		this.pressure = this.pressure.mapWithContext((f, in) -> in == inbound ? f + pressure : f);
	}

// PipeConnection.java:212-236
	public void serializeNBT(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		CompoundTag connectionData = new CompoundTag();
		tag.put(side.getName(), connectionData);

		if (hasPressure()) {
			ListTag pressureData = new ListTag();                     // :217
			pressureData.add(FloatTag.valueOf(getInboundPressure()));  // :218
			pressureData.add(FloatTag.valueOf(getOutwardPressure()));  // :219
			connectionData.put("Pressure", pressureData);
		}
		if (hasOpenEnd())
			connectionData.put("OpenEnd", ((OpenEndedPipe) source.get()).serializeNBT(registries));
		if (hasFlow()) {
			CompoundTag flowData = new CompoundTag();
			Flow flow = this.flow.get();
			flowData.put("Fluid", flow.fluid.saveOptional(registries));   // :229
			flowData.putBoolean("In", flow.inbound);
			if (!flow.complete)
				flowData.put("Progress", flow.progress.writeNBT());
			connectionData.put("Flow", flowData);
		}
	}

// PipeConnection.java:242-249（对端读取，格式被硬编码）
	public void deserializeNBT(CompoundTag tag, HolderLookup.Provider registries, BlockPos blockEntityPos, boolean clientPacket) {
		CompoundTag connectionData = tag.getCompound(side.getName());
		if (connectionData.contains("Pressure")) {
			ListTag pressureData = connectionData.getList("Pressure", Tag.TAG_FLOAT);
			pressure = Couple.create(pressureData.getFloat(0), pressureData.getFloat(1));
		} else
			pressure.replace(f -> 0f);
		...
```

**为什么慢**

- 频次并不高：`addPressure` 只在压力重算时被调用，而 `sendData()` 本身只是一个标脏操作
  （`ServerChunkCache.blockChanged` → 该 BE 的区块被标记为「有 BE 变化」，真正的 `ClientboundBlockEntityDataPacket`
  在区块广播阶段统一组装）。所以「每根管道 sendData」这一条**不是**热点。
- 真正的成本在「一次标脏 → 一次完整 `write`」：`FluidTransportBehaviour.write`（`:165-174`）会把**所有 6 个连接**
  全量序列化一遍，包括 `OpenEndedPipe.serializeNBT` 与 `FluidStack.saveOptional`（后者在有流动时每次都要写完整流体栈）。
- `ListTag + 2×FloatTag` 的编码本身很浪费（一个 8 字节的浮点信息被编码成 2 个 `Tag` 对象 + 一个列表），
  但**这是协议的一部分**：`deserializeNBT` 用 `getList("Pressure", Tag.TAG_FLOAT)` + `getFloat(0/1)` 读取，
  换成 `IntArrayTag`（float 位）后，任何未装本 mod 的客户端或服务端都会把压力读成 0 → 流体网络静默失效。

**改后**

- **可 Mixin 的部分（低收益、可选）**：如果只是不想让每根管道各调一次 `sendData()`，
  可以在 `PumpBlockEntity.distributePressureTo` 的末尾统一 `sendData()`；但 `sendData()` 是幂等的标脏操作，
  收益接近 0，**不建议为此增加 Mixin**。
- **不可 Mixin 的部分**：压力编码格式。若确实要改（例如换成 2 个 `FloatTag` 之外的紧凑表示），
  必须**同时**改 `serializeNBT`/`deserializeNBT` 并升协议版本、要求两端都装本 mod → 属于上游改动，见文末。

```java
// 唯一"安全"的纯 Java 改法：保持 wire format 完全不变，只减少标签对象
	// 无。ListTag + 2 FloatTag 是协议要求的最小结构，
	// 且 CompoundTag 会被写进网络缓冲，无法复用同一个 ListTag 实例（会被后续写入覆盖）。
```

**Mixin 骨架**

**无**（本 finding 不建议产出 Mixin）。若只想去掉 `addPressure` 里的 `sendData()`：

```java
@Mixin(value = com.simibubi.create.content.fluids.FluidTransportBehaviour.class, remap = false)
public abstract class FluidTransportBehaviourMixin {
	// 不推荐：sendData() 是幂等标脏，去掉它只是"少一次脏标记"，且会改变
	// 「压力变化后多久同步到客户端」的时序 → 属于行为变更。
	@Inject(method = "addPressure(Lnet/minecraft/core/Direction;ZF)V", at = @At("HEAD"), cancellable = true, remap = false)
	private void createperf$noop(CallbackInfo ci) { /* 不要这样做 */ }
}
```

**验证方法**

- 若尝试任何同步相关改动：**双端一致性测试**。
  一端装 mod、一端不装，检查压力值在两端的表现（`F3` 只读调试、管道流动可视化、`PipeConnection` 的粒子）。
  注意「格式不兼容」不会抛异常，只会静默把压力读成 0 —— 必须靠**行为观察**（流体不流动）才能发现。
- 序列化量：`/spark profiler --alloc` 观察 `net.minecraft.nbt.ListTag` / `FloatTag` 的分配样本。

**收益估计**（估计）

- 可 Mixin 部分（去掉每连接的 `sendData`）：**量级 = 每次压力重算省下与管道数同阶的 `blockChanged` 调用**，
  每次 `blockChanged` 只是 chunk 查询 + 标脏 → 总收益很小（相对 F1/F2 可忽略）。
- 不可 Mixin 部分（改编码）：能把每次同步的 NBT 对象数从 6×(2~6) 降到 6×(1~2)，
  但代价是协议不兼容 ⇒ 不建议在本 mod 内做。

**回归测试点**

1. 泵驱动流体网络时，客户端的管道流动动画/粒子正常（这依赖压力同步）。
2. 存档读回后压力值正确（`deserializeNBT` 能读到 2 个 float）。
3. `sendData()` 的调用次数**不要**减少到「压力变化不同步」的程度：离合器切换泵转速后，
   客户端应在 1 个同步周期内看到压力方向变化。

---

### F4. `FluidTransportBehaviour.tick` 每连接每 tick 新建捕获 lambda 并重复查 `getBlockState()` — [High→Low-Medium]

> **严重度修正**：原始 finding 标 High。核对后：lambda 数量是「每 tick 每管道 ≤6 个」，
> 单个 lambda 对象 16~24 字节，1000 根管道 = 约 6000 个对象/tick ≈ 144 KB/tick ≈ 2.9 MB/s 的年轻代垃圾
> —— 现代 GC 完全能吸收，**分配本身不是热点**。真正值得修的是 lambda 体内每次调用
> `blockEntity.getBlockState()`（一次 level 区块调色板查询），它在一次 `manageFlows` 里会被调用
> `1 + getTanks()` 次（`FlowSource.provideFluid`，`FlowSource.java:40-53`）。

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/fluids/FluidTransportBehaviour.java:130-135`（lambda 建于 `:132-133`），所在方法 `:62-143` |
| 调用频次 | `FluidTransportBehaviour.tick()` 每 tick 每管道 BE 一次；内层 `for (PipeConnection connection : connections)`（`:130`）最多 6 个连接（`interfaces` 是 6 面的 `IdentityHashMap`）→ **≤6 个 lambda / 管道 / tick**，每个 lambda 体内的 `getBlockState()` 调用 `1 + getTanks()` 次 |
| 复杂度 / 成本 | lambda：每次 1 个对象（16~24 B）+ 捕获 `connection` 与 `blockEntity`。`getBlockState()`：`BlockEntity.getBlockState()` = `level.getBlockState(worldPosition)`（区块调色板查询，非平凡） |
| 注入方式 | `@Inject(HEAD)`（在 `tick` 头部缓存 BlockState）+ `@WrapOperation`（打在**合成 lambda 方法**里替换 `getBlockState()`）。lambda 对象本身只能靠 `@Overwrite tick()` 消除，**不建议**（见下） |
| 目标成员 | `com.simibubi.create.content.fluids.FluidTransportBehaviour#tick()V`；被替换的调用点 `net.minecraft.world.level.block.entity.BlockEntity#getBlockState()Lnet/minecraft/world/level/block/state/BlockState;`（位于合成方法 `lambda$tick$0` 内） |
| 需新增状态 | `@Unique BlockState createperf$cachedState`（per-behaviour，每 tick 在 HEAD 刷新；不跨 tick，无失效问题，主线程） |
| 风险 | 中（依赖**编译器生成的合成 lambda 方法名**——这是本 finding 最脆的地方） |
| 脆弱度 | **高**。`lambda$tick$0` 的名字、序号与描述符由 javac 生成；上游在 `tick()` 里增删任何一个 lambda，序号就会移动 → `defaultRequire = 1` 会让它**启动即报错**（好过静默失效，但需要每次升级 Create 都重新核对）。若上游把 lambda 内联成普通代码块，注入点彻底消失。 |
| 置信度 | 行号/次数：**确定**。`getBlockState()` 的实际开销占比：**需实测**。合成 lambda 方法名的精确形态：**需实测**（必须用 `mixin.debug.export` 导出确认） |

**改前**

```java
// FluidTransportBehaviour.java:129-139（tick 内）
			boolean sendUpdate = false;
			for (PipeConnection connection : connections) {
				FluidStack internalFluid = singleSource != connection ? availableFlow : FluidStack.EMPTY;
				Predicate<FluidStack> extractionPredicate =
					extracted -> canPullFluidFrom(extracted, blockEntity.getBlockState(), connection.side);  // :132-133
				sendUpdate |= connection.manageFlows(world, pos, internalFluid, extractionPredicate);
			}
```

**为什么慢**

- 每个连接新建一个捕获（`connection`、`blockEntity`）的 `Predicate` 对象。这个对象**没有别的办法复用**：
  MixinExtras 的 `@ModifyArg` 只能替换**实参**，而 lambda 对象在 `invokedynamic` 处已经创建 → 与手册 §3.1 的陷阱同构。
- lambda 体内 `blockEntity.getBlockState()` 每次被求值都会走 `level.getBlockState(worldPosition)`；
  `FlowSource.provideFluid`（`FlowSource.java:33-56`）会调用 `extractionPredicate` 1 次（`drain(1, SIMULATE)` 之后）+
  每个非空 tank 各 1 次 → 一次 `manageFlows` 里可能 2~5 次同样的查询。
- 同一次 `tick()` 内 `blockEntity.getBlockState()` 的结果**必然相同**（tick 内方块状态不会变），所以重复查询纯属浪费。

**改后**

```java
// 纯 Java 形态：把 state 提到循环外，lambda 只捕获它
			BlockState cachedState = blockEntity.getBlockState();          // 每次 tick 一次
			boolean sendUpdate = false;
			for (PipeConnection connection : connections) {
				FluidStack internalFluid = singleSource != connection ? availableFlow : FluidStack.EMPTY;
				Predicate<FluidStack> extractionPredicate =
					extracted -> canPullFluidFrom(extracted, cachedState, connection.side);
				sendUpdate |= connection.manageFlows(world, pos, internalFluid, extractionPredicate);
			}
```

Mixin 无法改写局部变量 `extractionPredicate` 的那一行（它是 `invokedynamic`），
所以采取「**HEAD 缓存 + 在合成 lambda 里拦截 `getBlockState()`**」的组合：
lambda 仍然分配（可接受），但每次调用都返回缓存值，**不再查 level**。

**Mixin 骨架**

```java
@Mixin(value = com.simibubi.create.content.fluids.FluidTransportBehaviour.class, remap = false)
public abstract class FluidTransportBehaviourMixin {

	@Unique
	private BlockState createperf$cachedState;

	// blockEntity 声明在父类 BlockEntityBehaviour（public SmartBlockEntity blockEntity;，:18），
	// Mixin 会在层级中解析并打印一条 warning，属预期。若解析失败，改为 @Mixin(BlockEntityBehaviour.class) 的
	// 独立接口 mixin 提供 @Accessor。
	@Shadow
	public SmartBlockEntity blockEntity;

	@Inject(method = "tick()V", at = @At("HEAD"), remap = false)
	private void createperf$cacheBlockState(CallbackInfo ci) {
		Level level = blockEntity.getLevel();
		createperf$cachedState = level == null ? null : level.getBlockState(blockEntity.getBlockPos());
	}

	/**
	 * 注意：target 里的 class 是 FluidTransportBehaviour，但 lambda 体所在的方法签名必须精确匹配。
	 * 落地前用 -Dmixin.debug.export=true 导出，确认合成方法名（这里是 lambda$tick$0 的推测值）。
	 */
	@WrapOperation(method = "lambda$tick$0", remap = false,
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/block/entity/BlockEntity;getBlockState()Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState createperf$useCachedState(BlockEntity instance, Operation<BlockState> original) {
		BlockState cached = createperf$cachedState;
		return cached != null ? cached : original.call(instance);
	}
}
```

> 若你的 Mixin 版本/编译器下合成方法名不是 `lambda$tick$0`，用导出产物里的真实名字；
> 找不到就让它启动报错（`defaultRequire = 1`），**不要**降级为静默失败。

**Mixin 骨架（可选，去掉 lambda 分配——不推荐）**

```java
	@Overwrite(remap = false)
	public void tick() { /* 82 行、含 WAIT_FOR_PUMPS/FLIP_FLOWS/IDLE 三态机 + 客户端粒子分支 */ }
```

理由：`tick()` 承载状态机（`:82-100`）与客户端/服务端分支（`:66`、`:87`、`:102`），
重写它换来的只是「少 6 个短命对象/管道/tick」，风险收益比很差。

**验证方法**

- 导出核对合成方法名：`-Dmixin.debug.export=true` → `run/.mixin.out/` 下 `FluidTransportBehaviour.class`，
  用 `javap -p` 看 `lambda$tick$N` 是否存在、描述符是否与 `@WrapOperation` 一致。
- 探针：`@Unique` 计数器统计 `createperf$useCachedState` 被调用的次数（每 tick 应 ≈ 活跃管道的连接数 × 2~5），
  同时用 `System.nanoTime` 对比「走缓存」与「原调用」的耗时差。
- `/spark profiler --timeout 60` 观察 `Level.getBlockState` / `LevelChunk.getBlockState` 样本下降。

**收益估计**（估计）

- 消除重复的 `level.getBlockState`：**量级 = 每管道每 tick 少 6×(1~4) 次区块调色板查询**（≈ 12~24 次/tick/管道）；
  1000 根管道时是 1.2~2.4 万次/tick 的查询 → 这是本条的实际价值。
- lambda 分配仍然存在（**量级 = 每管道每 tick ≤6 个对象**），若要用 `@Overwrite tick()` 消除，收益太小、风险太大。

**回归测试点**

1. 管道抽取：`canPullFluidFrom` 的判定必须仍用**当前**方块状态。由于 `tick` 内方块状态不会变，
   缓存是安全的；但要验证「同一 tick 内放置/破坏管道」的极端情形（BE 已 tick 完，state 下一 tick 才变）。
2. 6 个方向的连接各自独立：`connection.side` 必须仍被传入（缓存只替换 state 参数）。
3. 客户端 `isVirtual()`（contraption 内的管道）也要走同一缓存路径且不 NPE。
4. 用 `/create ponder` 或 contraption 场景验证虚拟管道的流体逻辑不变。

---

### F5. `FluidNetwork.tick` 每 tick 的容器分配 + 每边一个 `BlockFace` — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/fluids/FluidNetwork.java`：`:185` `new IdentityHashMap<>`、`:219` `new ArrayList<>(targets)`、`:247` `getOrDefault(handler, 0)`（装箱）、`:254` `Integer.valueOf(fill)`、`:116` `new BlockFace(...)`（在 `:71` 的 16 次 cycle 循环 × `:113` 的 6 个方向之内） |
| 调用频次 | `PipeConnection.manageFlows`（`PipeConnection.java:145-150`）在管道有活动流向时每 tick 调一次 `network.get().tick()` → **每 tick 每个活跃流体网络一次**。`:71` 的 `CYCLES_PER_TICK = 16`（`:35`）。 |
| 复杂度 / 成本 | 每 tick/network：1 个 `IdentityHashMap`（约 180 B，含 32 槽 `Object[]`）+ 最多 2 个 `ArrayList` 防御拷贝（每个约 40 B + 数组）+ `getOrDefault` 的 `Integer` 默认值装箱（小整数有缓存，实际几乎不分配）。<br>最大项是 `:116`：每 tick 最多 `16 × frontier.size() × 6` 个 `BlockFace`（外加每个 `relative()` 的 `BlockPos`）。 |
| 注入方式 | **轻量**：`@Redirect` 打在两个 `NEW` 上（可选加 `@Inject(HEAD/RETURN)` 重入守卫）。**完整**：`@Overwrite`（连 `:116` 的 `BlockFace` 一起省掉） |
| 目标成员 | `com.simibubi.create.content.fluids.FluidNetwork#tick()V`；被替换的 NEW 目标 `java/util/IdentityHashMap`、`java/util/ArrayList` |
| 需新增状态 | `@Unique IdentityHashMap<IFluidHandler,Integer> createperf$fillScratch`、`@Unique ArrayList<Pair<BlockFace,FlowSource>> createperf$outputScratch`、`@Unique boolean createperf$active`（per-network，每 tick 用完清空；不跨线程） |
| 风险 | 中（scratch 复用需要「每 tick/每次构造清空」+ 重入守卫；`@Overwrite` 还要处理 `:143` 处 `determineSource` 会写入 `source` 的重新读取） |
| 脆弱度 | 轻量版依赖 `@At(value="NEW", target="java/util/IdentityHashMap")` 与 `target="java/util/ArrayList"` —— 上游换容器类型即启动报错。完整版依赖 11 个包私有字段名与 4 个私有方法名。 |
| 置信度 | 行号/分配点：**确定**（逐行核对，且已修正原始 finding 中「两遍各一次」的说法）。收益：**需实测** |

**改前**

```java
// FluidNetwork.java:184-185（tick 内，simulate 循环之外 → 每 tick 1 次）
		int flowSpeed = transferSpeed;
		Map<IFluidHandler, Integer> accumulatedFill = new IdentityHashMap<>();

		for (boolean simulate : Iterate.trueAndFalse) {                 // :187 最多 2 轮
			...
			List<Pair<BlockFace, FlowSource>> availableOutputs = new ArrayList<>(targets);   // :219 最多 2 次/tick
			while (!availableOutputs.isEmpty() && transfer.getAmount() > 0) {
				...
					int simulatedTransfer = toTransfer;
					if (simulate)
						simulatedTransfer += accumulatedFill.getOrDefault(targetHandler, 0);     // :247 装箱
					...
					if (simulate) {
						accumulatedFill.put(targetHandler, Integer.valueOf(fill));              // :254 装箱
						fill -= simulatedTransfer - toTransfer;
					}
			}
		}

// FluidNetwork.java:112-116（在最内层：16 cycles × frontier × 6 directions）
				for (Direction side : Iterate.directions) {
					if (side == blockFace.getFace())
						continue;
					BlockFace adjacentLocation = new BlockFace(blockFace.getPos(), side);        // :116 每边一个对象
					PipeConnection adjacent = get(adjacentLocation);
```

**为什么慢**

- 频次：**每 tick 每个活跃流体网络**一次，与管道/储罐数量同阶。基地里有 100 个活跃网络时，
  每分钟约 `100 × 20 × 60 = 120000` 次 `tick` 调用（这里说的是调用次数，成本按下面摊）。
- 单次成本：容器分配约 0.3~0.5 KB；`:116` 的 `BlockFace` 才是主导——`16 × frontier × 6`，
  frontier 是活跃的流向集合（一段长管道上可能有十几个元素）→ 每 tick 数百到数千个 `BlockFace` + 等量 `BlockPos`。
- `getOrDefault(targetHandler, 0)`：`0` 会被自动装箱成 `Integer`（`-128~127` 命中 `IntegerCache`，所以这一条**不是**分配热点，
  但仍是每次一次方法调用 + 拆箱；改成 `Integer v = map.get(h)` 可以少一次默认值构造路径）。

**改后**

- 轻量（推荐起步）：把 `:185`/`:219` 的两个容器变成 `@Unique` scratch，在**原构造点** `clear()`，
  并在 `finally` 里清空以避免跨 tick 持有 `IFluidHandler` 强引用：

```java
	@Unique private final IdentityHashMap<IFluidHandler, Integer> createperf$fillScratch = new IdentityHashMap<>();
	@Unique private final ArrayList<Pair<BlockFace, FlowSource>> createperf$outputScratch = new ArrayList<>();

	// :185 → 复用
	createperf$fillScratch.clear();
	Map<IFluidHandler, Integer> accumulatedFill = createperf$fillScratch;

	// :219 → 复用（原构造器是 ArrayList(Collection)，替换后要自己 addAll）
	createperf$outputScratch.clear();
	createperf$outputScratch.addAll(targets);
	List<Pair<BlockFace, FlowSource>> availableOutputs = createperf$outputScratch;

	// :247 → 去掉默认值装箱
	Integer previous = accumulatedFill.get(targetHandler);
	simulatedTransferred += previous == null ? 0 : previous;
```

- 完整（`@Overwrite`）：额外把 `:116` 改成不构造 `BlockFace` 的查表路径：

```java
	BlockPos adjacentPos = blockFace.getPos().relative(side);       // 仍需要这个 BlockPos
	FluidTransportBehaviour adjacentBehaviour = createperf$getFluidTransfer(adjacentPos);
	PipeConnection adjacent = adjacentBehaviour == null ? null : adjacentBehaviour.getConnection(side);
	// 只有真正要保存时才构造 BlockFace：
	//   targets.add(Pair.of(new BlockFace(adjacentPos, side), src));
	//   queued.add(new BlockFace(adjacentPos, side.getOpposite()));
```

完整方法体见 [overwrites/FluidNetwork-tick.md](overwrites/FluidNetwork-tick.md)（含 `PipeConnectionAccessor`、`FluidNetworkInvoker` 伴生接口）。

**Mixin 骨架**（轻量版；完整版见 overwrite 文档）

```java
@Mixin(value = com.simibubi.create.content.fluids.FluidNetwork.class, remap = false)
public abstract class FluidNetworkMixin {
	@Unique private final IdentityHashMap<IFluidHandler, Integer> createperf$fillScratch = new IdentityHashMap<>();
	@Unique private final ArrayList<Pair<BlockFace, FlowSource>> createperf$outputScratch = new ArrayList<>();

	@Redirect(method = "tick()V", at = @At(value = "NEW", target = "java/util/IdentityHashMap"), remap = false)
	private IdentityHashMap<IFluidHandler, Integer> createperf$reuseFillMap() {
		createperf$fillScratch.clear();
		return createperf$fillScratch;
	}

	@Redirect(method = "tick()V", at = @At(value = "NEW", target = "java/util/ArrayList"), remap = false)
	private ArrayList<Pair<BlockFace, FlowSource>> createperf$reuseOutputList(
		Collection<Pair<BlockFace, FlowSource>> source) {
		createperf$outputScratch.clear();
		createperf$outputScratch.addAll(source);   // 原构造器 ArrayList(Collection) 已被替换
		return createperf$outputScratch;
	}
}
```

**注意**：这是「`new` 出现在**赋值语句右侧**」的情形，`@Redirect(at = @At("NEW"))` 会整体替换 `NEW`+`INVOKESPECIAL`，
**能真正消除分配**——与手册 §3.1 的陷阱（`new` 作为**实参**）不同。若想与其它 mod 共存，可改用 `@WrapOperation`，
但 `@WrapOperation` 拿到的已是构造好的实例，**省不掉分配**。

**验证方法**

- 探针：`@Unique` 计数器统计 scratch 的复用次数与「重入回退到新建」的次数（后者应为 0）。
- JFR `ObjectAllocationSample`/`/spark profiler --alloc`：确认 `java.util.IdentityHashMap`、`java.util.ArrayList`
  样本消失，`catnip.math.BlockFace` 样本大幅下降（仅完整版）。
- **功能对照（最重要）**：见下；`accumulatedFill` 的复用一旦污染，表现是**输送量静默变少**，不会报错。

**收益估计**（估计）

- 轻量版：每 tick/network 省 0.3~0.5 KB 与 3 次容器构造；**量级：每 tick 每活跃网络 3 个对象**。
- 完整版：额外省掉每 tick 数百到数千个 `BlockFace`（`16 × frontier × 6`），**这是本条的主要收益**。
- 两者都不改变任何可见行为（除完整版需要严格等价迁移）。

**回归测试点**

1. **单源多目标总量**：1 泵 → 分叉 2/6 个储罐，**总输送量必须与改前完全一致**（scratch 污染的第一表现就是这里变少）。
2. 压力反转、长管道（>40 格，验证 `:173` 的 `world.getGameTime() % 40` 分支）。
3. 便携流体接口（`keepPortableFluidInterfaceEngaged`）在 contraption 运动时不提前断开（完整版把它换成 `@Invoker`）。
4. 跨区块输运时 `frontier`/`visited`/`queued` 的收敛（不死循环、不丢目标）。
5. 完整版专测：`:143` 处 `determineSource` 写入 `source` 后必须**重新读取** `source`（否则端点识别会丢一拍）。

---

## 不可 Mixin / 建议提给上游的候选

> 以下条目**不适合**由本 mod 用 Mixin 修复（协议兼容、字段类型、语义归属上游）。请由 Lead 汇总进
> `docs/perf/11-upstream-issues.md`。

### U1. 压力 NBT 编码（`PipeConnection.serializeNBT` / `deserializeNBT`）

- 现状：`ListTag` + 2 个 `FloatTag`（`PipeConnection.java:216-221`），读取端硬编码 `getList("Pressure", Tag.TAG_FLOAT)`（`:246-247`）。
- 为什么不能 Mixin：改编码即改**网络协议 + 存档格式**。任何未装本 mod 的客户端/服务端都会把压力读成 0（静默失效，不报错）。
  本 mod 的定位是「客户端/服务端可选安装的纯性能补丁」，不能要求对端也装。
- 建议上游：把压力编码成 `IntArrayTag`（2 个 float 的位模式）或直接 `CompoundTag` 里的 2 个 `FloatTag` 字段，
  并做一次协议版本判断；可省掉每面 1 个 `ListTag` + 2 个 `FloatTag`，同时保留向后兼容读。

### U2. `PipeConnection.pressure` 用 `Couple<Float>` 存两个 float

- 现状：`Couple<Float> pressure`（`PipeConnection.java:39`），`addPressure` 走 `Couple.mapWithContext`（`:310-312`），
  **高概率**每次累加都新建 `Couple` + 2 个 `Float` 装箱（catnip 源码不在本仓库，需实测）。
- 为什么不能 Mixin：改写字段类型无法用 Mixin 完成（不能重声明已有字段的类型；只能 `@Overwrite` 每一个读写点，
  涉及 `FluidNetwork`、`PipeConnection`、`FluidTransportBehaviour`、`PumpBlockEntity` 多处，冲突面不可接受）。
- 建议上游：改成 `float[] pressure = new float[2]` 或两个 `float` 字段，提供 `getInbound/Outbound` 访问器。
  收益：每次 `addPressure` 省 1 个 `Couple` + 2 个装箱；压力重算时按 `validFaces` 规模放大。

### U3. `FluidTankBlockEntity.onFluidStackChanged` 按体积广播 `updateNeighbourForOutputSignal`

- 现状：`FluidTankBlockEntity.java:177-194`，每次充放 `width² × height` 次邻居广播。
- 为什么不适合全由 Mixin 兜：本 mod 的 §F2 方案已经能省掉绝大部分（红石等级未变时不广播），
  但「按 volume 放大」的设计本身应该上游解决（例如缓存在 controller 上、只对**外表面且贴有比较器**的格子广播，
  或提供 `FluidTankBlock` 的 `getAnalogOutputSignal` 缓存）。本 mod 的做法引入了「邻居不再收到无意义的 `neighborChanged`」
  这一行为变更，上游若想做同样优化，也应写进 changelog。
- 建议上游：把 `updateNeighbourForOutputSignal` 按「红石等级真正变化」节流（并跟踪 `maxY` 保证光照），
  或把整个循环限制在「外表面格子」。

### U4. `FluidManipulationBehaviour`/`FluidPropagator`/`PumpBlockEntity` 的 `ArrayList.remove(0)`

- 现状：见 F1。上游改起来是一行的事（`ArrayDeque` + `pollFirst()`）。
- 为什么仍要提：本 mod 必须 `@Overwrite` 三个方法才能修（frontier 是局部变量/参数），
  upstream 一个 `ArrayDeque` 就能解决，且能避免本 mod 与其它 mod 的 `@Overwrite` 冲突。
- 建议上游：① 把 `FluidManipulationBehaviour.frontier` 的声明与 `search` 的参数类型改为 `Deque<BlockPosEntry>`；
  ② `PumpBlockEntity`/`FluidPropagator` 的局部 `List` 改 `ArrayDeque`，并用 `record` 替代 `Pair<Integer, BlockPos>`。

### U5. `FluidTransportBehaviour.tick` 每连接一个 lambda

- 现状：`FluidTransportBehaviour.java:130-135`。
- 为什么不适合 Mixin 单独修：lambda 对象在 `invokedynamic` 处创建，MixinExtras 的 `@ModifyArg` 只能换实参
  （对象已建好）。本 mod 只能退而求其次缓存 `BlockState`，且必须依赖**编译器生成的合成方法名**（脆弱）。
- 建议上游：把 `extractionPredicate` 提升为每 tick 一个的可复用 `Predicate`（用字段存当前 `side`/`state`），
  或把 `canPullFluidFrom` 的判定直接内联进 `manageFlows` 的调用参数。一行 `blockEntity.getBlockState()` 提到循环外即可。

### U6. `FluidNetwork.tick` 的每 tick 容器分配

- 现状：见 F5。上游可以简单地把两个容器改为实例字段并复用（同样是 `@Unique` scratch 的思路，但由上游持有更安全，
  且能顺带把 `:116` 的 `BlockFace` 去掉）。

---

## 附：finding ↔ overwrite 文档对照

| finding | 注入方式 | overwrite 文档 |
| --- | --- | --- |
| F1 ① | `@Inject(HEAD, cancellable)`（备选 `@Overwrite`） | [overwrites/FluidManipulationBehaviour-search.md](overwrites/FluidManipulationBehaviour-search.md) |
| F1 ② | `@Overwrite` | [overwrites/PumpBlockEntity-distributePressureTo.md](overwrites/PumpBlockEntity-distributePressureTo.md) |
| F1 ③ | `@Overwrite`（×2） | [overwrites/FluidPropagator-BFS.md](overwrites/FluidPropagator-BFS.md) |
| F2 | `@Inject(HEAD)` + `@WrapOperation`（备选 `@Overwrite`） | [overwrites/FluidTankBlockEntity-onFluidStackChanged.md](overwrites/FluidTankBlockEntity-onFluidStackChanged.md) |
| F3 | 不可 Mixin（主体） | 无（不建议产出） |
| F4 | `@Inject(HEAD)` + `@WrapOperation`（合成 lambda） | 无（骨架已在本文件） |
| F5 | `@Redirect(NEW)` ×2（备选 `@Overwrite`） | [overwrites/FluidNetwork-tick.md](overwrites/FluidNetwork-tick.md) |

> 按手册 §5.4/§6，以上 5 个 overwrite 候选都需要在 `docs/perf/00-overview-and-roadmap.md` 的路线图里登记回归测试点
> （该文件由 Lead 维护）。




