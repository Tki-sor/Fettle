# 07 · 客户端渲染 / GUI / 客户端 tick 性能问题

> 覆盖范围：`foundation/item`、`foundation/block`（BigOutlines）、`foundation/particle`、`foundation/utility`（TickBasedCache）、`foundation/sound`、`content/equipment/**`（goggles / armor / hats）、`content/trains/track`、`content/trains/entity`（CarriageCouplingRenderer）、`content/kinetics/belt`、`content/contraptions/minecart`、`content/logistics/stockTicker`、`content/redstone/nixieTube`
> 调查依据：Create 6.0.10 / MC 1.21.1 源码逐行核对；**文中行号均已逐条打开源码确认**，与最初清单不一致处以本文件为准（见每条的「位置」栏）
> 公共约定见 [01-mixin-playbook.md](01-mixin-playbook.md)

## 摘要

- **本领域最大的三处**：① `TooltipHelper.cutTextComponent` 对每个词调一次 `String.replaceAll`（即每次调用编译「词数」次正则，`TooltipHelper.java:99`）；② `GoggleOverlayRenderer.renderOverlay` 是 `LayeredDraw.Layer`，**每帧**重建整份护目镜 tooltip（`ClientEvents.java:379` 注册，`GoggleOverlayRenderer.java:103-183`）；③ `TrackBlockOutline.pickCurves` 由 `GameRenderer.pick` 尾钩每帧驱动，对「射线命中的弯道」做 `长度×2` 次迭代，每次迭代约 20 个 `Vec3`（`TrackBlockOutline.java:89-127`）。
- **一处与性能无关的真实 bug（优先级最高）**：`CouplingHandler.forEachLoadedCoupling` 的 `:57/:60/:63` 用 `return` 代替了 `continue`，遇到第一个「未加载 / 非 leading / 对端缺失」的 coupling 就**终止整轮遍历**。它同时被客户端渲染（`CouplingRenderer.java:35`）与**服务端**耦合物理（`CouplingPhysics.java:24` ← `CommonEvents.java:139`）调用，后果是从该点开始的 coupling 静默失去物理与渲染。这是服务端行为 bug，应按 High 处理。
- **Flywheel 门控校正（会改变结论）**：`TrackRenderer.renderSafe` 第一行就是 `if (VisualizationManager.supportsVisualization(level)) return;`（`TrackRenderer.java:39`，因为 Create 注册了 `TrackVisual`，`AllBlockEntityTypes.java:939`），所以**普通客户端上轨道 BER 的整段贝塞尔根本不执行**；相反 `BeltRenderer.renderItems` 在 `BeltRenderer.java:161` 位于门控之外，**始终执行**。原清单把这两条并列看待，此处已分别修正（见发现 5、10）。
- **外部 jar 免责**：`SuperByteBuffer` / `CachedBuffers` / `Outliner` / `VecHelper` / `Iterate` / `Couple` / `AnimationTickHolder` 属 Flywheel 与 catnip，**本仓库不含其源码**（已确认 gradle 缓存与仓库内均无相关 jar）。凡涉及它们的「每次分配几个对象」「复杂度 O(?)」均为**估计**；凡涉及它们内部语义（例如 `SuperByteBuffer` 的变换状态是否在 `renderInto` 后复位）一律标注「需实测」。
- **评级说明**：每条标题后括号内是本文件复核后的评级；若与原清单不同，在「复杂度 / 成本」栏给出降级/升级理由。**所有收益数字均为静态推理的量级估计，不是实测值。**

## 发现

### 1. `TooltipHelper.cutTextComponent` 每词一次 `Pattern.compile` — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/foundation/item/TooltipHelper.java:99`（`font.width(word.replaceAll("_", ""))`）。同一方法内相关行：`:79` 方法签名（4 参重载）、`:84` `new LinkedList<>()`、`:85` `BreakIterator.getLineInstance`、`:95` 第二个 `LinkedList`、`:121` `new ArrayList<>(lines.size())`、`:127` `string.split("_", -1)` |
| 调用频次 | 每帧，且同一帧内可达多次。调用链：`ClientEvents.registerGuiOverlays`（`ClientEvents.java:373,379`，`registerAbove(VanillaGuiLayers.HOTBAR, ...GoggleOverlayRenderer.OVERLAY)`）→ `GoggleOverlayRenderer.renderOverlay` → `IHaveHoveringInformation.addToTooltip`（`GoggleOverlayRenderer.java:118`）→ `KineticBlockEntity.addToTooltip` → `TooltipHelper.cutTextComponent`（`KineticBlockEntity.java:426` overstressed、`:442` notFastEnough——在「转速不足」的机器上每次注视都命中）。提示路径同理：`TooltipHelper.addHint`（`TooltipHelper.java:30-36`，内部 `:33` 调 `cutTextComponent`）← `ArmBlockEntity.java:598`、`DeployerBlockEntity.java:502`、`FrogportBlockEntity.java:382`、`HosePulleyBlockEntity.java:70`、`MechanicalBearingBlockEntity.java:348`、`TrainRelocator.java:371`；另有 `IDisplayAssemblyExceptions.java:29` |
| 复杂度 / 成本 | **每次调用**：1 个 `BreakIterator`（+`setText` 全文扫描）、2 个 `LinkedList`（+每词 1 个 Node）、每词 1 个 `substring`、**每词 1 次 `Pattern.compile` + 1 个 `Matcher` + 1 个 `StringBuilder` + 1 个结果字符串**（`replaceAll` 语义，确定）；收尾另有 1 个 `ArrayList`、每行 1 个 `plainCopy()`、每 `_` 段 1 个 `Component.literal`。规模放大：`每帧调用次数 × 提示词数`；典型护目镜提示 10–40 词 ⇒ **每帧数十次正则编译**。`String.split("_", -1)` 走 JDK 单字符非元字符快路径，**不**编译正则（原清单未区分，此处更正） |
| 注入方式 | 第一步 `@WrapOperation`（Batch A、零状态）；第二步 `@Inject(HEAD, cancellable)` + `@Unique` 有界缓存，或整段重写（Batch B，见 `overwrites/TooltipHelper-cutTextComponent.md`） |
| 目标成员 | `com.simibubi.create.foundation.item.TooltipHelper#cutTextComponent(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;I)Ljava/util/List;`（**必须写 4 参描述符**：`(Component;Palette)` 与 `(Component;Style;Style)` 两个重载最终都委托到它，只注 4 参版即可覆盖全部调用者） |
| 需新增状态 | 第一步无。第二步：`@Unique static` 有界 LRU（≤256 条）；作用域＝客户端进程级；失效＝以输入串（`c.getString()`）与样式作键，语言/资源重载会自然产生新键，另设容量上限兜底；并发＝只在客户端主线程与渲染线程调用（MC 客户端顺序执行 tick 与帧），无需 volatile |
| 风险 | 第一步 低（单点、无状态、语义等价）；第二步 中（缓存对象是否被下游就地修改，见回归测试点） |
| 脆弱度 | 中：`@WrapOperation` 依赖 `java/lang/String.replaceAll` 这一调用点存在且位于 4 参重载内。上游若改成 `replace`/`StringUtils`，注入失败会由 `injectors.defaultRequire = 1`（手册第 1 节）在启动期直接报错而不会静默失效；若上游改了重载结构，同样在启动期暴露 |
| 置信度 | 确定（`replaceAll` 每次调用编译 Pattern 是 JDK 语义；所有行号与调用点已逐行核对） |

**改前**

```java
// TooltipHelper.java:79-137（保留原缩进风格，节选关键行）
    public static List<Component> cutTextComponent(Component c, Style primaryStyle,
        Style highlightStyle, int indent) {
        String s = c.getString();

        // Split words
        List<String> words = new LinkedList<>();
        BreakIterator iterator = BreakIterator.getLineInstance(Minecraft.getInstance().getLocale());
        iterator.setText(s);
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String word = s.substring(start, end);
            words.add(word);
        }

        // Apply hard wrap
        Font font = Minecraft.getInstance().font;
        List<String> lines = new LinkedList<>();
        StringBuilder currentLine = new StringBuilder();
        int width = 0;
        for (String word : words) {
            int newWidth = font.width(word.replaceAll("_", ""));   // <-- 每词一次 Pattern.compile
            if (width + newWidth > MAX_WIDTH_PER_LINE) {
                if (width > 0) {
                    String line = currentLine.toString();
                    lines.add(line);
                    currentLine = new StringBuilder();
                    width = 0;
                } else {
                    lines.add(word);
                    continue;
                }
            }
            currentLine.append(word);
            width += newWidth;
        }
        if (width > 0) {
            lines.add(currentLine.toString());
        }

        // Format
        MutableComponent lineStart = Component.literal(Strings.repeat(" ", indent));
        lineStart.withStyle(primaryStyle);
        List<Component> formattedLines = new ArrayList<>(lines.size());
        Couple<Style> styles = Couple.create(highlightStyle, primaryStyle);

        boolean currentlyHighlighted = false;
        for (String string : lines) {
            MutableComponent currentComponent = lineStart.plainCopy();
            String[] split = string.split("_", -1);
            for (String part : split) {
                currentComponent.append(Component.literal(part).withStyle(styles.get(currentlyHighlighted)));
                currentlyHighlighted = !currentlyHighlighted;
            }

            formattedLines.add(currentComponent);
            currentlyHighlighted = !currentlyHighlighted;
        }

        return formattedLines;
    }
```

**为什么慢**

运行频次 × 单次成本：这条路径是「**只要戴着护目镜看着某个 Create 方块**」就每帧执行，而其中的 `KineticBlockEntity.addToTooltip` 在 `notFastEnough`（`KineticBlockEntity.java:419`：速度要求未满足且当前转速非 0，例如一台转速不够的辊压机/压床）或 `overStressed`（受配置 `enableOverstressedTooltip` 控制）时**必然**调用 `cutTextComponent`，不是偶发分支。每次调用按「词数」次编译正则——`Pattern.compile` 是 JVM 里最贵的常用操作之一（缓存未命中时要构造语法树）；与词数无关的固定部分（`BreakIterator`、两个 `LinkedList`、每行 `plainCopy`）也不小。规模放大场景：刷新率 144/240 fps 时每秒调用次数直接乘以帧率，护目镜 tooltip 属 GUI 层，帧率越高浪费越大。

**改后**

```java
// 第一步（Batch A，最小、零风险）："_" 不是正则元字符，replace 与 replaceAll 在本用例下语义完全等价
int newWidth = font.width(word.replace("_", ""));

// 第二步（Batch B / overwrite 的核心）：布局只跑一遍（去掉两个 LinkedList），并把
// 「布局结果」（行字符串 + 每行按 '_' 切好的片段）按 (输入串, 缩进) 缓存；
// Component 每次都新建（理由见回归测试点，不要缓存 Component 本体）。
```

**Mixin 骨架**（第一步：Batch A）

```java
package com.example.createperf.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.item.TooltipHelper;

@Mixin(value = TooltipHelper.class, remap = false)   // Create 类未混淆 -> remap = false（手册第 0 节）
public abstract class TooltipHelperMixin {

    @WrapOperation(
        method = "cutTextComponent(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;I)Ljava/util/List;",
        at = @At(value = "INVOKE",
                 target = "Ljava/lang/String;replaceAll(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"))
    private String createperf$replaceWithoutRegex(String self, String regex, String replacement,
                                                 Operation<String> original) {
        // 不调用 original：它内部就是 Pattern.compile(regex).matcher(self).replaceAll(replacement)。
        // 本调用点两个参数都是编译期常量（"_" 与 ""），因此 replace 与之逐字等价。
        return self.replace(regex, replacement);
    }
}
```

第二步（`@Inject` + `@Unique` 缓存）的完整实现见 `overwrites/TooltipHelper-cutTextComponent.md`；骨架形态：

```java
@Mixin(value = TooltipHelper.class, remap = false)
public abstract class TooltipHelperCacheMixin {

    @Unique private static final int createperf$MAX = 256;
    @Unique private static final Map<Object, List<Component>> createperf$cache = ...;   // 有界 LRU

    @Inject(method = "cutTextComponent(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;I)Ljava/util/List;",
            at = @At("HEAD"), cancellable = true)
    private static void createperf$pre(Component c, Style primaryStyle, Style highlightStyle, int indent,
                                       CallbackInfoReturnable<List<Component>> cir) {
        Object key = createperf$key(c.getString(), primaryStyle, highlightStyle, indent);
        List<Component> hit = createperf$cache.get(key);
        if (hit != null)                          // 只复用「布局」，Component 本体每次重建
            cir.setReturnValue(createperf$rebuild(hit, primaryStyle, highlightStyle, indent));
    }
}
```

**验证方法**

- 探针：在 mixin 内加 `@Unique static` 计数与 `System.nanoTime()` 累加（每 100 tick 打一次 logger，手册 6.2 节）。固定场景：「戴护目镜注视一台转速不足的辊压机 30 秒 + 注视一个已满的部署器 30 秒」，分别跑「注入开 / 关」两组。
- 反汇编确认注入生效：以 `-Dmixin.debug.verbose=true -Dmixin.debug.export=true` 启动（Create 的 dev run 已开，见 `build.gradle:61`），在 `run/.mixin.out/` 导出目录对 `TooltipHelper.class` 执行 `javap -c`，确认 `invokevirtual java/lang/String.replace` 取代了 `replaceAll`。
- 分配观测：JFR `ObjectAllocationSample` 或 `/spark profiler start --alloc`，过滤 `java.util.regex.Matcher` / `java.util.regex.Pattern$...` / `java.util.LinkedList$Node`，确认 `TooltipHelper.cutTextComponent` 不再出现在分配栈里。
- 端到端：`/spark profiler --timeout 60` 对照同一存档同一装置规模前后各 3 次取中位数。

**收益估计**：量级——每次调用消除「词数」次 `Pattern.compile`（典型 10–40 次/调用），护目镜常驻注视场景从「每帧数十次正则编译」降到 0；同时去掉 2 个 `LinkedList` 及其节点。绝对帧时间收益取决于词数与注视时长，**属估计**。

**回归测试点**

- 换行宽度与 `_` 高亮交替必须**逐字不变**：`_` 只从宽度计算里剔除，输出字符串里保留，并在格式化阶段切分（`TooltipHelper.java:99` vs `:127`）。
- 边界：单个词宽于 `MAX_WIDTH_PER_LINE=200`（走 `lines.add(word)` 分支）时行为不变。
- `indent` 参数（`ItemDescription.java:155,162` 传 1）产生的行首空格数量不变。
- 语言切换（中↔英）后提示立刻更新——用缓存的版本必须确认键里含「已解析字符串」而不是翻译键。
- 若采用第二步：**必须确认 `LangBuilder.add(Component)` 不会就地修改传入的 Component**。风险证据：`CreateLang.itemName`（`CreateLang.java:47-50`）对本来就是新对象的 `stack.getHoverName()` 仍先 `.copy()` 再 `add`，暗示 `add` 可能写入该 Component。因此本文件给出的方案是**只缓存布局、每次重建 Component**；验证通过之前不要缓存 Component 本体。
- 视觉回归：`ItemDescription`、`AbstractFilterScreen.java:138`、`SchematicannonScreen.java:361-369`、`LinkedControllerScreen.java:242` 的 tooltip 外观。

### 2. `GoggleOverlayRenderer.renderOverlay` 每帧重建整份护目镜 tooltip — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/equipment/goggles/GoggleOverlayRenderer.java:103`（`ItemStack item = AllItems.GOGGLES.asStack();`）、`:104`（`new ArrayList<>()`）、`:109-130`（三类 tooltip 内容重建）、`:179`（逐行 `mc.font.width` 测宽）、`:75`（遍历 `Outliner` 全部条目）。注册点：`ClientEvents.java:373-379`（`registerAbove(VanillaGuiLayers.HOTBAR, Create.asResource("goggle_info"), GoggleOverlayRenderer.OVERLAY)`） |
| 调用频次 | **每帧**（`LayeredDraw.Layer`）；只要 `mc.hitResult` 是方块命中，且（戴护目镜 或 该方块实现了 `IHaveHoveringInformation` / 有装配异常）就会走到 `:103` 之后 |
| 复杂度 / 成本 | 每次重建：1 个 `ItemStack`（`asStack()`）+ 1 个 `ArrayList` + 该方块 tooltip 的全部 `Component`/翻译查找/数值格式化（例如 `IHaveGoggleInformation.containedFluidTooltip` 每槽位若干 `LangBuilder`，`IHaveGoggleInformation.java:30-60`）+ 每行一次 `Font.width` 测宽（`:179`）+ `Outliner` 条目遍历（`:75`）。规模放大：tooltip 行数（库存/流体/应力/速度表）与帧率成正比；装满物品的蛙港 / 工厂面板 tooltip 可达几十行，每帧全部重建 |
| 注入方式 | `@WrapOperation` 包裹三类内容构建调用（`addToGoggleTooltip` / `addToTooltip` / `addExceptionToTooltip`）+ `@Unique` 每 tick 缓存；见下方「实现提示」（三个 `@WrapOperation` 的可重放性是成立的，**不需要**整段重写）。**不要**用 `@Inject(HEAD, cancellable)`：`renderOverlay` 尾部依赖 `hoverTicks`/fade/尺寸测量，整体短路会改变视觉 |
| 目标成员 | `com.simibubi.create.content.equipment.goggles.GoggleOverlayRenderer#renderOverlay(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V`；被包裹的三个调用点分别位于 `:111`、`:118`、`:125`：<br>`Lcom/simibubi/create/api/equipment/goggles/IHaveGoggleInformation;addToGoggleTooltip(Ljava/util/List;Z)Z`<br>`Lcom/simibubi/create/api/equipment/goggles/IHaveHoveringInformation;addToTooltip(Ljava/util/List;Z)Z`<br>`Lcom/simibubi/create/content/contraptions/IDisplayAssemblyExceptions;addExceptionToTooltip(Ljava/util/List;)Z` |
| 需新增状态 | `@Unique static` 缓存：键 = (BlockEntity 身份, isShifting, 客户端 tick)；值 = 该次调用**追加进 tooltip 的 Component 片段** + 返回的 boolean。作用域＝客户端进程级；失效＝客户端 tick 变化（`AnimationTickHolder.getTicks()`）、切换方块、切换 shift 状态；并发＝客户端单线程 |
| 风险 | 中高：缓存命中等价于「同一 tick 内重复调用结果不变」这一前提。已核对 `hoverTicks` 不参与任何 tooltip 实现（`GoggleOverlayRenderer.java:60,86-87,135,139,159,171,201` 是它全部读写点），且全仓库**没有任何 `addToGoggleTooltip`/`addToTooltip` 实现同时引用 `AnimationTickHolder`**（`BasinBlockEntity` 是唯一同时出现的文件，但 `:719` 位于 `createMovingFluidParticles`，`:749` 才是 tooltip 方法）。若第三方 mod 的 tooltip 读帧内插值量，则缓存会按 tick 粒度失真 |
| 脆弱度 | 中：依赖三个接口方法名/描述符（Create 公共 API，稳定）与调用点位于 `renderOverlay` 内。三方 mod 若也包裹同一调用点不冲突（MixinExtras 可组合） |
| 置信度 | 高概率（代码路径确定；「同一 tick 内 tooltip 内容不变」是基于调用链的推理，需实测确认第三方实现） |

**改前**

```java
// GoggleOverlayRenderer.java:103-130（原样摘录）
        ItemStack item = AllItems.GOGGLES.asStack();
        List<Component> tooltip = new ArrayList<>();

        if (be instanceof IHaveCustomOverlayIcon customOverlayIcon)
            item = customOverlayIcon.getIcon(isShifting);

        if (hasGoggleInformation && wearingGoggles) {
            IHaveGoggleInformation gte = (IHaveGoggleInformation) be;
            goggleAddedInformation = gte.addToGoggleTooltip(tooltip, isShifting);
        }

        if (hasHoveringInformation) {
            if (!tooltip.isEmpty())
                tooltip.add(CommonComponents.EMPTY);
            IHaveHoveringInformation hte = (IHaveHoveringInformation) be;
            hoverAddedInformation = hte.addToTooltip(tooltip, isShifting);

            if (goggleAddedInformation && !hoverAddedInformation)
                tooltip.remove(tooltip.size() - 1);
        }

        if (be instanceof IDisplayAssemblyExceptions) {
            boolean exceptionAdded = ((IDisplayAssemblyExceptions) be).addExceptionToTooltip(tooltip);
            if (exceptionAdded) {
                hasHoveringInformation = true;
                hoverAddedInformation = true;
            }
        }

        if (!hasHoveringInformation)
            if (hasHoveringInformation =
                hoverAddedInformation = TrainRelocator.addToTooltip(tooltip, isShifting))
                hoverTicks = prevHoverTicks + 1;
```

**为什么慢**

频次：GUI 层，**每渲染一帧一次**（不是每 tick）。成本：tooltip 内容是「按世界状态现算」——每个 `Component` 都要走翻译表查找、数字格式化、`LangBuilder` 拼装；`:179-183` 还要对每行调一次 `Font.width`（字形布局缓存命中时便宜，行数多时仍可观）。规模放大：注视装满物品的蛙港（每槽位一行）、工厂面板或多罐流体容器时 tooltip 可达几十行，每帧重建几十个 `Component`；144 fps 下即每秒数千个 `Component` 与翻译查找。`:75` 的 `Outliner` 遍历是无条件前置检查（存在非 passive `ValueBox` 即 `return`），属顺带发现。

**改后**

```java
// 纯 Java 等价改法：把「内容构建」整体提到每 tick 一次的缓存后面。
// 关键点：缓存的是「这次调用往 tooltip 里追加了什么」，命中时原样重放，
// 因此 list 的最终内容与直接调用完全一致（包括 EMPTY 行与 remove 的配对）。

private static long lastTick = Long.MIN_VALUE;
private static BlockEntity lastBe;
private static boolean lastShift;
private static List<Component> lastTip = List.of();
private static boolean lastGoggle;
private static boolean lastHover;

// 命中判定：仅当 (同一 BE) && (同一 shift) && (同一客户端 tick) 时重放
```

**Mixin 骨架**（三个 `@WrapOperation` 形态；注意配对问题）

```java
package com.example.createperf.mixin.client;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.equipment.goggles.GoggleOverlayRenderer;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

@Mixin(value = GoggleOverlayRenderer.class, remap = false)
public abstract class GoggleOverlayRendererMixin {

    @Unique private static final Map<BlockEntity, CachedTip> createperf$tips = new IdentityHashMap<>();
    @Unique private static long createperf$tick = Long.MIN_VALUE;

    @Unique
    private static CachedTip createperf$get(BlockEntity be) {
        long now = AnimationTickHolder.getTicks();     // 客户端 tick 计数（catnip）
        if (now != createperf$tick) {                  // 每 tick 清一次；同时被注视的 BE 为个位数
            createperf$tick = now;
            createperf$tips.clear();
        }
        return createperf$tips.get(be);
    }

    // 1) 护目镜信息
    @WrapOperation(
        method = "renderOverlay(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(value = "INVOKE",
                 target = "Lcom/simibubi/create/api/equipment/goggles/IHaveGoggleInformation;addToGoggleTooltip(Ljava/util/List;Z)Z"))
    private boolean createperf$cacheGoggle(IHaveGoggleInformation self, List<Component> tooltip, boolean sneaking,
                                           Operation<Boolean> original) {
        if (!(self instanceof BlockEntity be))
            return original.call(self, tooltip, sneaking);
        CachedTip hit = createperf$get(be);
        if (hit != null) { tooltip.addAll(hit.appended()); return hit.result(); }

        int mark = tooltip.size();
        boolean result = original.call(self, tooltip, sneaking);
        createperf$tips.put(be, new CachedTip(result, List.copyOf(tooltip.subList(mark, tooltip.size()))));
        return result;
    }

    // 2) IHaveHoveringInformation;addToTooltip(Ljava/util/List;Z)Z  —— 形态同上
    // 3) IDisplayAssemblyExceptions;addExceptionToTooltip(Ljava/util/List;)Z —— 去掉 sneaking 参数
}
```

> **实现提示（为什么三个 `@WrapOperation` 就够了）**：初看担心的是 `:115-116` 的 `if (!tooltip.isEmpty()) tooltip.add(EMPTY)` 与 `:120-121` 的 `remove(size-1)` 依赖「调用前的 tooltip 状态」。但在「同一 tick、同一方块、同一 shift 状态」下，三个调用返回的 boolean 与各自追加的片段都是确定的，而重放时它们的**顺序与 boolean 完全相同**，因此每一步看到的 tooltip 前缀也完全相同（第一步之前 tooltip 必为空；第二步之前的内容 = 第一步重放的结果；异常分支同理）。结论：三个 `@WrapOperation` 各自缓存「本次追加的片段 + 返回值」并按原顺序重放，与原方法逐帧调用**逐项等价**。
>
> 因此**不需要**整段重写这个 190 行的方法（那会连带复制 ModernUI 兼容与 fade 数学，脆弱度反而更高）。`:134` 的 `TrainRelocator.addToTooltip` 保持不缓存（每次实时执行），它与缓存重放的内容按原顺序叠加，结果一致。
>
> 如果只想上 Batch A，请只做发现 1 的 `replaceAll` 替换，本条留到 Batch B。

**验证方法**

- 探针：统计 `renderOverlay` 调用次数与三个 `addTo*` 实际执行次数（而非缓存命中次数），每 100 tick 打一次；命中率应 ≈ 帧率/20 − 1。
- 正确性对照：固定场景（装满物品的蛙港 + 满流体罐 + 受应力的机器），注入开/关下逐帧截图对比 tooltip 文本与颜色；再在数值确实变化的时刻（物品被抽走、应力变化）确认下一 tick 立即反映。
- 分配观测：JFR / Spark alloc，关注 `net.minecraft.network.chat.` 与 `LangBuilder` 相关分配量下降。
- 边界：`hideGui`、旁观者模式、切换 `isShiftKeyDown`、注视方块切换（`lastHovered` 变化）时 `hoverTicks` 的归零路径不可破坏。

**收益估计**：量级——把「每帧重建」降为「每 tick 重建」，60/120/240 fps 下分别省下约 3/6/12 倍 tooltip 构建量（**估计**）。行数越多收益越大。
**回归测试点**：tooltip 内容与颜色逐帧一致；装配异常提示（`IDisplayAssemblyExceptions`）仍显示；`:143-168` 活塞延长杆极点计数不变；`hoverTicks` 渐入动画不变；`IHaveCustomOverlayIcon.getIcon` 每次都重新取（不要缓存 item）；ModernUI 兼容分支（`:221-252`）不变；注视方块从「有提示」切到「无提示」时 tooltip 必须立刻消失（不能因缓存保留上一方块的内容）。

### 3. `TrackBlockOutline.pickCurves` 每帧对候选弯道做 `长度×2` 次重计算 — [High]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/trains/track/TrackBlockOutline.java:50-141`（`pickCurves` 全体）。逐帧驱动点：`src/main/java/com/simibubi/create/foundation/mixin/client/GameRendererMixin.java:15-19`（`@Inject(method = "pick(F)V", at = @At("TAIL"))` → `BigOutlines.pick(); TrackBlockOutline.pickCurves();`）。关键行：`:69` 遍历全部弯道 BE、`:74-76` 每个连接一次 `bounds.clip`（返回 `Optional`）、`:81-83` 每个连接重建 `segmentBounds`（`AllShapes.TRACK_ORTHO.get(SOUTH).bounds()` + `.move(...)` 再产 1 个 AABB）、`:89` `for (int i = 0; i < stepLUT.length - 2; i++)`、`:94-95` 两次 `bc.getPosition`、`:97` `TrackRenderer.getModelAngles(bc.getNormal(t1), diff)`、`:101` `localDirection` 每次重算、`:107` `segmentBounds.clip` 的 `Optional<Vec3>`、`:125-126` 每个「改进命中」的段都新建 `BezierTrackPointLocation` 与 `BezierPointSelection` |
| 调用频次 | **每帧一次**（`GameRenderer.pick(F)V` 每帧执行）。`:69` 遍历 `TRACKS_WITH_TURNS`（`TrackBlockOutline.java:45`，由 `TrackBlockEntity.java:359,365` 维护）中**全部已加载弯道**；其中 AABB 被射线穿过的弯道（实践中 0–3 条）进入 `:89` 内层循环 |
| 复杂度 / 成本 | 内层迭代数 = `stepLUT.length - 2` = `(int)(length*2) - 1`（`:80` `segments = (int) (bc.getLength() * 2)`）⇒ **40 格弯道 ≈ 79 次/帧**。每次迭代约 20 个 `Vec3`：`getPosition`×2；`getNormal`（内部 `bezierDerivative` + `normalize` + `slerp` + 两次 `cross` + `normalize`，约 5–6 个，catnip 外部 jar，**估计**）；`getModelAngles`（`new Vec3(0,1,0)` + 两次 `VecHelper.rotate` + `distanceToSqr`/`cross`/`normalize`/`dot`，约 5–6 个；该类本仓库可读，见 `TrackRenderer.java:113-132`，属确定项）；`diff`/`anchor`/`localOrigin`/`localDirection` 及 4 次 `VecHelper.rotate`（约 8 个）。另有 `Optional<Vec3>`（仅命中时分配，未命中时 `Optional.empty()` 是单例）与命中段的 2 个 record。⇒ **单条被命中弯道每帧约 1500–1900 个对象**；顺轨看过去同时命中 2–3 条即 3000–5000 个/帧（**估计**）。外层另有 `:74-76` 每连接一次 AABB 射线 slab 测试（不分配） |
| 注入方式 | `@Overwrite`，或等价的 `@Inject(HEAD, cancellable)` 整段重写（推荐后者：保留原方法体，其它 mod 的注入不丢）。见 `overwrites/TrackBlockOutline-pickCurves.md`。局部注入**无法**消除 `:89-127` 的临时对象：它们在循环体内构造并立即使用，`@ModifyExpressionValue`/`@Redirect` 修饰 `new` 不会阻止 `NEW` 指令执行（手册 3.1 / 3.2 节） |
| 目标成员 | `com.simibubi.create.content.trains.track.TrackBlockOutline#pickCurves()V`（handler 必须声明为 `static`） |
| 需新增状态 | `@Unique static`：① 标量化 box-clip 的复用量（`double[]`/局部原始类型，最多 3 个分量）；② 预建 `static final AABB`（`segmentBounds`）；③ 与 `i` 无关的基底方向 `target.subtract(origin)`；④ `static final Vec3 kUp = new Vec3(0, 1, 0)`。作用域＝客户端进程级静态复用；失效＝每次方法进入重置；并发＝仅渲染/主线程（`GameRenderer.pick`） |
| 风险 | 高：`pickCurves` 直接改写 `mc.hitResult`（`:137-140`）与公共静态字段 `result`（`TrackBlockOutline.java:48`），影响轨道选取与放置交互；任何语义偏差都表现为「轨道点不中 / 点错段」 |
| 脆弱度 | 高：整段重写绑定 `BezierConnection` 的公开方法（`getStepLUT/getLength/getBounds/getPosition/getNormal/getKey/isPrimary`，均 public，稳定）、`AllShapes.TRACK_ORTHO`、`RaycastHelper.getTraceTarget`、`AnimationTickHolder.getPartialTicks`。上游若改变 `stepLUT` 的定义（`:90-92` 的 `t/t1/t2` 组合很特殊）则必须同步改 |
| 置信度 | 确定（行号、循环次数公式、调用链已核对）；每次迭代对象数为**估计**（`VecHelper` 在外部 jar；`getModelAngles` 在本仓库可读） |

**改前**

完整原方法见 `overwrites/TrackBlockOutline-pickCurves.md` 的「原方法完整源码」（`TrackBlockOutline.java:50-141` 原样）。核心片段：

```java
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
                    ...
                    BezierTrackPointLocation location = new BezierTrackPointLocation(bc.getKey(), i);
                    result = new BezierPointSelection(be, location, anchor, angles, diff.normalize());
                }
```

**为什么慢**

频次：`GameRenderer.pick(F)V` 是**每帧**（不是每 tick），`pickCurves` 是它的 TAIL 注入，因此只要玩家注视着装有轨道的区域就每帧跑完整几何。单次成本：内层把「贝塞尔求值 + 法线 + 姿态角 + 局部空间裁剪」整套算了一遍以上；`getModelAngles`（`TrackRenderer.java:113-132`，本仓库可读）自身就构造 `new Vec3(0,1,0)`、两次 `VecHelper.rotate`、`distanceToSqr`、`cross`、`normalize`、`dot`。规模放大：曲线越长 `segments` 越大（**80 格弯道 ≈ 159 次/帧**）；玩家在大型铁路网里顺轨看会同时命中多条弯道的 AABB。注意 `:129-130` 会把 `maxRange` 收紧，因此越靠后的连接越容易被后续跳过，但**不能**保证前半段被命中弯道免于全量迭代。

**改后**

完整可粘贴实现见 `overwrites/TrackBlockOutline-pickCurves.md`。方案分两层，**第一层（粗筛）才是真正的大头**：

**第一层：按「锚点半径」预筛，把每次迭代的昂贵数学从 ~79 次降到 1–3 次。**

关键观察：原代码对第 `i` 段做的判定是「射线在**以 `anchor_i` 为中心**的 `segmentBounds` 局部坐标系内与该盒相交」（`localOrigin = origin - anchor`，`:99-107`）。因此：**只有 `anchor_i` 到射线段的距离 ≤ `segmentBounds` 半对角线 `R` 的段才可能被接受**（旋转保距，交点在盒内 ⇒ 它到盒心的距离 ≤ R ⇒ 该点在射线上 ⇒ 点到射线的距离 ≤ R）。`R` 可由 `AllShapes.TRACK_ORTHO.get(SOUTH).bounds()` 的尺寸算出（**取一个偏大的保守常数，例如 1.0 block，只会降低筛选率、绝不改变结果**）。

于是：**每个 `BezierConnection` 每帧只算一次 `anchor` 数组（缓存，见下），帧内先用标量「点—直线距离」筛掉绝大多数段，再对通过的少数段执行原有精确数学。** 迭代顺序仍为 `i` 升序，被跳过的段在原逻辑里必然 `clip.isEmpty()` ⇒ `bestSegment`/`bestDistance`/`maxResult`/`result` 逐项等价。

**第二层：消除循环内的零碎分配。**

1. `segmentBounds` 提为 `@Unique static final AABB`（只依赖静态形状表，与连接无关，现在**每个连接都重建一次**，`:81-83`）；
2. `target.subtract(origin)` 提到内层循环外（与 `i` 无关）；
3. `new BezierPointSelection`/`new BezierTrackPointLocation` 延迟到循环结束（只构造最终那一个），循环内只保存 `bestSegment` 与对应的 `anchor`/`angles`/`diff`；
4. 外层加一道距离粗筛：`origin` 到 `bounds.getCenter()` 超过 `interactionRange + 包围盒半对角线` 时直接 `continue`。

**anchor 数组缓存的生命周期**：键 = `BezierConnection` 身份（其 `starts/axes/normals` 是 `final`，几何创建后不变），值 = `float[]`（每段 3 个分量）；用**有界 LRU（如 256 条）** + 每帧只读，避免随玩家旅行无限增长；仅客户端主线程访问（`GameRenderer.pick` 在客户端 tick 线程执行）。

**剩余成本（如实记录）**：被保留的 1–3 个段仍会产生 `getPosition`×2、`getNormal`、`getModelAngles` 与 4 次 `VecHelper.rotate`（约 12–20 个 `Vec3`）。要进一步消除需要重写 `BezierConnection.getNormal` 与 `TrackRenderer.getModelAngles`（后者还被 `TrackVisual` 使用）并 `@Accessor` 读取 `BezierConnection.Runtime` 的私有字段（`finish1`/`finish2`），**不建议在第一阶段做**。

**Mixin 骨架**

```java
package com.example.createperf.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.trains.track.TrackBlockOutline;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

@Mixin(value = TrackBlockOutline.class, remap = false)
public abstract class TrackBlockOutlineMixin {

    // 原式：segmentBounds = AllShapes.TRACK_ORTHO.get(SOUTH).bounds()
    //                   .move(-.5, segmentBounds.getYsize() / -2, -.5)
    // 注意形状只依赖 AllShapes 的静态注册表，可在类初始化期安全求值。
    @Unique private static final AABB createperf$SEGMENT_BOUNDS = createperf$makeSegmentBounds();
    @Unique private static final net.minecraft.world.phys.Vec3 createperf$UP =
        new net.minecraft.world.phys.Vec3(0, 1, 0);

    @Unique
    private static AABB createperf$makeSegmentBounds() {
        AABB raw = com.simibubi.create.AllShapes.TRACK_ORTHO.get(Direction.SOUTH).bounds();
        return raw.move(-.5, raw.getYsize() / -2, -.5);
    }

    @Inject(method = "pickCurves()V", at = @At("HEAD"), cancellable = true)
    private static void createperf$pickCurves(CallbackInfo ci) {
        createperf$pickCurvesImpl();
        ci.cancel();          // 等价于整段重写，但原方法体保留（不与其他 mod 的注入互相删除）
    }

    @Unique
    private static void createperf$pickCurvesImpl() {
        // 完整实现见 overwrites/TrackBlockOutline-pickCurves.md
        // 直接读写 TrackBlockOutline.result（public static）与 mc.hitResult（public 字段）
    }
}
```

**验证方法**

- 探针：`@Unique static long` 统计「`pickCurves` 进入次数 / 内层迭代总次数 / 命中的段数」，每 100 tick 打日志。固定场景：站在一条 40 格 S 弯旁，准星压在轨道上 30 秒。
- **行为等价性（最重要）**：在同一存档同一视角下，用 `@Unique` 记录注入前/注入后每次 `pickCurves` 得到的 `result`（`be` 的 BlockPos、`location.curveIndex`、`anchor` 三分量、`angles` 三分量），对比是否逐位一致（浮点用严格相等或 1e-9 容差）。这是「点中同一段、同一位置」的直接证据。
- 交互回归：手动验证弯曲轨道 / 斜坡 / 交叉口的准星高亮段与接管（`drawCurveSelection`）一致；`mc.hitResult` 被改写后原版 `pick` 的方块命中不被破坏。
- 分配观测：JFR `ObjectAllocationSample` 过滤 `net.minecraft.world.phys.Vec3`，观察主线程 `GameRenderer.pick` 栈下的分配量。
- 注入确认：`-Dmixin.debug.export=true` 后反汇编 `TrackBlockOutline.class`，确认 `pickCurves` 开头调用你的 `@Unique` 实现。

**收益估计**：量级——引入锚点粗筛后，被命中弯道每帧执行昂贵数学的段数从 `长度×2` 段降到 1–3 段：40 格弯道从约 79 段 × 约 20 个对象降到约 3 段 × 约 20 个对象，即**单条弯道每帧约 1500–1900 个对象降到约 60 个**（**估计**）；帧内代价换成每段一次标量点—直线距离（79 次向量运算，可忽略）。
**回归测试点**：`result` 内容逐位一致；`drawCurveSelection` 绘制位置不变；`TRACKS_WITH_TURNS` 生命周期不变（不要在此清缓存）；斜坡 / 交叉口（`TrackShape.CR_*`）命中优先级不变；`mc.hitResult` 的 miss 改写（`:137-140`）仍发生；专用服务器无影响（纯客户端路径）。

### 4. `BigOutlines.pick` 无条件 3×3×3 邻域扫描 — [Medium]（原清单为 Medium-High，此处降级并说明）

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/foundation/block/BigOutlines.java:44-84`。关键行：`:45` `MutableBlockPos p = BlockPos.ZERO.mutable();`（每个射线步一次）、`:47-51` 三层循环 + `mc.level.getBlockState(p)`（**每步 27 次**）、`:56-57` `getInteractionShape(...).clip(origin, target, p.immutable())`（仅目标类型才走）、`:61-63` 两次 `Vec3.atCenterOf`、`:74-76` 又各一次 `Vec3.atCenterOf` |
| 调用频次 | **每帧**：`GameRendererMixin.java:17` → `BigOutlines.pick()`。内层谓词由 `RaycastHelper.rayTraceUntil`（`RaycastHelper.java:43-157`）逐体素调用，每次只前进 1 格（`:136-149` 三个分支各只改一个坐标），步数 = `min(命中距离, BLOCK_INTERACTION_RANGE) + 1` ≈ **6–8 步**（默认交互距离 4.5） |
| 复杂度 / 成本 | 27 × 6–8 = **每帧 162–216 次 `Level.getBlockState`**，即使世界上一个 `IHaveBigOutline` 方块都没有（Create 内仅 4 个实现者：`TrackBlock`、`SlidingDoorBlock`、`ChainConveyorBlock`、`TableClothBlock`）。另有每步 1 个 `MutableBlockPos`、命中时的 `p.immutable()` 与若干 `Vec3.atCenterOf`。**降级理由**：客户端 `getBlockState` 走 `ClientChunkCache` 的数组 + 哈希查找（几十 ns 量级），162–216 次合计约 **5–10 µs/帧**，相对 8.3 ms 帧预算不足 0.2%；这是「纯浪费」而非热点。值得修是因为零风险、可顺手做，而非绝对耗时 |
| 注入方式 | `@Inject(HEAD, cancellable)` 整段重写（推荐）或 `@Overwrite`，见 `overwrites/BigOutlines-pick.md`：复用 `MutableBlockPos` + 用「射线前进方向的增量切片」把每步 27 次查询降到 9/18/19 次 |
| 目标成员 | `com.simibubi.create.foundation.block.BigOutlines#pick()V`（handler 为 `static`） |
| 需新增状态 | `@Unique static final MutableBlockPos`（复用的临时槽位）+ `@Unique static` 记录「上一步的整数坐标与已扫描过的邻域」。作用域＝客户端进程级；失效＝每次 `pick` 入口重置；并发＝仅渲染/主线程 |
| 风险 | 低-中：`result`（`BigOutlines.java:24`）与 `mc.hitResult` 的写入语义必须保持（`:86-87` 只在 `result != null` 时覆盖）；增量切片的正确性依赖「`rayTraceUntil` 每步只前进一个轴」这一**已核对**事实（`RaycastHelper.java:136-149`） |
| 脆弱度 | 中：若上游把 `BigOutlines.pick` 改成按 AABB 扫描或引入缓存，注入失效（`defaultRequire=1` 会在启动期报错，不静默） |
| 置信度 | 确定（行号、循环次数、`IHaveBigOutline` 实现者清单均已核对）；「每步 27 次」确定，「每帧 5–10 µs」是**估计** |

**改前**

```java
// BigOutlines.java:44-84（原样摘录）
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
```

**为什么慢**

频次：每帧。成本：27 次方块状态查询/步，而真正的目标集合（`IHaveBigOutline` 方块）在绝大多数世界里为空；相邻射线步的 3×3×3 邻域**重叠 18 格**，即约 2/3 的查询是重复的。规模放大：与装置规模无关，恒定每帧 162–216 次，属「恒定背景浪费」，在任何整合包里长期存在。

**改后**

完整可粘贴实现见 `overwrites/BigOutlines-pick.md`。核心：只扫描「本步新进入的格」。

```java
// 相邻两步的整数坐标差恰好只有一个轴 ±1（RaycastHelper.java:136-149），因此
//   cube(cur) \ cube(prev) 的大小为：
//     1 个轴变化 -> 9 格（新增切面）
//     2 个轴变化 -> 18 格
//     3 个轴变化 -> 19 格
//   首步仍需 27 格。
// 单步场景 6 步：27 + 5×9 = 72 次查询（原 162 次）。
```

**Mixin 骨架**

```java
package com.example.createperf.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.foundation.block.BigOutlines;

import net.minecraft.core.BlockPos;

@Mixin(value = BigOutlines.class, remap = false)
public abstract class BigOutlinesMixin {

    @Unique private static final BlockPos.MutableBlockPos createperf$scratch = new BlockPos.MutableBlockPos();
    @Unique private static final BlockPos.MutableBlockPos createperf$prev = new BlockPos.MutableBlockPos();
    @Unique private static boolean createperf$hasPrev;

    @Inject(method = "pick()V", at = @At("HEAD"), cancellable = true)
    private static void createperf$pick(CallbackInfo ci) {
        createperf$hasPrev = false;
        createperf$pickFast();
        ci.cancel();
    }

    @Unique
    private static void createperf$pickFast() {
        // 完整实现见 overwrites/BigOutlines-pick.md
    }
}
```

**验证方法**

- 探针：统计 `pick` 调用次数与 `getBlockState` 调用次数（改前可临时加一个 `@Redirect` 计数）。期望 6 步场景 162 → 72。
- 行为等价：同一存档同一视角下，记录 `BigOutlines.result`（BlockPos + location 三分量 + direction + isInside）改前/改后逐位一致。覆盖 4 种实现方块：弯曲轨道、滑动门、链式传送带、桌布；以及「射线穿过邻域但目标在外」（例如站在桌布旁看空气）的边界。
- 交互回归：右键放置/交互仍优先命中大轮廓方块的正确位置（`:73` 注释说明这是为 `ServerGamePacketListenerImpl.handleUseItemOn` 做的修正）。

**收益估计**：量级——每帧 `getBlockState` 由 162–216 降到约 72（6 步场景），每帧再省 6 个 `MutableBlockPos`（**估计**）。绝对时间约几微秒，属**低收益、零风险**类。
**回归测试点**：上述「行为等价」清单；另确认世界上无 `IHaveBigOutline` 方块时 `result` 仍为 `null` 且 `mc.hitResult` 不被改写。

### 5. `BeltRenderer.renderItem` 每物品每帧的光照查询 / 视锥 AABB / 无用对象 — [Medium-High]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/kinetics/belt/BeltRenderer.java:232`（`MutableBlockPos mutablePos = new MutableBlockPos();`）、`:262`（`this.shouldCullItem(itemPos, be.getLevel())`）、`:282`（`LevelRenderer.getLightColor(be.getLevel(), mutablePos)`）、`:290`（`mc.player.getEyePosition(1.0F).distanceTo(itemPos)`）、`:293`（`Random r = new Random(transported.angle);`）、`:332`（`r.nextFloat() × 2`）。相关上游：`src/main/java/com/simibubi/create/foundation/blockEntity/renderer/SafeBlockEntityRenderer.java:48-55`（每次调用新建 0.5³ 的 `AABB`） |
| 调用频次 | **每帧 × 每个可见传送带控制器 × 带上每个物品**。`renderItems`（`:195-225`）由 `renderSafe` 在 `:161` 调用——**位于 `VisualizationManager.supportsVisualization` 门控之外**（门控只包住 `:60-159`），所以**装了 Flywheel 也照样执行**。物品数由 `BeltInventory.getTransportedItems()` 决定（每条带上限由长度/槽位决定） |
| 复杂度 / 成本 | 每物品每帧：1 个 `MutableBlockPos`、1 个 `Vec3`（`getEyePosition`，注意它在**每物品**循环体内被调用）、1 个 `java.util.Random`、1 个 `AABB`（`shouldCullItem`）+ 1 次视锥测试、1 次 `LevelRenderer.getLightColor` 光照引擎查询；另有 `Vec3.atLowerCornerOf(directionVec)`/`.scale(offset)`（`:247-248`）、`beltStartOffset` 相关、`BeltHelper.getVectorForOffset`（`:314`）等约 4–6 个 `Vec3`。⇒ 20 物品/带 ≈ 100 个短命对象 + 20 次光照查询 + 20 次视锥测试/帧 |
| 注入方式 | ① `@WrapOperation` 包裹 `LevelRenderer.getLightColor`，按 `(level, BlockPos.asLong())` 做**每 tick** 记忆化；② `@WrapOperation` 包裹 `Player.getEyePosition(F)`，按 tick 记忆化（参数是常量 1.0F，**同一 tick 内返回值恒定**，见「为什么慢」）；③ 消除 `new MutableBlockPos`/`new Random`/`shouldCullItem` 的 AABB 只能整段重写 → 见 `overwrites/BeltRenderer-renderItem.md`（可选） |
| 目标成员 | `com.simibubi.create.content.kinetics.belt.BeltRenderer#renderItem(Lcom/simibubi/create/content/kinetics/belt/BeltBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/core/Direction;Lnet/minecraft/core/Vec3i;Lcom/simibubi/create/content/kinetics/belt/BeltSlope;IZZLcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;Lnet/minecraft/world/phys/Vec3;)V`（私有方法，注入需要 `method = "renderItem(...)"` 的**完整描述符**；若上游改签名，注入会在启动期失败） |
| 需新增状态 | `@Unique static` `it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap`（光照记忆）+ `@Unique static long`（上次 tick）+ `@Unique static Vec3`（缓存的眼位）。作用域＝客户端进程级；失效＝客户端 tick 变化（`AnimationTickHolder.getTicks()`）时清空；并发＝客户端单线程。**必须避免持有 `Level`/`BlockEntity` 强引用**（手册第 7 节），因此只存 `long` 坐标键与 `int` 光照值 |
| 风险 | 中：光照记忆化最坏情况是「某方块被放置/破坏的那个 tick 内，传送带物品光照滞后最多 1 tick（≤50 ms）」——属可接受的行为变更，必须显式记录（手册第 7 节）。眼位记忆化在 `partialTicks = 1.0F` 下是精确的，不是近似 |
| 脆弱度 | 中高：依赖 `renderItem` 的**完整私有描述符**（改动风险大）；依赖 `LevelRenderer.getLightColor` 的重载签名（MC 1.21.1 中是 `BlockAndTintGetter, BlockPos` 版本，**需用 -Dmixin.debug.verbose 确认解析结果**，因为 `Level` 也可能存在重载） |
| 置信度 | 确定（行号与调用关系已核对）；「JIT 是否会标量替换这些短命对象」**需实测**——这也是下述收益估计保守的原因 |

**改前**

```java
// BeltRenderer.java:227-233 / 262 / 276-293（原样摘录）
    private void renderItem(BeltBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
        int overlay, Direction beltFacing, Vec3i directionVec, BeltSlope slope, int verticality, boolean slopeAlongX,
        boolean onContraption, TransportedItemStack transported, Vec3 beltStartOffset) {
        Minecraft mc = Minecraft.getInstance();
        ItemRenderer itemRenderer = mc.getItemRenderer();
        MutableBlockPos mutablePos = new MutableBlockPos();
        ...
        if (this.shouldCullItem(itemPos, be.getLevel())) {
            return;
        }
        ...
        int stackLight;
        if (onContraption) {
            stackLight = light;
        } else {
            int segment = (int) Math.floor(offset);
            mutablePos.set(be.getBlockPos()).move(directionVec.getX() * segment, verticality * segment, directionVec.getZ() * segment);
            stackLight = LevelRenderer.getLightColor(be.getLevel(), mutablePos);
        }
        ...
        int count = 0;
        if (be.getLevel() instanceof PonderLevel || mc.player.getEyePosition(1.0F).distanceTo(itemPos) < 16)
            count = (int) (Mth.log2((int) (transported.stack.getCount()))) / 2;

        Random r = new Random(transported.angle);
        ...
            if (blockItem && !box)
                ms.translate(r.nextFloat() * .0625f * i, 0, r.nextFloat() * .0625f * i);
```

**为什么慢**

频次：`renderItems` 每帧、每条可见控制器传送带、每个物品（`BeltRenderer.java:217-222`，含 `getLazyClientItem` 再来一次）。成本里最实的两项是：① **每物品一次 `LevelRenderer.getLightColor`**——光照引擎查询 + `BlockAndTintGetter.getBrightness` 两次分层查询，是这条路径上唯一的「引擎级」开销；② **每物品一次视锥测试**（`shouldCullItem` 新建一个 `AABB` 再交给 `Frustum.isVisible`）。另外 `mc.player.getEyePosition(1.0F)` 位于**物品循环体内**，而参数是常量 `1.0F`（`partialTicks = 1.0F` 表示「本 tick 的最终位置，不做帧插值」），因此**同一 tick 内返回值恒定**，纯属重复计算。`new Random(transported.angle)` 只为两次 `nextFloat` 抖动，且因为种子每次相同、序列每次重置，抖动值其实是**静态**的（不会随机变化）——这点必须保持，否则是视觉变更。规模放大：长传送带 + 密集物品（工厂线、火车站货运段）时，20 物品/带 × 10 条可见带 = 200 次光照查询/帧。**注意**：本节列出的短命对象在 JIT 逃逸分析下**可能已被标量替换**，因此收益估计保守（**需实测**，见验证方法）。

**改后**

```java
// 纯 Java 等价改法：把循环不变的两项提到循环外 / 加记忆化

// 1) 眼位：同一 tick 内 getEyePosition(1.0F) 恒定 —— 提到 renderItems 里算一次
//    （原代码在 renderItem 内部、每个物品都调一次）
Vec3 eye = mc.player.getEyePosition(1.0F);

// 2) 光照：按 (level, 段起始 BlockPos) 每 tick 记忆化。同一条带的相邻物品常落在同一段，
//    20 个物品在 3–5 个不同段上 -> 光照查询 20 次降到 3–5 次
long key = mutablePos.asLong();                     // 不分配
int cached = lightMemo.getOrDefault(key, -1);
if (cached == -1) { cached = LevelRenderer.getLightColor(level, mutablePos); lightMemo.put(key, cached); }

// 3) 真正消除 new MutableBlockPos / new Random / shouldCullItem 的 AABB：整段重写（见 overwrites/）
```

**Mixin 骨架**

```java
package com.example.createperf.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.belt.BeltRenderer;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.phys.Vec3;

@Mixin(value = BeltRenderer.class, remap = false)
public abstract class BeltRendererMixin {

    @Unique private static final Long2IntOpenHashMap createperf$light = new Long2IntOpenHashMap();
    @Unique private static long createperf$lightTick = Long.MIN_VALUE;
    @Unique private static long createperf$eyeTick = Long.MIN_VALUE;
    @Unique private static Vec3 createperf$eye = Vec3.ZERO;

    @Unique
    private static void createperf$rollTick() {
        long now = AnimationTickHolder.getTicks();      // 光照/眼位都只随 tick 变化
        if (now != createperf$lightTick) {
            createperf$lightTick = now;
            createperf$light.clear();
        }
    }

    @WrapOperation(
        method = "renderItem(Lcom/simibubi/create/content/kinetics/belt/BeltBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/core/Direction;Lnet/minecraft/core/Vec3i;Lcom/simibubi/create/content/kinetics/belt/BeltSlope;IZZLcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/renderer/LevelRenderer;getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I"))
    private int createperf$memoLight(BlockAndTintGetter level, BlockPos pos, Operation<Integer> original) {
        createperf$rollTick();
        long k = pos.asLong();                          // 注意：pos 是复用的 MutableBlockPos，必须先取 long 快照
        int v = createperf$light.getOrDefault(k, Integer.MIN_VALUE);
        if (v == Integer.MIN_VALUE) {
            v = original.call(level, pos);
            createperf$light.put(k, v);
        }
        return v;
    }

    @WrapOperation(
        method = "renderItem(Lcom/simibubi/create/content/kinetics/belt/BeltBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/core/Direction;Lnet/minecraft/core/Vec3i;Lcom/simibubi/create/content/kinetics/belt/BeltSlope;IZZLcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/world/entity/player/Player;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 createperf$memoEye(Player player, float partialTicks, Operation<Vec3> original) {
        long now = AnimationTickHolder.getTicks();
        if (now != createperf$eyeTick) {
            createperf$eyeTick = now;
            createperf$eye = original.call(player, partialTicks);
        }
        return createperf$eye;                          // partialTicks 恒为 1.0F -> 同一 tick 内精确
    }
}
```

> 描述符提醒：`LevelRenderer.getLightColor` 与 `Player.getEyePosition` 的**实际 INVOKE 归属类/重载**需用 `-Dmixin.debug.verbose=true` 的输出核对一次（MC 1.21.1 中 `LevelRenderer.getLightColor` 的形参类型是 `BlockAndTintGetter`；若编译期解析到别的重载，把 `target` 改成对应签名即可）。`renderItem` 的私有完整描述符一旦上游改参数就必须同步更新（`defaultRequire=1` 会让它在启动期报错）。

**验证方法**

- 探针：`@Unique static long` 统计 `getLightColor` 调用次数（记忆化命中/未命中各一个计数器）与 `getEyePosition` 调用次数，每 100 tick 打一次。期望：眼位调用次数从「物品数×帧」降到「20×帧" 即每 tick 1 次。
- 视觉回归（光照最怕错）：在夜间/洞穴里放一条带 20 件物品的传送带，注入开/关下逐帧截图对比物品亮度；再在带旁放置/破坏光源方块，确认下一 tick 光照正确更新（最多 1 tick 滞后，属已知行为变更）。
- 数量叠加：指令生成大堆物品（`/data` 或机械臂投料），确认 `count` 分支（`:290-291`）在 16 格外仍然不叠加数字。
- 视锥剔除：把传送带移出视野，确认物品不渲染（`shouldCullItem` 逻辑未被破坏）。
- 分配观测：JFR `ObjectAllocationSample` 过滤 `java.util.Random`、`net.minecraft.core.BlockPos$MutableBlockPos`、`net.minecraft.world.phys.AABB`、`net.minecraft.world.phys.Vec3`。

**收益估计**：量级——20 物品/带时每帧光照查询从 20 降到 3–5（**估计**，取决于物品分布段数）；眼位 `Vec3` 从 20/帧降到 1/tick；`new Random`/`new MutableBlockPos`/AABB 的消除**只有整段重写才能保证**，且其收益可能被 JIT 逃逸分析吃掉（**需实测**）。
**回归测试点**：物品位置/朝向/抖动（`Random(angle)` 序列必须保持）不变；物品堆叠数字的显示距离（16 格）不变；视锥剔除行为不变；PonderLevel 分支（`:290` `instanceof PonderLevel`）仍不剔除；`onContraption` 分支仍直接用传入的 `light`（不要对包裹路径生效）；传送带上包裹（`PackageItem`）的放大与偏移不变。

### 6. 矿车耦合渲染无剔除 + `forEachLoadedCoupling` 提前终止 bug — [Medium-High（含一处 High 级正确性 bug）]

| 项 | 内容 |
| --- | --- |
| 位置 | ① 性能：`src/main/java/com/simibubi/create/content/contraptions/minecart/CouplingRenderer.java:34-41`（`renderAll`）、`:48-110`（`renderCoupling`：`:54-56` 两次光照、`:58-62` 两次 `position()`、`:64` `carts.map(getSuitableCartEndpoint)`、`:68-70` 三个 `CachedBuffers.partial`、`:112-171` `getSuitableCartEndpoint` 里的 `:138-143` `cart.getPos/getPosOffs`）。② **Bug**：`src/main/java/com/simibubi/create/content/contraptions/minecart/CouplingHandler.java:57-58`、`:59-60`、`:63-64` 三处 `return` 应为 `continue` |
| 调用频次 | `renderAll`：**每帧**（`ClientEvents.java:248`，在 `RenderLevelStageEvent.Stage.AFTER_PARTICLES` 下）；`forEachLoadedCoupling` 每次遍历**客户端本维度全部已加载 coupling**。`CouplingPhysics.tick(world)`：**每服务器 tick**（`CommonEvents.java:139`）+ 每客户端 tick（`ClientEvents.java:158`），同样每次都调 `forEachLoadedCoupling`。另 `tickDebugModeRenders`（`CouplingRenderer.java:45`）仅在 `KineticDebugger` 激活时 |
| 复杂度 / 成本 | `renderCoupling` **没有任何距离/视锥剔除**（对比 `CarriageCouplingRenderer.java:54` 有 `anchor.closerThan(camera, 64)`）。每个 coupling 每帧：2 次 `LevelRenderer.getLightColor`（每次先 `carts.map` 造一个 `Couple`，每个 `BlockPos.containing(bbox.getCenter())` 先产 1 个 `Vec3` 再产 1 个 `BlockPos`）、2 次 `cart.position()`（各 1 个 `Vec3`）、2 次 `getSuitableCartEndpoint`（各含 2 个 `new Vec3` + 2 次 `VecHelper.rotate` + 最多 3 次原版 `cart.getPos/getPosOffs` 的轨道形状解析与 `Vec3` 垃圾，最后 1 个 `CartEndpoint`）、2 次 `CartEndpoint.apply(zero)`（各约 4–5 个 `Vec3`）、3 次 `CachedBuffers.partial`。⇒ **单 coupling 每帧约 30–50 个对象 + 2 次光照查询**（`CachedBuffers`/`Couple` 属外部 jar，**估计**）。规模放大：只有"已加载 + 已耦合"的矿车才进入集合，但**完全不看距离**——一个 50 组耦合的矿车网络 = 每帧 50 组全套计算，哪怕全部在 100 格外/背对玩家 |
| 注入方式 | ① `@Overwrite`（B）`CouplingHandler.forEachLoadedCoupling` 修 `return→continue`（**必须**，无其它注入方式能改这三处 return）；② `@Inject(HEAD, cancellable)` 整段重写 `CouplingRenderer.renderAll` 加距离剔除，或 `@WrapOperation` 包裹 `forEachLoadedCoupling` 的消费者；见 `overwrites/CouplingHandler-forEachLoadedCoupling.md` 与 `overwrites/CouplingRenderer-renderAll.md` |
| 目标成员 | `com.simibubi.create.content.contraptions.minecart.CouplingHandler#forEachLoadedCoupling(Lnet/minecraft/world/level/Level;Ljava/util/function/Consumer;)V`（静态 handler）<br>`com.simibubi.create.content.contraptions.minecart.CouplingRenderer#renderAll(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/phys/Vec3;)V`（静态 handler） |
| 需新增状态 | `renderAll` 需要 `@Unique static final Vec3`……**不需要**：相机位置由参数传入（`camera`），距离判定用 `cart.getX/getY/getZ` 标量（不调 `position()`，避免为剔除反而分配 `Vec3`）即可，零 `Unique` 状态。`forEachLoadedCoupling` 的修正也不需要新状态 |
| 风险 | Bug 修正：**低**（20 行方法，语义明确的 `return→continue`，且这是修 bug 不是改玩法；但要注意修正后**后续 coupling 会开始参与物理与渲染**，这会让原本被跳过的耦合开始受力——这是「恢复预期行为」，必须写进变更日志）。性能部分：低-中（剔除距离选取；参考 `CarriageCouplingRenderer` 的 64 格，但矿车原版渲染距离更小，建议取 64 或更小**需实测**决定） |
| 脆弱度 | 低-中：`forEachLoadedCoupling` 是 20 行、无私有状态的方法；`renderAll` 依赖 `CouplingRenderer.renderCoupling`（public static）与 `MinecartController::cart`、`hasContraptionCoupling` 的语义 |
| 置信度 | 确定（三处 `return` 的行号为 `:58/:60/:64` 对应的语句；`forEachLoadedCoupling` 的四个调用点已 grep 确认；`renderCoupling` 无剔除、`CarriageCouplingRenderer` 有剔除，均已逐行核对）。`Couple`/`CachedBuffers` 的内部开销为**估计** |

**改前**

```java
// CouplingHandler.java:48-67（原样摘录；:58/:60/:64 的 return 应为 continue）
    public static void forEachLoadedCoupling(Level world, Consumer<Couple<MinecartController>> consumer) {
        if (world == null)
            return;
        Set<UUID> cartsWithCoupling = CapabilityMinecartController.loadedMinecartsWithCoupling.get(world);
        if (cartsWithCoupling == null)
            return;

        for (UUID id : cartsWithCoupling) {
            MinecartController controller = CapabilityMinecartController.getIfPresent(world, id);
            if (controller == null)
                return;                                  // <-- bug：应为 continue
            if (!controller.isLeadingCoupling())
                return;                                  // <-- bug：应为 continue
            UUID coupledCart = controller.getCoupledCart(true);
            MinecartController coupledController = CapabilityMinecartController.getIfPresent(world, coupledCart);
            if (coupledController == null)
                return;                                  // <-- bug：应为 continue
            consumer.accept(Couple.create(controller, coupledController));
        };
    }
```

```java
// CouplingRenderer.java:34-41（原样摘录：无任何距离/视锥剔除）
    public static void renderAll(PoseStack ms, MultiBufferSource buffer, Vec3 camera) {
        CouplingHandler.forEachLoadedCoupling(Minecraft.getInstance().level, c -> {
            if (c.getFirst()
               .hasContraptionCoupling(true))
                return;
            CouplingRenderer.renderCoupling(ms, buffer, camera, c.map(MinecartController::cart));
        });
    }
```

**为什么慢 / 为什么是 bug**

性能：`renderAll` 在 `AFTER_PARTICLES` 阶段每帧执行，而 `forEachLoadedCoupling` 遍历的是**整个已加载维度**的耦合集合，`renderCoupling` 内部一次距离判断都没有——所以玩家周围 200 格内的所有耦合矿车都在做完整的光照查询 + 轨道形状解析 + 三次 `CachedBuffers.partial` + 若干变换计算，结果绝大多数被顶点阶段的视锥/深度测试丢弃。规模放大：矿车网络越大线性越差，且**没有上限**。

Bug：`forEachLoadedCoupling` 被四个地方使用：`CouplingRenderer.renderAll`（每帧）、`CouplingRenderer.tickDebugModeRenders`（调试）、`CouplingPhysics.tick`（**服务器每 tick**，`CommonEvents.java:139`；客户端每 tick，`ClientEvents.java:158`）。因为集合是 `HashSet<UUID>`（迭代顺序任意），一旦遇到 `controller == null`（该矿车已卸载 / 控制器移除，`CapabilityMinecartController.getIfPresent` 返回 null）或 `coupledController == null`（对端不在本维度/未加载），**该点之后的所有 coupling 在本轮全部被跳过**：客户端表现为耦合杆时隐时现；**服务端表现为这些矿车不再收到耦合物理（`softCollisionStep`/`hardCollisionStep`）**，即联挂矿车之间的距离约束静默失效，会出现拉伸/重叠/解耦外观不一致。这已经超出性能范畴，属于**服务端逻辑 bug**，应优先修。

**改后**

```java
// (B) CouplingHandler.forEachLoadedCoupling：三处 return -> continue（完整可粘贴体见 overwrites/）
    public static void forEachLoadedCoupling(Level world, Consumer<Couple<MinecartController>> consumer) {
        if (world == null)
            return;
        Set<UUID> cartsWithCoupling = CapabilityMinecartController.loadedMinecartsWithCoupling.get(world);
        if (cartsWithCoupling == null)
            return;

        for (UUID id : cartsWithCoupling) {
            MinecartController controller = CapabilityMinecartController.getIfPresent(world, id);
            if (controller == null)
                continue;                                 // was: return
            if (!controller.isLeadingCoupling())
                continue;                                 // was: return
            UUID coupledCart = controller.getCoupledCart(true);
            MinecartController coupledController = CapabilityMinecartController.getIfPresent(world, coupledCart);
            if (coupledController == null)
                continue;                                 // was: return
            consumer.accept(Couple.create(controller, coupledController));
        }
    }

// (A) CouplingRenderer.renderAll：加标量距离剔除（不调 position()，避免为剔除而分配 Vec3）
    public static void renderAll(PoseStack ms, MultiBufferSource buffer, Vec3 camera) {
        CouplingHandler.forEachLoadedCoupling(Minecraft.getInstance().level, c -> {
            if (c.getFirst().hasContraptionCoupling(true))
                return;
            Couple<AbstractMinecart> carts = c.map(MinecartController::cart);
            AbstractMinecart a = carts.getFirst();
            AbstractMinecart b = carts.getSecond();
            if (a == null || b == null)                     // renderCoupling 里也有这个判断，提前做省一次调用
                return;
            double dx = (a.getX() + b.getX()) * .5 - camera.x;
            double dy = (a.getY() + b.getY()) * .5 - camera.y;
            double dz = (a.getZ() + b.getZ()) * .5 - camera.z;
            if (dx * dx + dy * dy + dz * dz > CREATEPERF_MAX_DIST_SQR)   // 64 格
                return;
            CouplingRenderer.renderCoupling(ms, buffer, camera, carts);
        });
    }
```

**Mixin 骨架**

```java
package com.example.createperf.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import com.simibubi.create.content.contraptions.minecart.CouplingHandler;

import net.createmod.catnip.data.Couple;
import net.minecraft.world.level.Level;

@Mixin(value = CouplingHandler.class, remap = false)
public abstract class CouplingHandlerMixin {

    // 该方法是纯逻辑、无私有状态；Create 自己的 mixin 配置里没有注入它（create.mixins.json 只列原版目标）
    // 因此 @Overwrite 是安全且唯一能改 return 的方式。
    // 完整可粘贴方法体见 overwrites/CouplingHandler-forEachLoadedCoupling.md
    @Overwrite
    public static void forEachLoadedCoupling(Level world, java.util.function.Consumer<Couple<?>> consumer) {
        // 见 overwrites/；注意泛型擦除后描述符必须与上游一致（Consumer<Couple<MinecartController>>）
    }
}
```

> 用 `@Overwrite` 时**必须逐字保持**方法签名（含泛型不影响字节码，但参数类型要一致）。若不想 Overwrite，唯一替代是把整个方法体复制进一个 `@Unique` 静态方法并由 `@Inject(HEAD, cancellable)` 调用——效果相同，但同样要复制方法体。`renderAll` 的改造见 `overwrites/CouplingRenderer-renderAll.md`（`@Inject(HEAD, cancellable)` + `ci.cancel()` 即可，无需 Overwrite）。

**验证方法**

- **Bug 效果验证（最关键）**：在同一存档放 3 组以上耦合矿车，其中**至少一组的对端先被卸载**（例如把一组矿车开到远处/换维度，或让一组的一半进入未加载区块），然后观察：① 客户端其余 coupling 是否仍渲染（改前会时隐时现）；② 服务端用 `/data get entity` 观察矿车位置，确认后续耦合组之间的距离约束是否仍在（改前失效）。可以用 `@Unique static int` 计数器统计 `forEachLoadedCoupling` 里 `consumer.accept` 的调用次数——改前会突然掉到 1~2，改后恒等于真实 coupling 数。
- 性能探针：`@Unique static` 统计 `renderAll` 进入时集合大小 vs 实际处理的 coupling 数（剔除率），每 100 tick 打一次。
- 视觉回归：联挂矿车外观、耦合杆连接点位置、黑暗处光照；`hasContraptionCoupling(true)` 的跳过分支仍生效（装置耦合不画普通杆）。
- 服务器回归：`/spark tps` + 观察联挂矿车在铁轨上运动是否与改前一致（这是行为变更，必须双端都测）。

**收益估计**：量级——渲染侧每帧工作量从 O(全部已加载 coupling) 降到 O(64 格内 coupling)（**估计**，取决于网络密度）；物理侧修 bug 会**增加**服务端每 tick 的工作量（原本被跳过的耦合重新开始计算），这是恢复正确性的代价，不是性能退化。
**回归测试点**：`return→continue` 后所有 coupling 都进入 `CouplingPhysics.tick`（服务端 MSPT 需重新测基线）；`renderCoupling` 的 `carts.getFirst()/getSecond() == null` 分支仍可达；距离剔除阈值下的边界（正好 64 格）不出现闪烁；装置耦合（`hasContraptionCoupling`）仍被跳过。

### 7. `CarriageCouplingRenderer.renderAll` 每段缆绳重复整条变换链 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/trains/entity/CarriageCouplingRenderer.java:83-94`（`couplingSegments` 循环）、`:80-84`（`margin`/`couplingDistance`/`stretch`）、`:86-93`（每段 `CachedBuffers.partial` + 4 次变换 + `light`）。另见 `:42`（疑似笔误，见下） |
| 调用频次 | **每帧 × 每列车 × 每节相邻车厢对**（`ClientEvents.java:249` → `renderAll`）。`couplingSegments = (int) Math.round(couplingDistance * 4)`（`:83`），`couplingDistance = carriageSpacing - 2*margin - 两个 bogey 的 connectorAnchorOffset.z`（`:81-82`）⇒ 常见 8–32 段，长车厢间距下 32–64 |
| 复杂度 / 成本 | 每次迭代：1 次 `CachedBuffers.partial(AllPartialModels.TRAIN_COUPLING_CABLE, air)`（catnip 缓存查找，开销**需实测**）+ `rotateYDegrees`/`rotateXDegrees`/`translate`/`scale`/`translate` 5 次链式调用 + `light` + `renderInto`（真正的顶点写入）。**循环里唯一随 `j` 变化的是 `:91` 的 `translate(0, 0, j / 4f)`**；其余 4 次变换与 `stretch`/`light` 全部与 `j` 无关却每段重做。规模放大：列车越长、编组越多，每帧重复次数线性增加 |
| 注入方式 | 局部注入收益有限（变换链的调用点与 buffer 状态的耦合无法用 `@ModifyExpressionValue` 消除）。可选 `@Inject(HEAD, cancellable)` 整段重写；**但前置条件是确认 `SuperByteBuffer` 的变换状态在 `renderInto` 后是否复位 / `CachedBuffers.partial` 是否返回共享实例——catnip 外部 jar，本地无源码，必须实测**。若不能确认，本条只记录、不修 |
| 目标成员 | `com.simibubi.create.content.trains.entity.CarriageCouplingRenderer#renderAll(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/phys/Vec3;)V` |
| 需新增状态 | 若确认可复用：`@Unique static` 保存已构建好的 `SuperByteBuffer` 与其 `(yRot, xRot, margin, stretch)` 版本号，供同一帧内同一版本复用。作用域＝客户端进程级；失效＝每次列车/车厢对变化时重建 |
| 风险 | 中-高：`SuperByteBuffer` 是**有状态**对象（`rotate/translate/scale/light` 都作用于自身），把 `CachedBuffers.partial(...)` 提循环外**可能**导致变换累积或跨调用污染。在拿到 catnip 源码/实测结论前不要合入 |
| 脆弱度 | 高：绑定 `AllPartialModels.TRAIN_COUPLING_CABLE` 与 catnip 渲染 API 的调用形态 |
| 置信度 | 代码结构与行号：确定；「哪些调用可以安全提到循环外」：**需实测**（外部 jar）。另：`:42` 读的是 `carriage.getDimensional(level)` 而不是 `carriage2`，见下方说明——**不要顺手改** |

**改前**

```java
// CarriageCouplingRenderer.java:36-114（节选关键行）
        for (Train train : trains) {
            List<Carriage> carriages = train.carriages;
            for (int i = 0; i < carriages.size() - 1; i++) {
                Carriage carriage = carriages.get(i);
                CarriageContraptionEntity entity = carriage.getDimensional(level).entity.get();
                Carriage carriage2 = carriages.get(i + 1);
                CarriageContraptionEntity entity2 = carriage.getDimensional(level).entity.get();   // :42 疑似应为 carriage2
                ...
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
                            .translate(0, 0, j / 4f)        // <-- 只有这一项随 j 变化
                            .light(lightCoords)
                            .renderInto(ms, vb);
                    }
```

**为什么慢**

每帧、每编组、每段缆绳都重建一次完整的猫式渲染状态：`CachedBuffers.partial` 查表 + 5 次变换调用 + 光照设置，而其中 4 次变换与光照在 `j` 循环内恒定。规模放大：长编组列车（每节间距 4 格 ⇒ 约 12 段/对）叠加多列车时，一帧内是「列车数 × 车厢对数 × 段数」次重复的变换链构建。

**关于 `:42` 的疑似笔误（必须澄清后再动手）**

`entity2 = carriage.getDimensional(level).entity.get()` 与 `:40` 的 `entity` 是同一个表达式，所以恒有 `entity2 == entity`。影响面：

- `:44` 的 `entity2 == null` 判断是死代码；
- `:58` 的 `lightCoords2` 取自**第一节**车厢的位置，而不是第二节——这是**可见的错误**（夜间耦合头亮度取错位置）；
- `:100-103` 的 `position2.subtract(position).add(anchor2)`，因为 `position2 == position`，实际退化为 `anchor2 - camera`。而 `:73` 对第一个耦合头用的正是 `anchor.x/y/z - camera`，即**退化后的结果看起来才是本意**。

结论：**不要**简单把 `:42` 改成 `carriage2`——那会同时改变 `:100-103` 的平移公式（变成 `position2 - position + anchor2 - camera`），可能把耦合头画到错误位置。若上游确认 `:42` 是笔误，正确修法应是 `:42` 用 `carriage2` **且** `:100-103` 改为 `anchor2 - camera`（与第一节一致），并单独验证。这属于**行为修正**，不塞进性能补丁。

**改后**

```java
// 只有在实测确认 SuperByteBuffer 可安全复用（renderInto 后状态自动复位，或存在 .copy()）之后，
// 才能采用下面的形态；否则本条只应记录为「上游可优化点」。
//
// 纯 Java 优化点（与 catnip 内部无关、确定安全）：
//   1) margin / couplingDistance / stretch / couplingSegments / yRot / xRot / lightCoords
//      全部已在 j 循环外算好，保持不动；
//   2) 把 `:82` 的 getConnectorAnchorOffset(...).z 两次调用提到外层（每节车厢只算一次）；
//   3) 若 SuperByteBuffer 支持 copy：
//        SuperByteBuffer base = CachedBuffers.partial(TRAIN_COUPLING_CABLE, air)
//            .rotateYDegrees(-yRot + 180).rotateXDegrees(-xRot)
//            .translate(0, 0, margin + 2 / 16f).scale(1, 1, (float) stretch).light(lightCoords);
//        for (int j = 0; j < couplingSegments; j++)
//            base.copy().translate(0, 0, j / 4f).renderInto(ms, vb);
```

**Mixin 骨架**

```java
// 不推荐在未确认 catnip 语义前提供可运行骨架——错误复用会造成跨耦合的变换污染（视觉错乱）。
// 若你已实测确认，注入形态如下（否则请把本条留在「上游候选」）：
@Mixin(value = CarriageCouplingRenderer.class, remap = false)
public abstract class CarriageCouplingRendererMixin {
    @Inject(method = "renderAll(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"), cancellable = true)
    private static void createperf$renderAll(PoseStack ms, MultiBufferSource buffer, Vec3 camera, CallbackInfo ci) {
        createperf$renderAllImpl(ms, buffer, camera);
        ci.cancel();
    }
    @Unique private static void createperf$renderAllImpl(PoseStack ms, MultiBufferSource buffer, Vec3 camera) {
        // 见 overwrites/CarriageCouplingRenderer-renderAll.md（仅在你确认 SuperByteBuffer 可复用后编写）
    }
}
```

**验证方法**

- 探针：统计 `renderAll` 每帧的 `CachedBuffers.partial` 调用次数与段总数，每 100 tick 打日志；改后应降为「每耦合对 1 次」。
- 视觉回归（决定性）：多编组列车 + 弯曲轨道下逐帧截图对比缆绳外观；再刻意叠加 3 列以上列车同时入镜，确认没有变换污染（某一段缆绳出现在错误位置）。
- 光照：夜间确认两个耦合头亮度分别取自各自车厢（若同时修 `:42`/`:100-103`，需要单独一条变更记录）。

**收益估计**：量级——把每段 5 次变换调用降为 1 次 `translate` + 1 次 `renderInto`；`CachedBuffers.partial` 的次数从「段数 × 耦合对数 × 帧」降到「耦合对数 × 帧」（**估计**，取决于 catnip 内部实现；本仓库无法验证）。真正的顶点写入开销（`renderInto`）不变。
**回归测试点**：缆绳段的间距/伸缩（`stretch`）不变；耦合头位置与朝向不变；光照值不变；列车编组变化（增删车厢）后不出现残留段；`PonderLevel`/`WrappedLevel` 下不崩。

### 8. `StockKeeperRequestScreen.maxCraftable` 三层嵌套扫描 + 比较器里重算库存 — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/logistics/stockTicker/StockKeeperRequestScreen.java:1638-1704`（`maxCraftable`）。关键行：`:1644-1646` 外层 `ingredients` 循环、`:1648-1649` `summary.getItemMap().values()` 遍历、`:1650` `Entries:` 标签循环、`:1653-1658` `alreadyCreated` 线性扫描（`ItemStack.isSameItemSameComponents`）、`:1659-1664` 每次命中新建 `BigItemStack`、`:1670-1671` `Collections.sort` 的比较器**每次比较**调两次 `summary.getCountOf`、`:1677-1681` `stream + getOrderForItem`、`:1706-1717` `removeLeastEssentialItemStack` 里又一次 `getOrderForItem`。调用链：`:326`（`refreshSearchResults`，方法定义在 `:269`，**每次搜索框输入都跑**）与 `:1599`（`requestCraftable`，定义在 `:1542`，**每次下单/改数量都跑**）→ `updateCraftableAmounts`（`:1602-1636`）→ 对 `recipesToOrder` 的每一项调 `maxCraftable`。`getOrderForItem` 定义在 `:947-953`，是 `O(itemsToOrder)` 的 `isSameItemSameComponents` 扫描（`:1581`、`:1679`、`:1711`） |
| 调用频次 | 每次键盘输入 / 每次下单操作；不是每帧。`updateCraftableAmounts` 对 `recipesToOrder` 的**每个配方**调一次 `maxCraftable`，而 `:1599` 的路径是「同一帧内改一次数量」 |
| 复杂度 / 成本 | 内层是 `O(配料数 × Σ库存条目 × |alreadyCreated|)`：每个库存条目先做一次 `ingredient.test`（`:1651`，多数是 `Ingredient.test` → `ItemStack.isSameItemSameComponents` 或 tag 判定），再对 `alreadyCreated` 逐个 `isSameItemSameComponents`。以 300 个不同库存条目、9 个配料、每个配料已建 5–27 个候选估算：**每次 `maxCraftable` 约 2.7k–7 万次 `isSameItemSameComponents`**（**估计**，取决于 `valid` 的规模）。排序比较器再额外做 `O(|valid| log |valid|) × 2 次 `summary.getCountOf`（`InventorySummary.java:118-126`：IdentityHashMap 查 `Item` + 该物品变体列表线性扫描）。`getOrderForItem`（`:948-953`）每次是 `O(itemsToOrder)`。规模放大：股票终端接的仓库越多、物品种类越多，单次按键耗时线性甚至更差（`alreadyCreated` 是内层乘数） |
| 注入方式 | 只能整段重写（`@Overwrite` 或 `@Inject(HEAD, cancellable)` + 复制体）。局部注入救不了：① `alreadyCreated` 是方法内局部变量，要用索引替换必须重写方法；② 比较器 lambda 在方法体内，`@Redirect` `Collections.sort` 拿到的比较器无法降低其内部成本；③ `getOrderForItem` 的缓存需要 `@Unique` 状态并重写方法体。完整可粘贴体见 `overwrites/StockKeeperRequestScreen-maxCraftable.md` |
| 目标成员 | `com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen#maxCraftable(Lcom/simibubi/create/content/logistics/stockTicker/CraftableBigItemStack;Lcom/simibubi/create/content/logistics/packager/InventorySummary;Ljava/util/function/Function;I)Lnet/createmod/catnip/data/Pair;`<br>外加（可选）`#getOrderForItem(Lnet/minecraft/world/item/ItemStack;)Lcom/simibubi/create/content/logistics/BigItemStack;` |
| 需新增状态 | `@Unique` 实例字段（注意：这是 `Screen` 实例，不是静态）：① `Map<Integer, List<BigItemStack>> createperf$orderIndex`——以 `ItemStack.hashItemAndComponents(stack)`（`ItemStack` 静态工具，1.21 可用）为桶键、桶内仍用 `isSameItemSameComponents` 校验，把 `getOrderForItem` 的 `O(n)` 降到 `O(1)` 均摊；失效＝`itemsToOrder` 每次变更（`:1591`、`:1607`、`revalidateOrders` `:955-969`、`:1620-1628`）时重建，可在 `maxCraftable` 入口重建一次（本次调用内有效）。作用域＝该 `Screen` 实例、客户端主线程；并发＝单线程 |
| 风险 | 中：`maxCraftable` 的返回值直接决定「能合成多少」与「哪些库存条目被用掉」，任何顺序/取舍变化都会改变可见的合成数量。`:1670-1671` 的排序在计数相同时依赖 `Collections.sort` 的稳定性（stable），重写后必须保持稳定排序；`:1677-1685` 的 `newTypeLimit` 修剪与 `removeLeastEssentialItemStack` 的选取顺序同理 |
| 脆弱度 | 中：方法体较长（约 65 行）且引用了 `InventorySummary.getItemMap/getCountOf`、`CraftableBigItemStack.getIngredients/getOutputCount`、`Pair.of`、`resolveIngredientAmounts`、`removeLeastEssentialItemStack`（这些是**同类私有方法**——用 `@Overwrite` 时同包同一个类可访问，用 `@Unique` 复制体时则要 `@Invoker` 或复制它们） |
| 置信度 | 确定（行号与嵌套结构已逐行核对）；绝对耗时量级为**估计**（取决于库存规模，建议用探针实测） |

**改前**

```java
// StockKeeperRequestScreen.java:1638-1704（原样摘录核心）
    private Pair<Integer, List<List<BigItemStack>>> maxCraftable(CraftableBigItemStack cbis, InventorySummary summary,
                                                                 Function<ItemStack, Integer> countModifier, int newTypeLimit) {
        List<Ingredient> ingredients = cbis.getIngredients();
        List<List<BigItemStack>> validEntriesByIngredient = new ArrayList<>();
        List<BigItemStack> alreadyCreated = new ArrayList<>();

        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty())
                continue;
            List<BigItemStack> valid = new ArrayList<>();
            for (List<BigItemStack> list : summary.getItemMap()
                .values())
                Entries:for (BigItemStack entry : list) {
                    if (!ingredient.test(entry.stack))
                        continue;
                    for (BigItemStack visitedStack : alreadyCreated) {          // O(|alreadyCreated|)
                        if (!ItemStack.isSameItemSameComponents(visitedStack.stack, entry.stack))
                            continue;
                        valid.add(visitedStack);
                        continue Entries;
                    }
                    BigItemStack asBis = new BigItemStack(entry.stack,
                        summary.getCountOf(entry.stack) + countModifier.apply(entry.stack));
                    if (asBis.count > 0) {
                        valid.add(asBis);
                        alreadyCreated.add(asBis);
                    }
                }

            if (valid.isEmpty())
                return Pair.of(0, List.of());

            Collections.sort(valid,
                (bis1, bis2) -> -Integer.compare(summary.getCountOf(bis1.stack), summary.getCountOf(bis2.stack)));  // 比较器里重算
            validEntriesByIngredient.add(valid);
        }
        ...
    }
```

**为什么慢**

频次：这是 GUI 交互路径（每次敲键 / 每次下单），不是每帧，但 `refreshSearchResults`（`:326`）会在**每次输入**重建整个列表并对每个待合成配方重跑 `maxCraftable`，因此大仓库下会出现「打字卡顿」。成本结构：外层「配料」× 中层「库存条目」× 内层「alreadyCreated」三级相乘，且每一级都在做 `ItemStack.isSameItemSameComponents`（比较 Item + DataComponentMap）。规模放大：仓库种类上千时，按键一次的判定次数可达十万量级（**估计**）。

**改后**

```java
// 纯 Java 等价改法（完整可粘贴体见 overwrites/StockKeeperRequestScreen-maxCraftable.md）
// 1) 用「身份桶」替换 alreadyCreated 的线性扫描：
//    alreadyCreated 的意义是「同一个 (Item, components) 只建一个 BigItemStack，后续复用」。
//    改成 Map<Integer, List<BigItemStack>>（键 = ItemStack.hashItemAndComponents(stack)），
//    桶内再逐个 isSameItemSameComponents 校验（碰撞极少），复杂度从 O(n) 降到 O(1) 均摊。
// 2) 比较器不要每次比较都算 getCountOf：先把计数算进一个并行的 int 数组（或临时包装对象），
//    再按「计数降序、稳定」排序。Collections.sort 是稳定排序，必须保持同样稳定性。
// 3) getOrderForItem 的调用点（:1679 / :1711）用同一张桶索引表，O(itemsToOrder) -> O(1)。
```

**Mixin 骨架**

```java
package com.example.createperf.mixin.client;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.stockTicker.CraftableBigItemStack;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen;

import net.createmod.catnip.data.Pair;
import net.minecraft.world.item.ItemStack;

@Mixin(value = StockKeeperRequestScreen.class, remap = false)
public abstract class StockKeeperRequestScreenMixin {

    // 本类是同类的 @Overwrite（可以访问同类私有成员），但更稳的是 HEAD 短路 + @Unique 复制体。
    @Inject(method = "maxCraftable(Lcom/simibubi/create/content/logistics/stockTicker/CraftableBigItemStack;Lcom/simibubi/create/content/logistics/packager/InventorySummary;Ljava/util/function/Function;I)Lnet/createmod/catnip/data/Pair;",
            at = @At("HEAD"), cancellable = true)
    private void createperf$maxCraftable(CraftableBigItemStack cbis, InventorySummary summary,
                                         Function<ItemStack, Integer> countModifier, int newTypeLimit,
                                         CallbackInfoReturnable<Pair<Integer, List<List<BigItemStack>>>> cir) {
        cir.setReturnValue(createperf$maxCraftableFast(cbis, summary, countModifier, newTypeLimit));
    }

    @Unique
    private Pair<Integer, List<List<BigItemStack>>> createperf$maxCraftableFast(CraftableBigItemStack cbis,
        InventorySummary summary, Function<ItemStack, Integer> countModifier, int newTypeLimit) {
        // 见 overwrites/StockKeeperRequestScreen-maxCraftable.md
        // 注意：resolveIngredientAmounts / removeLeastEssentialItemStack / getOrderForItem
        //       都是同类私有方法；@Unique 复制体无法直接调用 -> 要么用 @Invoker 转发，
        //       要么把它们的逻辑一并复制（本文件给出的 overwrite 版本选择后者）。
        return null;
    }
}
```

**验证方法**

- 探针：`@Unique` 计时（`System.nanoTime`）包住 `maxCraftable`，每 100 次调用打一次「平均耗时 / 调用次数 / alreadyCreated 比较次数」；场景：接入一个装满 300+ 种物品的仓库，在搜索框连续输入 10 个字符。
- 结果等价（决定性）：把 `maxCraftable` 的返回值（`Pair.getFirst()` 的整数 + `getSecond()` 里每个 `BigItemStack` 的 `stack` 与 `count` 与顺序）在改前/改后逐项对比，必须在同一输入下**完全一致**。
- 端到端：同一输入序列下关注输入延迟（客户端帧时间尖峰）；用 `/spark profiler` 观察 `StockKeeperRequestScreen.maxCraftable` 占比下降。

**收益估计**：量级——把内层 `O(|alreadyCreated|)` 扫描降为 `O(1)` 均摊、把比较器里的 `getCountOf` 从「每次比较 2 次」降为「每项 1 次」，大仓库单次按键的判定次数从十万量级降到万级（**估计**）。
**回归测试点**：`maxCraftable` 返回值逐项一致（数量与顺序）；`newTypeLimit` 修剪（`:1676-1685`）选取顺序一致；`resolveIngredientAmounts` 的「共享配料均分」结果一致；`removeLeastEssentialItemStack` 的选择一致；搜索框输入时 `canRequestCraftingPackage`（`:1631-1635`）的置位时机不变；空库存/空 `valid` 的提前返回（`:1667-1668`、`:1699-1700`）仍发生。

### 9. `BacktankUtil.getAllWithAir` 每次查询都重建列表并用比较器反复算 maxAir — [Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/equipment/armor/BacktankUtil.java:50-65`（`getAllWithAir`）。相关行：`:40-47`（注册的 supplier **每次调用**都 `new ArrayList<>()` 并遍历盔甲槽）、`:62` `all.sort(comparing getAir)`、`:71-73` `getAir`、`:107-117` `maxAir(ItemStack)`（遍历 `getTagEnchantments()` 全部附魔项）、`:143-153` `isBarVisible`、`:155-179` `getBarWidth`（`:173-176` 还有一次 `stream().map().reduce()`）、`:187` `getBarColor`。调用点：`RemainingAirOverlay.java:70`、`DivingHelmetItem.java:91`、`PotatoCannonItem.java:255-261`（`isBarVisible`/`getBarWidth`）、`ExtendoGripItem.java:213-219`、`BacktankUtil.java:133/149/162/187` |
| 调用频次 | **修正原清单的表述**：`RemainingAirOverlay` 是 `LayeredDraw.Layer`（每帧），但它在 `:36-44` 有多重提前返回（创造模式、无 `VisualBacktankAir`、`(isAir || canBreathe) && !isInLava`），**只有真正在水下/岩浆里**才会走到 `:70` 的 `getAllWithAir`。真正**每帧无条件**的是原版 GUI 对物品条宽/可见性的查询：`PotatoCannonItem.isBarVisible/getBarWidth` 与 `ExtendoGripItem.isBarVisible/getBarWidth` 会转调 `BacktankUtil`，而原版 `Gui` 会为**每个渲染出来的快捷栏格**查询一次物品条 ⇒ 手持/快捷栏里有土豆加农炮或加长握把时**每帧约 9–10 次** `getAllWithAir` |
| 复杂度 / 成本 | 每次 `getAllWithAir`：1 个 `ArrayList` + supplier 里 1 个 `ArrayList` + 4 次盔甲槽 tag 匹配 + 过滤循环（每次调用 `hasAirRemaining` → `getAir` → `maxAir` → **遍历整个附魔表**）+ `all.sort`——排序比较器每次比较调用 `getAir`（同样进 `maxAir`）⇒ `O(n log n)` 次 `maxAir`（n = 命中的压力容器数，通常 1–4）。`getBarWidth` 里还有一次 `stream` 管道 + `reduce` 装箱。规模放大：附魔表条目数（多附魔容器）会放大每次 `maxAir`；每帧 9–10 次调用时线性放大 |
| 注入方式 | `@Inject(HEAD, cancellable)` + `@Unique` 每 tick 缓存（推荐），或整段 `@Overwrite` 做无分配实现；见 `overwrites/BacktankUtil-getAllWithAir.md` |
| 目标成员 | `com.simibubi.create.content.equipment.armor.BacktankUtil#getAllWithAir(Lnet/minecraft/world/entity/LivingEntity;)Ljava/util/List;`（静态 handler） |
| 需新增状态 | `@Unique static` 缓存：键 = 实体身份（`IdentityHashMap<LivingEntity, List<ItemStack>>`），值 = 结果列表 + 该 tick 的 `maxAir` 计算结果；失效＝客户端 tick 变化时整体清空。**注意**：不能长期持有 `LivingEntity` 强引用（手册第 7 节的内存泄漏反模式）——必须每 tick 清空，且只在客户端使用（`clientSide`）。并发＝`isBarVisible/getBarWidth` 走 `DistExecutor.unsafeCallWhenOn(Dist.CLIENT, ...)`（客户端主线程），单一侧 |
| 风险 | 中：返回的 `List<ItemStack>` 是**共享可变列表**，调用点只用 `isEmpty/getFirst/size`（已逐个核对：`RemainingAirOverlay.java:71-72`、`DivingHelmetItem.java:91`、`BacktankUtil.java:133-137,149-152,162-169,187-197`），没有调用方修改它——但这是「依赖调用方不变」的隐性契约，必须写进回归测试点。另：同一 tick 内玩家的空气值不会变化（空气消耗发生在使用时、服务端权威后同步），所以每 tick 缓存语义精确 |
| 脆弱度 | 中：`BACKTANK_SUPPLIERS` 是可扩展的（`:204-206` `addBacktankSupplier`，注释明确说明供其它 mod 注册「自定义槽位/物品」的容器）。缓存一旦按 tick 生效，第三方 supplier 的返回值也在同一 tick 内被记忆——若某个第三方 supplier 返回**随时间变化**的列表（同 tick 内变化不可能，跨 tick 会刷新），语义仍正确 |
| 置信度 | 确定（行号、调用点、`maxAir` 的附魔遍历均已核对）；「每帧 9–10 次」是基于原版 `Gui` 对每个快捷栏格查询物品条这一行为的**高概率**判断（原版源码不在本仓库，行号无从给出） |

**改前**

```java
// BacktankUtil.java:40-65（原样摘录）
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

**为什么慢**

频次：见上（每帧 9–10 次 / 水下叠加）。成本：每次调用至少 2 个 `ArrayList`，并在「过滤」和「排序」两处反复进 `getAir` → `maxAir`（`:107-117` 要遍历 `getTargEnchantments()` 的全部条目找 CAPACITY）。`sort` 的比较器每次都重算两边，因此 `maxAir` 被调用 `O(n log n)` 次而不是 `O(n)` 次。规模放大：附魔表 + 每帧调用次数双重放大；水下时 `RemainingAirOverlay` 再叠加一次。

**改后**

```java
// 纯 Java 等价改法（完整可粘贴体见 overwrites/BacktankUtil-getAllWithAir.md）
// 1) 一次遍历同时算出 air 与 maxAir，避免排序比较器里重复进 maxAir：
//    用两个并行数组（stack 引用 + air 值）做插入排序；n<=4，插入排序更快且零分配。
// 2) 结果列表只在缓存未命中时构建；每客户端 tick 失效一次。
// 3) supplier 的 ArrayList 可复用（每个 supplier 一个线程本地槽位）——注意第三方 supplier 的返回值不可写。
```

**Mixin 骨架**

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

    @Unique private static final Map<LivingEntity, List<ItemStack>> createperf$cache = new IdentityHashMap<>();
    @Unique private static long createperf$tick = Long.MIN_VALUE;

    @Inject(method = "getAllWithAir(Lnet/minecraft/world/entity/LivingEntity;)Ljava/util/List;",
            at = @At("HEAD"), cancellable = true)
    private static void createperf$cacheGetAllWithAir(LivingEntity entity, CallbackInfoReturnable<List<ItemStack>> cir) {
        if (entity == null || !entity.level().isClientSide)
            return;                                    // 服务端语义保持原样（例如 canAbsorbDamage）
        long now = AnimationTickHolder.getTicks();
        if (now != createperf$tick) {                  // 每 tick 清空，避免持有实体强引用
            createperf$tick = now;
            createperf$cache.clear();
        }
        List<ItemStack> hit = createperf$cache.get(entity);
        if (hit != null) {
            cir.setReturnValue(hit);
            return;
        }
        // 未命中：让原方法算（下面再补一个 RETURN 注入回填），或调用 @Unique 的快速实现
        // 建议直接调用 createperf$fastGetAllWithAir(entity) 并回填，见 overwrites/
    }
}
```

**验证方法**

- 探针：`@Unique static long` 统计 `getAllWithAir` 进入次数与 miss 次数；期望在持握土豆加农炮时 miss 数 = 每 tick 1 次而非每帧 9–10 次。
- 行为等价：不同容器组合（无容器 / 1 个普通 / 2 个且附魔不同 / 附魔 CAPACITY 等级不同）下，物品条宽度、颜色、剩余空气文本与改前完全一致。特别验证「升序排序」语义：`getFirst()` 必须是**最空**的那个（`:61-62` 注释明确说明这是为了先耗尽最空的容器）。
- 水下叠加：潜到水下时 `RemainingAirOverlay` 的图标与文字正常。
- 第三方路径：调用 `addBacktankSupplier` 的自定义实现（如果有）返回值仍被正确纳入。
- 泄漏检查：缓存每 tick 清空，长时间运行后不持有实体（用 JFR 观察 `IdentityHashMap$Entry` 数量恒定）。

**收益估计**：量级——持握空气类工具时每帧 `getAllWithAir` 从 9–10 次降到 1 次/tick（60 fps 下约 10 倍）；每次调用的 `maxAir` 调用次数从 `O(n log n)` 降到 `O(n)`（**估计**）。
**回归测试点**：返回列表的**升序**（最空在前）语义不变；`getBarColor`/`getBarWidth`/`isBarVisible` 的返回值不变；服务端 `canAbsorbDamage`（`:128-139`）行为不变（缓存只在客户端生效）；`DivingHelmetItem.java:91` 的潜水头盔路径不变；列表实例被调用方修改的风险（当前无调用方修改，若上游新增调用方需复查）。

### 10. `TrackRenderer.renderSafe` / `renderBezierTurn` 的每段光照与 buffer 查询 — [Low-Medium]（原清单为 Medium，此处降级并给出三项事实更正）

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/trains/track/TrackRenderer.java:36-44`（`renderSafe`）、`:46-79`（`renderBezierTurn`：`:52` `Blocks.AIR.defaultBlockState()`、`:53` `bc.getBakedSegments()`、`:57-76` 段循环、`:58` `LevelRenderer.getLightColor` + `segment.lightPosition[i].offset(bePosition)`、`:60` `bc.getMaterial().getModelHolder()`、`:62/70` `CachedBuffers.partial`）、`:81-111`（`renderGirder`：`:86` `air`、`:90` 光照、`:94/103` `CachedBuffers.partial`）、`:134-142`（`shouldRenderOffScreen` 返回 true、`getViewDistance` 返回 192） |
| 调用频次 | **仅在 Flywheel 可视化不可用时**：`:39` `if (VisualizationManager.supportsVisualization(level)) return;` —— 装了 Flywheel 且后端激活时，整个方法体（含 `renderBezierTurn`）每帧都直接返回。Create 注册了 `TrackVisual`（`AllBlockEntityTypes.java:939`，`TrackVisual.java:35`），因此**普通客户端上这段渲染根本不执行**。只有 Flywheel 可视化关闭 / `PonderLevel` 等场景才走到 `:42` 的 `forEach` |
| 复杂度 / 成本 | 若执行：每段 1 次 `LevelRenderer.getLightColor`（光照引擎查询）+ 1 次 `BlockPos.offset(BlockPos)`（**高概率是新建 BlockPos**，MC 源码不在本仓库，需实测）+ 1 次 `CachedBuffers.partial` + 1 次 `renderInto`，每段还有左右两条轨道 ×2；梁路径每段最多 5 次 `CachedBuffers.partial` + `renderInto`。**三项事实更正**：① `bc.getMaterial().getModelHolder()`（`:60`）**不是**「重复解析」——`BezierConnection.getMaterial()`（`BezierConnection.java:325-327`）与 `TrackMaterial.getModelHolder()`（`TrackMaterial.java:57-59`）都是**纯字段读取**，零解析零分配；② `Blocks.AIR.defaultBlockState()`（`:52`、`:86`）在 vanilla 里是记忆化的字段读取，**不是**「每次 BER 重建」；③ `getBakedSegments()`/`getBakedGirders()` 用 `AtomicReference` 缓存（`BezierConnection.java:693-714`），几何只烘焙一次。真正可优化的只剩光照查询与 `CachedBuffers.partial` 的重复（后者是否昂贵取决于 catnip 内部，**需实测**） |
| 注入方式 | **建议不注入**。可做的只有：`@ModifyExpressionValue`/`@WrapOperation` 包裹 `LevelRenderer.getLightColor` 做按段记忆化（同一 BE 的相邻段常共享光照），收益取决于 Flywheel 是否缺席。**本文件只登记，不产出 overwrite**（理由：装 Flywheel 时该路径根本不执行） |
| 目标成员 | `com.simibubi.create.content.trains.track.TrackRenderer#renderBezierTurn(Lnet/minecraft/world/level/Level;Lcom/simibubi/create/content/trains/track/BezierConnection;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V`（public static） |
| 需新增状态 | 若做光照记忆化：`@Unique static` `Long2IntOpenHashMap` + tick 校验（同发现 5） |
| 风险 | 低：只改光照查询次数，不改几何 |
| 脆弱度 | 低：`renderBezierTurn` 是 public static，签名稳定 |
| 置信度 | 确定（Flywheel 门控、`TrackVisual` 注册、`getMaterial/getModelHolder` 是字段读取、`air` 记忆化、烘焙缓存均已逐行核对）；「`BlockPos.offset(BlockPos)` 是否新建对象」**需实测**（无 MC 源码）；`CachedBuffers.partial` 开销为**估计** |

**改前**

```java
// TrackRenderer.java:36-79（原样摘录）
    protected void renderSafe(TrackBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
        int overlay) {
        Level level = be.getLevel();
        if (VisualizationManager.supportsVisualization(level))
            return;                                     // <-- Flywheel 门控：普通客户端到此为止
        VertexConsumer vb = buffer.getBuffer(RenderType.cutoutMipped());
        be.connections.values()
            .forEach(bc -> renderBezierTurn(level, bc, ms, vb));
    }

    public static void renderBezierTurn(Level level, BezierConnection bc, PoseStack ms, VertexConsumer vb) {
        if (!bc.isPrimary())
            return;

        ms.pushPose();
        BlockPos bePosition = bc.bePositions.getFirst();
        BlockState air = Blocks.AIR.defaultBlockState();
        SegmentAngles segment = bc.getBakedSegments();

        renderGirder(level, bc, ms, vb, bePosition);

        for (int i = 1; i < segment.length; i++) {
            int light = LevelRenderer.getLightColor(level, segment.lightPosition[i].offset(bePosition));

            TrackMaterial.TrackModelHolder modelHolder = bc.getMaterial().getModelHolder();

            CachedBuffers.partial(modelHolder.tie(), air)
                .mulPose(segment.tieTransform[i].pose())
                .mulNormal(segment.tieTransform[i].normal())
                .light(light)
                .renderInto(ms, vb);

            for (boolean first : Iterate.trueAndFalse) {
                Pose transform = segment.railTransforms[i].get(first);
                CachedBuffers.partial(first ? modelHolder.leftSegment() : modelHolder.rightSegment(), air)
                    .mulPose(transform.pose())
                    .mulNormal(transform.normal())
                    .light(light)
                    .renderInto(ms, vb);
            }
        }

        ms.popPose();
    }
```

**为什么慢（以及为什么本条多半不适用）**

在**没有 Flywheel 可视化**的环境里，每帧每个视野内轨道 BE（`shouldRenderOffScreen` 为 true 且 `getViewDistance` = 192，所以「视野内」其实是「192 格内」）都要把整条贝塞尔曲线的每一段重新提交一遍顶点，每段一次光照查询与若干次 `CachedBuffers.partial`。规模放大：一条 40 格弯道约 80 段，一个枢纽站区可能有几十个轨道 BE，此时顶点提交量巨大——**但真正的开销是顶点写入本身，而不是本清单列出的那几个查询/分配**，所以这条的正确处置是「若你确实需要无 Flywheel 运行，请考虑向上升级 Flywheel 而不是打这个 patch」。而在**装 Flywheel 的默认环境**里，`:39` 会直接返回，本条所有优化都不会被执行，收益恒为 0（除了由 `shouldRenderOffScreen = true` 引起的 BE 调度）。

**改后**

```java
// 唯一值得做的事（且只在无 Flywheel 时有意义）：把光照查询按 (BE, 段坐标) 记忆化。
// 注意 BlockPos.offset(BlockPos) 本身就可能是新对象，若实测确认是新建，
// 用 mutableSlots 复用一个 MutableBlockPos 即可省掉每段 1 个 BlockPos：
BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
...
lightMemo.computeIfAbsent(posLong, k -> {
    scratch.set(segment.lightPosition[i]).move(bePosition.getX(), bePosition.getY(), bePosition.getZ());
    return LevelRenderer.getLightColor(level, scratch);
});
```

**Mixin 骨架**

```java
// 本条不推荐注入：装 Flywheel 时 renderSafe 第一行就 return，收益为 0；
// 未装 Flywheel 时主要成本是顶点提交，注入光照记忆化只能省掉光照查询。
// 若确需注入，形态与发现 5 的 getLightColor 记忆化完全相同，只是方法描述符换成：
//   "renderBezierTurn(Lnet/minecraft/world/level/Level;Lcom/simibubi/create/content/trains/track/BezierConnection;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"
// 并且必须把 method 限定为 renderBezierTurn，避免误改 BeltRenderer 的同名调用点（那属于发现 5）。
```

**验证方法**

- 前置确认：先确认你的运行环境里 `VisualizationManager.supportsVisualization(mc.level)` 到底返回什么（临时 mixin 打日志 / 用 `/flywheel` 相关指令或配置关闭后端再对比）。**如果为 true，本条无需验证也无需修**。
- 探针：统计 `renderBezierTurn` 每帧调用次数与段总数、`getLightColor` 调用次数。
- 视觉回归：轨道外观（枕木/两侧轨/梁）逐帧一致；光照变化时（放置火把）轨道亮度随之更新。

**收益估计**：量级——**装 Flywheel 时 0**；不装时每段省 1 次光照查询（**估计**），顶点提交成本不变。因此本条被评为 Low-Medium，且**不推荐**纳入补丁包。
**回归测试点**：`shouldRenderOffScreen` 保持 `true`（详见下方「上游候选」中的说明——长曲线的可见部分可能远离它自己的方块位置，把这里改成 false 会导致曲线在端点方块离屏时整条消失，**不要改**）；`:47` 的 `isPrimary` 过滤不变；`getViewDistance` = 192 不变。

### 11. `CreateHatArmorLayer` / `EntityHats` 每个可见实体的帽子判定 — [Low]（原清单为 Low-Medium，此处降级并更正次数）

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/equipment/hats/CreateHatArmorLayer.java:39-67`（`:41` `EntityHats.getHatFor`、`:49` `TrainHatInfoReloadListener.getHatInfoFor`、`:50` `new ArrayList<>()`）；`content/equipment/hats/EntityHats.java:40-69`（`:51-63` 的双层循环）；`content/trains/schedule/hat/TrainHatInfoReloadListener.java:51-58`（`:54` `new TrainHatInfo` + `new Vec3`） |
| 调用频次 | 该 `RenderLayer` 注册在**所有生物渲染器**上，因此每个可见生物每帧调用一次（`:39-43`）。**但**：`:41` 的 `EntityHats.getHatFor` 在 `EntityHats.java:30-32` 先看 `HEAD` 槽位——有头盔直接返回 `null`；随后 `:37` `getLogisticsHatFor` 在 `:41-44` 对「不是 `SeatEntity` 乘客」直接返回 `null`。所以 **`:50` 的 `new ArrayList` 只有在实体真的戴了帽子时才会执行**（火车帽 / 物流帽），不是「每个可见实体每帧一个 ArrayList」 |
| 复杂度 / 成本 | 真正的每帧成本是 `getHatFor` 的分支判定（`getItemBySlot(HEAD)` + `getPersistentData().contains`；`shouldRenderTrainHat` 在 `EntityHats.java:71-89` 还可能走 `getSeatOf`/`conductorSeats` 查表）。当实体是 `SeatEntity` 乘客时，`getLogisticsHatFor` 做 **4 个水平方向 × 2 个高度 = 8 次** `level.getBlockState`（`:51-63`）。**更正原清单的「8 方向×2 高度 = 16 次」**：`Iterate.horizontalDirections` 是 **4 个水平方向**——同一用法在 `StockTickerInteractionHandler.java:211-212`、`ConnectedGirderModel.java:37-38`（只连 4 个水平面）、`TrackBlockOutline.java:256` 等 70+ 处一致，斜向方向在 MC 的 `Direction` 里不存在。另 `TrainHatInfoReloadListener.java:54` 对**戴南瓜的雪傀儡**每次调用都 `new TrainHatInfo(...) + new Vec3(...)` |
| 注入方式 | ① `@ModifyExpressionValue` 包裹 `TrainHatInfoReloadListener.getHatInfoFor` 的雪傀儡分支（返回 `@Unique static final TrainHatInfo` 常量），零风险、每帧省 2 个对象/雪傀儡；② `EntityHats.getLogisticsHatFor` 的 8 次 `getBlockState` 需要 `@Unique` 每 tick 缓存（键 = 实体身份 + tick）或上游在 `SeatEntity` 上缓存站点 —— 见下方「上游候选」 |
| 目标成员 | `com.simibubi.create.content.trains.schedule.hat.TrainHatInfoReloadListener#getHatInfoFor(Lnet/minecraft/world/entity/Entity;)Lcom/simibubi/create/content/trains/schedule/hat/TrainHatInfo;`<br>`com.simibubi.create.content.equipment.hats.EntityHats#getLogisticsHatFor(Lnet/minecraft/world/entity/LivingEntity;)Ldev/engine_room/flywheel/lib/model/baked/PartialModel;` |
| 需新增状态 | 雪傀儡常量：`@Unique static final TrainHatInfo`（不可变 record + `Vec3.ZERO` 之外的常量 Vec3，线程安全）。物流帽缓存：`@Unique static` `IdentityHashMap<LivingEntity, PartialModel>` + tick 清空（**必须每 tick 清，不能持有实体强引用**） |
| 风险 | 低（雪傀儡常量）；中（物流帽缓存：帽子取决于附近站点方块，方块可被 GPU 之外的逻辑随时改变——用每 tick 失效即可，与发现 2/9 同一模式） |
| 脆弱度 | 低-中：`TrainHatInfoReloadListener.getHatInfoFor` 是 public static；`EntityHats.getLogisticsHatFor` 也是 public static（但返回值类型是 Flywheel 的 `PartialModel`，需 Flywheel 在编译期可见） |
| 置信度 | 确定（行号、「4 个方向」的证据、帽子判定分支均已核对） |

**改前**

```java
// CreateHatArmorLayer.java:39-67（节选）
    public void render(PoseStack ms, MultiBufferSource buffer, int light, LivingEntity entity, float limbSwing, float limbSwingAmount,
                       float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
        PartialModel hat = EntityHats.getHatFor(entity);
        if (hat == null)
            return;                                       // <-- 绝大多数实体在这里返回

        M entityModel = getParentModel();
        ms.pushPose();

        var msr = TransformStack.of(ms);
        TrainHatInfo info = TrainHatInfoReloadListener.getHatInfoFor(entity);
        List<ModelPart> partsToHead = new ArrayList<>();  // <-- 只有真戴帽子的实体才分配
        ...

// EntityHats.java:40-69（原样摘录）
    public static PartialModel getLogisticsHatFor(LivingEntity entity) {
        if (!entity.isPassenger())
            return null;
        if (!(entity.getVehicle() instanceof SeatEntity cce))
            return null;

        int stations = 0;
        Level level = entity.level();
        BlockPos pos = entity.blockPosition();
        PartialModel hat = null;

        for (Direction d : Iterate.horizontalDirections) {      // 4 个方向
            for (int y : Iterate.zeroAndOne) {                  // 2 个高度
                if (!(level.getBlockState(pos.relative(d)
                    .above(y))
                    .getBlock() instanceof StockTickerBlock lw))
                    continue;
                PartialModel hatOfStation = lw.getHat(level, pos, entity);
                if (hatOfStation == null)
                    continue;
                hat = hatOfStation;
                stations++;
            }
        }

        if (stations == 1)
            return hat;

        return null;
    }

// TrainHatInfoReloadListener.java:51-58（原样摘录）
    public static TrainHatInfo getHatInfoFor(Entity entity) {
        // Manual override for snow golems, they are a special case when they have a pumpkin on their head
        if (entity instanceof SnowGolem snowGolem && snowGolem.hasPumpkin()) {
            return new TrainHatInfo("", 0, new Vec3(0.0F, -3.0F, 0.0F), 1.18F);   // 每次新建
        }

        return ENTITY_INFO_MAP.getOrDefault(entity.getType(), DEFAULT);
    }
```

**为什么慢**

相对量级：每个可见实体每帧 1 次 `getHatFor` 分支判定（廉价），只有「戴帽子」或「坐在 Create 座位上」的实体才会产生可观工作 —— 8 次 `getBlockState`（坐位上的每个乘客每帧一次）或 2 个新对象（每个戴南瓜的雪傀儡每帧一次）。规模放大：一个满是玩家的有座列车/坐垫区（`SeatEntity`）会有很多乘客实体同时渲染，每人每帧 8 次方块查询。这是 **Low** 级：方块查询是纯内存操作，且乘客数量通常 = 可见玩家数。

**改后**

```java
// 雪傀儡：提为不可变常量（TrainHatInfo 是 record；Vec3 亦不可变）
private static final TrainHatInfo SNOW_GOLEM_HAT = new TrainHatInfo("", 0, new Vec3(0.0F, -3.0F, 0.0F), 1.18F);

// 物流帽：每 tick 缓存一次判定结果（帽子只随「附近站点方块」变化，而方块变化发生在 tick 中）
// 注意用 IdentityHashMap + 每 tick 清空，绝不长期持有实体引用。
```

**Mixin 骨架**

```java
package com.example.createperf.mixin.client;

import com.simibubi.create.content.trains.schedule.hat.TrainHatInfo;
import com.simibubi.create.content.trains.schedule.hat.TrainHatInfoReloadListener;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.phys.Vec3;

@Mixin(value = TrainHatInfoReloadListener.class, remap = false)
public abstract class TrainHatInfoReloadListenerMixin {

    @Unique
    private static final TrainHatInfo createperf$SNOW_GOLEM_HAT =
        new TrainHatInfo("", 0, new Vec3(0.0F, -3.0F, 0.0F), 1.18F);

    // 注意：不能用 @ModifyExpressionValue 去改 new TrainHatInfo(...) 那个表达式——
    // NEW 指令在修饰器收到值之前就已经执行，分配依旧发生（手册 3.2 节）。
    // 因此这里直接在 HEAD 短路整个方法（方法体只有 6 行，见 overwrites/TrainHatInfoReloadListener-getHatInfoFor.md）。
    @Inject(
        method = "getHatInfoFor(Lnet/minecraft/world/entity/Entity;)Lcom/simibubi/create/content/trains/schedule/hat/TrainHatInfo;",
        at = @At("HEAD"), cancellable = true)
    private static void createperf$hatInfo(Entity entity, CallbackInfoReturnable<TrainHatInfo> cir) {
        if (entity instanceof SnowGolem snowGolem && snowGolem.hasPumpkin())
            cir.setReturnValue(createperf$SNOW_GOLEM_HAT);   // 命中即返回常量；未命中让原方法跑
    }
}
```

**验证方法**

- 探针：统计 `getLogisticsHatFor` 调用次数与 `getBlockState` 次数（应为调用次数 × 8），以及 `getHatInfoFor` 中雪傀儡分支的命中次数。
- 视觉回归：给雪傀儡戴南瓜，确认火车帽位置/缩放与改前一致（`TrainHatInfo` 四元组必须逐字段相同）；玩家坐在有票机的座位旁，确认帽子仍出现；放两台票机时（`stations != 1`）仍不戴帽。
- 缓存正确性：拆掉票机后下一 tick 帽子应消失（每 tick 失效）。

**收益估计**：量级——每个戴南瓜的雪傀儡每帧省 2 个对象（`TrainHatInfo` + `Vec3`）；每个坐在 Create 座位上的实体每帧省 7 次 `getBlockState`（**估计**，仅在采用缓存方案时）。
**回归测试点**：雪傀儡帽子参数逐字段一致；物流帽「恰好 1 个站点」的判定不变；`SeatEntity` 之外的载具不触发物流帽；每 tick 缓存清空（无实体泄漏）。

### 12. `NixieTubeRenderer` 每帧两次消费世界随机源 — [Low-Medium]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/content/redstone/nixieTube/NixieTubeRenderer.java:66`（`RandomSource random = be.getLevel().getRandom();`）、`:71`/`:77`（两次 `drawTube(...)`）、`:87`（`float flicker = random.nextFloat();`） |
| 调用频次 | **每帧 × 每个可见辉光管**，且每个辉光管每帧消费 **2 次** `nextFloat`（`:71` 与 `:77` 各一次，`drawTube` 内部 `:87`）。未找到辉光管的 Flywheel visual（`AllBlockEntityTypes.java:823-829` 只注册了 `NixieTubeRenderer::new`），所以这条路径在装了 Flywheel 时**照样执行** |
| 复杂度 / 成本 | ① `Level.getRandom()` 返回的是 **世界共享** 的 `RandomSource`（vanilla `Level` 的 `random` 字段，`LegacyRandomSource`，内部 `AtomicLong` 种子，每次取样一次 CAS）——从**渲染线程**消费它，会与客户端 tick 线程（以及所有其它用 `level.random` 的系统：粒子、音效、战利品装饰等）争用同一个原子变量；② 更隐蔽的是**确定性污染**：屏幕内辉光管数量会改变其它系统取到的随机数序列；③ 成本本身很小（2 次 `nextFloat` + 1 次 `Color.mixColors`/管/帧），所以本条的重点是**线程/确定性卫生**而非耗时 |
| 注入方式 | `@WrapOperation` 包裹 `Level.getRandom()`，返回一个 `@Unique static final RandomSource`（专用实例，只被渲染线程触碰）。阈值/视觉不变，只是随机序列来源变了 |
| 目标成员 | `Lnet/minecraft/world/level/Level;getRandom()Lnet/minecraft/util/RandomSource;`（调用点在 `NixieTubeRenderer.renderSafe` → `drawTube` 之前的 `:66`） |
| 需新增状态 | `@Unique static final RandomSource createperf$flickerRng = RandomSource.create();`。作用域＝客户端进程级；失效＝不需要（只用于闪烁）；并发＝仅渲染线程访问（无争用的 `AtomicLong` CAS）。若要完全避免原子操作，可换成自己实现的单线程 `RandomSource`（**可选，非必需**） |
| 风险 | 低：视觉上「闪烁随机值序列」变化（本来就随机，不可感知差异）；真正的行为变更是**世界随机数流不再被渲染消费**——这会让其它系统的随机结果与改前不同（属于修复确定性，但要写进变更日志） |
| 脆弱度 | 低：`Level.getRandom()` 是 vanilla 稳定 API；调用点 `:66` 位于 `renderSafe` 内，上游若改成 `level.random` 字段访问则注入失败（`defaultRequire=1` 会启动报错） |
| 置信度 | 确定（行号、两次调用、无 Flywheel visual 均已核对）；「`LegacyRandomSource` 用 `AtomicLong`」是 vanilla 实现细节，**高概率**（无本地 MC 源码） |

**改前**

```java
// NixieTubeRenderer.java:64-78（原样摘录）
        Couple<String> s = be.getDisplayedStrings();
        DyeColor color = NixieTubeBlock.colorOf(be.getBlockState());
        RandomSource random = be.getLevel().getRandom();     // <-- 世界共享随机源，渲染线程消费

        ms.pushPose();
        ms.translate(-4 / 16f, 0, 0);
        ms.scale(scale, -scale, scale);
        drawTube(ms, buffer, s.getFirst(), height, color, random);
        ms.popPose();

        ms.pushPose();
        ms.translate(4 / 16f, 0, 0);
        ms.scale(scale, -scale, scale);
        drawTube(ms, buffer, s.getSecond(), height, color, random);
        ms.popPose();

// drawTube 内部 :87
        float flicker = random.nextFloat();
```

**为什么慢**

严格说不是「慢」：每管每帧只有 2 次 `nextFloat` 与 1 次颜色混合。真正的问题有两层：**(a) 跨线程共享状态**——把世界 RNG 的原子种子在渲染线程上做 CAS，屏幕里辉光管越多、帧率越高，争用越频繁，最坏情况下 render 与 tick 互相拖慢；**(b) 确定性污染**——任何依赖 `level.random` 的系统（粒子、音效随机、装饰）的取值序列会随「玩家视野里有多少辉光管」而改变，这让「同一存档同一操作复现同一随机结果」变得不可能。对做性能/行为审计的整合包来说是明确的卫生问题。

**改后**

```java
// 纯 Java 等价改法：给闪烁一个专用随机源（只在渲染线程使用，无争用）
private static final RandomSource FLICKER_RNG = RandomSource.create();
...
        RandomSource random = FLICKER_RNG;
```

**Mixin 骨架**

```java
package com.example.createperf.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.redstone.nixieTube.NixieTubeRenderer;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

@Mixin(value = NixieTubeRenderer.class, remap = false)
public abstract class NixieTubeRendererMixin {

    @Unique
    private static final RandomSource createperf$flickerRng = RandomSource.create();

    @WrapOperation(
        method = "renderSafe(Lcom/simibubi/create/content/redstone/nixieTube/NixieTubeBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getRandom()Lnet/minecraft/util/RandomSource;"))
    private RandomSource createperf$flickerRng(Level level, Operation<RandomSource> original) {
        // 不调用 original：切断与世界 RNG 的耦合
        return createperf$flickerRng;
    }
}
```

> `renderSafe` 的描述符需按 `SafeBlockEntityRenderer` 的实际签名写（`protected void renderSafe(T, float, PoseStack, MultiBufferSource, int, int)`）。若 `renderSafe` 的描述符不匹配，可把注入点改成 `drawTube`（`:83`，`public static`）——但 `:87` 的 `nextFloat` 在 `drawTube` 内，包裹 `drawTube` 无法换掉已经传入的 `random` 引用，所以必须注在 `renderSafe` 的 `getRandom()` 调用点上（**需实测确认描述符**）。

**验证方法**

- 探针：`@Unique static long` 统计专用 RNG 的 `nextFloat` 次数（应 = 2 × 可见辉光管数 × 帧数）；同时用一个临时 mixin 在 `level.random` 上打点，确认渲染路径不再消费它。
- 确定性回归：固定种子存档，在同一位置放置不同数量的辉光管，观察某个依赖 `level.random` 的可见效果（例如发射器粒子或随机刻驱动的装饰）序列是否不再受辉光管数量影响。
- 视觉回归：辉光管闪烁的幅度/颜色范围不变（只变了随机序列来源）。

**收益估计**：量级——每帧每管省 2 次世界 RNG 的原子取样，去掉渲染线程与 tick 线程的共享点；耗时收益接近于 0（**估计**），主要收益是确定性。
**回归测试点**：辉光管双管显示（`s.getFirst()/getSecond()`）的闪烁幅度与颜色混合公式（`Color.mixColors(bright, dark, flicker / 4)`）不变；信号模式（`:53-57` `renderAsSignal`）分支不受影响；专用 RNG 不会被其它系统复用（保持 `@Unique` 私有）。

### 13. `AirParticle` 每粒子每 tick 的 `Vec3`/三角函数与每帧光照查询 — [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/foundation/particle/AirParticle.java:72-74`（`tick`：`Math.pow` + `new Vec3(0, twirlRadius, 0)` + `VecHelper.rotate`）、`:88-91`（`getLightColor`：`BlockPos.containing` + `level.isLoaded` + `LevelRenderer.getLightColor`） |
| 调用频次 | `tick()`：每粒子每 tick；`getLightColor(float)`：**每粒子每帧**（粒子渲染阶段被调用）。粒子由风扇气流/风箱产生，数量随装置规模放大（一个大型风箱阵列可以同时存在数百个 `AirParticle`） |
| 复杂度 / 成本 | 每 tick：1 次 `Math.pow`（等价于 `exp(log)`，很贵）+ `VecHelper.rotate(new Vec3(...), angle, axis)`——后者在本仓库内不可见（catnip 外部 jar），**估计**内部 1 个入参 `Vec3` + 2–3 个中间 `Vec3` + 2 次三角函数。每帧：1 个 `BlockPos`（`BlockPos.containing(double,double,double)`）+ 1 次光照引擎查询。规模放大：数百粒子时每帧数百次光照查询、每 tick 数百次 `Math.pow` + 上千个 `Vec3` |
| 注入方式 | `@ModifyExpressionValue` 包裹 `BlockPos.containing(DDD)`，返回粒子自带的 `@Unique MutableBlockPos`（`MutableBlockPos` 是 `BlockPos` 子类，类型安全）。`tick` 里的 `Vec3` 只能靠 `@Overwrite` 消除（14 行纯数学，风险低但收益也低，不做也可以） |
| 目标成员 | `com.simibubi.create.foundation.particle.AirParticle#getLightColor(F)I`；包裹目标是 `Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;` |
| 需新增状态 | `@Unique` 实例字段 `MutableBlockPos createperf$pos`（每粒子一个）。作用域＝粒子实例；失效＝不需要（每次写入后立即使用）；并发＝`getLightColor` 只在渲染线程调用，`tick` 在客户端线程——**若两者可能并发触碰同一 `MutableBlockPos` 则有竞争**，需要实测确认；保守做法是给 `tick` 也用一个实例字段（两个字段互不干扰） |
| 风险 | 低-中：把 `BlockPos` 换成 `MutableBlockPos` 传给 `LevelRenderer.getLightColor` 是安全的**前提是该调用不保留引用**（vanilla 实现只读坐标；**高概率**，需实测/查源码确认）。若下游把 `pos` 存进 map 作为键，复用会出错——`getLightColor` 与 `level.isLoaded` 都不会 |
| 脆弱度 | 低：`BlockPos.containing(DDD)` 与 `LevelRenderer.getLightColor` 都是稳定 API |
| 置信度 | 确定（行号、调用频次）；`VecHelper.rotate` 内部的分配数为**估计** |

**改前**

```java
// AirParticle.java:62-91（原样摘录）
    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }

        float progress = (float) Math.pow(((float) age) / lifetime, drag);
        float angle = (progress * 2 * 360 + twirlAngleOffset) % 360;
        Vec3 twirl = VecHelper.rotate(new Vec3(0, twirlRadius, 0), angle, twirlAxis);

        float x = (float) (Mth.lerp(progress, originX, targetX) + twirl.x);
        float y = (float) (Mth.lerp(progress, originY, targetY) + twirl.y);
        float z = (float) (Mth.lerp(progress, originZ, targetZ) + twirl.z);
        ...
    }

    public int getLightColor(float partialTick) {
        BlockPos blockpos = BlockPos.containing(this.x, this.y, this.z);
        return this.level.isLoaded(blockpos) ? LevelRenderer.getLightColor(level, blockpos) : 0;
    }
```

**为什么慢**

频次×成本：单粒子成本很小，但粒子是「数量型」负载——风箱阵列/大型风扇一次产生几百个粒子，于是每帧数百次光照引擎查询 + 每 tick 数百次 `Math.pow` 与上千个 `Vec3`。`Math.pow` 在 `tick` 里只是为了算 `progress = t^drag`（`drag` 是常数），完全可以用快速幂/预计算表替代，但那是 `@Overwrite` 的活。

**改后**

```java
// 纯 Java 等价改法
private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

public int getLightColor(float partialTick) {
    scratchPos.set(Mth.floor(this.x), Mth.floor(this.y), Mth.floor(this.z));
    return this.level.isLoaded(scratchPos) ? LevelRenderer.getLightColor(level, scratchPos) : 0;
}
// 注意：BlockPos.containing(d,d,d) 内部就是 Mth.floor 三坐标，语义等价。
```

**Mixin 骨架**

```java
package com.example.createperf.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.foundation.particle.AirParticle;

import net.minecraft.core.BlockPos;

@Mixin(value = AirParticle.class, remap = false)
public abstract class AirParticleMixin {

    @Unique
    private final BlockPos.MutableBlockPos createperf$lightPos = new BlockPos.MutableBlockPos();

    @ModifyExpressionValue(
        method = "getLightColor(F)I",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;"))
    private BlockPos createperf$reusePos(BlockPos original, float partialTick) {
        return createperf$lightPos.set(original);
    }
}
```

> `@ModifyExpressionValue` 的 handler 可以访问 `this`（默认 `AirParticle` 类型），所以 `@Unique` 实例字段可用。注意 handler 签名里可选注入目标方法参数（这里加了 `float partialTick`）——MixinExtras 允许在 `Operation`/原值之后追加目标方法的参数（**需实测确认**；若不允许，删掉该参数即可）。

**验证方法**

- 探针：统计 `getLightColor` 调用次数（= 粒子数 × 帧数）与 `tick` 调用次数。
- 分配观测：JFR/Spark alloc 过滤 `net.minecraft.core.BlockPos`，观察粒子渲染栈下的分配下降。
- 视觉回归：风扇粒子在黑暗/明亮环境下的亮度一致；粒子消失时无异常（`level.isLoaded` 分支）。
- 并发：确认渲染线程调用 `getLightColor` 时 `tick` 不会同时触碰同一实例（若会，给 `tick` 另用一个 `MutableBlockPos`）。

**收益估计**：量级——每粒子每帧省 1 个 `BlockPos`（数百粒子时每帧数百个对象）（**估计**）。`tick` 里的 `Vec3` 与 `Math.pow` 需要 `@Overwrite` 才能动，本条不含。
**回归测试点**：粒子亮度与 `isLoaded` 语义不变；粒子轨迹（`twirl` 半径/轴/相位）不变；粒子渲染类型（`PARTICLE_SHEET_TRANSLUCENT`）不变。

### 14. `TickBasedCache` 每次 put/miss 分配 `MutableInt`，且两套 map 线程语义不一致 — [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/foundation/utility/TickBasedCache.java:33`（`private Map<K, MutableInt> timestamps = new HashMap<>();`）、`:34`（`ConcurrentHashMap` 值表）、`:52`/`:56`/`:57`（`getIfPresent` 同时读写两套 map）、`:77`/`:97`（`timestamps.put(key, now())`）、`:84-86`（`private MutableInt now() { return new MutableInt(ticks()); }`）、`:140-150`（`cleanUp()` 建 `HashSet` + 两次 `forEach`） |
| 调用频次 | 使用点（`grep` 结果）：`LogisticsManager.java:38-39`（`ACCURATE_SUMMARIES`/`SUMMARIES`）、`LogisticallyLinkedBehaviour.java:57,60,96`（每个物流链接行为一个实例）、`ChainConveyorInteractionHandler.java:41`、`ChainConveyorPackage.java:27`、`AddressEditBoxHelper.java:29`、`CardboardArmorHandlerClient.java:32`、`TrainMapSync.java:150`。其中 `SUMMARIES` 的 `getIfPresent` 会在物流查询路径上被高频调用（每次面板/包裹寻址） |
| 复杂度 / 成本 | 每次 `put` 或 `get` 未命中：1 个 `MutableInt`（`:77`/`:97` 经 `now()`）+ 两套 map 的插入。`getIfPresent`（`:51-63`）在没有超时时也要做一次 `ticks()` 与 `intValue()` 拆箱；`cleanUp()` 每次调用建 `HashSet` + 2 个 lambda 对象 + 两轮遍历。**更严重的是语义问题**：值表是 `ConcurrentHashMap`（暗示期望并发），时间戳表却是普通 `HashMap`——两者在 `getIfPresent` 里成对读写，只要有任何一处跨线程使用就是数据竞争（`HashMap` 并发写可造成死循环/丢数据，JDK8+ 至少是丢更新）。本文件**未**逐点追踪调用线程（`TrainMapSync.requestingPlayers` 服务端、`CardboardArmorHandlerClient` 客户端），所以「是否真的跨线程」标**需实测** |
| 注入方式 | `@Overwrite` `getIfPresent`/`get`/`put`/`now`/`cleanUp` 换成「单一 fastutil `Object2IntOpenHashMap`（时间戳）+ `Object2ObjectOpenHashMap`（值）」并去掉 `MutableInt`；见下方骨架。**但**：这属于「收益极小、改动面覆盖一个通用工具类」的典型，建议作为**上游候选**而不是打进补丁包（见文末） |
| 目标成员 | `com.simibubi.create.foundation.utility.TickBasedCache#getIfPresent(Ljava/lang/Object;)Ljava/lang/Object;`、`#get(Ljava/lang/Object;Ljava/util/concurrent/Callable;)Ljava/lang/Object;`、`#put(Ljava/lang/Object;Ljava/lang/Object;)V`、`#cleanUp()V`、`#now()Lorg/apache/commons/lang3/mutable/MutableInt;` |
| 需新增状态 | 替换现有两个私有字段（`timestamps`/`map`）需要 `@Accessor`/`@Shadow` 或直接 `@Overwrite` 全部使用点——**字段类型替换在 Mixin 里不能用 `@Unique` 完成**（`@Unique` 只能新增字段）。可选路径：新增 `@Unique` 的 fastutil 容器做真实存储，把旧的 `map`/`timestamps` 留空（浪费一点内存），并 `@Overwrite` 全部读写方法。这也是本条「不值得做」的原因之一 |
| 风险 | 中：把一个**同时被服务端与客户端**使用的通用缓存整体重写；若某处依赖 `asMap()` 返回 `ConcurrentMap`（`:134-137`）会受影响 |
| 脆弱度 | 中：绑定多个方法的签名与 `Cache` 接口实现 |
| 置信度 | 确定（行号、分配点、`MutableInt` 使用；线程语义不一致是代码事实）；「是否存在真实跨线程调用」**需实测** |

**改前**

```java
// TickBasedCache.java:33-34 / 51-63 / 77 / 84-86（原样摘录）
    private Map<K, MutableInt> timestamps = new HashMap<>();
    private ConcurrentHashMap<K, V> map = new ConcurrentHashMap<>();
    ...
    @Override
    public V getIfPresent(Object key) {
        MutableInt timestamp = timestamps.get(key);
        if (timestamp == null)
            return null;
        if (timestamp.intValue() < ticks() - ticksUntilTimeout) {
            timestamps.remove(key);
            map.remove(key);
            return null;
        }
        if (resetTimerOnAccess)
            timestamp.setValue(ticks());
        return map.get(key);
    }
    ...
            timestamps.put(key, now());
    ...
    private MutableInt now() {
        return new MutableInt(ticks());
    }
```

**为什么慢 / 为什么危险**

性能面：每次写入 1 个 `MutableInt`（+1 次拆箱读取），`cleanUp()` 每次建 `HashSet` 与 2 个 lambda。危险面：`HashMap` 与 `ConcurrentHashMap` 被同一方法成对操作，而类名/字段选择暴露了「作者认为这里是并发的」这一意图——如果真有跨线程调用，`timestamps` 的并发写会静默丢更新，表现为「缓存永不过期」或「提前过期」，是**比性能更值得关注**的正确性隐患。

**改后**

```java
// 纯 Java 等价改法（上游修复建议，见文末候选）
// 用两个 fastutil 容器（或一个 Object2ObjectOpenHashMap<Object, long[]>）替代，
// 时间戳用 int 而不装箱：
private final it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<K> timestamps = new Object2IntOpenHashMap<>();
private final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<K, V> map = new Object2ObjectOpenHashMap<>();

public V getIfPresent(Object key) {
    long k = key.hashCode();                                  // 注意：Object2IntOpenHashMap 已按 equals/hashCode 工作
    if (!timestamps.containsKey(key))
        return null;
    if (timestamps.getInt(key) < ticks() - ticksUntilTimeout) {
        timestamps.removeInt(key);
        map.remove(key);
        return null;
    }
    if (resetTimerOnAccess)
        timestamps.put(key, ticks());
    return map.get(key);
}
// 并显式声明该类「非线程安全」，或整体用 synchronized 包一层（取决于实测结论）。
```

**Mixin 骨架**

```java
// 不推荐为这个类写 Mixin：
// 1) 需要 @Overwrite 至少 5 个方法并新增 @Unique 容器（字段类型无法替换），改动面横跨服务端/客户端；
// 2) 单次收益是「1 个 MutableInt / 次写」，量级在纳秒；
// 3) 真正的价值是「线程语义一致性」，那属于上游设计修复。
// 若你仍要做，形态是：
@Mixin(value = TickBasedCache.class, remap = false)
public abstract class TickBasedCacheMixin {
    @Inject(method = "getIfPresent(Ljava/lang/Object;)Ljava/lang/Object;", at = @At("HEAD"), cancellable = true)
    private void createperf$getIfPresent(Object key, CallbackInfoReturnable<Object> cir) { /* 见 overwrites/（未提供） */ }
}
```

**验证方法**

- 线程归属实测：在你关心的调用点（`LogisticsManager.SUMMARIES`、`CardboardArmorHandlerClient`）用 `@Unique` 记录 `Thread.currentThread().getName()`，确认是否存在渲染线程/服务端线程并行访问同一实例。
- 探针：`@Unique static AtomicLong` 统计 `getIfPresent` 命中/未命中/过期删除次数；再统计 `MutableInt` 分配量（JFR alloc）。
- 功能回归：缓存过期语义（`ticksUntilTimeout` 与 `resetTimerOnAccess` 的两种组合）不变；`size()`（`:123-126`）、`asMap()`（`:134-137`）、`invalidateAll()` 行为不变。

**收益估计**：量级——每次写入省 1 个 `MutableInt`，`cleanUp` 省 1 个 `HashSet` + 2 个 lambda（**估计**）；对热点的影响远小于发现 1/2/3。
**回归测试点**：过期/重置定时语义；`asMap` 返回类型；服务端与客户端各自的 tick 计数源（`TimedCache.tick()` 由 `CommonEvents.java:111` 驱动、`clientTick()` 由 `ClientEvents.java:202` 驱动）不变。

### 15. `SoundScapes.addSound` 每次调用分配 `Pair` 作 map 键 — [Low]

| 项 | 内容 |
| --- | --- |
| 位置 | `src/main/java/com/simibubi/create/foundation/sound/SoundScapes.java:73`（`private static Map<AmbienceGroup, Map<PitchGroup, Set<BlockPos>>> counter = new IdentityHashMap<>();`）、`:104-106`（`tick` 里每 5 tick 双层 `forEach` 清空嵌套 set）、`:109-121`（`addSound`：`:111-112` 两次 `computeIfAbsent`、`:115` `Pair.of(group, groupFromPitch)`、`:116` `activeSounds.computeIfAbsent(pair, ...)`）、`:144-147`（`getAllLocations` 回退） |
| 调用频次 | `SoundScapes.play` 由 `KineticBlockEntity.java:609/613` **每 tick** 调用（`KINETIC` 与 `COG` 各一次），以及 `MillstoneBlockEntity.java:83`、`CrushingWheelControllerBlockEntity.java:273`。`play` 在 `:79` 先做 `outOfRange(pos)`（16 格）过滤，所以命中范围的动力 BE 每 tick 都会走到 `addSound` |
| 复杂度 / 成本 | 每次 `addSound`：1 个 `Pair`（`:115`，**即使 `activeSounds` 已命中也会先分配**）+ 2 次 `computeIfAbsent`（`:111-112`，IdentityHashMap 按键身份比较）+ 1 次 `Set.add` + 1 次 `computeIfAbsent`（`:116`）。`tick` 每 5 tick 双层遍历整个嵌套结构（`:104-106`）。**更正原清单**：`:145-146` 的 `Collections.emptyMap()/emptySet()` 返回的是**单例**，**不分配** |
| 注入方式 | `@WrapOperation` 包裹 `addSound` 内的 `Pair.of(...)`，返回一个缓存过的 `Pair`（`(AmbienceGroup, PitchGroup)` 组合只有 4×5 = 20 种）。**这是本条唯一可 Mixin 且有实际收益的点**（`Pair.of` 是静态工厂方法，不调用它就不会分配——与 `new X()` 不同，手册 3.1 节的陷阱不适用） |
| 目标成员 | `com.simibubi.create.foundation.sound.SoundScapes#addSound(Lcom/simibubi/create/foundation/sound/SoundScapes$AmbienceGroup;Lnet/minecraft/core/BlockPos;F)V`；包裹目标 `Lnet/createmod/catnip/data/Pair;of(Ljava/lang/Object;Ljava/lang/Object;)Lnet/createmod/catnip/data/Pair;` |
| 需新增状态 | `@Unique static final Pair<?, ?>[] createperf$pairs`（长度 4×5 = 20，惰性初始化）或 `@Unique static final java.util.EnumMap`。作用域＝客户端进程级；失效＝不需要（枚举组合固定）；并发＝客户端单线程。**注意**：注入 handler 的返回类型必须声明为 `Pair`（catnip 类型），因此目标 mod 的编译期需要 catnip 依赖（`net.createmod.catnip:catnip` 或经由 Create 的传递依赖） |
| 风险 | 低：`Pair` 仅作为 map 键使用（`activeSounds` 是 `HashMap`，依赖 `Pair.equals/hashCode`）——**必须确认 `Pair` 的 `equals/hashCode` 是按值实现**（catnip 外部 jar；若是按身份实现，原代码每次新 `Pair` 都会 miss，缓存反而**修复**了一个隐藏 bug——两种情况下返回单一实例都更安全，但语义差异需要实测确认） |
| 脆弱度 | 低：`Pair.of` 是 catnip 公共 API；调用点 `:115` 在 `addSound` 内 |
| 置信度 | 确定（行号、每 tick 调用链、`addSound` 的分配点）；`Pair.equals/hashCode` 的实现为**需实测** |

**改前**

```java
// SoundScapes.java:104-121 / 144-147（原样摘录）
        counter.values()
            .forEach(m -> m.values()
                .forEach(Set::clear));
    }

    private static void addSound(AmbienceGroup group, BlockPos pos, float pitch) {
        PitchGroup groupFromPitch = getGroupFromPitch(pitch);
        Set<BlockPos> set = counter.computeIfAbsent(group, ag -> new IdentityHashMap<>())
            .computeIfAbsent(groupFromPitch, pg -> new HashSet<>());
        set.add(pos);

        Pair<AmbienceGroup, PitchGroup> pair = Pair.of(group, groupFromPitch);   // <-- 每次分配
        activeSounds.computeIfAbsent(pair, $ -> {
            SoundScape soundScape = group.instantiate(pitch);
            soundScape.play();
            return soundScape;
        });
    }

    public static Set<BlockPos> getAllLocations(AmbienceGroup group, PitchGroup pitchGroup) {
        return counter.getOrDefault(group, Collections.emptyMap())
            .getOrDefault(pitchGroup, Collections.emptySet());     // 单例，不分配
    }
```

**为什么慢**

频次：范围内的每个动力 BE（转速非 0 的轴/齿轮/研磨机/粉碎轮）每 tick 一次（`KineticBlockEntity` 还会发两次：`KINETIC` 与 `COG`）。成本：1 个 `Pair`（未命中与命中都分配）+ 3 次哈希查找 + 1 次集合插入。规模放大：一个密集机械区里 50 个动力 BE ⇒ 每 tick 约 50–100 个 `Pair` + 150–300 次哈希查找（**估计**），60 tick/s 下即每秒数千个短命对象。这是「数量型」浪费，单次很便宜。

**改后**

```java
// 纯 Java 等价改法：把 20 种组合的 Pair 预先建好（或直接用嵌套 EnumMap 免键）
private static final Map<AmbienceGroup, Map<PitchGroup, SoundScape>> activeSounds = new EnumMap<>(AmbienceGroup.class);

private static void addSound(AmbienceGroup group, BlockPos pos, float pitch) {
    PitchGroup groupFromPitch = getGroupFromPitch(pitch);
    Set<BlockPos> set = counter.computeIfAbsent(group, ag -> new EnumMap<>(PitchGroup.class))
        .computeIfAbsent(groupFromPitch, pg -> new HashSet<>());
    set.add(pos);

    activeSounds.computeIfAbsent(group, ag -> new EnumMap<>(PitchGroup.class))
        .computeIfAbsent(groupFromPitch, pg -> {
            SoundScape soundScape = group.instantiate(pitch);
            soundScape.play();
            return soundScape;
        });
}
// 附带收益：counter 的键是枚举，IdentityHashMap -> EnumMap（数组索引，且不会因身份比较产生歧义）
```

**Mixin 骨架**

```java
package com.example.createperf.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.sound.SoundScapes;

import net.createmod.catnip.data.Pair;

@Mixin(value = SoundScapes.class, remap = false)
public abstract class SoundScapesMixin {

    @Unique private static final Pair<?, ?>[] createperf$pairs = new Pair<?, ?>[20];   // 4 组 x 5 音高

    @WrapOperation(
        method = "addSound(Lcom/simibubi/create/foundation/sound/SoundScapes$AmbienceGroup;Lnet/minecraft/core/BlockPos;F)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/createmod/catnip/data/Pair;of(Ljava/lang/Object;Ljava/lang/Object;)Lnet/createmod/catnip/data/Pair;"))
    private Pair<?, ?> createperf$cachedPair(Object first, Object second, Operation<Pair<?, ?>> original) {
        // AmbienceGroup.ordinal() x PitchGroup.ordinal() 最多 20 种组合；
        // 缓存后 activeSounds 的键实例恒定（若 Pair 按值实现等价，若按身份实现则顺带修掉永不命中）
        int idx = ((Enum<?>) first).ordinal() * 5 + ((Enum<?>) second).ordinal();
        Pair<?, ?> cached = createperf$pairs[idx];
        if (cached == null)
            createperf$pairs[idx] = cached = original.call(first, second);
        return cached;
    }
}
```

> `addSound` 是 `private static`，方法描述符里的内部枚举写作 `SoundScapes$AmbienceGroup`。`Pair` 的返回类型要求编译期可见 catnip（属 Create 的依赖，需在目标 mod 的 `build.gradle` 里显式声明 catnip 依赖或走 `compileOnly`）。

**验证方法**

- 探针：统计 `addSound` 调用次数与 `Pair` 分配次数（JFR alloc 过滤 `net.createmod.catnip.data.Pair`）；期望分配数从「每 tick 每 BE」降到 0。
- 功能回归：环境音仍能起停（`activeSounds` 在 `tick` 的 `:98` 判定 `getSoundCount == 0` 后移除）；同一个 `(组,音高)` 的 `SoundScape` 只被创建一次（`:116-120` 的 `instantiate + play` 不可被重复触发）。
- `Pair` 语义确认：打印 `Pair.of(a,b).equals(Pair.of(a,b))` 为 true（否则原代码的 `activeSounds` 一直在累积废弃条目，属上游 bug，需单独记录）。

**收益估计**：量级——密集机械区每 tick 约 50–100 个 `Pair` 降到 0（**估计**）；`tick` 的双层 `forEach`（每 5 tick）与 `counter` 的 `IdentityHashMap` → `EnumMap` 属附带收益（纳秒级）。
**回归测试点**：环境音的音量/音高分组边界（`getGroupFromPitch` 的 0.70/0.90/1.10/1.30 阈值）不变；`outOfRange` 的 16 格限制不变；`invalidateAll`（`:123-127`）仍移除全部活动音；客户端配置 `enableAmbientSounds` 关闭时 `:90-102` 的清理不变。

## 不可 Mixin / 建议提给上游的候选

以下条目**不建议**（或无法）由本补丁包的 Mixin 修复，建议作为上游 issue / PR 提交。每条都给出「为什么不能 Mixin」与「上游应怎么改」。

| # | 位置 | 问题 | 为什么不由 Mixin 修 | 建议的上游改法 |
| --- | --- | --- | --- | --- |
| U1 | `CouplingHandler.java:57-64` | 三处 `return` 应为 `continue`，导致 `forEachLoadedCoupling` 提前终止（**服务端耦合物理也会静默跳过**） | 本条**我们仍然用 `@Overwrite` 修**（见 `overwrites/CouplingHandler-forEachLoadedCoupling.md`），但根因必须上游修，否则任何「重写本方法」的 mod 都会与上游实现分叉 | 三处 `return` → `continue`；顺带把 `forEachLoadedCoupling` 的注释补上「会跳过对端未加载的 coupling」 |
| U2 | `CarriageCouplingRenderer.java:42`（+ `:100-103`） | `entity2 = carriage.getDimensional(level).entity.get()` 与 `:40` 重复，`entity2` 恒等于 `entity`：`:44` 的空判断成死代码，`:58` 的 `lightCoords2` 取错位置 | **不能简单改**：因为 `position2 == position`，`:100-103` 的 `position2.subtract(position).add(anchor2)` 恰好退化成 `anchor2`，看起来才是本意；只改 `:42` 会把第二个耦合头画到错误位置 | 上游澄清意图：若本意是「用第二节车厢的位置」，则 `:42` 改 `carriage2` **且** `:100-103` 简化为 `anchor2 - camera`（与 `:73` 一致）；若本意就是 `anchor2`，则删除 `position2` 相关计算并修 `lightCoords2` |
| U3 | `SoundScapes.java:73` | 以枚举为键却用 `IdentityHashMap`，应为 `EnumMap`；`activeSounds` 用临时 `Pair` 当键 | 字段类型替换在 Mixin 里做不到（`@Unique` 只能加字段不能换类型），函数式改写别的注入救不了 | `counter` 与 `activeSounds` 都改成 `EnumMap<AmbienceGroup, EnumMap<PitchGroup, ?>>`（顺带确认 `Pair.equals/hashCode` 是否按值实现——若按身份，`activeSounds` 一直在累积废弃条目） |
| U4 | `TickBasedCache.java:33-34,52-63,84-86` | 值表 `ConcurrentHashMap` 与时间戳表 `HashMap` 语义不一致（并发写会丢更新）；`MutableInt` 每次写入都装箱 | 需要 `@Overwrite` 5 个方法 + 新增 `@Unique` 容器（字段类型无法替换），改动面横跨服务端/客户端，收益在纳秒级 | 统一为单一 `Object2IntOpenHashMap` + `Object2ObjectOpenHashMap`（或加 `synchronized` 并显式声明非线程安全）；把 `MutableInt` 换成 `int` |
| U5 | `EntityHats.java:51-63` | 每个坐在 Create 座位上的实体**每帧**做 8 次 `getBlockState` 找票机 | 可以 Mixin 缓存（见发现 11），但「帽子取决于附近哪个票机」这一信息属于 `SeatEntity`/`StockTickerBlockEntity` 的状态，缓存在渲染侧等于把状态放到错误的位置 | 在 `SeatEntity` 上缓存「附近唯一票机」并在票机方块变化/座位变更时失效；或让 `StockTickerBlockEntity` 主动向座位推送帽子 |
| U6 | `TrainHatInfoReloadListener.java:54` | 戴南瓜的雪傀儡每次调用都 `new TrainHatInfo` + `new Vec3` | 可 Mixin（`@Inject(HEAD, cancellable)` 复刻 6 行方法即可，见发现 11），但更该上游修 | 提为 `private static final TrainHatInfo SNOW_GOLEM_HAT`（`TrainHatInfo` 是 record、`Vec3` 不可变，线程安全） |
| U7 | `TrackRenderer.java:134-142` | `shouldRenderOffScreen() == true` + `getViewDistance() == 96*2`：装了 Flywheel 时每个 192 格内的轨道 BE 仍会被 vanilla 调度进 `globalBlockEntities`，然后 `:39` 立刻 return（纯调度浪费） | **不能 Mixin**：`shouldRenderOffScreen` 必须为 true——长曲线的可见中段可能远离它自己的方块位置，改成 false 会让曲线在端点方块离屏时整条消失。这是「正确性优先于性能」的取舍 | 上游可考虑把 Flywheel 判定放进 `shouldRenderOffScreen`（返回 `!VisualizationManager.supportsVisualization(level)`）**同时**保证 `getRenderBoundingBox` 覆盖整条曲线；或者干脆接受这点开销 |
| U8 | `BeltRenderer.java:290` / `TrackRenderer.java:58` | `mc.player.getEyePosition(1.0F)` 位于每物品循环体内（同一 tick 内恒定）；`LevelRenderer.getLightColor` + `BlockPos.offset(BlockPos)` 每段/每物品一次 | 可以 Mixin（发现 5、10 给了方案），但「把不变量提到循环外」属于上游一行改动，收益等同且无 mod 冲突风险 | 在 `renderItems` 里算一次眼位并作为参数传入；在 `renderBezierTurn` 里对 `lightPosition[i].offset(bePosition)` 用复用的 `MutableBlockPos` |
| U9 | `GoggleOverlayRenderer.java:103-183` | 每帧重建整份 tooltip | 可以 Mixin（发现 2），但「缓存按 tick 失效」属于屏幕/覆盖层的自然职责，放在上游更好维护 | 上游在 `renderOverlay` 里按「客户端 tick + 命中方块 + shift 状态」缓存 tooltip 片段 |
| U10 | `TickBasedCache` / `GoggleOverlayRenderer` 之外：`foundation/block/BigOutlines.java:45` | 每个射线步都新建 `MutableBlockPos`（以及 `:61-76` 的 `Vec3.atCenterOf`） | 可 Mixin（发现 4 给了完整方案），但方法只有 40 行，上游一行改动即可 | 用 `@Unique` 等价的静态 `MutableBlockPos` 复用，并把 3×3×3 扫描做成增量切片 |

### 附：与批次路线图的对应建议

- **Batch A（零状态、单点注入，风险最低）**：发现 1 第一步（`replaceAll` → `replace`）、发现 12（专用闪烁 RNG）、发现 15（`Pair` 缓存）、发现 11（雪傀儡常量）、发现 4（`BigOutlines` 整段但无状态）。
- **Batch B（需要 `@Unique` 状态与明确失效时机）**：发现 2（tooltip 每 tick 缓存）、发现 5（光照/眼位记忆化）、发现 9（`getAllWithAir` 每 tick 缓存）、发现 13（`MutableBlockPos` 复用）、发现 6 性能部分（距离剔除）。
- **Batch C（`@Overwrite` 大改造，逐条评审）**：发现 3（`pickCurves`）、发现 6 的 `forEachLoadedCoupling` 修正、发现 8（`maxCraftable`）、发现 7（`CarriageCouplingRenderer`，**前置条件是 catnip 实测**）。
- **不建议进补丁包**：发现 10（Flywheel 门控下收益为 0）、发现 14（收益纳秒级、改动面横跨双端）、U1/U2/U3/U4/U5/U6/U7 各条的「根因修复」。

### 附：本领域产出的 overwrites 文件

- `overwrites/TooltipHelper-cutTextComponent.md`
- `overwrites/TrackBlockOutline-pickCurves.md`
- `overwrites/BigOutlines-pick.md`
- `overwrites/BeltRenderer-renderItem.md`
- `overwrites/CouplingRenderer-renderAll.md`
- `overwrites/CouplingHandler-forEachLoadedCoupling.md`
- `overwrites/CarriageCouplingRenderer-renderAll.md`（仅条件性方案）
- `overwrites/StockKeeperRequestScreen-maxCraftable.md`
- `overwrites/BacktankUtil-getAllWithAir.md`
- `overwrites/TrainHatInfoReloadListener-getHatInfoFor.md`




