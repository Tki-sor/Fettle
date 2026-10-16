# Overwrite 施工图：`CarriageCouplingRenderer.renderAll`（**条件性方案：本文件不提供可运行方法体**）

> 目标方法：`com.simibubi.create.content.trains.entity.CarriageCouplingRenderer#renderAll(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/phys/Vec3;)V`
> 对应发现：[07-client-render-ui.md](../07-client-render-ui.md) 发现 7（Medium）
> 公共约定见 [01-mixin-playbook.md](../01-mixin-playbook.md)

## 0. 本文件的立场（请先读）

**在拿到 catnip（`CachedBuffers` / `SuperByteBuffer`）的源码或实测结论之前，不要合入这一条的代码。** 本文件只做三件事：

1. 说明循环里到底重复了什么（可核对的确定事实）；
2. 说明**为什么**不能凭现有信息写出安全的复用版本（不确定点）；
3. 给出「若要动手，必须先回答的实验」与「与 catnip 无关、确定安全的改动」清单。

这符合手册的原则：外部 jar 的分配/状态语义一律标「需实测」，不许编造。

## 1. 原方法关键段落完整源码（`CarriageCouplingRenderer.java:36-114`，逐字摘录）

```java
		for (Train train : trains) {
			List<Carriage> carriages = train.carriages;
			for (int i = 0; i < carriages.size() - 1; i++) {
				Carriage carriage = carriages.get(i);
				CarriageContraptionEntity entity = carriage.getDimensional(level).entity.get();
				Carriage carriage2 = carriages.get(i + 1);
				CarriageContraptionEntity entity2 = carriage.getDimensional(level).entity.get();

				if (entity == null || entity2 == null)
					continue;

				CarriageBogey bogey1 = carriage.trailingBogey();
				CarriageBogey bogey2 = carriage2.leadingBogey();
				Vec3 anchor = bogey1.couplingAnchors.getSecond();
				Vec3 anchor2 = bogey2.couplingAnchors.getFirst();

				if (anchor == null || anchor2 == null)
					continue;
				if (!anchor.closerThan(camera, 64))
					continue;

				int lightCoords = getPackedLightCoords(entity, partialTicks);
				int lightCoords2 = getPackedLightCoords(entity2, partialTicks);

				double diffX = anchor2.x - anchor.x;
				double diffY = anchor2.y - anchor.y;
				double diffZ = anchor2.z - anchor.z;
				float yRot = AngleHelper.deg(Mth.atan2(diffZ, diffX)) + 90;
				float xRot = AngleHelper.deg(Math.atan2(diffY, Math.sqrt(diffX * diffX + diffZ * diffZ)));

				Vec3 position = entity.getPosition(partialTicks);
				Vec3 position2 = entity2.getPosition(partialTicks);

				ms.pushPose();

				{
					ms.pushPose();
					ms.translate(anchor.x - camera.x, anchor.y - camera.y, anchor.z - camera.z);
					CachedBuffers.partial(AllPartialModels.TRAIN_COUPLING_HEAD, air)
						.rotateYDegrees(-yRot)
						.rotateXDegrees(xRot)
						.light(lightCoords)
						.renderInto(ms, vb);

					float margin = 3 / 16f;
					double couplingDistance = train.carriageSpacing.get(i) - 2 * margin
						- bogey1.type.getConnectorAnchorOffset(bogey1.isUpsideDown()).z - bogey2.type.getConnectorAnchorOffset(bogey2.isUpsideDown()).z;
					int couplingSegments = (int) Math.round(couplingDistance * 4);
					double stretch = ((anchor2.distanceTo(anchor) - 2 * margin) * 4) / couplingSegments;
					for (int j = 0; j < couplingSegments; j++) {
						CachedBuffers.partial(AllPartialModels.TRAIN_COUPLING_CABLE, air)
							.rotateYDegrees(-yRot + 180)
							.rotateXDegrees(-xRot)
							.translate(0, 0, margin + 2 / 16f)
							.scale(1, 1, (float) stretch)
							.translate(0, 0, j / 4f)
							.light(lightCoords)
							.renderInto(ms, vb);
					}
					ms.popPose();
				}

				{
					ms.pushPose();
					Vec3 translation = position2.subtract(position)
						.add(anchor2)
						.subtract(camera);
					ms.translate(translation.x, translation.y, translation.z);
					CachedBuffers.partial(AllPartialModels.TRAIN_COUPLING_HEAD, air)
						.rotateYDegrees(-yRot + 180)
						.rotateXDegrees(-xRot)
						.light(lightCoords2)
						.renderInto(ms, vb);
					ms.popPose();
				}

				ms.popPose();
			}
		}
```

## 2. 循环里确定重复的内容

`:85-94` 的 `j` 循环里，随 `j` 变化的**只有** `:91` 的 `translate(0, 0, j / 4f)`。以下全部与 `j` 无关却每段重做一次：

- `CachedBuffers.partial(AllPartialModels.TRAIN_COUPLING_CABLE, air)`（catnip 缓存查找，成本**需实测**）；
- `rotateYDegrees(-yRot + 180)`、`rotateXDegrees(-xRot)`、`translate(0, 0, margin + 2/16f)`、`scale(1, 1, stretch)`；
- `light(lightCoords)`。

另外与 `j` 无关、确定安全的优化点（**与 catnip 无关**）：

- `:82` 的 `bogey1.type.getConnectorAnchorOffset(bogey1.isUpsideDown()).z` 与 `bogey2...` 每对车厢各算一次即可（当前已经在 `j` 循环外，**无需修改**）；
- `:83-84` 的 `couplingSegments`/`stretch` 同样已在循环外（**无需修改**）。

也就是说：**除了 `j` 循环内的重复，这一段没有别的可安全优化项**。

## 3. 为什么不能凭现有信息写出复用版本

要把 `CachedBuffers.partial(...)` 与其后的 4 次变换提到 `j` 循环外（或每次 `copy()`），必须回答两个问题，而**catnip 未随本仓库提供**（已确认 gradle 缓存与仓库内均无相关 jar）：

1. `CachedBuffers.partial(model, state)` 对同一 `(model, state)` 是返回**缓存共享实例**还是**新实例**？若是共享实例，把它提到循环外意味着同一实例被连续变换并在 `renderInto` 中消费；
2. `SuperByteBuffer.renderInto(PoseStack, VertexConsumer)` 之后，自己的变换状态（`rotateY/translate/scale/light` 累积的矩阵与光照）是**自动复位**、需要显式 `resetTransforms()`、还是**永远累积**？
   - 若永远累积：原代码每次调 `CachedBuffers.partial` 正是因为**必须**拿一个新对象，把它提出循环会导致第 2 段起变换翻倍（视觉错乱）；
   - 若自动复位且 `partial` 返回新对象：提出循环就等于省掉 `couplingSegments-1` 次「构造 + 4 次变换」；
   - 若 `partial` 返回共享对象：即使自动复位也未必安全（同一帧内其它调用点可能也在用同一实例，例如 `:74` 的 `TRAIN_COUPLING_HEAD` 与此处的 `TRAIN_COUPLING_CABLE` 模型不同，但 `CouplingRenderer.java:68-70` 也用了 `CachedBuffers.partial`）。

**无法从本仓库判定**，所以本文件不给方法体。给出的实测方法见第 4 节。

## 4. 动手前必须先做的三个实验

1. **`partial` 是否返回同一实例**：临时 mixin 在 `j` 循环里记录 `System.identityHashCode(buffer)`，打印前 3 段——相同 ⇒ 共享缓存实例，不同 ⇒ 每次新建。
2. **`renderInto` 是否复位**：在同一实例上连续调用两次 «`rotateYDegrees(90).renderInto(...)`»，观察第二次是否额外旋转了 90°（截图比对或读取 `SuperByteBuffer` 的公开 getter，如果有）。
3. **代理验证**：在 `j` 循环外调一次 `CachedBuffers.partial(...)` 并把 `j` 循环改成只做 `translate + renderInto（若支持 `copy()` 就每次 `copy()`），然后在**多编组弯曲轨道**场景下逐帧截图与改前比对；另外刻意让 3 列以上列车同时入镜，检查是否有缆绳落在错误位置（变换污染的典型症状）。

以上三个实验都不需要修改 Create 源码（用临时 mixin / 调试命令即可）。

## 5. 与 catnip 无关、可以先做的改动（若你不想等实验）

- `:42` `entity2 = carriage.getDimensional(level).entity.get()`：**不是**单纯笔误，**不要顺手改**——因为 `position2 == position`，`:100-103` 的 `position2.subtract(position).add(anchor2)` 恰好退化为 `anchor2`（与 `:73` 对第一个耦合头用的 `anchor - camera` 一致），看起来才是本意。若把 `:42` 改成 `carriage2`，必须同时把 `:100-103` 改成 `anchor2.subtract(camera)`。**唯一明确的错误是 `:58` 的 `lightCoords2` 取了第一节车厢的位置**（因为 `entity2 == entity`）。修 `lightCoords2` 需要同时决定 `:100-103` 的形式，因此这条应提给上游（见主文件 U2），不要在性能补丁里夹带。
- `:80-84` 的 `margin`/`couplingDistance`/`couplingSegments`/`stretch` 已经是循环外计算，无需改动。

## 6. 回归测试点（无论最终是否改）

1. 多编组列车（≥3 列）同时入镜，缆绳外观逐帧与改前一致（重点检查是否出现「某一段落在错误位置」）。
2. 弯曲轨道 / 上下坡列车：缆绳的伸缩（`stretch`）与倾斜正确。
3. 编组变化（增删车厢）：不出现残留段。
4. 夜间：两个耦合头的光照分别取自各自车厢位置（若同时修 `:42/:58`，需要单独一条变更记录）。
5. `PonderLevel` / 包装世界下不崩。
6. 性能：统计每帧 `CachedBuffers.partial` 调用次数与 `couplingSegments` 总和；若采用实验通过后的版本，前者应从「段数 × 耦合对数」降到「耦合对数」。
