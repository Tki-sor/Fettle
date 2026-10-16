# @Overwrite 详解：`ContraptionCollider.getPotentiallyCollidedShapes`

> 关联 finding：`03-contraptions-trains.md` 第 4 条（High）
> 目标：`com.simibubi.create.content.contraptions.ContraptionCollider`
> 本文件给的是**完整可粘贴**的替换实现。

## 1. 原方法完整源码（Create 6.0.10，行号已逐行核对）

文件：`src/main/java/com/simibubi/create/content/contraptions/ContraptionCollider.java`，第 582–608 行。

```java
	private static void getPotentiallyCollidedShapes(Level world, Contraption contraption, AABB localBB, DoubleLineConsumer out) {
		double height = localBB.getYsize();
		double width = localBB.getXsize();
		double horizontalFactor = (height > width && width != 0) ? height / width : 1;
		double verticalFactor = (width > height && height != 0) ? width / height : 1;
		AABB blockScanBB = localBB.inflate(0.5f);
		blockScanBB = blockScanBB.inflate(horizontalFactor, verticalFactor, horizontalFactor);

		BlockPos min = BlockPos.containing(blockScanBB.minX, blockScanBB.minY, blockScanBB.minZ);
		BlockPos max = BlockPos.containing(blockScanBB.maxX, blockScanBB.maxY, blockScanBB.maxZ);

		for (BlockPos p : BlockPos.betweenClosed(min, max)) {
			if (contraption.blocks.containsKey(p) && !contraption.isHiddenInPortal(p)) {
				StructureBlockInfo info = contraption.getBlocks().get(p);

				BlockState blockState = info.state();
				BlockPos pos = info.pos();

				VoxelShape collisionShape = blockState.getCollisionShape(world, p)
						.move(pos.getX(), pos.getY(), pos.getZ());

				if (!collisionShape.isEmpty()) {
					collisionShape.forAllBoxes(out);
				}
			}
		}
	}
```

## 2. 为什么值得 @Overwrite

1. **扫描盒按「实体盒宽高比」膨胀**（`:585-588`）：`localBB` 是被检测实体（玩家）在 contraption **局部坐标系**里的包围盒。一个 1×20×1 的竖直 contraption，玩家盒高 ≈1.8、宽 ≈0.6，得到 `verticalFactor ≈ 3`、`horizontalFactor ≈ 3`，再叠加 `inflate(0.5)` → 扫描盒在 Y 上约 20+3+1 ≈ 24 格、X/Z 各约 4 格 → `betweenClosed` 遍历量级 **20³ 上下（数千格）**，而其中命中的 contraption 方块可能不到 20 个。这是典型的「用体积换代码简洁」。
2. **每格都要走 `BlockPos.MutableBlockPos`**（`betweenClosed` 迭代器复用同一个可变 pos），但每次迭代仍要 `containsKey` + `get` 两次哈希（`IdentityHashMap`？**不是** —— `Contraption.blocks` 是普通 `Map<BlockPos, StructureBlockInfo>`，所以是两次 `BlockPos.hashCode`，每次都遍历坐标分量）。扫描量越大，这两次哈希的绝对值越大。
3. **调用点**：`ContraptionCollider.collideEntities` 里**每个**满足 `canCollideWith` 的实体调用一次（`:157-164`）；`collideEntities` 本身由 `AbstractContraptionEntity.tick` 路径每 tick 调用。所以「附近有几个实体」就是放大系数。
4. **重要更正（相对调查清单）**：清单说 `:157-164` 的降级分支是死代码，**不准确**。
   - `Contraption.getSimplifiedEntityColliders()`（`Contraption.java:1497-1500`）确实不返回 null；
   - 但 `CarriageContraption` **重写了**它（`CarriageContraption.java:252-258`）：`notInPortal()` 为 false（即正处在传送门中）时**返回 null**；
   - 因此对「正在传送门里的列车车厢」，`getPotentiallyCollidedShapes` 会被真正执行 —— 这条不是死代码，只是触发条件窄。
   - 另注：`Contraption.simplifiedEntityColliders`（`Contraption.java:136`）在 contraption 没有任何实心碰撞盒时是**空列表而非 null**，所以「空列表」不会走降级分支。

## 3. 改后完整方法体（可粘贴）

把「按比例膨胀的立方体扫描」换成「只遍历 contraption 自己声明过的方块，并用局部盒做常量时间哈希测试」。**行为等价**：原实现命中的每个方块，其包围盒必然与 `blockScanBB` 相交（`blockScanBB` 是 `localBB.expandTowards(motion)` 再双向膨胀得到，任何与 `localBB` 相交的方块都在其中），而新实现用的是更紧的 `localBB` —— 保守方向是「可能多收集」（把不与实体盒相交的方块也收进来），**绝不会漏**。多收集只会让 `ContinuousOBBCollider.collideMany` 多做几次精确判定，不改变结果。

```java
	private static void getPotentiallyCollidedShapes(Level world, Contraption contraption, AABB localBB, DoubleLineConsumer out) {
		Map<BlockPos, StructureBlockInfo> blocks = contraption.blocks;
		if (blocks.isEmpty())
			return;

		// 原实现按实体盒宽高比 inflate 出一个立方体扫描盒（1x20x1 的 contraption 会扫数千格）。
		// 这里改为「遍历 contraption 方块 + AABB 相交测试」：O(方块数)。
		// 注意用 localBB 而不是膨胀后的 blockScanBB：范围更紧（保守方向是多收集，不漏）。
		double minX = localBB.minX;
		double minY = localBB.minY;
		double minZ = localBB.minZ;
		double maxX = localBB.maxX;
		double maxY = localBB.maxY;
		double maxZ = localBB.maxZ;

		for (Entry<BlockPos, StructureBlockInfo> entry : blocks.entrySet()) {
			BlockPos localPos = entry.getKey();

			// 手工内联的 AABB 相交测试，避免每个方块 new AABB
			if (localPos.getX() + 1 <= minX || localPos.getX() >= maxX)
				continue;
			if (localPos.getY() + 1 <= minY || localPos.getY() >= maxY)
				continue;
			if (localPos.getZ() + 1 <= minZ || localPos.getZ() >= maxZ)
				continue;

			if (contraption.isHiddenInPortal(localPos))
				continue;

			StructureBlockInfo info = entry.getValue();
			BlockState blockState = info.state();
			BlockPos pos = info.pos();

			VoxelShape collisionShape = blockState.getCollisionShape(world, localPos)
				.move(pos.getX(), pos.getY(), pos.getZ());

			if (!collisionShape.isEmpty()) {
				collisionShape.forAllBoxes(out);
			}
		}
	}
```

### 需要的 import 与说明

```java
import java.util.Map.Entry;                                        // 新增
import com.simibubi.create.content.contraptions.Contraption;       // 已有
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo; // 已有
import net.minecraft.world.level.block.state.BlockState;           // 已有
import net.minecraft.world.phys.AABB;                              // 已有
import net.minecraft.world.phys.shapes.Shapes.DoubleLineConsumer;  // 已有
import net.minecraft.world.phys.shapes.VoxelShape;                 // 已有
import net.minecraft.core.BlockPos;                                // 已有
```

- `contraption.blocks` 是 `public` 字段（`Contraption.java` 里 `public Map<BlockPos, StructureBlockInfo> blocks`）→ Overwrite 里直接读；若用 `@Inject` handler 则需 `@Shadow @Final` 或 `@Accessor`。
- `blockState.getCollisionShape(world, p)` 原实现传的是**扫描盒里的 p**；新实现传 `localPos`。注意：`localPos` 与 `info.pos()` **本来就是同一个对象**（`blocks` 的 key 就是 `StructureBlockInfo.pos()`），所以两者取值完全一致，只是新实现避免了重复取。
- `isHiddenInPortal(localPos)` 对 `CarriageContraption` 是坐标敏感的方法（`CarriageContraption.java:260-270`），必须传局部坐标 —— 新实现传的就是局部坐标。

## 4. Mixin 类骨架

```java
@Mixin(value = ContraptionCollider.class, remap = false)
public abstract class ContraptionColliderMixin {

	@Overwrite
	private static void getPotentiallyCollidedShapes(Level world, Contraption contraption, AABB localBB,
		DoubleLineConsumer out) {
		/* 见第 3 节 */
	}
}
```

`@Overwrite` handler 的可见性必须与目标一致：目标是 `private static` → handler 也写 `private static`。
Mixin 允许改写 static 方法；目标描述符：`(Lnet/minecraft/world/level/Level;Lcom/simibubi/create/content/contraptions/Contraption;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/phys/shapes/Shapes$DoubleLineConsumer;)V`。

**替代（更保守的）方案**：不重写，改在 `collideEntities` 里用 `@WrapOperation` 包裹 `:157` 的 `getSimplifiedEntityColliders()`，当返回空 `CollisionList`（`size == 0`）时**先尝试一次**「紧局部盒扫描」，若扫到的碰撞盒数量 > 0 则跳过 `:163` 的 `getPotentiallyCollidedShapes`。这样侵入性更小，但要多维护一个「已扫描」状态。**两方案都需要实测确认碰撞结果一致。**

## 5. 风险

| 风险 | 说明 |
| --- | --- |
| 收集范围变化 | 新实现**可能多收集**不与实体盒相交、但与局部盒相交的方块。这些额外 AABB 进入 `ContinuousOBBCollider.collideMany` 后会被精确判定排除，理论不改变碰撞结果；**但**如果 `collideMany` 有「找最早接触时间」的数值抖动，额外盒可能改变浮点结果。属于**需实测**项。 |
| `blocks` 迭代顺序 | 新实现按 `blocks`（HashMap）迭代顺序收集，原实现按坐标顺序。收集顺序会影响 `CollisionList` 里的排列，从而可能影响 `collideMany` 在「同一 tick 多个等价碰撞」时的选择。同理需实测。 |
| 性能反例 | 对**非常大**的 contraption（数百方块）且扫描区域本就很小（比如玩家只在盒子一角），新实现要遍历全部方块，可能比原扫描更慢。建议加一个规模阈值：`blocks.size() > 4096` 时回退原扫描逻辑（把原代码整个搬进 `else` 分支）。 |
| 上游重构 | 方法签名变动 → 启动报错（`defaultRequire:1`）。 |
| 与 Create 自带 mixin | 无重叠。 |

## 6. 回归测试点

1. **玩家站在竖直装置前**：造一个 1×20×1 的柱状装置（比如 20 格高的机械臂/吊臂），让玩家贴着它移动，确认**不会被推进墙里**、也不会「穿过去」。这是本改动的核心场景。
2. **传送门中的车厢**：把列车开进下界传送门（触发 `CarriageContraption.getSimplifiedEntityColliders() == null`），确认玩家站在车厢上时碰撞仍然正常。
3. **宽装置**：造一个 20×1×20 的平板装置，从下方顶玩家，确认玩家能被顶起来（`maxUpStep` 路径）。
4. **装置内开口**：装置中间留一个 3 格高的门洞，让玩家穿过去，确认能穿过（验证不会因多收集而卡住）。
5. **多个实体**：往装置里塞 5 个盔甲架 + 2 个玩家，确认帧率与碰撞都不退化。
6. **回归原逻辑**：临时加开关（`@Unique static boolean createperf$useTightScan = true`），A/B 两次跑同一场景，比较玩家位置轨迹（用 F3 坐标每 5 tick 打点）。

## 7. 收益

**估计**：单个 1×20×1 contraption 的降级扫描从「数千格 `betweenClosed` + 数千次双哈希」降到「装置方块数（约 20）× 3 次整数比较 + 约 20 次哈希」。量级：**每次调用少上千次哈希查找**（估计值，需 Spark `--alloc` 与 `--time` 实测确认）。
