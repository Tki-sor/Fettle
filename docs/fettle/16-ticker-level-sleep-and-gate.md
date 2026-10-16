# 16 · 下沉到 ticker 层（覆盖第三方 mod）+ 真·空转类完整试点 + 行为门禁

## 16.1 沉睡拦截下沉到 `LevelChunk$BoundTickingBlockEntity`

### 第一次尝试失败，而且**从字节码里抓到了**
先按"重定向 ticker 调用"写（`@Redirect` 到 `BlockEntityTicker.tick(...)`）。编译通过、Mixin 也报 "Mixing ... into LevelChunk$BoundTickingBlockEntity"，
但导出字节码显示：**handler 是死代码**（`tick()` 里偏移 118 处仍是原始 `invokeinterface`，没有任何 fettle 调用），而且因为 `defaultRequire: 0` **静默跳过**，日志里没有报错。

> 这正是"不信日志、看字节码"的价值：只看日志会以为已经生效。

### 改成 HEAD 注入（已验证生效）
```java
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BlockEntityTickerMixin {
    @Shadow private BlockEntity blockEntity;   // 官方映射字段名

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void fettle$observeAndMaybeSkip(CallbackInfo ci) {
        if (HotFlags.OBSERVE) BehaviourProbe.tick(blockEntity.getClass());
        if (!BeSleepController.shouldTick(blockEntity)) ci.cancel();
    }
}
```

**为什么这一层能覆盖第三方 mod**：区块持有的 ticker 列表不关心是哪个 mod 注册的 —— 任何 mod 的方块实体都从这里派发。
状态也从"Create 的 `SmartBlockEntity`"下移到**所有 `BlockEntity`**（新增 `BlockEntitySleepStateMixin`）。

**实测覆盖**（观察层，纯 vanilla 类）：
```
observed classes=4 never-changed=4
  MechanicalCrafterBlockEntity   ticks=764166  changed/tick=0.0000
  HopperBlockEntity              ticks=121200  changed/tick=0.0000   ← 纯 vanilla，非 Create
  FurnaceBlockEntity             ticks=1212    changed/tick=0.0000
  BellBlockEntity                ticks=909     changed/tick=0.0000
```

## 16.2 对"真正空转"的类跑完整试点：`HopperBlockEntity`

**选它的理由**：它是原版类（证明第三方覆盖有效），且空漏斗每 tick 都在 tick、却**什么都不改**（`changed/tick = 0.0000`，40 万次 tick 零变化）。

### 实测（400 个空漏斗，observeTicks=40、maxSkipTicks=20）

```
sample A: ticks=26800  skipped=214400 heartbeats=11200 wakes=0 blacklist=0
sample B: ticks=34800  skipped=367600 heartbeats=19200 wakes=0 blacklist=0
观察计数: HopperBlockEntity ticks=404800 changed/tick=0.0000
```

- **稳态跳过率 = 19 跳 : 1 心跳 = 95%**（用两个采样点做差：`Δskipped/Δticks = 153200/8000 = 19.15`，与设计值 20-1 完全一致）；
- 采样 A 里比率偏低只是因为**还处于前 40 tick 的观察期**（观察期内必须照常 tick）；
- **零异常、零拉黑**；`/fettle idle wake` 一键回退后 `enabled=false`。

### 顺带修掉一个由数据暴露的设计缺陷
第一版心跳会把实例**重新变回醒着并清零观察计数** → 每个周期变成"跳 19 次 + 心跳 1 次 + 重新观察 40 次"，实测跳过率只有 **33%**。
改成"心跳只跑一次、实例保持沉睡"后，稳态跳过率 **95%**。若不是看数字，这个缺陷会被"机制跑通了"掩盖过去。

## 16.3 行为门禁

`./gradlew tasks --all` 里**只有 `runData` 和 `runServer`** —— 这个 MDG 配置**没有 `runGameTestServer` 任务**（直接跑会报 `Task 'runGameTestServer' not found`）。

因此把门禁实现成等价的、自动化且更贴合本项目的形式：`tools/fettle_gate.py` ——
**无人值守启动服务端（无任何 JVM 开关），然后断言我们所有优化项的不变量**：

| 检查 | 断言 |
| --- | --- |
| 配方索引一致性 | `parity self-test: checked=1211 skipped=340 mismatches=0` |
| 列车碰撞候选过滤 | `fresh … mismatches=0` 且 `stale_guarded … mismatches=0 missed=0` |
| 动力学增量应力 | `stress=[n=27 mismatches=0]` 且 `afterReconcile=[n=3 mismatches=0]` |

**实测结果（exit code 0 = 通过）**：
```
[gate] kinetic incremental: members=201 stress=[n=27 mismatches=0] speedViaNetwork=[n=15 mismatches=10] \
       rawSpeedWrite=[n=27 mismatches=18] afterReconcile=[n=3 mismatches=0]
[gate] collisionDifferential: fresh=[n=24 collisions=3 mismatches=0] stale_guarded=[n=24 … mismatches=0 missed=0]
[gate] recipe parity self-test: checked=1211 skipped=340 mismatches=0
[gate] PASS        (EXIT=0)
```

> 门禁**故意保留**了动力学"转速窗口"那条已知且已文档化的差异（`speedViaNetwork mismatches=10`）在输出里，而不是断言它——该窗口由每 tick 对账兜住，且已确认无法用 setter 钩子消除（见 [15](15-finalisation-and-sleep-pilot.md)）。

## 16.4 全部走配置，无 JVM 参数

本轮新增/使用的开关全部是配置项，可用 `/fettle feature <名> <值>` 运行时切换：
`observe`、`benchProbes`、`debugPanelKeepAlive`、`mixinLog`，以及沉睡试点的 `/fettle debug sleep <类> <观察tick> <最大跳过>`、`/fettle idle wake`。

## 16.5 复现

```bash
# 完整行为门禁（无人值守，退出码 = 失败项数）
python tools/fettle_gate.py

# 手动跑一次沉睡试点（原版空漏斗，覆盖非 Create 的 BE）
fettle feature observe true
execute positioned 0 100 4000 run fettle debug hoppers 20
fettle idle report
fettle debug sleep HopperBlockEntity 40 20
fettle idle sleeptest        # 期望 Δskipped/Δticks ≈ 19
fettle idle wake
```

