# 00 · 命名与定位（Fettle）

> 状态：**已定名**。下一步是 Modrinth / CurseForge 建项时按本文的标识符一次性对齐。
> 本文件只记录名称裁定、查证证据与命名规范，不含代码、不含工程骨架。

## 1. 最终命名

| 项 | 值 | 说明 |
| --- | --- | --- |
| 显示名 | **Fettle** | 英习语 *in fine fettle* = 状态极佳、精神抖擞 |
| mod id | `fettle` | 全小写、无数字、无下划线；**发布后永不可改**（改了会导致存档/配置/数据包失效） |
| 包名 | `com.tkisor.fettle` | 按作者命名空间 |
| Mixin 包 | `com.tkisor.fettle.mixin.<领域>` | 领域 = `recipe` / `transfer` / `diagnostics` / `compat` |
| Mixin 配置 | `fettle.mixins.json`、`fettle.client.mixins.json` | 客户端专用注入放 client 配置，避免专用服务端类加载期崩溃 |
| 资源命名空间 | `fettle` | `assets/fettle/**`、`data/fettle/**` |
| GitHub 仓库 | `Fettle` | 仓库名可与 mod id 大小写不同 |
| Modrinth slug | `fettle` | 需与 mod id 一致，便于 `/modrinth fettle` 类检索 |
| CurseForge slug | `fettle`（**待手动建项时确认占用**） | 见 §3 的尾巴 |

**副标题（英文）**：*Cross-mod shared optimizations for Minecraft — recipe indexing & transfer acceleration.*

**一句话简介（中文口径）**：**把多个 mod 重复计算的同一件事，算一次、大家共用。**

**中文名**：**不另起音译**，直接用 **Fettle**；中文语境用上面那句口径解释即可（音译"费特尔"只会增加记忆负担）。

## 2. 命名依据（为什么是 Fettle）

查证方法：Modrinth `/v2/search` 模糊 + `/v2/project/<slug>` 精确、CurseForge 精确 slug（经 cfwidget，官网被 Cloudflare 挡）、MC百科 `search.mcmod.cn`、GitHub `search/repositories?q=<name> in:name`。

| 平台 | 结果 |
| --- | --- |
| Modrinth | `query=fettle` → **total_hits = 0**（本次由 Lead 独立复核，非子代理转述） |
| GitHub | `fettle in:name` → **118 个仓库，头部 mehatab/fettle（GitHub 状态页）等，无 MC 相关**（Lead 独立复核） |
| MC百科 | 模组 0 命中 |
| CurseForge | slug `fettle` 404（子代理查证；**Lead 未能独立复核**，见 §3） |
| 商标 / 语义 | 无商标冲突；*in fine fettle* 是正面习语，语义直接对应"把包保持在最佳状态" |
| 可读性 | 6 字母、单一拼法、中英玩家都不会拼错；不含 `Minecraft`/`Create` 等商标词 |

### 为什么不选其它候选

| 候选 | 淘汰/降级原因 |
| --- | --- |
| **KinetiCore** | ❌❌ 最危险：GitHub 上有 `KvadroKubik/kineticore`，README 原文 *"KinetiCore optimisation mod for minecraft"* —— **同名 + 同类目**；CurseForge 另有仅差一字母的 `KineticCore`（自成一个 mod 家族）；还有 Specialized 自行车轮胎商标 |
| **OmniCore / FusionCore / Coalesce** | ❌ Modrinth + CurseForge **双平台同名**（OmniCore 还是同为 NeoForge 的库 mod，2.9 万下载） |
| **PolyTune** | ❌ 与 1868 万下载的 `Polytone` 仅差一字母 + TC Electronic 调音器商标 |
| **Synergy / Concord / Amalgam** | ❌ 同名 + 搜索被大量非 MC 项目淹没（Synergy 在 GitHub 有 1.1 万个仓库） |
| **MergeCore** | ⚠️ 撞车低，但 FiveM 圈同名团队有组织性欺诈调查报道，"模组圈 + 同名 + 丑闻"组合不利 |
| **Kilter** | ⚠️ 三平台干净、语义同样贴切；唯一代价是与兼容层 mod `Kilt` **一字母之差且同为"跨 mod"品类** → 列第一备选 |
| **Cognate** | ⚠️ 干净，"同源"语义贴切；但学术腔重、GitHub 有同名编程语言（265★）→ 第二备选 |
| **Verve / Halyard** | ⚠️ 干净可用；Verve 已有同名整合包（非 mod），Halyard 意义偏门 |
| **TkPerf** | ⚠️ 原名单里唯一 MC 生态零撞车（只与一个 SSD 测试工具重名），但 "Tk" 对玩家不可读、品牌资产为零 → 仅作**保底方案** |

## 3. 定名前唯一未决项（**请你手动确认一次**）

CurseForge 官网被 Cloudflare 403 拦截，子代理是用 cfwidget 精确 slug + 定向检索得出的"slug 404"，**Lead 未能独立复核**（`api.cfwidget.com` 返回了非 JSON）。

**动作**：在 CurseForge 上手动搜索一次 `fettle`，确认没有同名 MC mod 后即可正式定案；若有同名项目，退回第一备选 **Kilter**（并在 Modrinth/CF 上同步查一次）。

## 4. 发布期的命名纪律（避免以后返工）

1. **mod id 一旦发布永不变更**；显示名可以在大版本时微调，但 id/包名/命名空间必须冻结。
2. 配置文件名与数据包目录用 `fettle` 前缀（`config/fettle-common.toml` 等），不要用显示名大小写。
3. 数据组件（若有）一律带 `fettle:` 命名空间，避免与其它 mod 的组件键冲突。
4. 若将来要拆分模块，用 `fettle-recipe` / `fettle-transfer` 之类**后缀**，不要新起品牌名。
