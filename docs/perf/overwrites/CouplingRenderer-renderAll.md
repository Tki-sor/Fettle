# Overwrite 施工图：`CouplingRenderer.renderAll`（加距离剔除）

> 目标方法：`com.simibubi.create.content.contraptions.minecart.CouplingRenderer#renderAll(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/phys/Vec3;)V`（`static`）
> 对应发现：[07-client-render-ui.md](../07-client-render-ui.md) 发现 6（性能部分）
> 公共约定见 [01-mixin-playbook.md](../01-mixin-playbook.md)

## 1. 为什么需要（以及为什么用 `@Inject(HEAD, cancellable)` 而不是 `@Overwrite`）

原实现把「逐 coupling 处理」写在一个 lambda 里交给 `forEachLoadedCoupling`，剔除逻辑必须插在**消费每个 coupling 之前**。三个可选做法：

1. `@Overwrite` 整个方法（20 行不到，风险低）；
2. `@Inject(HEAD, cancellable = true)` + `@Unique` 复制体 + `ci.cancel()`（**推荐**：原方法体保留，别的 mod 若也 `@Inject` 进 `renderAll` 不会被顶掉）；
3. `@WrapOperation` 包裹 `forEachLoadedCoupling` 调用——handler 能拿到 `(world, consumer)`，但 consumer 内部才做 `cart()` 映射，剔除所需的位置信息在 wrapper 里拿不到（除非再包一层），因此不推荐。

本文件按第 2 种给出完整方法体（也可直接当 `@Overwrite` 用）。

## 2. 原方法完整源码（`CouplingRenderer.java:34-41`，逐字摘录）

```java
	public static void renderAll(PoseStack ms, MultiBufferSource buffer, Vec3 camera) {
		CouplingHandler.forEachLoadedCoupling(Minecraft.getInstance().level, c -> {
			if (c.getFirst()
				.hasContraptionCoupling(true))
				return;
			CouplingRenderer.renderCoupling(ms, buffer, camera, c.map(MinecartController::cart));
		});
	}
```

对照 `CarriageCouplingRenderer.java:54`：列车耦合有 `if (!anchor.closerThan(camera, 64)) continue;`，矿车耦合**没有**任何距离/视锥剔除。

## 3. 改后完整可粘贴实现

```java
	@Unique private static final double CREATEPERF_MAX_DIST_SQR = 64 * 64;

	@Inject(
		method = "renderAll(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/phys/Vec3;)V",
		at = @At("HEAD"), cancellable = true)
	private static void createperf$renderAll(PoseStack ms, MultiBufferSource buffer, Vec3 camera, CallbackInfo ci) {
		CouplingHandler.forEachLoadedCoupling(Minecraft.getInstance().level, c -> {
			if (c.getFirst()
				.hasContraptionCoupling(true))
				return;

			Couple<AbstractMinecart> carts = c.map(MinecartController::cart);
			AbstractMinecart first = carts.getFirst();
			AbstractMinecart second = carts.getSecond();
			if (first == null || second == null)          // renderCoupling 内也有该判断，提前做省一次调用
				return;

			// 用中间点做标量距离判断：不调用 Entity.position()（它会 new Vec3，等于为剔除反而分配）
			double mx = (first.getX() + second.getX()) * .5 - camera.x;
			double my = (first.getY() + second.getY()) * .5 - camera.y;
			double mz = (first.getZ() + second.getZ()) * .5 - camera.z;
			if (mx * mx + my * my + mz * mz > CREATEPERF_MAX_DIST_SQR)
				return;

			CouplingRenderer.renderCoupling(ms, buffer, camera, carts);
		});
		ci.cancel();
	}
```

**阈值选择**：`64` 格直接对齐 `CarriageCouplingRenderer` 的既有常量（`:54` `closerThan(camera, 64)`），风险最低。原版实体渲染距离通常小于此值，因此剔除不会造成可见的「耦合杆突然消失」；**但 64 这个数需要实测**（见第 6 节），若要更保守可放大到 96。

## 4. 需要的 `@Unique` / import

```java
package com.example.createperf.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.minecart.CouplingHandler;
import com.simibubi.create.content.contraptions.minecart.CouplingRenderer;
import com.simibubi.create.content.contraptions.minecart.capability.MinecartController;

import net.createmod.catnip.data.Couple;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.phys.Vec3;

@Mixin(value = CouplingRenderer.class, remap = false)
public abstract class CouplingRendererMixin {
    // 第 3 节的 @Unique 常量 + @Inject 方法
}
```

- **无新增可变状态**：剔除用纯标量，方法本身是每帧一次的静态调用。这使它成为「零状态、单点注入」的 Batch A 条目（与主文件发现 6 的评级一致）。
- 本 mixin 只影响客户端渲染，应放进 `createperf.mixins.json` 的 `"client"` 数组。

## 5. `@Overwrite` 风险（若选择 Overwrite 形态）

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| `hasContraptionCoupling(true)` 语义 | 装置耦合不画普通杆，必须保留 | 已保留 |
| `null` 车厢 | `MinecartController.cart()` 可能为 null（实体未加载）；原实现靠 `renderCoupling` 内的判断兜底 | 已提前判断，且不改变后续行为 |
| 距离阈值 | 过小会导致耦合杆在可视范围内消失 | 对齐 `CarriageCouplingRenderer` 的 64；实测后调整 |
| 渲染层级 | `renderAll` 在 `RenderLevelStageEvent.Stage.AFTER_PARTICLES`（`ClientEvents.java:237,248`）执行，剔除不改变绘制顺序 | 无需处理 |

## 6. 回归测试点

1. 视觉：在 64 格内/外、正对/背对、不同光照（夜间）下观察耦合杆外观；用 `/tp` 在临界距离来回移动，确认没有「闪烁出现/消失」以外的异常（**闪烁本身也可能发生，只是 64 > 实体渲染距离时不可见**）。
2. 联挂运动：耦合矿车在轨道上运动时，耦合杆两端始终贴合两辆车（距离剔除不改变杆的几何）。
3. 性能：统计 `renderAll` 进入时的 coupling 总数与通过剔除的数量（`@Unique static long` 计数器），期望后者随距离显著小于前者。
4. 与发现 6 的 bug 修复一起验证：修完 `forEachLoadedCoupling` 后 coupling 数变多，剔除的意义更大——两者应一起上线、一起测。
