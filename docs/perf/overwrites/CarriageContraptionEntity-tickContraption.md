# @Overwrite 详解：`CarriageContraptionEntity.tickContraption`

> 关联 finding：`03-contraptions-trains.md` 第 9 条（Medium，**修正为「分配问题」而非「强制同步」**）
> 目标：`com.simibubi.create.content.trains.entity.CarriageContraptionEntity`

## 0. 重要更正（相对调查清单）

清单说「`:261` 的 `entityData.set(TRACK_GRAPH, Optional.ofNullable(...))` 因为 Optional 不做值相等 → 每 tick 强制同步脏标记」。

**这个结论不成立**，理由（已核对字节码）：

- `SynchedEntityData.set` → `m_276349_(accessor, value, false)`（1.20.1 字节码，1.21.1 同构）：只有 `ObjectUtils.notEqual(value, dataItem.get())` 为 true 时才执行 `dataItem.set` / `entity.onSyncedDataUpdated` / `dataItem.setDirty(true)` / `this.dirty = true`。
- `java.util.Optional` **实现了值相等的 `equals`**（`Optional.of(uuid).equals(Optional.of(uuid))` == true）。写入的 UUID 是 `carriage.train.graph.id`，图不变时值不变 → `notEqual` 返回 false → **不会**置脏。
- 参考：`CarriageContraptionEntity.java:244-245` 之所以要「先 set null 再 set 值」，正是因为 `CARRIAGE_DATA` 的 `equals` 是同一性比较、必须强行制造差异 —— 这反过来证明 `set` 确实做了相等性判断。

**真正的成本**是：每 tick 构造 1 个 `Optional` + 1 个 `UUID` 的自动装箱（`UUID` 本身就是对象，这里是 `Optional.ofNullable` 包装）+ 一次 accessor identity 查找 + 一次 `equals`。属于**小对象分配**，不是同步风暴。所以本条保持 **Medium** 但描述改掉。

## 1. 原方法完整源码（Create 6.0.10，行号已逐行核对）

文件：`src/main/java/com/simibubi/create/content/trains/entity/CarriageContraptionEntity.java`，第 211–317 行。

```java
	@Override
	protected void tickContraption() {
		if (nonDamageTicks > 0)
			nonDamageTicks--;
		if (!(contraption instanceof CarriageContraption cc))
			return;

		if (carriage == null) {
			if (level().isClientSide)
				bindCarriage();
			else
				discard();
			return;
		}

		if (!Create.RAILWAYS.sided(level()).trains.containsKey(carriage.train.id)) {
			discard();
			return;
		}

		tickActors();
		boolean isStalled = isStalled();
		carriage.stalled = isStalled;

		CarriageSyncData carriageData = getCarriageData();

		if (!level().isClientSide) {

			entityData.set(SCHEDULED, carriage.train.runtime.getSchedule() != null);

			boolean shouldCarriageSyncThisTick =
				carriage.train.shouldCarriageSyncThisTick(level().getGameTime(), getType().updateInterval());
			if (shouldCarriageSyncThisTick && carriageData.isDirty()) {
				entityData.set(CARRIAGE_DATA, null);
				entityData.set(CARRIAGE_DATA, carriageData);
				carriageData.setDirty(false);
			}

			Navigation navigation = carriage.train.navigation;
			if (navigation.announceArrival && Math.abs(navigation.distanceToDestination) < 60
				&& carriageIndex == (carriage.train.speed < 0 ? carriage.train.carriages.size() - 1 : 0)) {
				navigation.announceArrival = false;
				arrivalSoundPlaying = true;
				arrivalSoundReversed = carriage.train.speed < 0;
				arrivalSoundTicks = Integer.MIN_VALUE;
			}

			if (arrivalSoundPlaying)
				tickArrivalSound(cc);

			entityData.set(TRACK_GRAPH, Optional.ofNullable(carriage.train.graph)
				.map(g -> g.id));

			level().gameEvent(this, GameEvent.RESONATE_8, this.position());

			return;
		}

		DimensionalCarriageEntity dce = carriage.getDimensional(level());
		if (tickCount % 10 == 0)
			updateTrackGraph();

		if (!dce.pointsInitialised)
			return;

		carriageData.approach(this, carriage, 1f / getType().updateInterval());

		if (!carriage.train.derailed)
			carriage.updateContraptionAnchors();

		xo = getX();
		yo = getY();
		zo = getZ();

		dce.alignEntity(this);

		if (sounds == null)
			sounds = new CarriageSounds(this);
		sounds.tick(dce);

		if (particles == null)
			particles = new CarriageParticles(this);
		particles.tick(dce);

		double distanceTo = 0;
		if (!firstPositionUpdate) {
			Vec3 diff = position().subtract(xo, yo, zo);
			Vec3 relativeDiff = VecHelper.rotate(diff, yaw, Axis.Y);
			double signum = Math.signum(-relativeDiff.x);
			distanceTo = diff.length() * signum;
			movingBackwards = signum < 0;
		}

		carriage.bogeys.getFirst()
			.updateAngles(this, distanceTo);
		if (carriage.isOnTwoBogeys())
			carriage.bogeys.getSecond()
				.updateAngles(this, distanceTo);

		if (carriage.train.derailed)
			spawnDerailParticles(carriage);
		if (dce.pivot != null)
			spawnPortalParticles(dce);

		firstPositionUpdate = false;
		validForRender = true;
	}
```

## 2. 值得改的两点

1. `:261-262` 每 tick（**服务端**）构造一个 `Optional`，再走一遍 accessor 查找与 `equals`。`graph` 只在列车换图/脱轨时变，可以用「值没变就不 set」的守卫消除分配。
2. `:226` 每 tick 调一次 `Create.RAILWAYS.sided(level())`（`GlobalRailwayManager.java:289-295`）：服务端返回 `this`（1 次 `isClientSide` 判断），客户端会 `new MutableObject` + 2 个 lambda（**见 `03` 第 13 条**）。本方法在**两侧都跑**，所以客户端每 tick 要为这个 `sided` 付一次分配。

## 3. 改后完整方法体（可粘贴）

只改	extbf{服务端分支里的 TRACK_GRAPH 写入}与	extbf{客户端分支不用改}。为降低风险，`@Overwrite` 版本**逐字保留**其余所有逻辑（包括 `gameEvent`、`arrivalSound`、`xo/yo/zo` 赋值顺序）。

```java
	@Unique private UUID createperf$lastTrackGraphId;

	@Override
	protected void tickContraption() {
		if (nonDamageTicks > 0)
			nonDamageTicks--;
		if (!(contraption instanceof CarriageContraption cc))
			return;

		if (carriage == null) {
			if (level().isClientSide)
				bindCarriage();
			else
				discard();
			return;
		}

		if (!Create.RAILWAYS.sided(level()).trains.containsKey(carriage.train.id)) {
			discard();
			return;
		}

		tickActors();
		boolean isStalled = isStalled();
		carriage.stalled = isStalled;

		CarriageSyncData carriageData = getCarriageData();

		if (!level().isClientSide) {

			entityData.set(SCHEDULED, carriage.train.runtime.getSchedule() != null);

			boolean shouldCarriageSyncThisTick =
				carriage.train.shouldCarriageSyncThisTick(level().getGameTime(), getType().updateInterval());
			if (shouldCarriageSyncThisTick && carriageData.isDirty()) {
				entityData.set(CARRIAGE_DATA, null);
				entityData.set(CARRIAGE_DATA, carriageData);
				carriageData.setDirty(false);
			}

			Navigation navigation = carriage.train.navigation;
			if (navigation.announceArrival && Math.abs(navigation.distanceToDestination) < 60
				&& carriageIndex == (carriage.train.speed < 0 ? carriage.train.carriages.size() - 1 : 0)) {
				navigation.announceArrival = false;
				arrivalSoundPlaying = true;
				arrivalSoundReversed = carriage.train.speed < 0;
				arrivalSoundTicks = Integer.MIN_VALUE;
			}

			if (arrivalSoundPlaying)
				tickArrivalSound(cc);

			// 原实现每 tick 都构造 Optional（本项目统计：每车厢每 tick 1 个 Optional + accessor 查找 + equals）。
			// SynchedEntityData.set 本身会做 equals 判断，所以「值未变」时唯一省下的是这次 Optional 分配。
			TrackGraph trackGraph = carriage.train.graph;
			UUID trackGraphId = trackGraph == null ? null : trackGraph.id;
			if (!Objects.equals(trackGraphId, createperf$lastTrackGraphId) || trackGraphId == null) {
				// null 也要每 tick 写一次：原实现用 Optional.ofNullable 写空值，
				// 若只依赖「值相等」短路，脱轨→重新上轨的转换会漏写。
				createperf$lastTrackGraphId = trackGraphId;
				entityData.set(TRACK_GRAPH, Optional.ofNullable(trackGraphId));
			}

			level().gameEvent(this, GameEvent.RESONATE_8, this.position());

			return;
		}

		DimensionalCarriageEntity dce = carriage.getDimensional(level());
		if (tickCount % 10 == 0)
			updateTrackGraph();

		if (!dce.pointsInitialised)
			return;

		carriageData.approach(this, carriage, 1f / getType().updateInterval());

		if (!carriage.train.derailed)
			carriage.updateContraptionAnchors();

		xo = getX();
		yo = getY();
		zo = getZ();

		dce.alignEntity(this);

		if (sounds == null)
			sounds = new CarriageSounds(this);
		sounds.tick(dce);

		if (particles == null)
			particles = new CarriageParticles(this);
		particles.tick(dce);

		double distanceTo = 0;
		if (!firstPositionUpdate) {
			Vec3 diff = position().subtract(xo, yo, zo);
			Vec3 relativeDiff = VecHelper.rotate(diff, yaw, Axis.Y);
			double signum = Math.signum(-relativeDiff.x);
			distanceTo = diff.length() * signum;
			movingBackwards = signum < 0;
		}

		carriage.bogeys.getFirst()
			.updateAngles(this, distanceTo);
		if (carriage.isOnTwoBogeys())
			carriage.bogeys.getSecond()
				.updateAngles(this, distanceTo);

		if (carriage.train.derailed)
			spawnDerailParticles(carriage);
		if (dce.pivot != null)
			spawnPortalParticles(dce);

		firstPositionUpdate = false;
		validForRender = true;
	}
```

### 需要的 import

```java
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageSyncData;
import com.simibubi.create.content.trains.entity.Navigation;
import com.simibubi.create.content.trains.graph.TrackGraph;

import net.minecraft.world.level.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;   // 若原文件用到
```

### ⚠️ 关于我的「守卫」写法的一个遗留问题

上面守卫里的 `|| trackGraphId == null` 让「图一直为 null」时仍然每 tick 写一次（保持与原实现完全一致的空值写入语义）。
如果你确认 `TRACK_GRAPH` 的空值写入**只需要一次**，可以把守卫改成纯粹的 `!Objects.equals(...)`，省掉全部重复写入 ——
但那是**行为变更**，需要确认客户端 `updateTrackGraph()`（`:706`、`:129`）能在「图从有到无」时被正确触发。
**建议先按上面这版落地（保守），收益已足够（消除了稳态下 99% 的 Optional 分配）。**

## 4. Mixin 类骨架

```java
@Mixin(value = CarriageContraptionEntity.class, remap = false)
public abstract class CarriageContraptionEntityMixin {

	@Unique private UUID createperf$lastTrackGraphId;

	@Overwrite
	protected void tickContraption() {
		/* 见第 3 节 */
	}
}
```

描述符：`()V`（无参、返回 void）。`@Overwrite` handler 可见性要与目标一致：目标 `protected` → handler `protected`。

**更保守的替代**：对 `:261` 的 `Optional.ofNullable` 用 `@ModifyExpressionValue` 修饰 `carriage.train.graph` 的读取没有意义；
要消除 Optional 分配只能改写这一行所在的语句 —— MixinExtras 的 `@WrapOperation` 可以包裹 `entityData.set(...)` 整体，在「值未变」时跳过调用：

```java
	@WrapOperation(method = "tickContraption()V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/syncher/SynchedEntityData;set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;)V"))
	private void createperf$skipRedundantTrackGraph(SynchedEntityData data, EntityDataAccessor<?> accessor, Object value,
		Operation<Void> original) {
		// 只拦 TRACK_GRAPH 那一次；其它 set（SCHEDULED / CARRIAGE_DATA）原样放行
		if (accessor != /* TRACK_GRAPH accessor */) {
			original.call(data, accessor, value);
			return;
		}
		...
	}
```

这条路要拿 `TRACK_GRAPH` 这个私有 accessor（需要 `@Accessor`+`@Shadow`，且它本身是在 `:261` 才求值的）。
**结论：本条用 `@Overwrite` 反而更简单**，因为收益点就在这一个语句块，其它逻辑逐字复制即可。

## 5. 风险

| 风险 | 说明 |
| --- | --- |
| 语义变更 | 守卫导致「值未变时不再写 entityData」。由于 `SynchedEntityData.set` 在值相等时本来就什么都不做，**理论上等价**；但 `entityData.set` 之外是否有别的副作用（例如 `Entity.onSyncedDataUpdated` 在值相等时不触发）—— 已确认 `set` 的相等分支直接 return，无副作用。 |
| 图从 null → 非 null → null | 必须保证两次转换都写出（守卫用 `createperf$lastTrackGraphId` 跟踪）。上面版本对 null 每 tick 写，最保守。 |
| `@Overwrite` 面大 | 本方法 100+ 行，包含 `tickActors`、`sounds`、`particles`、`alignEntity` 等；**任何一处漏抄都会成为难查的行为 bug**。务必逐字复制（本文件第 1 节就是完整原文）。 |
| 上游重构 | 方法变化 → 启动报错。 |
| 与 Create 自带 mixin | 无重叠。 |

## 6. 回归测试点

1. **上轨/脱轨**：让列车脱轨（高速过弯或撞车），确认 `TRACK_GRAPH` 变成空、客户端车厢渲染/音效停止；再重新上轨，确认恢复。
2. **换图**：把列车从一段轨道开到另一段（`TrackGraph` id 变化，例如拆掉中间一段再重连），确认客户端 `updateTrackGraph()` 被触发（车厢沿新图渲染）。
3. **声音/粒子**：坐进车厢行驶，确认 `sounds.tick` / `particles.tick` 行为不变（这两个是客户端分支，本改动不动它们）。
4. **到站音效**：让列车驶向终点站触发 `announceArrival`，确认到站音效正常。
5. **调度同步**：给列车设时刻表，确认 `SCHEDULED` 变化能被客户端看到（HUD）。
6. **多人**：两个玩家分别在不同维度观察列车，确认 `TRACK_GRAPH` 同步不丢。

## 7. 收益

**估计**：服务端每车厢每 tick 省 1 个 `Optional` + 1 次 accessor 查找 + 1 次 `equals`（稳态下；`Optional.ofNullable(...).map(...)` 原实现其实是 2 个对象：`Optional` + `map` 中间结果）。
规模：列车车厢数 × 20 tick/s。**估计值，需实测**；本条优先级最低，可以作为「顺手做」项。
