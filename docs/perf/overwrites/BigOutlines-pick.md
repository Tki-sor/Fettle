# Overwrite 施工图：`BigOutlines.pick`

> 目标方法：`com.simibubi.create.foundation.block.BigOutlines#pick()V`（`static`）
> 对应发现：[07-client-render-ui.md](../07-client-render-ui.md) 发现 4（Low-Medium）
> 公共约定见 [01-mixin-playbook.md](../01-mixin-playbook.md)

## 1. 为什么需要 Overwrite

三件事都发生在方法体内部，局部注入做不到：

1. `:45` 每步 `BlockPos.ZERO.mutable()`——`NEW` 指令先于可被 `@Redirect` 的构造调用（手册 3.1 / 3.2 节），只能重写方法体来复用；
2. `:61-63`、`:74-76` 的 `Vec3.atCenterOf` 调用点嵌在复合条件里，`@ModifyExpressionValue` 无法让同一次迭代里的两次调用共享一个值；
3. 「每步只扫新增切片」是对 `:47-51` 三层循环的**结构性改写**，没有等价的注入点。

方法只有 41 行（`:26-88`），Overwrite 的脆性可控。

## 2. 原方法完整源码（`BigOutlines.java:26-88`，逐字摘录）

```java
	public static void pick() {
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.cameraEntity instanceof LocalPlayer player))
			return;
		if (mc.level == null)
			return;

		result = null;

		Vec3 origin = player.getEyePosition(AnimationTickHolder.getPartialTicks(mc.level));

		double maxRange = mc.hitResult == null ? Double.MAX_VALUE
			: mc.hitResult.getLocation()
				.distanceToSqr(origin) + 0.5;

		double range = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
		Vec3 target = RaycastHelper.getTraceTarget(player, Math.min(maxRange, range) + 1, origin);

		RaycastHelper.rayTraceUntil(origin, target, pos -> {
			MutableBlockPos p = BlockPos.ZERO.mutable();

			for (int x = -1; x <= 1; x++) {
				for (int y = -1; y <= 1; y++) {
					for (int z = -1; z <= 1; z++) {
						p.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
						BlockState blockState = mc.level.getBlockState(p);

						if (!(blockState.getBlock() instanceof IHaveBigOutline))
							continue;

						BlockHitResult hit = blockState.getInteractionShape(mc.level, p)
								.clip(origin, target, p.immutable());
						if (hit == null)
							continue;

						if (result != null && Vec3.atCenterOf(p)
								.distanceToSqr(origin) >= Vec3.atCenterOf(result.getBlockPos())
								.distanceToSqr(origin))
							continue;

						Vec3 vec = hit.getLocation();
						double interactionDist = vec.distanceToSqr(origin);
						if (interactionDist >= maxRange)
							continue;

						BlockPos hitPos = hit.getBlockPos();

						// pacifies ServerGamePacketListenerImpl.handleUseItemOn
						vec = vec.subtract(Vec3.atCenterOf(hitPos));
						vec = VecHelper.clampComponentWise(vec, 1);
						vec = vec.add(Vec3.atCenterOf(hitPos));

						result = new BlockHitResult(vec, hit.getDirection(), hitPos, hit.isInside());
					}
				}
			}

			return result != null;
		});

		if (result != null)
			mc.hitResult = result;
	}
```

## 3. 改后完整可粘贴方法体

```java
	@Overwrite
	public static void pick() {
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.cameraEntity instanceof LocalPlayer player))
			return;
		if (mc.level == null)
			return;

		result = null;

		Vec3 origin = player.getEyePosition(AnimationTickHolder.getPartialTicks(mc.level));

		double maxRange = mc.hitResult == null ? Double.MAX_VALUE
			: mc.hitResult.getLocation()
				.distanceToSqr(origin) + 0.5;

		double range = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
		Vec3 target = RaycastHelper.getTraceTarget(player, Math.min(maxRange, range) + 1, origin);

		createperf$hasPrev = false;

		RaycastHelper.rayTraceUntil(origin, target, pos -> {
			int px = pos.getX();
			int py = pos.getY();
			int pz = pos.getZ();

			if (!createperf$hasPrev) {
				// 首步：完整 3x3x3（与原实现一致）
				for (int x = -1; x <= 1; x++)
					for (int y = -1; y <= 1; y++)
						for (int z = -1; z <= 1; z++)
							createperf$testCell(mc.level, origin, target, px + x, py + y, pz + z, maxRange);
			} else {
				// 后继步：RaycastHelper 每步只沿一个轴前进 ±1（RaycastHelper.java:136-149），
				// 因此 cube(cur) 相对 cube(prev) 的新增格 = 变化轴方向上 r == delta 的那些格。
				// 用 27 次纯算术判断（不查方块）把 getBlockState 调用压到 9/18/19 次。
				int dx = px - createperf$prev.getX();
				int dy = py - createperf$prev.getY();
				int dz = pz - createperf$prev.getZ();

				if (dx != 0 || dy != 0 || dz != 0) {   // 位置没变则 27 格上一步已测过
					for (int rx = -1; rx <= 1; rx++) {
						for (int ry = -1; ry <= 1; ry++) {
							for (int rz = -1; rz <= 1; rz++) {
								boolean isNew = (dx != 0 && rx == dx)
									|| (dy != 0 && ry == dy)
									|| (dz != 0 && rz == dz);
								if (!isNew)
									continue;
								createperf$testCell(mc.level, origin, target, px + rx, py + ry, pz + rz, maxRange);
							}
						}
					}
				}
			}

			createperf$prev.set(px, py, pz);
			createperf$hasPrev = true;
			return result != null;
		});

		if (result != null)
			mc.hitResult = result;
	}

	@Unique
	private static void createperf$testCell(ClientLevel level, Vec3 origin, Vec3 target,
		int x, int y, int z, double maxRange) {
		createperf$scratch.set(x, y, z);
		BlockState blockState = level.getBlockState(createperf$scratch);

		if (!(blockState.getBlock() instanceof IHaveBigOutline))
			return;

		BlockHitResult hit = blockState.getInteractionShape(level, createperf$scratch)
			.clip(origin, target, createperf$scratch.immutable());
		if (hit == null)
			return;

		// 只算命中的这个格与当前 result 两个中心（原实现每次命中算 4 次 atCenterOf）
		if (result != null) {
			BlockPos rp = result.getBlockPos();
			double dCandidate = createperf$centerDistSqr(x, y, z, origin);
			double dCurrent = createperf$centerDistSqr(rp.getX(), rp.getY(), rp.getZ(), origin);
			if (dCandidate >= dCurrent)
				return;
		}

		Vec3 vec = hit.getLocation();
		double interactionDist = vec.distanceToSqr(origin);
		if (interactionDist >= maxRange)
			return;

		BlockPos hitPos = hit.getBlockPos();

		// pacifies ServerGamePacketListenerImpl.handleUseItemOn
		Vec3 center = Vec3.atCenterOf(hitPos);
		vec = vec.subtract(center);
		vec = VecHelper.clampComponentWise(vec, 1);
		vec = vec.add(center);

		result = new BlockHitResult(vec, hit.getDirection(), hitPos, hit.isInside());
	}

	// Vec3.atCenterOf(p).distanceToSqr(origin) 的标量展开（零分配，数学等价）
	@Unique
	private static double createperf$centerDistSqr(int x, int y, int z, Vec3 origin) {
		double cx = x + .5 - origin.x;
		double cy = y + .5 - origin.y;
		double cz = z + .5 - origin.z;
		return cx * cx + cy * cy + cz * cz;
	}
```

**等价性论证（为什么「只测新增格」逐位等价于「每步测 27 格」）**

设第 `i` 步的整数位置为 `p_i`，原实现测的集合是 `cube(p_i)`。增量实现测的是 `cube(p_i) \ cube(p_{i-1})`（首步为完整 `cube(p_1)`）。归纳可知「到第 `i` 步为止已测过的并集」始终 ⊇ `cube(p_i)`，因此**任何**原实现能命中的格子都一定被测过。又因为原实现在第 `i` 步能产生命中的格子若属于 `cube(p_{i-1})`，则它在第 `i-1` 步就已经产生同一命中并让谓词返回 `true` 结束遍历——所以「实际导致命中的格子」在第 `i` 步恰好就是新增格，而新增格在两种实现里都按 `(rx, ry, rz)` 的 x→y→z 顺序测试，且 `:61-64` 的取舍是「严格更近才替换」，因此最终 `result` 与 `:86-87` 的 `mc.hitResult` 赋值**逐位相同**（前提：`getBlockState/getInteractionShape/clip` 是纯函数，成立）。

## 4. 需要的 `@Unique` / `@Shadow` / import

```java
package com.example.createperf.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.simibubi.create.foundation.block.BigOutlines;
import com.simibubi.create.foundation.block.IHaveBigOutline;
import com.simibubi.create.foundation.utility.RaycastHelper;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

@Mixin(value = BigOutlines.class, remap = false)
public abstract class BigOutlinesOverwriteMixin {

    // result 是 package-private static（BigOutlines.java:24）-> 不同包必须 @Shadow
    @Shadow private static BlockHitResult result;

    @Unique private static final BlockPos.MutableBlockPos createperf$scratch = new BlockPos.MutableBlockPos();
    @Unique private static final BlockPos.MutableBlockPos createperf$prev = new BlockPos.MutableBlockPos();
    @Unique private static boolean createperf$hasPrev;

    // 第 3 节的方法体（pick + 两个 @Unique helper）
}
```

- **字段生命周期**：`作用域`＝客户端进程级静态复用（三个 `@Unique` 字段）；`失效时机`＝每次 `pick()` 入口重置 `createperf$hasPrev = false`（`prev` 只在 `hasPrev == true` 时被读取，因此不需要主动清除）；`并发`＝`GameRenderer.pick` 只在客户端 tick 线程执行（**不是**渲染线程），单线程使用这些可变静态字段安全。
- 注意 `AnimationTickHolder` 的 import 不能漏（原方法 `:35` 用到）。

## 5. `@Overwrite` 风险

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| 与其它 mod 冲突 | `BigOutlines` 是给第三方 mod 用的 API（`IHaveBigOutline` 注释），可能有别的 mod 也盯上它 | 优先用 `@Inject(HEAD, cancellable = true)` + `@Unique` 复制体（等价、不动原方法体）；本文件给 `@Overwrite` 形态只为「完整可粘贴」 |
| 行为差异 | 只有「位置没变」的极端情况下会跳过（此时上一步已测过同一批格子，结论相同） | 用第 6 节的逐位一致性验证兜底 |
| `mc.hitResult` 写入 | `:86-87` 只在 `result != null` 时覆盖，且 `:37-39` 的 `+ 0.5` 必须照抄 | 已照抄 |
| 上游重构 | `BigOutlines.pick` 若改成基于 AABB 的扫描，`@Overwrite` 在启动期报错（`defaultRequire = 1`） | 期望行为 |

## 6. 回归测试点

1. **逐位一致性**：同一存档、同一视角下，把 `BigOutlines.result` 的 `(getBlockPos(), getLocation(), getDirection(), isInside())` 在改前/改后各打印一次并比对；覆盖 4 种实现方块（`TrackBlock`、`SlidingDoorBlock`、`ChainConveyorBlock`、`TableClothBlock`）与「无任何实现方块」的存档。
2. 计数器：把 `level.getBlockState` 的调用次数前后对比（改前可临时加一个 `@Redirect` 计数），6 步场景应为 162 → 72。
3. 交互：右键大轮廓方块（轨道连接、滑动门、链式传送带、桌布），确认命中位置与服务端 `handleUseItemOn` 的结果一致（`:73` 的 clamp 修正不能被破坏）。
4. 无目标时不改写 `mc.hitResult`（否则会屏蔽原版方块命中）。
