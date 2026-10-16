# Overwrite 施工图：`CouplingHandler.forEachLoadedCoupling`（含 bug 修复）

> 目标方法：`com.simibubi.create.content.contraptions.minecart.CouplingHandler#forEachLoadedCoupling(Lnet/minecraft/world/level/Level;Ljava/util/function/Consumer;)V`（`static`）
> 对应发现：[07-client-render-ui.md](../07-client-render-ui.md) 发现 6（Medium-High，**含 High 级正确性 bug**）
> 公共约定见 [01-mixin-playbook.md](../01-mixin-playbook.md)

## 1. 为什么需要 Overwrite

要改的是 `:58/:60/:64` 三个 `return` → `continue`。`return` 是控制流指令，**没有任何注入手段可以把它改成 `continue`**（`@Redirect`/`@ModifyExpressionValue` 都作用于表达式，`@Inject` 无法删除已有指令）。可选做法只有两种：

- `@Overwrite` 整个方法（本文件的做法，方法只有 20 行）；
- `@Inject(HEAD, cancellable = true)` + 在 `@Unique` 复制体里重写方法体（等价，好处是原方法体保留、其它 mod 的 `@Inject` 不会被顶掉）。

## 2. 原方法完整源码（`CouplingHandler.java:48-67`，逐字摘录）

```java
	public static void forEachLoadedCoupling(Level world, Consumer<Couple<MinecartController>> consumer) {
		if (world == null)
			return;
		Set<UUID> cartsWithCoupling = CapabilityMinecartController.loadedMinecartsWithCoupling.get(world);
		if (cartsWithCoupling == null)
			return;

		for (UUID id : cartsWithCoupling) {
			MinecartController controller = CapabilityMinecartController.getIfPresent(world, id);
			if (controller == null)
				return;
			if (!controller.isLeadingCoupling())
				return;
			UUID coupledCart = controller.getCoupledCart(true);
			MinecartController coupledController = CapabilityMinecartController.getIfPresent(world, coupledCart);
			if (coupledController == null)
				return;
			consumer.accept(Couple.create(controller, coupledController));
		};
	}
```

## 3. Bug 影响面（为什么这不是「顺手改」而是必修）

`forEachLoadedCoupling` 的四个调用点（已 grep 确认）：

| 调用点 | 侧 | 频次 | 后果 |
| --- | --- | --- | --- |
| `CouplingRenderer.renderAll`（`CouplingRenderer.java:35`） | 客户端 | 每帧 | 该点之后的 coupling **不再绘制**；因 `cartsWithCoupling` 是 `HashSet<UUID>`（迭代顺序任意），表现为「耦合杆时隐时现 / 只画一部分」 |
| `CouplingRenderer.tickDebugModeRenders`（`:45`） | 客户端 | 调试模式 | 调试渲染缺失 |
| `CouplingPhysics.tick`（`CouplingPhysics.java:24` ← `CommonEvents.java:139`） | **服务端 + 客户端** | 每 tick | 该点之后的 coupling **不再收到 `softCollisionStep`/`hardCollisionStep`**：联挂矿车的距离约束静默失效，表现为拉伸/重叠、联挂运动不同步 |
| （`ClientEvents.java:158` 同 `CouplingPhysics.tick`） | 客户端 | 每 tick | 同上（客户端预测侧） |

触发 `return` 的条件都不罕见：`controller == null`（`getIfPresent` 查不到 = 该矿车未加载/已移除）、`coupledController == null`（对端不在本维度或未加载）、`isLeadingCoupling()` 为 false（`superscript`：只有 leading 的一端会被加进 `cartsWithCoupling`，见 `CapabilityMinecartController.java:79-80`，但状态可以在加入集合之后变化）。

## 4. 改后完整可粘贴方法体

```java
	@Overwrite
	public static void forEachLoadedCoupling(Level world, Consumer<Couple<MinecartController>> consumer) {
		if (world == null)
			return;
		Set<UUID> cartsWithCoupling = CapabilityMinecartController.loadedMinecartsWithCoupling.get(world);
		if (cartsWithCoupling == null)
			return;

		for (UUID id : cartsWithCoupling) {
			MinecartController controller = CapabilityMinecartController.getIfPresent(world, id);
			if (controller == null)
				continue;                                  // was: return  —— 修 bug
			if (!controller.isLeadingCoupling())
				continue;                                  // was: return  —— 修 bug
			UUID coupledCart = controller.getCoupledCart(true);
			MinecartController coupledController = CapabilityMinecartController.getIfPresent(world, coupledCart);
			if (coupledController == null)
				continue;                                  // was: return  —— 修 bug
			consumer.accept(Couple.create(controller, coupledController));
		}
	}
```

## 5. 需要的 `@Unique` / import

```java
package com.example.createperf.mixin.common;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import com.simibubi.create.content.contraptions.minecart.CouplingHandler;
import com.simibubi.create.content.contraptions.minecart.capability.CapabilityMinecartController;
import com.simibubi.create.content.contraptions.minecart.capability.MinecartController;

import net.createmod.catnip.data.Couple;
import net.minecraft.world.level.Level;

@Mixin(value = CouplingHandler.class, remap = false)
public abstract class CouplingHandlerMixin {
    // 第 4 节的方法体
}
```

- 无需新增字段/状态。所有引用成员都是 public：`CapabilityMinecartController.loadedMinecartsWithCoupling`（`:39` public static）、`getIfPresent`（`:169` public static）、`MinecartController.isLeadingCoupling()` / `getCoupledCart(boolean)`（被不同包的 `CouplingHandler` 调用，故必然是 public）、`Couple.create`。
- **分类注意**：本 mixin 影响服务端逻辑，应放在 `createperf.mixins.json` 的 `"mixins"`（通用）数组而不是 `"client"`，否则专用服务器上不生效。

## 6. `@Overwrite` 风险

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| 顶掉其它 mod 的注入 | Create 自己的 `create.mixins.json` 没有注入这个 mod 类方法（它只列原版目标），风险低；但第三方性能 mod 可能也 Overwrite 它 | 若担心，改用 `@Inject(HEAD, cancellable = true)` + `@Unique` 复制体 |
| **行为变更（必须公告）** | 修复后，原本被静默跳过的 coupling **会重新参与物理与渲染**，服务端每 tick 的实际工作量上升（这是恢复正确性的代价，不是性能退化） | 在变更日志里显式写明；服务端 MSPT 基线必须重测 |
| 语义 | `isLeadingCoupling()` 为 false 的一端本来就不应该被消费（避免重复），`continue` 保持这一过滤 | 已保持 |
| 上游重构 | 若上游改了方法签名，`@Overwrite` 启动期报错（`defaultRequire = 1`） | 期望行为 |

## 7. 回归测试点

1. **Bug 复现与修复验证**：存档里放 ≥3 组耦合矿车，让**其中一组的一端**离开加载范围（开远 / 送入未加载区块 / 覆盖对端所在区块卸载）。改前：`consumer.accept` 的调用次数会突然从 N 掉到 1–2；改后：恒等于当前有效 coupling 数。用 `@Unique static int` 计数器统计。
2. 服务端物理：联挂矿车在轨道上被推动/下坡时，距离约束保持（`softCollisionStep`/`hardCollisionStep`）。改前会出现「后半段矿车不被牵引」。
3. 客户端：所有耦合组都能看到耦合杆（注意 `HashSet` 顺序任意 ⇒ 改前"丢哪一组"是不确定的，不能只测一组）。
4. `hasContraptionCoupling(true)` 的跳过逻辑仍在 `CouplingRenderer.renderAll` 内生效（不在本方法里）。
5. 专用服务器上确认本 mixin 生效（`mixins` 数组，不是 `client`）。
