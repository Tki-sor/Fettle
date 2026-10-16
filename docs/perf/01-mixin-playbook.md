# Mixin 落地手册（Create 性能补丁）

> 本文件是 `docs/perf/*` 中所有「修复方案」的公共约定。任何一条 finding 给出的 Mixin 代码都必须符合本手册；
> 与手册冲突的写法视为未完成。目标：**一个独立的 NeoForge 1.21.1 Mixin mod**，在加载期改写 Create 6.0.10 的热路径。

## 0. 硬事实（已核实，不要推翻）

| 事实 | 出处 |
| --- | --- |
| MC `1.21.1` / NeoForge `21.1.219` | `gradle.properties:8,18` |
| MixinExtras `0.5.4` 可用（`io.github.llamalad7:mixinextras-common`） | gradle 缓存；NeoForge 运行时内置 |
| Create 自己的 mixin 配置：`required: true`, `priority: 1000`, `plugin: CreateMixinPlugin` | `src/main/resources/create.mixins.json` |
| Create 的类**未被混淆**（mod 类，非 vanilla） | 因此注入 Create 类必须 `remap = false` |
| 本仓库无 `.git` | 无法 diff 上游，行号以 6.0.10 源码为准 |

## 1. 目标 mod 骨架

```
com.example.createperf.CreatePerfMod        // @Mod, neoforge.mods.toml 里声明对 create 的硬依赖
com.example.createperf.mixin.<领域>.*       // 所有 @Mixin 类
src/main/resources/createperf.mixins.json
```

`createperf.mixins.json` 模板（客户端专用注入放到 `client` 数组，避免专用服务端类加载期崩）：

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "com.example.createperf.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": ["trains.TrackGraphMixin", "fluids.FluidTransportBehaviourMixin"],
  "client": ["client.SchematicHandlerMixin"],
  "injectors": { "defaultRequire": 1 }
}
```

- ⚠️ **mixin 配置的声明位置（已实测踩坑）**：NeoForge 1.21.1（FML 4.0.42）**只**从 `neoforge.mods.toml` 的 `[[mixins]]` 段读取 mixin 配置：
  ```toml
  [[mixins]]
  config = "createperf.mixins.json"
  ```
  只写 jar manifest 的 `MixinConfigs` **在开发环境完全无效**（连 "Selecting config" 都不会出现），`-Dmixin.config` 也会被 FML 覆盖。证据：`javap -c net.neoforged.fml.loading.moddiscovery.ModFileParser` 里 `ldc "mixins"` → `getConfigList(["mixins"])` → `MixinConfig(config, requiredMods)`。详见 `docs/fettle/04-implementation-status.md` §2。
- `injectors.defaultRequire = 1`：注入点找不到时**启动即报错**，而不是静默失效。配合下面的「脆弱度」一栏使用。
- 你的 mixin `priority` 建议**显式加大**（如 `"priority": 1100`），保证在 Create 自身 mixin 之后应用，否则你 `@Redirect` 的调用点可能先被 Create 的 `@Redirect` 拿走。
- 两条 `@Redirect` 抢同一个调用点 = 后者失效（Mixin 会记录 conflict，但不会崩），所以**优先 MixinExtras**（见 §2）。

## 2. 注入方式选择（按优先级）

| 优先 | 注解 | 用途 | 为什么优先 |
| --- | --- | --- | --- |
| 1 | `@ModifyExpressionValue` | 改一个**表达式的结果**（常量、字段读取、`new`、方法调用返回值） | 精确、可组合、不抢调用点 |
| 2 | `@WrapOperation` | **包裹**一次调用/字段访问，可在条件分支里决定是否调用原逻辑 | 多 mod 共存时最稳（MixinExtras 的核心价值） |
| 3 | `@Inject` (+ `cancellable = true`) | 方法头/尾部插入逻辑、**短路整个方法** | 官方 API，适合「提前返回 / 缓存命中」 |
| 4 | `@Redirect` | 替换一次调用 | 只能有一个 mod 生效；参数仍会被求值（见 §3 陷阱） |
| 5 | `@Overwrite` | 整体重写方法 | 最激进，和上游改动、其它 mixin 冲突面最大；只在收益足够大时用 |
| 6 | `@Accessor` / `@Invoker` | 读私有字段 / 调私有方法 | 你的 mixin 类不在目标包内时的唯一正路 |

### 目标描述符
Create 类 `remap = false`，方法名直接用源码名；**参数类型必须写方法描述符**（obf 无关但 Mixin 需要精确匹配）：

```java
@Mixin(value = com.simibubi.create.content.trains.graph.TrackGraph.class, remap = false)
public abstract class TrackGraphMixin {
    @Inject(method = "getConnectionsFrom(Lcom/simibubi/create/content/trains/graph/TrackNode;)Ljava/util/Map;",
            at = @At("HEAD"), cancellable = true)
    private void createperf$fastGetConnectionsFrom(TrackNode node, CallbackInfoReturnable<Map<TrackNode, TrackEdge>> cir) { ... }
}
```

## 3. 「消除一次分配」的三个陷阱

以 `TrackGraph.getConnectionsFrom` 为例：

```java
return connectionsByNode.getOrDefault(node, new HashMap<>());   // 命中路径也 new 一个 HashMap
```

1. **`@Redirect` 在 `getOrDefault` 上无效**：handler 拿到的参数里，`new HashMap<>()` **已经在调用前构造好了**。分配依旧发生，只是白改。
2. **`@ModifyExpressionValue` 修饰 `new HashMap<>()` 会生成 `checkcast HashMap`**：返回 `Collections.emptyMap()` 会在运行期 `ClassCastException`（`emptyMap` 不是 `HashMap`）。要返回共享默认值时，只能返回一个**共享且绝不被写入的 `HashMap` 实例**，并确认调用方不会改它。
3. **正确做法**是短接整个方法：`@Inject(at = @At("HEAD"), cancellable = true)` 直接 `cir.setReturnValue(connectionsByNode.get(node))`（`@Inject` 需要私有成员时配 `@Accessor`），或 `@Overwrite`。对于 3 行的 getter，`@Inject` 比 `@Overwrite` 更抗上游改动。

**通用判据**：分配的表达式在调用点内 → 短接/重写方法；分配是方法体中的独立语句（字段初始化、临时变量）→ `@ModifyExpressionValue` 或 `@Inject` 改写局部变量。

## 4. `@Unique` 缓存字段的生命周期

缓存类修复必须回答三个问题，写在 finding 的「失效时机」一栏：

1. **作用域**：per-instance（`@Unique` 字段）还是全局（静态 `@Unique` 字段，多线程注意）；
2. **失效条件**：写清`谁改数据、什么时候失效`（如 `addNode/removeNode/connect/disconnect` 上 `@Inject` 清缓存、或校验 `graph.revision`/`size()`）；
3. **并发**：服务端主线程 + 客户端渲染/主线程各自持有；跨线程共享的缓存必须有 volatile 或只在单侧使用。

```java
@Mixin(value = TrackGraph.class, remap = false)
public abstract class TrackGraphCacheMixin {
    @Unique private final Map<TrackNode, Map<TrackNode, TrackEdge>> createperf$cache = new IdentityHashMap<>();
    // @Inject 到所有会改 connectionsByNode 的方法里清空 createperf$cache
}
```

## 5. `@Overwrite` 使用规范

1. 只在 finding 标了「Overwrite 候选」时使用；`docs/perf/overwrites/*.md` 会给**完整可粘贴方法体**。
2. Overwrite 必须**保持原语义**（含提前返回、事件触发、方块更新广播、随机数消耗顺序、`sendData/notifyUpdate` 调用次数）—— 语义变更会让存档行为漂移，属于 bug 不属于优化。
3. 与 Create 自带 mixin 的重叠点先确认：`src/main/resources/create.mixins.json` + `foundation/mixin/CreateMixinPlugin` 里列出了 Create 注入的原版方法；**不要 Overwrite 已被 Create 注入的方法**（否则 Create 的注入全部丢失）。
4. 每个 Overwrite 都要在 `docs/perf/00-overview-and-roadmap.md` 的路线图里登记「回归测试点」。

## 6. 验证手段（每条 High finding 必附）

1. **注入是否生效**：开发环境加 `-Dmixin.debug.verbose=true -Dmixin.debug.export=true`（Create 自己的 dev run 已开，见 `build.gradle:61`），检查 `run/.mixin.out/` 里导出后的类。
2. **探针**：`@Unique` 静态计数器 + `System.nanoTime()` 累加，每 100 tick 输出一次到 logger；对比开/关你的 mixin 两次。
3. **分配量**：`-XX:+UnlockDiagnosticVMOptions` + JFR `ObjectAllocationSample`，或 Spark `/spark profiler start --alloc`；关注 `java.util.HashMap`、`java.util.ArrayList`、`it.unimi.dsi.fastutil.*`。
4. **端到端**：Spark `/spark tps` + `/spark profiler --timeout 60`（对照场景：加载同一存档、同一 contraption/列车规模，前后各跑 3 次取中位数）。
5. 任何「估计值」必须写明是估计 —— 文档里的收益数字默认是**静态推理的量级**，不是实测。

## 7. 反模式（会被判为不合格的写法）

- 注入 Create 类却漏写 `remap = false` → 生产环境静默注入失败。
- 用 `@Redirect` 去"消除"参数表达式里的分配（§3.1）。
- 在 `@Unique` 缓存里存 `Entity`/`Level` 强引用且不清理 → 内存泄漏（真实性能退步）。
- 把 `AllConfigs.server().xxx.get()` 缓存到静态字段而不监听配置重载 → 用户改配置不生效。
- 修改原语义换性能（少发一次 `sendData`、少一次方块更新）却未在文档中显式声明为**行为变更**。
