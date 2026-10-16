# 21 · A8 `BeltBlock#hasShaftTowards` 实测：**无收益**

## 21.1 结论先说

**A8 没有可测量的收益**。原版（`Optional` 路径）与 Fettle（`instanceof` 路径）在 200–400 万次调用的微基准里**落在同一区间**：

| 路径 | ns/op（三次独立运行） |
| --- | --- |
| 原版（`create.beltShaftLookup=false`） | **9.14 / 12.09 / 11.49** |
| Fettle（`=true`，默认） | **10.17 / 11.15** |

差值（< 1 ns/op）**小于轮间抖动**（同一配置两次运行就能差 3 ns），因此判为**无收益**。
原因基本可以确定：原版那行 `getBlockEntityOptional(...).map(...).orElse(false)` 里的 `Optional` 是**短生命周期、不外逃**的，
HotSpot 的**逃逸分析**会把它标量替换掉，分配开销在编译后等于不存在。

## 21.2 测量方法（可复现）

微基准 `beltShaft`（本轮新增）自行放置一条真实皮带（`SLOPE=HORIZONTAL, PART=START, CASING=false, FACING=EAST`）与强制加载区块，
热身后循环调用 `BeltBlock#hasShaftTowards`，输出 ns/op；因为该 mixin 是 `@Overwrite`（装载期由 `create.beltShaftLookup` 决定是否应用），
所以 A/B 采用**改配置 + 重启**的方式，而不是 `/fettle feature`：

```bash
# 1) create.beltShaftLookup = true（默认）
./gradlew runServer
fettle micro beltShaft 2000000
# 2) 改成 false，重启
fettle micro beltShaft 2000000
```
输出形如：`beltShaft: 10.17 ns/op (2000000 iterations) blockEntity=true result=false`

## 21.3 处理

保留该 mixin（行为与原版等价、无风险），但**不再作为卖点**；若未来 Create 变更导致这个 `@Overwrite` 冲突，直接删除即可，无功能损失。
台账见 [20-status-ledger.md](20-status-ledger.md)。
