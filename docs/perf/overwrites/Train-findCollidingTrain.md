# @Overwrite 详解：`Train.findCollidingTrain`

> 关联 finding：`03-contraptions-trains.md` 第 6 条（Medium）
> 目标：`com.simibubi.create.content.trains.entity.Train`
> 本文件给的是**完整可粘贴**的替换实现。

## 1. 原方法完整源码（Create 6.0.10，行号已逐行核对）

文件：`src/main/java/com/simibubi/create/content/trains/entity/Train.java`，第 655–730 行。

```java
	public Pair<Train, Vec3> findCollidingTrain(Level level, Vec3 start, Vec3 end, ResourceKey<Level> dimension) {
		Vec3 diff = end.subtract(start);
		double maxDistanceSqr = Math.pow(AllConfigs.server().trains.maxAssemblyLength.get(), 2.0);

		Trains: for (Train train : Create.RAILWAYS.sided(level).trains.values()) {
			if (train == this)
				continue;
			if (train.graph != null && train.graph != graph)
				continue;

			Vec3 lastPoint = null;

			for (Carriage otherCarriage : train.carriages) {
				for (boolean betweenBits : Iterate.trueAndFalse) {
					if (betweenBits && lastPoint == null)
						continue;

					TravellingPoint otherLeading = otherCarriage.getLeadingPoint();
					TravellingPoint otherTrailing = otherCarriage.getTrailingPoint();
					if (otherLeading.edge == null || otherTrailing.edge == null)
						continue;
					ResourceKey<Level> otherDimension = otherLeading.node1.getLocation().dimension;
					if (!otherDimension.equals(otherTrailing.node1.getLocation().dimension))
						continue;
					if (!otherDimension.equals(dimension))
						continue;

					Vec3 start2 = otherLeading.getPosition(train.graph);
					Vec3 end2 = otherTrailing.getPosition(train.graph);

					if (Math.min(start2.distanceToSqr(start), end2.distanceToSqr(start)) > maxDistanceSqr)
						continue Trains;

					if (betweenBits) {
						end2 = start2;
						start2 = lastPoint;
					}

					lastPoint = end2;

					if ((end.y < end2.y - 3 || end2.y < end.y - 3)
						&& (start.y < start2.y - 3 || start2.y < start.y - 3))
						continue;

					Vec3 diff2 = end2.subtract(start2);
					Vec3 normedDiff = diff.normalize();
					Vec3 normedDiff2 = diff2.normalize();
					double[] intersect = VecHelper.intersect(start, start2, normedDiff, normedDiff2, Axis.Y);

					if (intersect == null) {
						Vec3 intersectSphere = VecHelper.intersectSphere(start2, normedDiff2, start, .125f);
						if (intersectSphere == null)
							continue;
						if (!Mth.equal(normedDiff2.dot(intersectSphere.subtract(start2)
							.normalize()), 1))
							continue;
						intersect = new double[2];
						intersect[0] = intersectSphere.distanceTo(start) - .125;
						intersect[1] = intersectSphere.distanceTo(start2) - .125;
					}

					if (intersect[0] > diff.length())
						continue;
					if (intersect[1] > diff2.length())
						continue;
					if (intersect[0] < 0)
						continue;
					if (intersect[1] < 0)
						continue;

					return Pair.of(train, start.add(normedDiff.scale(intersect[0])));
				}
			}
		}
		return null;
	}
```

## 2. 为什么值得 @Overwrite

1. `Vec3 diff = end.subtract(start)` 是**循环不变量**，但 `Vec3 normedDiff = diff.normalize()` 被写在**最内层**：`O(T×C×2)` 次 `normalize()`（含一次 `1/length` 与一次 `scale`，每次都在碰撞判定的**前**执行，即使紧接着的 `continue` 会丢弃结果）。
2. `Math.pow(config, 2.0)` 每 tick 一次（这是小头，但顺手改掉）。
3. `AllConfigs.server().trains.maxAssemblyLength.get()` 每 tick 读一次配置对象链（`AllConfigs.server()` 是 static 字段读取 + `ConfigValue.get()`）。**注意**：把它缓存到静态字段会让用户改配置不生效（手册 §7 反模式）——所以这里**不缓存**，只是把它从 `Math.pow` 里提出来。
4. 每节车的 `otherLeading.getPosition(train.graph)` / `getPosition` 生成 2 个 `Vec3`，这两个是**必要**的（位置本来就要算）；但 `start2.distanceToSqr(start)` 与 `end2.distanceToSqr(start)` 在 `betweenBits` 两轮里重复算了一遍（`lastPoint` 复用后第二轮会变），属于可省。

## 3. 改后完整方法体（可粘贴）

```java
	public Pair<Train, Vec3> findCollidingTrain(Level level, Vec3 start, Vec3 end, ResourceKey<Level> dimension) {
		Vec3 diff = end.subtract(start);
		double diffLength = diff.length();
		// 原实现每节车每个 betweenBits 都重算 diff.normalize()（循环不变量）
		Vec3 normedDiff = diff.normalize();
		// 不缓存到静态字段：用户改配置必须立即生效（见手册 §7）
		double maxAssemblyLength = AllConfigs.server().trains.maxAssemblyLength.get();
		double maxDistanceSqr = maxAssemblyLength * maxAssemblyLength;

		Iterator<Entry<UUID, Train>> trainIterator = Create.RAILWAYS.sided(level).trains.entrySet().iterator();
		while (trainIterator.hasNext()) {
			Train train = trainIterator.next().getValue();
			if (train == this)
				continue;
			if (train.graph != null && train.graph != graph)
				continue;

			Vec3 lastPoint = null;

			for (Carriage otherCarriage : train.carriages) {
				for (boolean betweenBits : Iterate.trueAndFalse) {
					if (betweenBits && lastPoint == null)
						continue;

					TravellingPoint otherLeading = otherCarriage.getLeadingPoint();
					TravellingPoint otherTrailing = otherCarriage.getTrailingPoint();
					if (otherLeading.edge == null || otherTrailing.edge == null)
						continue;
					ResourceKey<Level> otherDimension = otherLeading.node1.getLocation().dimension;
					if (!otherDimension.equals(otherTrailing.node1.getLocation().dimension))
						continue;
					if (!otherDimension.equals(dimension))
						continue;

					Vec3 start2 = otherLeading.getPosition(train.graph);
					Vec3 end2 = otherTrailing.getPosition(train.graph);

					if (Math.min(start2.distanceToSqr(start), end2.distanceToSqr(start)) > maxDistanceSqr)
						continue Trains;

					if (betweenBits) {
						end2 = start2;
						start2 = lastPoint;
					}

					lastPoint = end2;

					if ((end.y < end2.y - 3 || end2.y < end.y - 3)
						&& (start.y < start2.y - 3 || start2.y < start.y - 3))
						continue;

					Vec3 diff2 = end2.subtract(start2);
					Vec3 normedDiff2 = diff2.normalize();
					double[] intersect = VecHelper.intersect(start, start2, normedDiff, normedDiff2, Axis.Y);

					if (intersect == null) {
						Vec3 intersectSphere = VecHelper.intersectSphere(start2, normedDiff2, start, .125f);
						if (intersectSphere == null)
							continue;
						if (!Mth.equal(normedDiff2.dot(intersectSphere.subtract(start2)
							.normalize()), 1))
							continue;
						intersect = new double[2];
						intersect[0] = intersectSphere.distanceTo(start) - .125;
						intersect[1] = intersectSphere.distanceTo(start2) - .125;
					}

					if (intersect[0] > diffLength)
						continue;
					if (intersect[1] > diff2.length())
						continue;
					if (intersect[0] < 0)
						continue;
					if (intersect[1] < 0)
						continue;

					return Pair.of(train, start.add(normedDiff.scale(intersect[0])));
				}
			}
		}
		return null;
	}
```

> **注意**：上面的 `continue Trains` 是带标签的 continue，**不能**在改成 `while (iterator)` 之后照抄 —— 需要保留标签或改写为「标志位 + break 内层」。为减少改写面，**推荐保留原 `for` 循环结构，只做三处替换**：① `Math.pow` → 乘法；② 把 `normedDiff`/`diffLength` 提到循环外（diff 是循环不变量）；③ 循环内不再重复 `diff.normalize()`。这样 `continue Trains` 原样保留。

**最小改动版（推荐落地用这个）**：

```java
	public Pair<Train, Vec3> findCollidingTrain(Level level, Vec3 start, Vec3 end, ResourceKey<Level> dimension) {
		Vec3 diff = end.subtract(start);
		double diffLength = diff.length();                    // ① 提到循环外
		Vec3 normedDiff = diff.normalize();                   // ① 提到循环外（原在最内层）
		double maxAssemblyLength = AllConfigs.server().trains.maxAssemblyLength.get();
		double maxDistanceSqr = maxAssemblyLength * maxAssemblyLength;   // ② 去 Math.pow

		Trains: for (Train train : Create.RAILWAYS.sided(level).trains.values()) {
			/* ... 中间与原实现逐字相同 ... */

					Vec3 diff2 = end2.subtract(start2);
					Vec3 normedDiff2 = diff2.normalize();     // ③ 不再重算 normedDiff
					double[] intersect = VecHelper.intersect(start, start2, normedDiff, normedDiff2, Axis.Y);

			/* ... */

					if (intersect[0] > diffLength)            // ③ 用缓存长度
						continue;
					if (intersect[1] > diff2.length())
						continue;
					/* ... */
		}
		return null;
	}
```

## 4. Mixin 类骨架

```java
@Mixin(value = Train.class, remap = false)
public abstract class TrainMixin {

	@Overwrite
	public Pair<Train, Vec3> findCollidingTrain(Level level, Vec3 start, Vec3 end, ResourceKey<Level> dimension) {
		/* 见第 3 节 */
	}
}
```

描述符：`(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/resources/ResourceKey;)Lnet/createmod/catnip/data/Pair;`

**更保守的替代（不用 Overwrite）**：`@ModifyExpressionValue` 挂在 `Math.pow` 那次 `maxAssemblyLength.get()` 上无法消除 `Math.pow` 本身；挂 `Vec3.normalize()` 无意义。所以本条**要么改写、要么不动** —— 这也是把它列为 Overwrite 候选的原因。

## 5. 风险

| 风险 | 说明 |
| --- | --- |
| 语义等价性 | 三处替换都只是把**同一个值**从内层提到外层（`diff` 在整个方法内不变），不改变任何分支；风险低。 |
| 浮点一致性 | `maxAssemblyLength * maxAssemblyLength` 与 `Math.pow(x, 2.0)` 对整数值（配置项是 `ConfigInt`，5..512）结果**完全一致**（double 乘法对 < 2³⁰ 的整数是精确的）。 |
| 标签循环 | 若照抄第 3 节第一版（`while + iterator`）而忘了处理 `continue Trains`，会编译失败或死循环。**用最小改动版**。 |
| 上游重构 | 方法签名变动 → 启动报错。 |
| 与 Create 自带 mixin | 无重叠。 |

## 6. 回归测试点

1. **对撞**：两列车在同一轨道相向而行（相距 200 格），确认**仍然爆炸/相撞**（`findCollidingTrain` 返回值非 null 时 `Train.java:646-652` 触发 `explode`）。这是关键回归项 —— 如果改动漏判，列车会互相穿过。
2. **擦肩而过**：两列车在**平行但不同**的轨道上相向而过（间距 2 格），确认**不**误判为相撞。
3. **近距离同向**：两列车同向行驶、速度差很大时追尾，确认能判到。
4. **维度隔离**：主世界列车与下界列车（图不同）不应互相检测。
5. **列车总长 > maxAssemblyLength**：把 `maxAssemblyLength` 调到 5，确认长列车不会被跳过检测（验证 `maxDistanceSqr` 计算正确）。
6. **配置改动**：运行中把 `maxAssemblyLength` 从 128 改到 16，确认新值立刻生效（验证没有把配置缓存到静态字段）。
7. **搬迁**：用列车装配器（`TrainRelocator`，`:227` 也调用本方法）搬迁列车到另一条轨道，确认目标位置有车时**拒绝装配**。

## 7. 收益

**估计**：每 tick 少 `O(T×C×2)` 次 `Vec3.normalize()`（每次 = 1 个 `diff.length()` → `Math.sqrt` + 1 次 `scale` → 1 个 `Vec3`）与少 1 次 `Math.pow`+配置读取。T=10、C=5 时约每 tick 省 100 次 `normalize`。**估计值，需实测**。
