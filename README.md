# Fettle

[![build](https://github.com/Tki-sor/Fettle/actions/workflows/build.yml/badge.svg)](https://github.com/Tki-sor/Fettle/actions/workflows/build.yml)

Cross-mod performance work for Minecraft **1.21.1 / NeoForge**, focused on Create 6.0.10.

Fettle is a standalone **Mixin mod**: it never edits the source of the mods it optimizes, it injects at
runtime. Everything is driven by config; there are no JVM arguments or environment switches.

> 把多个 mod 重复计算的同一件事，算一次、大家共用。

## Status

**✅ verified · ⚠️ measured, no benefit / unproven · 🧪 implemented, not verified** — the authoritative
per-feature ledger is [docs/fettle/20-status-ledger.md](Fettle-1.21.1/docs/fettle/20-status-ledger.md).

| Area | What it does | Measured |
| --- | --- | --- |
| Recipe index (vanilla + all mods) | Pre-compiled bitset pre-filter for `RecipeManager#getRecipeFor`; the final decision stays with vanilla `Recipe#matches` | **−85%** per lookup (23.4 µs → 3.5–4.1 µs); parity `checked=1211 mismatches=0` |
| Kinetic network | Per-tick memo of the block-entity validation lookup, plus O(1) incremental stress bookkeeping with a per-tick reconciliation | **4.5–5×** (lookups 442,200 → 201); **213×** on the update path |
| Train collision | Candidate filter from a per-tick carriage bounding-box snapshot, with a moved-train guard | **−94%** candidates; differential test vs vanilla: **0 mismatches** |
| Factory panels | Aggregation every N ticks instead of every tick — **opt-in, default = vanilla** | server scaffolding scene: panel section −72%; naturally active client scene: 0.5210 vs 0.5302 ms → **no benefit, unproven** ([docs 19](Fettle-1.21.1/docs/fettle/19-client-spark-test.md)) |
| Create misc ✅ | `TrackGraph#getConnectionsFrom` allocation, package address regex cache | address cache **~4×** (552→136 ns/op) |
| Fluids ✅ | `FluidManipulationBehaviour#search`: dequeue with `ArrayDeque` instead of `ArrayList.remove(0)` (O(n²)→O(n)) | **~1.5×** on a real 5000-block water body (360 vs 540–572 µs/call); traversal result *and* the leftover frontier are bit-identical to vanilla over 60 calls |
| Allocation micro-optimisations ⚠️ | Belt shaft lookup (`Optional`→`instanceof`), mounted-storage sync, `SmartBlockEntity` tick | **no steady-state benefit**: HotSpot's escape analysis removes all three (measured 0.0 B/op on two of them), see [docs 22](Fettle-1.21.1/docs/fettle/22-allocation-optimisations.md) |
| Belt shaft lookup ⚠️ | `BeltBlock#hasShaftTowards`: `Optional` chain replaced by `instanceof` | **no measurable benefit** on a real belt (9.14/12.09/11.49 vs 10.17/11.15 ns/op) — kept because it is behaviour-equivalent, see [docs 21](Fettle-1.21.1/docs/fettle/21-a8-belt-shaft.md) |
| BE observation + sleeping pilot | Zero-behaviour-change observation of per-class tick/change/sync counts, plus an opt-in sleeping pilot with config whitelist, heartbeats and auto-blacklist | steady state **19 skips : 1 heartbeat** (95% skipped) |

Design notes, evidence and numbers live in [docs/fettle/](docs/fettle/) — start with
[docs/fettle/02-crossmod-plan.md](docs/fettle/02-crossmod-plan.md) for the architecture and
[docs/fettle/17-config-whitelist-and-long-run-observer.md](docs/fettle/17-config-whitelist-and-long-run-observer.md)
for the newest results.

## Requirements

- **JDK 21** (the project toolchain is pinned to 21; compiling with a newer JDK is not verified)
- Create 6.0.10 for 1.21.1 — used at **compile time only**

## Build

The Create jar is not redistributed here. Download it into `libs/` first (see
[libs/README.md](libs/README.md) for the exact URL and the jar-in-jar companions):

```bash
mkdir -p libs && curl -L -o libs/create-1.21.1-6.0.10.jar \
  "https://cdn.modrinth.com/data/LNytGWDc/versions/UjX6dr61/create-1.21.1-6.0.10.jar"
./gradlew build
```

The resulting jar is `build/libs/fettle-<version>.jar`. Mixins that target Create are applied only when
Create is present:

```toml
[[mixins]]
config = "fettle.create.mixins.json"
requiredMods = ["create"]
```

## Configuration

Everything is a config option (no JVM flags). File: `config/fettle-common.toml`.

| Section | Option | Default | Meaning |
| --- | --- | --- | --- |
| — | `enabled` | true | Master switch; off = 100% vanilla behaviour |
| `recipe` | `indexEnabled`, `bitsetIndex`, `cacheGetAllRecipesFor`, `strictVanillaSerializersOnly`, `selfTestOnStartup` | true / true / true / false / false | Recipe index layer |
| `create` | `enabled`, `trackGraphConnections`, `beltShaftLookup`, `kineticIncrementalStress`, `kineticValidityCache`, `trainCollisionIndex`, `trainCollisionIndexVerifyMoved`, `travelPositionCache`, `packageAddressCache` | all true | Create-targeted injection |
| `create` | `factoryPanelCheckInterval` | 1 | Panel aggregation interval; **1 = vanilla, and that is the default because the optimisation is unproven** |
| `compat` | `yieldToFastSuite` | false | Do not index if FastSuite is installed |
| `diagnostics` | `benchProbes`, `debugPanelKeepAlive`, `mixinLog` | false | Measurement scaffolds, not gameplay features |
| `observe` | `enabled`, `reportIntervalMinutes` | false, 5 | Per-class tick/change/sync observation; snapshots appended to `fettle-observe.csv` |
| `beSleeping` | `enabled`, `classes`, `observeTicks`, `maxSkipTicks`, `blacklistAfterWakes` | false, [], 1200, 20, 20 | Sleeping pilot; `classes` accepts simple names, FQNs, wildcards and `modid:*` |

Most options can also be toggled at runtime:

```text
/fettle status
/fettle feature <name> <value>
/fettle selftest            # recipe index parity against a brute-force vanilla scan
/fettle idle report         # per-class observation table
/fettle idle wake           # stop sleeping and let every instance tick again
```

## Verification

`tools/fettle_gate.py` boots the dev server headless and asserts the invariants of every optimization
(recipe parity, train-collision differential including the moved-train case, kinetic differential plus
reconciliation, and the sleeping pilot's skip ratio). Its exit code is the number of failed checks.

```bash
python tools/fettle_gate.py     # -> [gate] PASS   (EXIT=0)
```

The benchmark and analysis tooling lives in [tools/](tools/): `fettle_bench.py` builds in-world
scenarios and records per-tick sections, `fettle_analyze.py` produces the CSVs, and `fettle_rcon.py`
is a small RCON client.

## Sleeping pilot scope

Default off, empty whitelist. Implemented wake conditions: `setChanged`, the heartbeat,
`/fettle idle wake`, **block/neighbour updates** and **capability queries** (see
[docs/fettle/18-wake-conditions.md](docs/fettle/18-wake-conditions.md)).

Still missing: chunk reload, player proximity and interaction (right-click). See
[docs/fettle/09-be-sleeping-boundary.md](docs/fettle/09-be-sleeping-boundary.md). Do not enable it in a
world you care about.

## License

MIT — see [LICENSE](LICENSE). Create is MIT too; its jar is a compile-time dependency and is not
redistributed in this repository.
