# Overwrite 施工图：`TrackBlockOutline.pickCurves`

> 目标方法：`com.simibubi.create.content.trains.track.TrackBlockOutline#pickCurves()V`（`static`，包外可见性 `public`）
> 对应发现：[07-client-render-ui.md](../07-client-render-ui.md) 发现 3（High）
> 公共约定见 [01-mixin-playbook.md](../01-mixin-playbook.md)

## 1. 为什么需要整段重写

`:89-127` 的内层循环把「贝塞尔求值 + 法线 + 姿态角 + 局部坐标裁剪」整套算了一遍，每段产生约 20 个 `Vec3`，而这一切发生在**每帧**（`GameRenderer.pick` 的 TAIL 注入，`foundation/mixin/client/GameRendererMixin.java:15-19`）。可选手段的边界：

| 手段 | 能否解决 | 原因 |
| --- | --- | --- |
| `@ModifyExpressionValue` 修饰 `new Vec3` / `VecHelper.rotate` 的返回值 | 否 | `NEW` 与工厂调用先执行，修饰器只换值，分配仍在（手册 3.2 节） |
| `@WrapOperation` 包裹 `bc.getPosition/getNormal` | 否 | 调用点仍在循环内，包裹不减少调用次数 |
| `@Inject` 在循环体内插桩做「提前 continue」 | 否 | 注入无法改变原循环的迭代次数与内部赋值 |
| **整段重写（本文件）** | 是 | 可以把「每段都做的昂贵数学」换成「先用缓存锚点做标量粗筛，只对 1–3 段做昂贵数学」 |

因此本条用 `@Inject(HEAD, cancellable = true)` + `@Unique` 复制体（**推荐**，原方法体保留、不顶掉其它 mod 的 `@Inject`），或等价的 `@Overwrite`。

## 2. 原方法完整源码（`TrackBlockOutline.java:50-141`，逐字摘录）

```java
	public static void pickCurves() {
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.cameraEntity instanceof LocalPlayer player))
			return;
		if (mc.level == null)
			return;

		Vec3 origin = player.getEyePosition(AnimationTickHolder.getPartialTicks(mc.level));

		double maxRange = mc.hitResult == null ? Double.MAX_VALUE
			: mc.hitResult.getLocation()
				.distanceToSqr(origin);

		result = null;

		double range = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
		Vec3 target = RaycastHelper.getTraceTarget(player, Math.min(maxRange, range) + 1, origin);
		Map<BlockPos, TrackBlockEntity> turns = TRACKS_WITH_TURNS.get(mc.level);

		for (TrackBlockEntity be : turns.values()) {
			for (BezierConnection bc : be.connections.values()) {
				if (!bc.isPrimary())
					continue;

				AABB bounds = bc.getBounds();
				if (!bounds.contains(origin) && bounds.clip(origin, target)
					.isEmpty())
					continue;

				float[] stepLUT = bc.getStepLUT();
				int segments = (int) (bc.getLength() * 2);
				AABB segmentBounds = AllShapes.TRACK_ORTHO.get(Direction.SOUTH)
					.bounds();
				segmentBounds = segmentBounds.move(-.5, segmentBounds.getYsize() / -2, -.5);

				int bestSegment = -1;
				double bestDistance = Double.MAX_VALUE;
				double newMaxRange = maxRange;

				for (int i = 0; i < stepLUT.length - 2; i++) {
					float t = stepLUT[i] * i / segments;
					float t1 = stepLUT[i + 1] * (i + 1) / segments;
					float t2 = stepLUT[i + 2] * (i + 2) / segments;

					Vec3 v1 = bc.getPosition(t);
					Vec3 v2 = bc.getPosition(t2);
					Vec3 diff = v2.subtract(v1);
					Vec3 angles = TrackRenderer.getModelAngles(bc.getNormal(t1), diff);

					Vec3 anchor = v1.add(diff.scale(.5));
					Vec3 localOrigin = origin.subtract(anchor);
					Vec3 localDirection = target.subtract(origin);
					localOrigin = VecHelper.rotate(localOrigin, AngleHelper.deg(-angles.x), Axis.X);
					localOrigin = VecHelper.rotate(localOrigin, AngleHelper.deg(-angles.y), Axis.Y);
					localDirection = VecHelper.rotate(localDirection, AngleHelper.deg(-angles.x), Axis.X);
					localDirection = VecHelper.rotate(localDirection, AngleHelper.deg(-angles.y), Axis.Y);

					Optional<Vec3> clip = segmentBounds.clip(localOrigin, localOrigin.add(localDirection));
					if (clip.isEmpty())
						continue;

					if (bestSegment != -1 && bestDistance < clip.get()
						.distanceToSqr(0, 0.25f, 0))
						continue;

					double distanceToSqr = clip.get()
						.distanceToSqr(localOrigin);
					if (distanceToSqr > maxRange)
						continue;

					bestSegment = i;
					newMaxRange = distanceToSqr;
					bestDistance = clip.get()
						.distanceToSqr(0, 0.25f, 0);

					BezierTrackPointLocation location = new BezierTrackPointLocation(bc.getKey(), i);
					result = new BezierPointSelection(be, location, anchor, angles, diff.normalize());
				}

				if (bestSegment != -1)
					maxRange = newMaxRange;
			}
		}

		if (result == null)
			return;

		if (mc.hitResult != null && mc.hitResult.getType() != Type.MISS) {
			Vec3 priorLoc = mc.hitResult.getLocation();
			mc.hitResult = BlockHitResult.miss(priorLoc, Direction.UP, BlockPos.containing(priorLoc));
		}
	}
```

（注意 `:70` 用的是 `be.connections`（package-private 字段），本 mixin 在不同包 ⇒ 改写版必须用 public 的 `be.getConnections()`（`TrackBlockEntity.java:68-70`）。）

## 3. 改后完整可粘贴实现

### 3.1 核心思路与正确性论证

**粗筛（真正的大头）**：原实现对第 `i` 段的判定，是把射线变换到「以 `anchor_i` 为中心」的 `segmentBounds` 局部坐标系后做盒相交（`:99-107`）。由于旋转与平移是等距变换，可以推出：**只有当 `anchor_i` 到射线段的距离 ≤ `segmentBounds` 的半对角线 `R` 时，该段才可能通过裁剪测试**（交点必须落在盒内 ⇒ 该点到盒心距离 ≤ R ⇒ 该点在射线上 ⇒ 点到射线的距离 ≤ R）。于是：

- 每个 `BezierConnection` **只算一次**每段中点 `anchor_i` 并缓存成 `float[]`（键 = 连接对象身份，几何字段 `starts/axes/normals` 是 `final`）；
- 每帧对每段只做一次标量「点到直线距离」（叉积），距离 > R 的段直接跳过，**不做任何 `Vec3`/`VecHelper` 调用**；
- 只对通过的 1–3 段执行原样的精确数学。

保守性：用「点到**无限直线**的距离」代替「点到有限线段的距离」只会更小 ⇒ 保留更多段 ⇒ 绝不漏判。`R` 由 `segmentBounds` 的尺寸实时算出并乘 1.02 余量。

**等价性**：被粗筛跳过的段在原逻辑里必然 `clip.isEmpty()`（因为 `R` 是保守上界），因此 `bestSegment`/`bestDistance`/`newMaxRange` `/maxRange`/`result` 的演化与原实现逐项一致；迭代顺序仍是 `i` 升序；`result` 的赋值点与顺序也保持一致（只是把 `new BezierPointSelection(...)` 延迟到全部循环结束后构造一次）。

### 3.2 完整代码

```java
package com.example.createperf.mixin.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.AllShapes;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.BezierTrackPointLocation;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackBlockOutline;
import com.simibubi.create.content.trains.track.TrackBlockOutline.BezierPointSelection;
import com.simibubi.create.content.trains.track.TrackRenderer;
import com.simibubi.create.foundation.utility.RaycastHelper;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;

@Mixin(value = TrackBlockOutline.class, remap = false)
public abstract class TrackBlockOutlinePickCurvesMixin {

    // ---- 预建 segmentBounds（原文每个连接都重建一次：AllShapes...bounds() + move() = 2 个 AABB/连接） ----
    @Unique private static final AABB CREATEPERF_SEGMENT_BOUNDS = createperf$makeSegmentBounds();

    @Unique
    private static AABB createperf$makeSegmentBounds() {
        AABB raw = AllShapes.TRACK_ORTHO.get(Direction.SOUTH).bounds();
        return raw.move(-.5, raw.getYsize() / -2, -.5);
    }

    // ---- 粗筛半径：segmentBounds 的半对角线 × 1.02（保守上界；用实际尺寸算，不硬编码） ----
    @Unique private static final double CREATEPERF_ANCHOR_RADIUS = createperf$radius();

    @Unique
    private static double createperf$radius() {
        AABB b = CREATEPERF_SEGMENT_BOUNDS;
        double hx = b.getXsize() * .5;
        double hy = b.getYsize() * .5;
        double hz = b.getZsize() * .5;
        return Math.sqrt(hx * hx + hy * hy + hz * hz) * 1.02;
    }

    // ---- 每连接的 anchor 缓存（几何不变 -> 键用对象身份；有界 LRU 防无限增长） ----
    @Unique private static final int CREATEPERF_CACHE_MAX = 256;

    @Unique
    private static final Map<BezierConnection, float[]> CREATEPERF_ANCHORS =
        Collections.synchronizedMap(new LinkedHashMap<>(64, .75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<BezierConnection, float[]> eldest) {
                return size() > CREATEPERF_CACHE_MAX;
            }
        });

    /** 返回 [ax, ay, az] × count 的扁平数组，count = stepLUT.length - 2（与原循环次数一致）。 */
    @Unique
    private static float[] createperf$anchors(BezierConnection bc) {
        float[] cached = CREATEPERF_ANCHORS.get(bc);
        if (cached != null)
            return cached;

        float[] stepLUT = bc.getStepLUT();
        int segments = (int) (bc.getLength() * 2);
        int count = Math.max(0, stepLUT.length - 2);
        float[] out = new float[count * 3];
        for (int i = 0; i < count; i++) {
            float t = stepLUT[i] * i / segments;
            float t2 = stepLUT[i + 2] * (i + 2) / segments;
            Vec3 v1 = bc.getPosition(t);
            Vec3 v2 = bc.getPosition(t2);
            // anchor = v1 + (v2 - v1) * .5 = (v1 + v2) / 2 —— 与原式恒等
            out[i * 3] = (float) ((v1.x + v2.x) * .5);
            out[i * 3 + 1] = (float) ((v1.y + v2.y) * .5);
            out[i * 3 + 2] = (float) ((v1.z + v2.z) * .5);
        }
        CREATEPERF_ANCHORS.put(bc, out);
        return out;
    }

    @Inject(method = "pickCurves()V", at = @At("HEAD"), cancellable = true)
    private static void createperf$pickCurves(CallbackInfo ci) {
        createperf$pickCurvesFast();
        ci.cancel();     // 等价于整段重写；原方法体保留，其它 mod 的 @Inject 不会被顶掉
    }

    @Unique
    private static void createperf$pickCurvesFast() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.cameraEntity instanceof LocalPlayer player))
            return;
        if (mc.level == null)
            return;

        Vec3 origin = player.getEyePosition(AnimationTickHolder.getPartialTicks(mc.level));

        double maxRange = mc.hitResult == null ? Double.MAX_VALUE
            : mc.hitResult.getLocation()
                .distanceToSqr(origin);

        TrackBlockOutline.result = null;

        double range = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        Vec3 target = RaycastHelper.getTraceTarget(player, Math.min(maxRange, range) + 1, origin);
        Map<BlockPos, TrackBlockEntity> turns = TrackBlockOutline.TRACKS_WITH_TURNS.get(mc.level);

        // 射线方向（与 i 无关 -> 提到循环外，原实现每段都 target.subtract(origin)）
        double dirX = target.x - origin.x;
        double dirY = target.y - origin.y;
        double dirZ = target.z - origin.z;
        double dirLenSqr = dirX * dirX + dirY * dirY + dirZ * dirZ;
        double radiusSqrTimesLen = CREATEPERF_ANCHOR_RADIUS * CREATEPERF_ANCHOR_RADIUS * dirLenSqr;

        // result 的延迟构造（原实现每个"改进命中"的段都 new 两个 record）
        TrackBlockEntity pendingBe = null;
        BezierConnection pendingBc = null;
        int pendingSegment = -1;
        double pAnchorX = 0, pAnchorY = 0, pAnchorZ = 0;
        double pAngleX = 0, pAngleY = 0, pAngleZ = 0;
        double pDiffX = 0, pDiffY = 0, pDiffZ = 0;

        for (TrackBlockEntity be : turns.values()) {
            for (BezierConnection bc : be.getConnections().values()) {     // 原为 package-private 字段
                if (!bc.isPrimary())
                    continue;

                AABB bounds = bc.getBounds();
                if (!bounds.contains(origin) && bounds.clip(origin, target)
                    .isEmpty())
                    continue;

                float[] stepLUT = bc.getStepLUT();
                int segments = (int) (bc.getLength() * 2);
                AABB segmentBounds = CREATEPERF_SEGMENT_BOUNDS;           // 不再每连接重建
                float[] anchors = createperf$anchors(bc);
                int count = anchors.length / 3;

                int bestSegment = -1;
                double bestDistance = Double.MAX_VALUE;
                double newMaxRange = maxRange;

                for (int i = 0; i < count; i++) {
                    // ===== 粗筛：anchor_i 到射线的距离 > R 的段不可能通过裁剪 =====
                    double ox = anchors[i * 3] - origin.x;
                    double oy = anchors[i * 3 + 1] - origin.y;
                    double oz = anchors[i * 3 + 2] - origin.z;
                    double cx = oy * dirZ - oz * dirY;
                    double cy = oz * dirX - ox * dirZ;
                    double cz = ox * dirY - oy * dirX;
                    if (cx * cx + cy * cy + cz * cz > radiusSqrTimesLen)
                        continue;

                    // ===== 以下与原实现逐行一致 =====
                    float t = stepLUT[i] * i / segments;
                    float t1 = stepLUT[i + 1] * (i + 1) / segments;
                    float t2 = stepLUT[i + 2] * (i + 2) / segments;

                    Vec3 v1 = bc.getPosition(t);
                    Vec3 v2 = bc.getPosition(t2);
                    Vec3 diff = v2.subtract(v1);
                    Vec3 angles = TrackRenderer.getModelAngles(bc.getNormal(t1), diff);

                    Vec3 anchor = v1.add(diff.scale(.5));
                    Vec3 localOrigin = origin.subtract(anchor);
                    Vec3 localDirection = target.subtract(origin);
                    localOrigin = VecHelper.rotate(localOrigin, AngleHelper.deg(-angles.x), Axis.X);
                    localOrigin = VecHelper.rotate(localOrigin, AngleHelper.deg(-angles.y), Axis.Y);
                    localDirection = VecHelper.rotate(localDirection, AngleHelper.deg(-angles.x), Axis.X);
                    localDirection = VecHelper.rotate(localDirection, AngleHelper.deg(-angles.y), Axis.Y);

                    Optional<Vec3> clip = segmentBounds.clip(localOrigin, localOrigin.add(localDirection));
                    if (clip.isEmpty())
                        continue;

                    if (bestSegment != -1 && bestDistance < clip.get()
                        .distanceToSqr(0, 0.25f, 0))
                        continue;

                    double distanceToSqr = clip.get()
                        .distanceToSqr(localOrigin);
                    if (distanceToSqr > maxRange)
                        continue;

                    bestSegment = i;
                    newMaxRange = distanceToSqr;
                    bestDistance = clip.get()
                        .distanceToSqr(0, 0.25f, 0);

                    // 只记录，不构造（normalize 延迟到最终一次；vanilla Vec3.normalize 对零向量返回 ZERO，无异常）
                    pendingBe = be;
                    pendingBc = bc;
                    pendingSegment = i;
                    pAnchorX = anchor.x;
                    pAnchorY = anchor.y;
                    pAnchorZ = anchor.z;
                    pAngleX = angles.x;
                    pAngleY = angles.y;
                    pAngleZ = angles.z;
                    pDiffX = diff.x;
                    pDiffY = diff.y;
                    pDiffZ = diff.z;
                }

                if (bestSegment != -1)
                    maxRange = newMaxRange;
            }
        }

        if (pendingSegment == -1)
            return;                                  // 等价于原实现的 result == null

        Vec3 anchor = new Vec3(pAnchorX, pAnchorY, pAnchorZ);
        Vec3 angles = new Vec3(pAngleX, pAngleY, pAngleZ);
        Vec3 diff = new Vec3(pDiffX, pDiffY, pDiffZ);
        BezierTrackPointLocation location = new BezierTrackPointLocation(pendingBc.getKey(), pendingSegment);
        TrackBlockOutline.result = new BezierPointSelection(pendingBe, location, anchor, angles, diff.normalize());

        if (mc.hitResult != null && mc.hitResult.getType() != Type.MISS) {
            Vec3 priorLoc = mc.hitResult.getLocation();
            mc.hitResult = BlockHitResult.miss(priorLoc, Direction.UP, BlockPos.containing(priorLoc));
        }
    }
}
```

## 4. 需要的 `@Unique` 字段 / helper / import

已全部列在 3.2 的代码里，汇总：

| 成员 | 作用 | 生命周期 |
| --- | --- | --- |
| `CREATEPERF_SEGMENT_BOUNDS` | 预建 `segmentBounds`（原 `:81-83` 每连接重建 2 个 AABB） | 类初始化期一次性求值；静态常量 |
| `CREATEPERF_ANCHOR_RADIUS` | 粗筛半径 = 半对角线 × 1.02 | 同上；由 `CREATEPERF_SEGMENT_BOUNDS` 的实际尺寸算出 |
| `CREATEPERF_ANCHORS` | `BezierConnection → float[]（每段中点） | 有界 LRU（256 条）；键 = 连接身份；**不持有 `Level`/`Entity` 引用**，只持有连接对象（连接由 `TrackBlockEntity$connections` 持有，本身不会泄漏） |
| `createperf$anchors` | 惰性构建 anchor 数组 | 每连接一次 |
| `pendingXxx` 局部量 | 延迟构造 `BezierPointSelection` | 每次调用内的局部变量（**不是字段**，所以可重入/无并发问题） |

- **并发**：`pickCurves` 由 `GameRenderer.pick(F)V` 调用，而 `pick` 在 `Minecraft.runTick` 里执行 ⇒ **客户端主线程**（不是渲染线程）。`CREATEPERF_ANCHORS` 仍包了 `Collections.synchronizedMap` 作为额外保险（防止别的路径意外访问）；若确认只有主线程，可去掉同步包装减少开销。
- **反注册/失效**：连接几何不变（`starts/axes/normals` 均 `final`，见 `BezierConnection.java:42-50`），因此只要 `BezierConnection` 对象不变，anchor 数组永不失效；LRU 上限保证内存有界（256 条 × 80 段 × 3 float ≈ 245 KB）。
- 需要的 import 已列全（注意 `TrackBlockOutline.BezierPointSelection` 是嵌套 record，`BezierTrackPointLocation` 是顶层 record）。

## 5. `@Overwrite` / `@Inject(HEAD)` 风险

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| **交互语义** | `pickCurves` 直接写 `TrackBlockOutline.result`（`:48` public static）并改写 `mc.hitResult`（`:137-140`），是轨道选取/放置的核心 | 3.2 与原实现逐行对齐；验证方法要求对 `result` **逐位比对** |
| 粗筛半径偏小 | 若 `R` 算小了，会**漏判**（轨道点不中） | `R` 由 `segmentBounds` 实际尺寸算出并乘 1.02；面板上可临时把 `1.02` 调到 `2.0` 验证「调大不影响结果」，从而证明 `R` 足够 |
| 粗筛用无限直线距离 | 只比有限线段更保守（保留更多段），不会漏判 | 无需处理 |
| anchor 缓存与几何变化 | `BezierConnection` 的 `starts/axes/normals` 是 `final`；若上游未来支持「就地修改曲线」，缓存会失效 | 用 `@Unique` 的 LRU 并注释该前提；上游一旦改动需同步（`defaultRequire` 不会帮你发现这种语义变化） |
| `diff.normalize()` 延迟 | vanilla `Vec3.normalize()` 对零长度返回 `Vec3.ZERO`，不抛异常，因此延迟与原「赋值时立刻 normalize」结果相同 | 已核对语义；若对某版本行为不确定，可改为在记录时就 `normalize()`（代价是每个改进命中的段多 1 个 `Vec3`） |
| 顶掉其它 mod 的 `@Inject` | 用 `@Inject(HEAD, cancellable = true)` 而非 `@Overwrite`，原方法体保留，其它 mod 对 `pickCurves` 的 `@Inject` 仍会被调用（只是执行不到，因为我们在 HEAD 就 cancel） | 若与其它 mod 冲突，改为「只包裹 `:89` 的循环」不现实——此时应放弃本条或与对方协调 |
| 与 Create 自带 mixin 的关系 | Create 的 `GameRendererMixin` 只是**调用** `pickCurves`，不注入 `pickCurves` 本身 ⇒ 无冲突 | 无需处理 |

## 6. 回归测试点

1. **逐位一致性（最重要）**：在同一存档、同一位置、同一朝向（可用 `/tp` 固定到小数坐标）下，比较改前/改后 `TrackBlockOutline.result` 的：
   `be.getBlockPos()`、`loc.curveTarget()`、`loc.segment()`、`anchor` 三分量、`angles` 三分量、`direction` 三分量（浮点用严格相等；若因浮点差异不等，说明实现有偏差，必须修正）。
   建议覆盖：直道、40 格 S 弯、斜坡（`CR_PD/CR_ND`）、交叉口（`CR_O/CR_D`）、双轨并行、多层高架（不同 Y）。
2. 交互：站在弯道上用轨道扳手/放置轨道，确认高亮段与接管段与改前一致；注视弯道边缘（`R` 临界）来回移动，确认不会「整条弯道突然点不中」。
3. `mc.hitResult`：准星在弯道上时原版方块命中被改成 MISS 的行为保持不变（`:137-140`）。
4. 性能：探针统计「内层迭代次数」「实际执行精确数学的次数」「构造的对象数」；期望 40 格弯道从约 79 段精确数学降到 1–3 段。用 JFR `ObjectAllocationSample` 过滤 `Vec3` 复核主线程 `GameRenderer.pick` 栈下的分配下降。
5. 注入确认：`-Dmixin.debug.export=true` 后反汇编 `TrackBlockOutline.class`，确认 `pickCurves` 开头调用你的 `@Unique` 实现。
6. 边界：`mc.level == null`、非 `LocalPlayer`（相机实体为其它实体）、`mc.hitResult` 为 `MISS`/`null` 的三条早退路径都要跑到（会直接影响 `maxRange` 的初值）。
