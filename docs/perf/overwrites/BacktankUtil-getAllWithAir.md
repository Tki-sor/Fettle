# Overwrite 施工图：`BacktankUtil.getAllWithAir`

> 目标方法：`com.simibubi.create.content.equipment.armor.BacktankUtil#getAllWithAir(Lnet/minecraft/world/entity/LivingEntity;)Ljava/util/List;`（`static`）
> 对应发现：[07-client-render-ui.md](../07-client-render-ui.md) 发现 9（Medium）
> 公共约定见 [01-mixin-playbook.md](../01-mixin-playbook.md)

## 1. 为什么用「HEAD 短路 + RETURN 回填」而不是整段 `@Overwrite`

`getAllWithAir` 依赖 private 静态字段 `BACKTANK_SUPPLIERS`（`:204-206` 的 `addBacktankSupplier` 允许其它 mod 注册额外容器来源）。整段重写要么 `@Shadow` 该字段、要么复制 supplier 迭代逻辑——两者都会在「别的 mod 注册了新 supplier」时增加出错面。因此这里只加一层**每客户端 tick 的缓存**：

- **miss**：让原方法照常执行（`@At("RETURN")` 注入把结果放进缓存）；
- **hit**：`@At("HEAD")` + `cir.setReturnValue(cached)` 直接返回（HEAD 取消后原方法体不执行，因此 RETURN 注入**不会**再跑，缓存不会被二次写入）。

语义前提：同一 tick 内 `getAllWithAir` 的输入与输出不会变化（空气值只在消耗/服务端同步时变化，都发生在 tick 边界；返回的 `List` 里的 `ItemStack` 是实时引用，调用方只读）。调用点已逐个核对为只读：`RemainingAirOverlay.java:71-72`、`DivingHelmetItem.java:91`、`BacktankUtil.java:133-137,149-152,162-169,187-197`。

## 2. 原方法完整源码（`BacktankUtil.java:40-65`，逐字摘录）

```java
		addBacktankSupplier(entity -> {
			List<ItemStack> stacks = new ArrayList<>();
			for (ItemStack itemStack : entity.getArmorSlots())
				if (AllTags.AllItemTags.PRESSURIZED_AIR_SOURCES.matches(itemStack))
					stacks.add(itemStack);

			return stacks;
		});
	}

	public static List<ItemStack> getAllWithAir(LivingEntity entity) {
		List<ItemStack> all = new ArrayList<>();

		for (Function<LivingEntity, List<ItemStack>> supplier : BACKTANK_SUPPLIERS) {
			List<ItemStack> result = supplier.apply(entity);

			for (ItemStack stack : result)
				if (hasAirRemaining(stack))
					all.add(stack);
		}

		// Sort with ascending order (we want to prioritize the most empty so things actually run out)
		all.sort((a, b) -> Float.compare(getAir(a), getAir(b)));

		return all;
	}
```

（注意 `:40-47` 的 supplier 是注册在 `BACKTANK_SUPPLIERS` 里的 lambda，**每次调用**都 `new ArrayList<>()` 并遍历 `getArmorSlots()`——这是每次 `getAllWithAir` 的第二处分配。）

## 3. 改后完整可粘贴实现（缓存层）

```java
	@Unique private static final Map<LivingEntity, List<ItemStack>> CREATEPERF_CACHE = new IdentityHashMap<>();
	@Unique private static long CREATEPERF_TICK = Long.MIN_VALUE;

	@Inject(method = "getAllWithAir(Lnet/minecraft/world/entity/LivingEntity;)Ljava/util/List;",
		at = @At("HEAD"), cancellable = true)
	private static void createperf$pre(LivingEntity entity, CallbackInfoReturnable<List<ItemStack>> cir) {
		if (entity == null || !entity.level().isClientSide)
			return;                                   // 服务端语义保持原样（例如 canAbsorbDamage）
		createperf$rollTick();
		List<ItemStack> hit = CREATEPERF_CACHE.get(entity);
		if (hit != null)
			cir.setReturnValue(hit);
	}

	@Inject(method = "getAllWithAir(Lnet/minecraft/world/entity/LivingEntity;)Ljava/util/List;",
		at = @At("RETURN"))
	private static void createperf$post(LivingEntity entity, CallbackInfoReturnable<List<ItemStack>> cir) {
		if (entity == null || !entity.level().isClientSide)
			return;
		createperf$rollTick();
		CREATEPERF_CACHE.put(entity, cir.getReturnValue());
	}

	@Unique
	private static void createperf$rollTick() {
		long now = AnimationTickHolder.getTicks();     // 客户端 tick 计数（catnip）
		if (now != CREATEPERF_TICK) {                  // 每 tick 清空：绝不长期持有实体强引用
			CREATEPERF_TICK = now;
			CREATEPERF_CACHE.clear();
		}
	}
```

**（可选）第 2 阶段：减少排序比较器里的 `maxAir` 重算。** 原 `all.sort((a,b) -> Float.compare(getAir(a), getAir(b)))`（`:62`）每次比较都会进 `getAir` → `maxAir(ItemStack)` → 遍历整个附魔表（`:107-117`）。缓存层已经把调用次数从「每帧 9–10 次」降到「每 tick 1 次」，所以这一步的收益有限；若仍要做，需要 `@Overwrite` + `@Shadow` private 的 `BACKTANK_SUPPLIERS`（`@Shadow private static List<Function<LivingEntity, List<ItemStack>>> BACKTANK_SUPPLIERS;`），在单次遍历里算出每个容器的 air，再用 n≤4 的插入排序。**本文件不提供该版本**，因为收益/风险比不划算（顺序若有一处不同，玩家会看到「先耗尽哪个容器」的行为变化）。

## 4. 需要的 import / 生命周期

```java
package com.example.createperf.mixin.client;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.content.equipment.armor.BacktankUtil;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

@Mixin(value = BacktankUtil.class, remap = false)
public abstract class BacktankUtilMixin {
    // 第 3 节的字段 + 两个 @Inject + 一个 @Unique helper
}
```

- **作用域**＝客户端进程级静态（`CREATEPERF_CACHE` 键为 `LivingEntity` **身份**，用 `IdentityHashMap`）；
- **失效时机**＝客户端 tick 变化即整体 `clear()`（因此永远不会跨 tick 持有实体引用 —— 满足手册第 7 节的「不得长期持有 Entity 强引用」）；
- **并发**＝`isBarVisible`/`getBarWidth`/`getBarColor` 都经 `DistExecutor.unsafeCallWhenOn(Dist.CLIENT, ...)`（`:146,158,184`）在客户端线程执行，`RemainingAirOverlay` 在渲染线程执行 —— **注意这两者可能不是同一个线程**（原版 GUI 渲染在渲染线程；`DistExecutor` 的调用方在哪个线程取决于调用点）。因此这里的 `IdentityHashMap` 严格说是跨线程共享的。若实测确认存在并行访问，把缓存改为 `ConcurrentHashMap`（键仍是身份语义则可退回 `IdentityHashMap` + 外部同步），或只对渲染线程调用点生效。**这一条必须实测确认。**
- 本 mixin 只影响客户端，放进 `createperf.mixins.json` 的 `"client"` 数组。

## 5. `@Overwrite` / `@Inject` 风险

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| 返回共享列表 | 命中缓存时多个调用方拿到**同一个** `List` 实例（原实现每次返回新列表） | 已核对全部调用点只读（`isEmpty/getFirst/size/stream`）；若上游新增会修改该列表的调用方，必须复查（写进回归测试点） |
| 服务端分支 | `canAbsorbDamage`（`:128-139`）在服务端使用；若给服务端也加缓存，会影响「同 tick 内消耗空气后再次查询」的语义 | 已用 `entity.level().isClientSide` 排除服务端，服务端走原实现 |
| 线程 | 见第 4 节（`DistExecutor` 与渲染线程） | 实测确认，必要时换 `ConcurrentHashMap` 或去掉缓存 |
| 每 tick 清理开销 | `clear()` 每次 tick 一次，量级为「同 tick 内查询过的实体数」（通常 1） | 无 |
| 半径（`< / >`） | 缓存不改变返回内容，只减少重复计算 | 无 |

## 6. 回归测试点

1. 容器组合：无容器 / 1 个 / 2 个（附魔等级不同 / 空气量不同）→ 物品条宽度、颜色、`RemainingAirOverlay` 文本与改前完全一致。
2. **升序语义**：`getFirst()` 必须是空气**最少**的那个（`:61-62` 注释明确：为了先耗尽最空的容器）；同一 tick 内多次查询返回同一实例，跨 tick 重新计算。
3. 水下/岩浆：`RemainingAirOverlay` 显示正常（它只在 `:43-44` 判定为「不可呼吸」时才调用本方法）。
4. 服务端：`canAbsorbDamage`（护甲吸收伤害消耗空气）行为不变。
5. 第三方 supplier：`addBacktankSupplier` 注册的额外来源仍被纳入结果。
6. 泄漏：长时间运行后 `CREATEPERF_CACHE` 的条目数不超过同一 tick 内的实体数（每 tick 清空 ⇒ 不可能增长）。
7. 性能：`@Unique static long` 统计 `getAllWithAir` 进入次数与 miss 次数；持握土豆加农炮时 miss 应为「每 tick 1 次」而非「每帧 9–10 次」。
