# Overwrite 施工图：`TooltipHelper.cutTextComponent`

> 目标方法：`com.simibubi.create.foundation.item.TooltipHelper#cutTextComponent(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;I)Ljava/util/List;`
> 对应发现：[07-client-render-ui.md](../07-client-render-ui.md) 发现 1（High）
> 公共约定见 [01-mixin-playbook.md](../01-mixin-playbook.md)

## 1. 为什么需要 Overwrite

把 `word.replaceAll("_", "")` 换成 `word.replace("_", "")` 只需要一个 `@WrapOperation`（Batch A，见主文件的 Mixin 骨架），**不需要 Overwrite**。需要 Overwrite 的是「布局只跑一遍 + 按输入串缓存布局」这一档收益：

- 两个 `LinkedList`（`:84`、`:95`）与每词一个 Node 在方法体内部构造，无法用 `@ModifyExpressionValue` 去掉（`NEW` 先于 `INVOKESPECIAL` 执行，手册 3.1 / 3.2 节）；
- 缓存要吞掉「`BreakIterator` + 每词 `replace` + 每词宽度测量」，这些是方法体的主干语句，只能重写。

**注意**：本方案**不缓存 `Component` 本体**。理由见第 5 节：`CreateLang.itemName` 对自己的新对象都要先 `.copy()` 再交给 `LangBuilder.add`，强烈暗示 `add` 会就地写传入的 Component；缓存 Component 会让这类调用污染缓存。因此缓存的是「布局」（行字符串 + 每行按 `_` 切好的数组），Component 每次重建。

## 2. 原方法完整源码（`TooltipHelper.java:79-137`，逐字摘录）

```java
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
			int newWidth = font.width(word.replaceAll("_", ""));
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

（`:127` 的 `String.split("_", -1)` 走 JDK 单字符非元字符快路径，**不**编译正则——不要为了它引入额外优化，但缓存 `String[]` 也无害。）

## 3. 改后完整可粘贴方法体

```java
	// ===== 以下整体放在一个 @Mixin(value = TooltipHelper.class, remap = false) 的 class 里 =====

	@Overwrite
	public static List<Component> cutTextComponent(Component c, Style primaryStyle,
		Style highlightStyle, int indent) {
		String s = c.getString();
		Layout layout = createperf$layoutFor(s, indent);

		// Format（与原文逐行等价：lineStart 只建一次并 withStyle 后 plainCopy）
		MutableComponent lineStart = Component.literal(Strings.repeat(" ", indent));
		lineStart.withStyle(primaryStyle);
		List<Component> formattedLines = new ArrayList<>(layout.lines().size());
		Couple<Style> styles = Couple.create(highlightStyle, primaryStyle);

		boolean currentlyHighlighted = false;
		List<String> lines = layout.lines();
		String[][] partsByLine = layout.parts();
		for (int li = 0; li < lines.size(); li++) {
			MutableComponent currentComponent = lineStart.plainCopy();
			String[] split = partsByLine[li];
			for (String part : split) {
				currentComponent.append(Component.literal(part).withStyle(styles.get(currentlyHighlighted)));
				currentlyHighlighted = !currentlyHighlighted;
			}

			formattedLines.add(currentComponent);
			currentlyHighlighted = !currentlyHighlighted;
		}

		return formattedLines;
	}

	@Unique
	private static Layout createperf$layoutFor(String s, int indent) {
		Key key = new Key(s, indent);
		Layout cached = CREATEPERF_LAYOUTS.get(key);
		Font font = Minecraft.getInstance().font;
		// 字体实例在资源重载时会被替换 -> 用它做缓存有效性判据（比监听 ResourceManager 更省事且不会漏）
		if (cached != null && cached.font() == font)
			return cached;

		Layout computed = createperf$computeLayout(s, font);
		CREATEPERF_LAYOUTS.put(key, computed);
		return computed;
	}

	@Unique
	private static Layout createperf$computeLayout(String s, Font font) {
		// 单遍完成：BreakIterator 一边切词一边折行（原文是"先建 words 再折行"两次遍历，
		// 但折行只用 words，不修改它，所以单遍结果逐字等价）
		List<String> lines = new ArrayList<>(4);
		BreakIterator iterator = BreakIterator.getLineInstance(Minecraft.getInstance().getLocale());
		iterator.setText(s);
		StringBuilder currentLine = new StringBuilder();
		int width = 0;
		int start = iterator.first();
		for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
			String word = s.substring(start, end);
			int newWidth = font.width(word.replace("_", ""));      // 修复点：不再编译正则
			if (width + newWidth > MAX_WIDTH_PER_LINE) {
				if (width > 0) {
					lines.add(currentLine.toString());
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
		if (width > 0)
			lines.add(currentLine.toString());

		String[][] parts = new String[lines.size()][];
		for (int i = 0; i < lines.size(); i++)
			parts[i] = lines.get(i).split("_", -1);

		return new Layout(font, lines, parts);
	}
```

## 4. 需要的 `@Unique` 字段 / helper / import

```java
package com.example.createperf.mixin.client;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import com.google.common.base.Strings;
import com.simibubi.create.foundation.item.TooltipHelper;

import net.createmod.catnip.data.Couple;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

@Mixin(value = TooltipHelper.class, remap = false)
public abstract class TooltipHelperOverwriteMixin {

    @Unique private static final int CREATEPERF_MAX_ENTRIES = 256;

    @Unique
    private static final Map<Key, Layout> CREATEPERF_LAYOUTS =
        new LinkedHashMap<>(64, .75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Layout> eldest) {
                return size() > CREATEPERF_MAX_ENTRIES;
            }
        };

    @Unique private record Key(String text, int indent) {}

    @Unique private record Layout(Font font, List<String> lines, String[][] parts) {}
    // 注：@Unique 只能标在方法/字段上，嵌套 record 直接声明即可（Mixin 允许 mixin 类带内部类型）。

    // 下面是第 3 节的两个方法 + @Overwrite 本体（略去重复）
}
```

- **字段生命周期**：`作用域`＝客户端进程级静态（`CREATEPERF_LAYOUTS`）；`失效时机`＝键里含「已解析字符串」（语言/资源包切换会换串 ⇒ 自动 miss）+ 值里存 `Font` 实例、每次命中都比对 `Minecraft.getInstance().font`（字形宽度变化会换 Font 实例 ⇒ 自动 miss）+ LRU 上限 256；`并发`＝只在客户端主线程与渲染线程调用（MC 客户端顺序执行 tick 与帧），无需 `volatile`/同步（**若日后出现跨线程调用，需改为 `ConcurrentHashMap` + 无 LRU，或用 `Collections.synchronizedMap`**）。
- **不需要新增第三方依赖**；`MAX_WIDTH_PER_LINE` 直接引用 `TooltipHelper.MAX_WIDTH_PER_LINE`（public static final，本仓库 `:22`）。

## 5. `@Overwrite` 风险

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| 多 mod 冲突 | `@Overwrite` 会顶掉其它 mod 对该方法的注入（若它们也 Overwrite 同一方法，后加载者生效并有 conflict 日志） | 只覆盖「4 参重载」这一个方法；在本仓库内它是唯一的实现体（另外两个重载都委托到它），因此覆盖面最小。若发现 `-Dmixin.debug` 报 conflict，退回到「Batch A 的 `@WrapOperation` + 不缓存」 |
| 语义漂移 | 折行、`_` 高亮交替、`indent`、超长单词分支都必须逐字一致 | 第 3 节的实现与原文逐步对齐；验证方法给出「逐字节比较输出」的做法 |
| 缓存污染 | 若缓存的是 `Component`，下游 `LangBuilder.add` 一旦就地修改就会污染缓存 | **本方案不缓存 Component**，每次重建；见下节回归测试点 |
| 上游重构 | `cutTextComponent` 签名/内部实现变化会让 `@Overwrite` 在启动期报 `defaultRequire = 1` 失败 | 这是期望行为（不静默） |
| 内存 | 256 条 ×（行字符串 + split 数组），量级几十 KB | LRU 上限；值里不持有 `Level`/`Entity` 引用（只持有 `Font`，Font 是单例且随重载替换） |

## 6. 回归测试点（逐项手动过）

1. **逐字节输出等价**：在同一个 `(Component, primaryStyle, highlightStyle, indent)` 输入集合上，比较 `@Overwrite` 前后返回的 `List<Component>`——把每个元素 `getString()` 与 `getStyle()` 依次打印（或直接 `toString()`）逐项比对。建议构造：纯英文、含 `_` 高亮、含中文（BreakIterator 行为不同）、含超长单词（>200 px）、含连续多个 `_`（`split` 保留空串）、空字符串、`indent = 0/1/4`。
2. `MAX_WIDTH_PER_LINE = 200` 边界：构造刚好 200 px 与 201 px 的行。
3. 语言切换：游戏内切中/英（`Options → Language`），确认提示立即以新语言显示（缓存按已解析串作键）。
4. **回调污染检验（决定第 2 阶段是否可以缓存 Component）**：临时把 `Key` 改成只含 `(primaryStyle, highlightStyle, indent)` 而 `Layout` 缓存 Component，观察护目镜 tooltip 是否出现「重复拼接/样式串味」。若出现，证明 `LangBuilder.add` 会就地修改传入 Component，必须保持「不缓存 Component」。
5. 视觉：`ItemDescription`（shift/ctrl 展开）、方块护目镜提示、`AbstractFilterScreen`、`SchematicannonScreen`、`LinkedControllerScreen` 的 tooltip 外观与改前一致。
6. 性能：见主文件发现 1 的验证方法（探针 + JFR `Matcher`/`Pattern` 分配 + Spark 火焰图）。
