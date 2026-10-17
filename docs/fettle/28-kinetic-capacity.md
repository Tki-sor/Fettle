# 28 · C2 动能容量侧增量：**前提被实测修正，收益是微秒级，默认关**

## 28.1 先说最重要的：scout 的对称假设只对了一半

C2 的设想是"把已验证 213× 的应力增量方案对称搬到容量侧"。实测推翻了对称性：

- `KineticNetwork#members`（应力路径遍历的）= **网络里所有成员**（实测 **201** 个）；
- `KineticNetwork#sources`（容量路径遍历的，`calculateCapacity()` 迭代的就是它）= **提供容量的源**，
  即马达/发电机一类，实测 **1** 个。

所以 `updateCapacityFor` 的原版全量扫描本来就是 O(容量源数量) ≈ 1–3 次循环，
**根本不存在"201 个成员全扫"那笔开销**——应力侧能拿 213×，容量侧拿不到。

## 28.2 实现与实测

仍按对称方案实现了 `@Overwrite updateCapacityFor`（首见成员回退全量；之后 O(1) 差值；每 tick 一次对账重扫），
新增 `create.kineticIncrementalCapacity` 开关与 `/fettle micro kineticCapacity` 微基准。

| 状态 | us/op（sources 数 = 1） |
| --- | --- |
| 增量开 | 0.075 / 0.153 |
| 增量关（原版全扫） | 0.198 / 0.234 |

→ 约 **2×**，但绝对值是 **0.2 微秒 → 0.08 微秒**。对照同一网络上的应力侧：

```
[Fettle] kineticStress: members=201 iters=200000 us/op=0.148 ...
[Fettle] kineticCapacity: 0.075 us/op (members=1, 200000 iterations, incrementalCapacity=true)
```

（`kineticCapacity` 里的 `members=1` 就是 `sources` 的大小——这也正是 28.1 的证据。）

## 28.3 门禁

```
[Fettle] kinetic incremental: members=201 stress=[n=27 mismatches=0] speedViaNetwork=[n=15 mismatches=10]
  rawSpeedWrite=[n=27 mismatches=18] afterReconcile=[n=3 mismatches=0] incremental=27 reconciles=1 syncs=26
[gate] PASS
```

注意 `incremental=27` **没有变化**：门禁的动能差分主要在驱动**应力**变化，
**容量路径没有被差分真正跑到**。也就是说这次 PASS 只证明"没有回归"，不证明"容量和原版逐值一致"。

## 28.4 决定：默认关（保留代码，等值级 parity 验证）

理由三条：

1. **收益是微秒级**（0.12 µs/次），对 MSPT 无意义；
2. **风险不小**：容量决定过载判定，若运行和漂移会导致"该过载的没停"；
3. **值级一致性没测过**：差分没覆盖容量路径，我们对"增量和对得上 `calculateCapacity()`"只有结构论证。

所以 `create.kineticIncrementalCapacity` 默认 **false**（面板节流也是同样的处理方式），
并已写明：要翻默认值，先补一个"交替改容量值 N 次后，把运行和与 `calculateCapacity()` 全扫结果对账"的差分。

## 28.5 结论

- scout 把容量侧当应力侧的对称项，**前提错了**：遍历集合的规模差 200 倍；
- 有价值的部分是"容量源数量"这个量级认知：马达多的网络才有意义，而那类网络的容量源仍是十位数以内；
- C2 的"对账降频"（第 3 步）**不做**：分母本来就只有 1–3 次循环，降频毫无意义。
