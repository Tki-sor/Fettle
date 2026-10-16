# Overwrite 施工图：`TrainHatInfoReloadListener.getHatInfoFor`（每帧常量提升）

> 目标方法：`com.simibubi.create.content.trains.schedule.hat.TrainHatInfoReloadListener#getHatInfoFor(Lnet/minecraft/world/entity/Entity;)Lcom/simibubi/create/content/trains/schedule/hat/TrainHatInfo;`（`static`）
> 对应发现：[07-client-render-ui.md](../07-client-render-ui.md) 发现 11（Low）
> 公共约定见 [01-mixin-playbook.md](../01-mixin-playbook.md)

## 1. 为什么不是 `@ModifyExpressionValue`

要消除的是 `new TrainHatInfo(...)` 与 `new Vec3(...)` 这两个**分配**。`@ModifyExpressionValue` 修饰 `new` 表达式时，`NEW` 指令已经执行（手册 3.2 节），分配依旧发生；因此必须用 `@Inject(HEAD, cancellable = true)` 在雪傀儡分支上**直接从方法头返回常量**。方法体只有 7 行，注入的语义面极小。

## 2. 原方法完整源码（`TrainHatInfoReloadListener.java:51-58`，逐字摘录）

```java
	public static TrainHatInfo getHatInfoFor(Entity entity) {
		// Manual override for snow golems, they are a special case when they have a pumpkin on their head
		if (entity instanceof SnowGolem snowGolem && snowGolem.hasPumpkin()) {
			return new TrainHatInfo("", 0, new Vec3(0.0F, -3.0F, 0.0F), 1.18F);
		}

		return ENTITY_INFO_MAP.getOrDefault(entity.getType(), DEFAULT);
	}
```

（`ENTITY_INFO_MAP` 与 `DEFAULT`（`:27`）本身已是静态常量/查表，不需要优化。）

## 3. 改后完整可粘贴实现

```java
	@Unique
	private static final TrainHatInfo CREATEPERF_SNOW_GOLEM_HAT =
		new TrainHatInfo("", 0, new Vec3(0.0F, -3.0F, 0.0F), 1.18F);

	@Inject(
		method = "getHatInfoFor(Lnet/minecraft/world/entity/Entity;)Lcom/simibubi/create/content/trains/schedule/hat/TrainHatInfo;",
		at = @At("HEAD"), cancellable = true)
	private static void createperf$hatInfo(Entity entity, CallbackInfoReturnable<TrainHatInfo> cir) {
		if (entity instanceof SnowGolem snowGolem && snowGolem.hasPumpkin())
			cir.setReturnValue(CREATEPERF_SNOW_GOLEM_HAT);   // 命中即返回常量；未命中让原方法跑
	}
```

## 4. 需要的 import / 生命周期

```java
package com.example.createperf.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.content.trains.schedule.hat.TrainHatInfo;
import com.simibubi.create.content.trains.schedule.hat.TrainHatInfoReloadListener;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.phys.Vec3;

@Mixin(value = TrainHatInfoReloadListener.class, remap = false)
public abstract class TrainHatInfoReloadListenerMixin {
    // 第 3 节的常量 + @Inject
}
```

- **作用域**＝客户端进程级静态常量；**失效时机**＝无需失效（`TrainHatInfo` 是不可变 record，`Vec3` 不可变，且这个覆盖值不来自资源包——原实现也用硬编码字面量）；**并发**＝只读常量，线程安全。
- 注意：**不要**用 `VALUES` 里 `1.18F` 之类的常量替换成别的值——四元组必须逐字段相同（`""`、`0`、`Vec3(0,-3,0)`、`1.18F`）。
- 本 mixin 只影响客户端渲染，放进 `"client"` 数组。

## 5. `@Overwrite` / `@Inject` 风险

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| 分支条件抄错 | `snowGolem.hasPumpkin()` 必须完全一致 | 已逐字对齐 `:53` |
| 常量字段可变性 | 若 `TrainHatInfo` 不是 record / 有可变状态，共享常量会被下游修改 | 本仓库 `TrainHatInfoReloadListener.java:27` 用 `new TrainHatInfo("", 0, Vec3.ZERO, 1.0F)` 作静态常量 ⇒ 说明该类型可安全共享；**仍建议在验证时确认 `TrainHatInfo` 的字段都是 final** |
| 其它分支 | 未命中时让原方法继续（`cir.setReturnValue` 只在命中时调用） | 已保证 |
| 上游重构 | 签名变化会在启动期报错 | 期望行为 |

## 6. 回归测试点

1. 给雪傀儡戴南瓜：火车帽的位置、缩放、朝向与改前**逐帧一致**（把 `TrainHatInfo` 的四个字段打印出来比对：`""` / `0` / `(0,-3,0)` / `1.18F`）。
2. 雪傀儡**未**戴南瓜：走原路径（`ENTITY_INFO_MAP` 查表）。
3. 反复戴/摘南瓜（方块替换触发重载）：行为不变。
4. 性能：探针统计该分支命中次数与 `TrainHatInfoReloadListener.getHatInfoFor` 的分配量（JFR alloc 过滤 `TrainHatInfo`/`Vec3`）。
