# Overwrite 候选：`FluidTankBlockEntity.onFluidStackChanged`

> 目标：`src/main/java/com/simibubi/create/content/fluids/tank/FluidTankBlockEntity.java`
> 关联 finding：[05-fluids.md](../05-fluids.md) F2
> 公共约定见 [01-mixin-playbook.md](../01-mixin-playbook.md)

## 0. 事实核对（重要：原始 finding 的修法会破坏比较器）

1. 循环体每格调用 `level.updateNeighbourForOutputSignal(pos, block)`（`:187`），**在**亮度比较（`:189`）**之前**。
2. 但 `FluidTankBlock.getAnalogOutputSignal`（`FluidTankBlock.java:350-354`）返回的是
   `ComparatorUtil.fractionToRedstoneLevel(be.getControllerBE().getFillState())` —— 它依赖**填充比例**，**不依赖** `luminosity`。
   所以「把 `updateNeighbourForOutputSignal` 挪到亮度比较之后」会让**比较器在每次充放时不再更新** → 属于语义破坏，不是优化。
3. `luminosity` 只用于 `FluidTankBlock.getLightEmission`（`FluidTankBlock.java:115-123`），
   且客户端在 `read()` 里靠 `luminosity != prevLum` 触发 `lightEngine.checkBlock`（`:467-470`）。
   而 `actualLuminosity` 依赖 `maxY = (int)(getFillState() * height + 1)`（`:175`），
   它的变化点（`f = k/height`）与红石等级的变化点（`f ≈ k/14`）**不重合**：
   例：`height = 3`、`f = 0.3333 → 0.3334` 时 `maxY` 从 1 变 2，而红石等级都是 5。
   因此**任何**「只按红石等级跳过」的方案都必须同时跟踪 `maxY`，否则光照会滞后。

## 1. 原方法完整源码（`FluidTankBlockEntity.java:167-207`，逐行核对）

```java
	protected void onFluidStackChanged(FluidStack newFluidStack) {
		if (!hasLevel())
			return;

		FluidType attributes = newFluidStack.getFluid()
			.getFluidType();
		int luminosity = (int) (attributes.getLightLevel(newFluidStack) / 1.2f);
		boolean reversed = attributes.isLighterThanAir();
		int maxY = (int) ((getFillState() * height) + 1);

		for (int yOffset = 0; yOffset < height; yOffset++) {
			boolean isBright = reversed ? (height - yOffset <= maxY) : (yOffset < maxY);
			int actualLuminosity = isBright ? luminosity : luminosity > 0 ? 1 : 0;

			for (int xOffset = 0; xOffset < width; xOffset++) {
				for (int zOffset = 0; zOffset < width; zOffset++) {
					BlockPos pos = this.worldPosition.offset(xOffset, yOffset, zOffset);
					FluidTankBlockEntity tankAt = ConnectivityHandler.partAt(getType(), level, pos);
					if (tankAt == null)
						continue;
					level.updateNeighbourForOutputSignal(pos, tankAt.getBlockState()
						.getBlock());
					if (tankAt.luminosity == actualLuminosity)
						continue;
					tankAt.setLuminosity(actualLuminosity);
				}
			}
		}

		if (!level.isClientSide) {
			setChanged();
			sendData();
		}

		if (isVirtual()) {
			if (fluidLevel == null)
				fluidLevel = LerpedFloat.linear()
					.startWithValue(getFillState());
			fluidLevel.chase(getFillState(), .5f, Chaser.EXP);
		}
	}
```

调用链：`SmartFluidTank.onContentsChanged()` / `SmartFluidTank.setFluid()`（`foundation/fluid/SmartFluidTank.java:17-26`）
→ `this::onFluidStackChanged`（构造于 `FluidTankBlockEntity.java:96`）。
`FluidNetwork.tick()` 的 EXECUTE 分支每 tick 对目标 handler 调一次 `fill`，因此**活跃输送期间约每 tick 一次**。

## 2. 改后实现

### 2.1 推荐：`@Inject(HEAD)` + `@WrapOperation`（**不需要** `@Overwrite`，语义最稳）

思路：只节流「邻居信号广播」（27 格 × 6 方向 = 162 次 `neighborChanged`），
**保留**体积遍历（每格的 `partAt` 与 `setLuminosity` 照常执行）。红石等级未变 → 比较器输出必然不变；
流体类型未变 → 亮度无关；`setLuminosity` 仍按最大填充高度逐格刷新，光照语义**完全不变**。

```java
package com.example.createperf.mixin.fluids;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.foundation.blockEntity.ComparatorUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;

@Mixin(value = FluidTankBlockEntity.class, remap = false)
public abstract class FluidTankBlockEntityMixin {

	@Unique
	private int createperf$lastComparatorLevel = Integer.MIN_VALUE;

	@Unique
	private FluidType createperf$lastFluidType;

	/** 本次调用是否需要广播邻居信号；由 HEAD 注入计算，供 @WrapOperation 读取。 */
	@Unique
	private boolean createperf$signalDirty;

	@Inject(method = "onFluidStackChanged(Lnet/neoforged/neoforge/fluids/FluidStack;)V",
			at = @At("HEAD"), remap = false)
	private void createperf$decideSignalUpdate(FluidStack newFluidStack, CallbackInfo ci) {
		FluidTankBlockEntity self = (FluidTankBlockEntity) (Object) this;
		if (!self.hasLevel())
			return; // 原方法也会直接 return，@WrapOperation 不会被执行
		FluidType type = newFluidStack.getFluid()
			.getFluidType();
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

**为什么用 `@WrapOperation` 而不是 `@ModifyExpressionValue`**：`@ModifyExpressionValue` 只会改写返回值，
**原调用照样执行**；要「决定是否调用原逻辑」只能用 `@WrapOperation`（手册 §2 表）。

**行为变更声明**：当红石等级与流体类型都未变时，罐体不再向邻居发送 `neighborChanged`。
对原版比较器无影响（等级未变），但第三方方块若依赖「罐体每次流体变化都触发 `neighborChanged`」
会感知不到；这是本方案唯一的语义差异，必须写进 changelog。

### 2.2 备选：`@Overwrite` 完整方法体（连 27 次 `partAt` 也一并省掉）

额外收益只有每次流体变化少 27 次 `level.getBlockEntity`；代价是必须自己维护
`(comparatorLevel, maxY, reversed, fluidType)` 四个状态量（缺 `maxY`/`reversed` 会导致光照滞后），
且要跨实例调用 protected 的 `setLuminosity`，因此需要一个 `@Invoker` 伴生接口。

```java
@Mixin(value = FluidTankBlockEntity.class, remap = false)
public interface FluidTankBlockEntityInvoker {
	@Invoker("setLuminosity")
	void createperf$invokeSetLuminosity(int luminosity);

	// 跨实例读 protected 字段同样非法（javac 层面），必须走 @Accessor
	@Accessor("luminosity")
	int createperf$getLuminosityRaw();
}
```

```java
	@Unique private int createperf$lastComparatorLevel = Integer.MIN_VALUE;
	@Unique private int createperf$lastMaxY = Integer.MIN_VALUE;
	@Unique private boolean createperf$lastReversed;
	@Unique private FluidType createperf$lastFluidType;

	@Overwrite(remap = false)
	protected void onFluidStackChanged(FluidStack newFluidStack) {
		Level level = getLevel();
		if (level == null)
			return;

		FluidType attributes = newFluidStack.getFluid()
			.getFluidType();
		int luminosity = (int) (attributes.getLightLevel(newFluidStack) / 1.2f);
		boolean reversed = attributes.isLighterThanAir();
		int maxY = (int) ((getFillState() * height) + 1);
		int comparatorLevel = ComparatorUtil.fractionToRedstoneLevel(getFillState());

		boolean scan = comparatorLevel != createperf$lastComparatorLevel
			|| maxY != createperf$lastMaxY
			|| reversed != createperf$lastReversed
			|| attributes != createperf$lastFluidType;

		if (scan) {
			createperf$lastComparatorLevel = comparatorLevel;
			createperf$lastMaxY = maxY;
			createperf$lastReversed = reversed;
			createperf$lastFluidType = attributes;

			for (int yOffset = 0; yOffset < height; yOffset++) {
				boolean isBright = reversed ? (height - yOffset <= maxY) : (yOffset < maxY);
				int actualLuminosity = isBright ? luminosity : luminosity > 0 ? 1 : 0;

				for (int xOffset = 0; xOffset < width; xOffset++) {
					for (int zOffset = 0; zOffset < width; zOffset++) {
						BlockPos pos = getBlockPos().offset(xOffset, yOffset, zOffset);
						FluidTankBlockEntity tankAt = ConnectivityHandler.partAt(getType(), level, pos);
						if (tankAt == null)
							continue;
						level.updateNeighbourForOutputSignal(pos, tankAt.getBlockState()
							.getBlock());
						FluidTankBlockEntityInvoker invoker = (FluidTankBlockEntityInvoker) tankAt;
						if (invoker.createperf$getLuminosityRaw() == actualLuminosity)
							continue;
						invoker.createperf$invokeSetLuminosity(actualLuminosity);
					}
				}
			}
		}

		if (!level.isClientSide) {
			setChanged();
			sendData();
		}

		if (isVirtual()) {
			if (fluidLevel == null)
				fluidLevel = LerpedFloat.linear()
					.startWithValue(getFillState());
			fluidLevel.chase(getFillState(), .5f, Chaser.EXP);
		}
	}
```

需要的 `@Shadow`（均为目标类**自己声明**的成员，解析稳定）与 import：

```java
	@Shadow protected int height;
	@Shadow protected int width;
	@Shadow protected int luminosity;
	@Shadow private LerpedFloat fluidLevel;
	// 注意：不要直接写 tankAt.luminosity / tankAt.setLuminosity(...)——protected 成员跨实例访问在 javac 层面就非法，
	// 必须通过上面的 @Invoker / @Accessor 伴生接口。
	// imports: net.createmod.catnip.animation.LerpedFloat; LerpedFloat.Chaser;
	//          com.simibubi.create.api.connectivity.ConnectivityHandler;
	//          com.simibubi.create.foundation.blockEntity.ComparatorUtil;
	//          net.minecraft.core.BlockPos; net.minecraft.world.level.Level;
	//          net.neoforged.neoforge.fluids.FluidStack; FluidType;
```

## 3. 风险

| 风险 | 2.1（@Inject + @WrapOperation，推荐） | 2.2（@Overwrite） |
| --- | --- | --- |
| 语义 | 中：唯一的差异是「红石等级未变时不广播 `neighborChanged`」，需在 changelog 声明 | 高：额外跳过整段体积遍历，必须同时跟踪 `maxY`/`reversed`/`fluidType`，任一漏掉都会导致**光照不刷新** |
| 缓存生命周期 | `@Unique` 两个字段，per-instance，随 BE 一起回收；不引用 `Level`/`Entity`，无泄漏 | 同上，4 个字段 |
| 失效时机 | 红石等级或流体类型变化 → 自动重新广播（无需手动失效）。BE 首次调用时 `lastComparatorLevel = Integer.MIN_VALUE` → 必然广播一次 | 同上 |
| 并发 | 全部在服务端主线程 + 客户端 `read()` 路径；客户端也会走 `onFluidStackChanged`（`setFluid`），字段 per-instance，无跨线程共享 | 同 |
| 与其它 mod 的 mixin | `@WrapOperation` 只在 MixinExtras 之间抢点，不阻塞其它 mod 的 `@Redirect` | `@Overwrite` 与任何其它 mixin 冲突面最大 |
| 脆弱度 | 依赖调用点 `Level.updateNeighbourForOutputSignal(BlockPos, Block)` 的精确描述符；上游一旦把这一行改成别的写法（例如换成 `tankAt.setLuminosity` 内部触发），`defaultRequire = 1` 会**启动即报错**（这正是我们要的） | 依赖 `height/width/luminosity/fluidLevel` 四个字段名与 `getFillState()`；上游改字段名 → 编译/启动期报错 |

## 4. 回归测试点

1. **比较器**：在 1×1×1、3×3×3 两种罐体上，每面贴一个比较器；用泵以恒定速率注水/抽水，比较器输出必须**平滑逐级**变化，不能跳级或卡住。这是 2.1 / 2.2 的核心验收点。
2. **比较器贴在内侧格**：3×3×3 罐体的中下层外侧格子各贴一个比较器（它们的 `getAnalogOutputSignal` 都取自 controller），都必须更新。
3. **光照**：容器/发光流体（熔岩、`luminosity > 0` 的 mod 流体）在 1×1×1、2×2×2、3×3×3 下装满/倒空，`getLightEmission` 与客户端光照必须随填充高度变化；重点验证「填充到 1/3、2/3 边界」的瞬间（2.2 的风险点）。
4. **反重力流体**（`isLighterThanAir()`，例如 Create 的某些 mod 流体）：`reversed = true` 分支的 `height - yOffset <= maxY` 逻辑与改前一致。
5. **桶/管道灌装**：用管道在 1 tick 内连续 fill + drain 到同一红石等级，比较器不更新（预期），但客户端 `sendData` 与存档内容一致。
6. **多块成型/解体**：`removeController`（`:237-260`）会直接调 `onFluidStackChanged`，成型/解体后 `Luminosity` 与比较器正确。
7. **性能对照**：`/spark profiler --timeout 60`（同一存档，3×3×3 罐体 + 泵持续输送）；预期火焰图中
   `LevelChunk.blockChanged` / `BlockState.updateNeighbourForOutputSignal` / `Block.neighborChanged` 明显下降。


