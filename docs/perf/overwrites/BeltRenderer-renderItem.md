# Overwrite 施工图：`BeltRenderer.renderItem`（最小差异版）

> 目标方法：`com.simibubi.create.content.kinetics.belt.BeltRenderer#renderItem(...)V`（`private`，14 个参数，完整描述符见第 4 节）
> 对应发现：[07-client-render-ui.md](../07-client-render-ui.md) 发现 5（Medium-High）
> 公共约定见 [01-mixin-playbook.md](../01-mixin-playbook.md)

## 1. 范围声明（为什么不改向量数学）

`renderItems`（`BeltRenderer.java:195-225`）在 `:161` 被无条件调用（**位于 `VisualizationManager` 门控之外**），所以这是真热路径。但本条只做 4 处**最小差异**替换，理由：

- 被替换的四项各有明确等价物：复用 `MutableBlockPos`、复用并重置种子的 `Random`、每 tick 光照记忆化、每 tick 眼位记忆化——**语义可证明等价**（`Random` 重置种子后序列完全相同；`getEyePosition(1.0F)` 在同一 tick 内恒定）。
- `Vec3.atLowerCornerOf(directionVec).scale(offset)`（`:247-248`）与 `shouldCullItem` 内部的 `AABB` 需要改写向量数学或重写 `Frustum` 测试，收益是每物品 2–3 个短命对象（很可能已被 JIT 逃逸分析消除），而**视觉回归风险不成比例**。因此本轮不动，留待实测证明有必要后再做。

## 2. 原方法完整源码（`BeltRenderer.java:227-354`，逐字摘录）

```java
	private void renderItem(BeltBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
		int overlay, Direction beltFacing, Vec3i directionVec, BeltSlope slope, int verticality, boolean slopeAlongX,
		boolean onContraption, TransportedItemStack transported, Vec3 beltStartOffset) {
		Minecraft mc = Minecraft.getInstance();
		ItemRenderer itemRenderer = mc.getItemRenderer();
		MutableBlockPos mutablePos = new MutableBlockPos();

		float offset = Mth.lerp(partialTicks, transported.prevBeltPosition, transported.beltPosition);
		float sideOffset = Mth.lerp(partialTicks, transported.prevSideOffset, transported.sideOffset);
		float verticalMovement = verticality;

		if (be.getSpeed() == 0) {
			offset = transported.beltPosition;
			sideOffset = transported.sideOffset;
		}

		if (offset < .5)
			verticalMovement = 0;
		else
			verticalMovement = verticality * (Math.min(offset, be.beltLength - .5f) - .5f);
		Vec3 offsetVec = Vec3.atLowerCornerOf(directionVec)
			.scale(offset);
		if (verticalMovement != 0)
			offsetVec = offsetVec.add(0, verticalMovement, 0);
		boolean onSlope = slope != BeltSlope.HORIZONTAL && Mth.clamp(offset, .5f, be.beltLength - .5f) == offset;
		boolean tiltForward = (slope == BeltSlope.DOWNWARD
			^ beltFacing.getAxisDirection() == AxisDirection.POSITIVE) == (beltFacing.getAxis() == Direction.Axis.Z);
		float slopeAngle = onSlope ? tiltForward ? -45 : 45 : 0;

		Vec3 itemPos = beltStartOffset.add(
				be.getBlockPos().getX(),
				be.getBlockPos().getY(),
				be.getBlockPos().getZ())
			.add(offsetVec);

		if (this.shouldCullItem(itemPos, be.getLevel())) {
			return;
		}

		ms.pushPose();
		TransformStack.of(ms).nudge(transported.angle);
		ms.translate(offsetVec.x, offsetVec.y, offsetVec.z);

		boolean alongX = beltFacing.getClockWise()
			.getAxis() == Direction.Axis.X;
		if (!alongX)
			sideOffset *= -1;
		ms.translate(alongX ? sideOffset : 0, 0, alongX ? 0 : sideOffset);

		int stackLight;
		if (onContraption) {
			stackLight = light;
		} else {
			int segment = (int) Math.floor(offset);
			mutablePos.set(be.getBlockPos()).move(directionVec.getX() * segment, verticality * segment, directionVec.getZ() * segment);
			stackLight = LevelRenderer.getLightColor(be.getLevel(), mutablePos);
		}

		boolean renderUpright = BeltHelper.isItemUpright(transported.stack);
		BakedModel bakedModel = itemRenderer.getModel(transported.stack, be.getLevel(), null, 0);
		boolean blockItem = bakedModel.isGui3d();

		int count = 0;
		if (be.getLevel() instanceof PonderLevel || mc.player.getEyePosition(1.0F).distanceTo(itemPos) < 16)
			count = (int) (Mth.log2((int) (transported.stack.getCount()))) / 2;

		Random r = new Random(transported.angle);

		boolean slopeShadowOnly = renderUpright && onSlope;
		float slopeOffset = 1 / 8f;
		if (slopeShadowOnly)
			ms.pushPose();
		if (!renderUpright || slopeShadowOnly)
			ms.mulPose((slopeAlongX ? Axis.ZP : Axis.XP).rotationDegrees(slopeAngle));
		if (onSlope)
			ms.translate(0, slopeOffset, 0);
		ms.pushPose();
		ms.translate(0, -1 / 8f + 0.005f, 0);
		ShadowRenderHelper.renderShadow(ms, buffer, .75f, .2f);
		ms.popPose();
		if (slopeShadowOnly) {
			ms.popPose();
			ms.translate(0, slopeOffset, 0);
		}

		if (renderUpright) {
			Vec3 cameraPosition = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
			Vec3 vectorForOffset = BeltHelper.getVectorForOffset(be, offset);
			Vec3 diff = vectorForOffset.subtract(cameraPosition);
			float yRot = (float) (Mth.atan2(diff.x, diff.z) + Math.PI);
			ms.mulPose(Axis.YP.rotation(yRot));
			ms.translate(0, 3 / 32d, 1 / 16f);
		}

		for (int i = 0; i <= count; i++) {
			ms.pushPose();

			boolean box = PackageItem.isPackage(transported.stack);
			ms.mulPose(Axis.YP.rotationDegrees(transported.angle));
			if (!blockItem && !renderUpright) {
				ms.translate(0, -.09375, 0);
				ms.mulPose(Axis.XP.rotationDegrees(90));
			}

			if (blockItem && !box)
				ms.translate(r.nextFloat() * .0625f * i, 0, r.nextFloat() * .0625f * i);

			if (box) {
				ms.translate(0, 4 / 16f, 0);
				ms.scale(1.5f, 1.5f, 1.5f);
			} else {
				ms.scale(.5f, .5f, .5f);
			}

			itemRenderer.render(transported.stack, ItemDisplayContext.FIXED, false, ms, buffer, stackLight, overlay, bakedModel);
			ms.popPose();

			if (!renderUpright) {
				if (!blockItem)
					ms.mulPose(Axis.YP.rotationDegrees(10));
				ms.translate(0, blockItem ? 1 / 64d : 1 / 16d, 0);
			} else
				ms.translate(0, 0, -1 / 16f);

		}

		ms.popPose();
	}
```

## 3. 改后完整可粘贴方法体

```java
	@Overwrite
	private void renderItem(BeltBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
		int overlay, Direction beltFacing, Vec3i directionVec, BeltSlope slope, int verticality, boolean slopeAlongX,
		boolean onContraption, TransportedItemStack transported, Vec3 beltStartOffset) {
		Minecraft mc = Minecraft.getInstance();
		ItemRenderer itemRenderer = mc.getItemRenderer();
		MutableBlockPos mutablePos = createperf$mutablePos;                 // 原: new MutableBlockPos()

		float offset = Mth.lerp(partialTicks, transported.prevBeltPosition, transported.beltPosition);
		float sideOffset = Mth.lerp(partialTicks, transported.prevSideOffset, transported.sideOffset);
		float verticalMovement = verticality;

		if (be.getSpeed() == 0) {
			offset = transported.beltPosition;
			sideOffset = transported.sideOffset;
		}

		if (offset < .5)
			verticalMovement = 0;
		else
			verticalMovement = verticality * (Math.min(offset, be.beltLength - .5f) - .5f);
		Vec3 offsetVec = Vec3.atLowerCornerOf(directionVec)
			.scale(offset);
		if (verticalMovement != 0)
			offsetVec = offsetVec.add(0, verticalMovement, 0);
		boolean onSlope = slope != BeltSlope.HORIZONTAL && Mth.clamp(offset, .5f, be.beltLength - .5f) == offset;
		boolean tiltForward = (slope == BeltSlope.DOWNWARD
			^ beltFacing.getAxisDirection() == AxisDirection.POSITIVE) == (beltFacing.getAxis() == Direction.Axis.Z);
		float slopeAngle = onSlope ? tiltForward ? -45 : 45 : 0;

		Vec3 itemPos = beltStartOffset.add(
				be.getBlockPos().getX(),
				be.getBlockPos().getY(),
				be.getBlockPos().getZ())
			.add(offsetVec);

		if (this.shouldCullItem(itemPos, be.getLevel())) {
			return;
		}

		ms.pushPose();
		TransformStack.of(ms).nudge(transported.angle);
		ms.translate(offsetVec.x, offsetVec.y, offsetVec.z);

		boolean alongX = beltFacing.getClockWise()
			.getAxis() == Direction.Axis.X;
		if (!alongX)
			sideOffset *= -1;
		ms.translate(alongX ? sideOffset : 0, 0, alongX ? 0 : sideOffset);

		int stackLight;
		if (onContraption) {
			stackLight = light;
		} else {
			int segment = (int) Math.floor(offset);
			mutablePos.set(be.getBlockPos()).move(directionVec.getX() * segment, verticality * segment, directionVec.getZ() * segment);
			stackLight = createperf$light(be.getLevel(), mutablePos);      // 原: LevelRenderer.getLightColor(...)
		}

		boolean renderUpright = BeltHelper.isItemUpright(transported.stack);
		BakedModel bakedModel = itemRenderer.getModel(transported.stack, be.getLevel(), null, 0);
		boolean blockItem = bakedModel.isGui3d();

		int count = 0;
		if (be.getLevel() instanceof PonderLevel || createperf$eye(mc).distanceTo(itemPos) < 16)
			count = (int) (Mth.log2((int) (transported.stack.getCount()))) / 2;

		Random r = createperf$random;
		r.setSeed(transported.angle);                                      // 原: new Random(transported.angle)

		boolean slopeShadowOnly = renderUpright && onSlope;
		float slopeOffset = 1 / 8f;
		if (slopeShadowOnly)
			ms.pushPose();
		if (!renderUpright || slopeShadowOnly)
			ms.mulPose((slopeAlongX ? Axis.ZP : Axis.XP).rotationDegrees(slopeAngle));
		if (onSlope)
			ms.translate(0, slopeOffset, 0);
		ms.pushPose();
		ms.translate(0, -1 / 8f + 0.005f, 0);
		ShadowRenderHelper.renderShadow(ms, buffer, .75f, .2f);
		ms.popPose();
		if (slopeShadowOnly) {
			ms.popPose();
			ms.translate(0, slopeOffset, 0);
		}

		if (renderUpright) {
			Vec3 cameraPosition = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
			Vec3 vectorForOffset = BeltHelper.getVectorForOffset(be, offset);
			Vec3 diff = vectorForOffset.subtract(cameraPosition);
			float yRot = (float) (Mth.atan2(diff.x, diff.z) + Math.PI);
			ms.mulPose(Axis.YP.rotation(yRot));
			ms.translate(0, 3 / 32d, 1 / 16f);
		}

		for (int i = 0; i <= count; i++) {
			ms.pushPose();

			boolean box = PackageItem.isPackage(transported.stack);
			ms.mulPose(Axis.YP.rotationDegrees(transported.angle));
			if (!blockItem && !renderUpright) {
				ms.translate(0, -.09375, 0);
				ms.mulPose(Axis.XP.rotationDegrees(90));
			}

			if (blockItem && !box)
				ms.translate(r.nextFloat() * .0625f * i, 0, r.nextFloat() * .0625f * i);

			if (box) {
				ms.translate(0, 4 / 16f, 0);
				ms.scale(1.5f, 1.5f, 1.5f);
			} else {
				ms.scale(.5f, .5f, .5f);
			}

			itemRenderer.render(transported.stack, ItemDisplayContext.FIXED, false, ms, buffer, stackLight, overlay, bakedModel);
			ms.popPose();

			if (!renderUpright) {
				if (!blockItem)
					ms.mulPose(Axis.YP.rotationDegrees(10));
				ms.translate(0, blockItem ? 1 / 64d : 1 / 16d, 0);
			} else
				ms.translate(0, 0, -1 / 16f);

		}

		ms.popPose();
	}
```

## 4. 需要的 `@Unique` 字段 / helper / import

```java
package com.example.createperf.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import com.simibubi.create.content.kinetics.belt.BeltRenderer;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

@Mixin(value = BeltRenderer.class, remap = false)
public abstract class BeltRendererMixin {

    // 每个渲染器实例一份（BlockEntityRendererDispatcher 只创建 1 个实例）
    @Unique private final BlockPos.MutableBlockPos createperf$mutablePos = new BlockPos.MutableBlockPos();
    @Unique private final java.util.Random createperf$random = new java.util.Random();

    // 光照/眼位按客户端 tick 记忆化（渲染线程专用；TickBased 计数器来自 tick 线程，读到旧值是良性）
    @Unique private static final Long2IntOpenHashMap createperf$light = new Long2IntOpenHashMap();
    @Unique private static long createperf$lightTick = Long.MIN_VALUE;
    @Unique private static long createperf$eyeTick = Long.MIN_VALUE;
    @Unique private static Vec3 createperf$eye = Vec3.ZERO;

    @Unique
    private static int createperf$light(Level level, BlockPos pos) {
        long now = AnimationTickHolder.getTicks();
        if (now != createperf$lightTick) {
            createperf$lightTick = now;
            createperf$light.clear();
        }
        long k = pos.asLong();                       // pos 是复用的 MutableBlockPos -> 先取快照
        int v = createperf$light.getOrDefault(k, Integer.MIN_VALUE);
        if (v == Integer.MIN_VALUE) {
            v = LevelRenderer.getLightColor(level, pos);
            createperf$light.put(k, v);
        }
        return v;
    }

    @Unique
    private static Vec3 createperf$eye(Minecraft mc) {
        long now = AnimationTickHolder.getTicks();
        if (now != createperf$eyeTick) {
            createperf$eyeTick = now;
            createperf$eye = mc.player.getEyePosition(1.0F);   // partialTicks = 1.0F -> 同一 tick 内恒定
        }
        return createperf$eye;
    }

    // 第 3 节的 @Overwrite 方法体
}
```

- **字段生命周期**：`作用域`＝`createperf$mutablePos`/`createperf$random` 为渲染器实例级（`@Unique` 实例字段，随 `BlockEntityRenderer` 单例存活）；`createperf$light`/`createperf$eye` 为客户端进程级静态；`失效时机`＝按 `AnimationTickHolder.getTicks()` 变化清空/刷新（光照与眼位都只随 tick 变化）；`并发`＝`renderItem` 在**渲染线程**执行，静态记忆表只有渲染线程访问 ⇒ 无锁安全；`getEyePosition` 的 `partialTicks = 1.0F` 保证同 tick 内取到相同值。
- 需要 `fastutil`（已在 classpath，见主文件「改后」说明），不需要新增依赖。
- 若 `AnimationTickHolder.getTicks()` 在你的运行环境不可用，可换成 `Minecraft.getInstance().level.getGameTime()`（同样只在 tick 变化），但须确认 PonderLevel 下有值。

## 5. `@Overwrite` 风险

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| 私有方法描述符 | `renderItem` 有 14 个参数，任何上游改签名都会让 `@Overwrite` 在启动期失败 | 期望行为（`defaultRequire = 1`）；若只想低风险，退回主文件发现 5 的两个 `@WrapOperation` |
| 视觉回归 | 物品位置/朝向/抖动由 `Random` 序列与矩阵栈顺序决定 | `setSeed` 保证序列与原 `new Random(angle)` 逐值相同；矩阵栈调用顺序逐行未改 |
| 光照滞后 | 记忆化最坏情况是「方块被放置/破坏的那个 tick 内，传送带物品光照滞后 ≤1 tick（≤50 ms）」 | 显式记录为行为变更；夜间/洞穴截图对比 |
| 眼位滞后 | 若渲染线程读到上一 tick 的 `createperf$eyeTick`，`<16` 的堆叠数字判定会在边界抖动一帧 | 只在 16 格边界可见；可接受，或改为不缓存眼位（保留 `getEyePosition` 调用） |
| `PonderLevel` | `count` 分支在 PonderLevel 下短路，`createperf$eye` 不会被调用 | 保持原短路位置 |
| `shouldCullItem` 的 AABB | 本轮不动；若日后要消除，需自写 `Frustum` 可见性判定（不建议） | 记录在案 |

## 6. 回归测试点

1. 物品外观：普通/方块物品、包裹（`PackageItem`，1.5× 放大 + Y 偏移）、直立物品（`renderUpright`）、斜带（`onSlope` 影子、`slopeOffset`）、侧向带（`sideways`）的位置与朝向逐帧与改前一致。
2. 抖动：方块物品堆叠（`blockItem && !box`）的偏移量必须与原 `Random(angle)` 序列**逐值相同**（重置种子是关键）。
3. 堆叠数字：距离 <16 格显示、>16 格消失；PonderLevel 下始终显示。
4. 光照：夜间/洞穴中物品亮度随周围方块变化，最多 1 tick 滞后；跨区块长带上不同段的物品亮度不同（证明 key 用的是段坐标而非带坐标）。
5. 视锥剔除：带子移出视野后物品不渲染。
6. 装置上的传送带（`onContraption` 为 true）必须直接用传入的 `light`，不与记忆表交互。
7. 性能：探针统计 `getLightColor`/`getEyePosition`/`new Random` 的次数（`@Unique static long` 计数器），场景为 20 物品 × 5 条可见带。
