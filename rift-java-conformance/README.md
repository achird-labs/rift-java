# rift-java-conformance

The **conformance gate** (M1 spawn, M2 embedded). Replays the engine-canonical SDK conformance corpus
(`sdk-conformance-<version>.tar.gz`, published per Rift release) over each transport the SDK supports
— **spawn** (a real engine process) and **embedded** (the engine in-process over Panama FFM) — and
asserts DSL ↔ engine parity on each. This module is test-only and never published to Maven Central.

Every official Rift SDK replays this same corpus; it is the single source of truth for whether the
typed DSL has drifted from the engine grammar. A fixture the DSL cannot express is a **red build**
— by design, a new corpus fixture forces the DSL to grow.

## The four gates (per fixture)

| Gate | Where | What it proves |
|------|-------|----------------|
| **1 · Parse fidelity** | `ParseFidelityTest` | `ImposterDefinition.fromJson(fixture).toJson()` is `semanticEquals` to the fixture — no key dropped, renamed, or retyped. |
| **2 · DSL expressibility** | `DslExpressibilityTest` + `DslFixtures` | the fixture rebuilt through the typed `RiftDsl` API serializes back to the fixture (modulo the `_verify` test annotation). A runnable fixture with no `DslFixtures` entry fails. |
| **3 · Raw engine replay** | `CorpusReplayIT` | `rift.create(fixtureJson)` on a spawned engine serves the fixture; each stub's `_verify` request/response transcript holds. |
| **4 · DSL engine replay** | `CorpusReplayIT` | the DSL-built imposter serves the **same** transcripts — the DSL's output is engine-equivalent, not merely JSON-equivalent. |

Fixtures without `_verify` still run gates 1–2 and a smoke GET.

## Transports

Gates 3–4 (engine replay) run over a selectable transport — one parameter, not a second harness —
chosen by `CONFORMANCE_TRANSPORT` / `-Dconformance.transport` (default `SPAWN`). Each dynamic test's
display name carries the transport (e.g. `01 · Basic REST API [EMBEDDED]`), so a fixture that fails
only on one transport is reportable as such.

| Transport | Engine | Notes |
|-----------|--------|-------|
| `SPAWN` | a real rift process | `workingDir = corpus/`, so relative `data/` paths resolve. Needs JDK 17+. |
| `EMBEDDED` | in-process over Panama FFM (`Rift.embedded()`) | needs `rift-java-embedded` (JDK 22+) or `rift-java-embedded-jdk21` (JDK 21) on the classpath — added by a JDK-gated profile — plus a resolvable `librift_ffi`. Relative `data/` paths are absolutized via `Corpus.rewriteForEmbedded` because the in-process engine inherits the JVM working directory. |

The replay lane self-skips entirely unless `RIFT_IT=1` (all transports). Once enabled, if `EMBEDDED`
is selected but cannot run — under JDK 17 (no FFM artifact on the classpath) or with no resolvable
`librift_ffi` — the run **fails loudly** rather than passing vacuously. The CI matrix never pairs JDK 17
with `EMBEDDED` (JDK 17 runs only the `SPAWN` lane).

## What gets skipped, and why (never silently)

- **Capability lane-skip** — the corpus `manifest.json` declares each fixture's `requires` from the
  closed set `{injection, proxy, redis, https, shell}`. The remote/spawn lane provides `injection`
  (the engine spawns with `--allowInjection`) but not `proxy`/`redis`/`https`/`shell`, so fixtures
  needing those are skipped per the corpus replay contract §4.
- **Known fidelity gaps** — a fixture the wire model cannot yet round-trip (gate 1) is listed in
  `KnownFidelityGaps` with the core issue tracking it, and excluded from gates 2–4. This is *not* a
  silence: `ParseFidelityTest` asserts each listed fixture still fails, so the day core is fixed the
  entry flips the build red and must be removed. A gap can never rot into a silent pass.

## Running it

The gates need the corpus. Point at an extracted `sdk-conformance-<version>` directory (the one
holding `manifest.json` and `corpus/`):

```bash
# Download + extract the corpus for the pinned engine version, then:
./mvnw -pl rift-java-conformance -am test \
  -Drift.corpus.root=/path/to/sdk-conformance-v0.17.0
```

Resolution order for the corpus: `-Drift.corpus.root` → `RIFT_CORPUS_ROOT` → `target/corpus`. When
the corpus is absent the pure gates skip (locally) or fail loud (in CI, where `RIFT_IT=1`).

The engine replay lane (gates 3–4, `CorpusReplayIT`) is heavier — it starts a real engine — so it
self-skips unless `RIFT_IT=1`. Over the default spawn transport (the engine binary is downloaded and
cached on first use):

```bash
RIFT_IT=1 ./mvnw -pl rift-java-conformance -am verify \
  -Drift.corpus.root=/path/to/sdk-conformance-v0.17.0
```

Over the embedded transport (JDK 21 or 22+, with a `librift_ffi` for your platform):

```bash
RIFT_IT=1 CONFORMANCE_TRANSPORT=EMBEDDED ./mvnw -pl rift-java-conformance -am verify \
  -Drift.corpus.root=/path/to/sdk-conformance-v0.17.0 \
  -Drift.ffi.lib=/path/to/librift_ffi-<platform>.<ext>
```

CI runs these in the `conformance` job (see `.github/workflows/ci.yml`), a matrix over the transports,
JDKs, and OSes: `SPAWN` on ubuntu/JDK 17; `EMBEDDED` on ubuntu (JDK 22 and JDK 21 preview) and
macOS (JDK 22, `darwin-aarch64`); Windows is `EMBEDDED`/experimental (`continue-on-error`) until it
is green for two consecutive weeks. Each embedded lane fetches its platform's `librift_ffi` from the
version-locked engine release and exposes it via `RIFT_FFI_LIB`.

## Adding a fixture to the DSL registry

When the corpus grows, gate 2 fails for the new fixture until you add its entry to `DslFixtures`:
one `buildNN()` method that reconstructs the imposter through `RiftDsl` only (see the existing
entries and core's `CorpusExpressibilityTest` for the idioms). If the DSL genuinely cannot express a
construct, that is the signal to grow the DSL — not to weaken the gate.
