# Contributing to rift-java

Thanks for helping build the official Java SDK for [Rift](https://github.com/achird-labs/rift).

## Prerequisites

- A JDK. The zero-dependency modules build on **JDK 17+**. Two modules are JDK-gated (see below):
  `rift-java-embedded` needs **JDK 22+**, `rift-java-embedded-jdk21` needs **JDK 21**.
- No local Maven install is required — the repo ships the **Maven Wrapper** (`./mvnw`).

## Build & verify

```sh
./mvnw -B verify        # compile every active module + run tests
./mvnw -pl rift-java-core test   # a single module
```

The CI matrix runs `./mvnw -B verify` on **JDK 17, 21, 22** across **Linux, macOS, and Windows**.
Run the wrapper on your JDK before opening a PR; CI covers the rest of the matrix.

A full source install works with no flags, including behind a restricted network:

```sh
./mvnw -DskipTests install        # installs every module (incl. rift-java-bom) to ~/.m2
```

`rift-java-bom`'s `resolve-through-bom` self-validation IT is skipped in a plain build (it needs
network to resolve the managed modules' transitive deps and would otherwise block the BOM from
installing). It runs automatically in the release lane (`-Prelease`, i.e. CI's release-smoke and
deploy); to run it locally, add `-Dinvoker.skip=false`.

Javadoc errors fail the build. A broken `{@link}`, an unresolvable reference, or a malformed tag
is an error under the JDK's default doclint, and the javadoc jars published to Maven Central are
built with the plugin's default `failOnError=true`. Javadoc only runs in the release lane, so a
plain `./mvnw verify` will not catch it, but CI's release-smoke will — check it locally with:

```sh
./mvnw -DskipTests compile javadoc:javadoc   # add -pl <module> -am to narrow it
```

`compile` is required: `javadoc:javadoc` on its own resolves each module's dependencies from the
repository rather than the reactor, so on a clean clone it fails to resolve the sibling modules
before it ever reaches javadoc. The embedded modules are JDK-gated (`rift-java-embedded-jdk21`
needs exactly JDK 21, `rift-java-embedded` needs 22+), so on JDK 17 the command silently skips
both; release-smoke covers them on every PR.

Missing-tag warnings (`no @param`, `no @return`) are *not* errors and do not fail the build.

That strictness is itself guarded. A clean build cannot distinguish "strict and clean" from
"permissive and clean", so release-smoke runs a canary step that injects a broken `{@link}` into
`Rift.java` and fails the job if the release-lane build *succeeds* — re-adding `failOnError=false`,
`doclint=none`, or any other silencer cannot slip through unnoticed. The step reverts its own edit.
One maintenance note: it anchors on the phrase `admin API.` in `Rift.java`'s opening javadoc
sentence, so if you reword that sentence, update the anchor in `.github/workflows/ci.yml`. The step
fails loudly and says so when the anchor stops matching.

## Module layout

| Module | JDK | Contents |
|---|---|---|
| `rift-java-core` | 17+ | typed wire model, fluent DSL, remote + spawn transports, verification. Zero runtime deps. |
| `rift-java-jackson` | 17+ | optional Jackson POJO body codec |
| `rift-java-junit5` | 17+ | `@RiftTest` extension, imposter injection |
| `rift-java-natives` | 17+ | per-platform classifier jars bundling the `librift_ffi` cdylib |
| `rift-java-embedded` | 22+ | in-process engine over the **stable** Panama FFM API |
| `rift-java-embedded-jdk21` | 21 | same engine on JDK 21 (**preview** FFM) |

### JDK gating

The two embedded modules are added to the reactor by JDK-activated Maven profiles, so `./mvnw verify`
builds the right set on any supported JDK:

- **JDK 17** → core, jackson, junit5, natives
- **JDK 21** → the above **+ `rift-java-embedded-jdk21`** (profile `embedded-jdk21`)
- **JDK 22+** → the above four **+ `rift-java-embedded`** (profile `embedded`)

You never need to pass a profile by hand for a normal build.

## Conventions

- **Branches**: `feat/`, `fix/`, `refactor/`, `test/`, `build/`, `docs/` prefixes.
- **Commits**: [Conventional Commits](https://www.conventionalcommits.org/), imperative mood,
  explaining *why* over *what*.
- **Code style**: no `null` returns in public APIs where an `Optional`/sealed type fits; errors are
  values or typed exceptions, never swallowed; public API carries Javadoc.

## Releasing

Artifacts publish to Maven Central under the `io.github.achird-labs` namespace via the
[Central Publishing plugin](https://central.sonatype.org/publish/publish-portal-maven/).

- **Snapshots** deploy automatically on every push to `master`, at whatever `-SNAPSHOT` version the
  root pom currently carries.
- **Releases are cut by pushing a `vX.Y.Z` tag** — that is the only trigger. The `Publish` workflow
  stamps every module with the version from the tag, deploys, *then* creates the GitHub Release
  object and pushes a follow-up commit advancing `master` to the next `-SNAPSHOT` (which also syncs
  the README's install snippets to the released version).

```sh
git tag v0.1.3 && git push origin v0.1.3
```

Push the tag with credentials of your own, not `GITHUB_TOKEN`: GitHub deliberately does not trigger
a workflow from a tag pushed by `GITHUB_TOKEN`, which is why the optional auto-release loop needs a
separate `RELEASE_TOKEN` secret.

### The dependency-bump loop and its two secrets

`Engine Bump` (weekly, plus `workflow_dispatch`) polls `achird-labs/rift` and opens a
`chore/engine-<version>` PR through the reusable `dep-bump.yml`; when CI on that PR is green,
`auto-release.yml` merges it and pushes the release tag that `Publish` acts on. The loop needs **two**
repository secrets, and it stalls in a different place if either is missing:

| secret | used for | if absent |
|--------|----------|-----------|
| `BUMP_TOKEN` | pushing the bump branch and opening the bump PR | the bump fails loudly before pushing — because a PR opened by `GITHUB_TOKEN` reports zero checks, so CI never runs and `auto-release` never fires |
| `RELEASE_TOKEN` | pushing the `vX.Y.Z` tag after the merge | `auto-release` fails before merging; the bump PR is left open for a human |

Both are the same shape of credential — a PAT or fine-grained token with `contents:write` and
`pull-requests:write`, plus `workflows` if the repo's bump touches `.github/workflows/`. The reason
neither can be `GITHUB_TOKEN` is the same in both rows: GitHub does not trigger workflows from events
it authors, so a token-authored PR or tag produces no downstream run.

The `Publish` workflow is a no-op until these repository secrets are configured:
`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` (a Central Portal user token), `GPG_PRIVATE_KEY`,
and `MAVEN_GPG_PASSPHRASE`. Signing and the sources/javadoc jars live in the `release` profile
(`./mvnw -Prelease deploy`).

### One-shot: the 0.1.3 relocation publish

0.1.0-0.1.2 shipped under the old `io.github.etacassiopeia` groupId. `relocation/` holds
pom-only stubs that redirect those coordinates at 0.1.3 to the new ones, so anyone who bumps
an existing dependency gets a warning naming the new coordinates instead of an unresolvable
artifact. It is deliberately **not** in the root reactor.

Publish it **once, after 0.1.3 is live** under the new groupId — the relocation target has to
already exist on Central, or consumers get redirected to a 404, which is strictly worse than the
plain "not found" the stub replaces. Run the **Publish relocation stubs (one-shot)** workflow
(`workflow_dispatch`), which does exactly that with the credentials already held as repository
secrets:

```sh
gh workflow run relocation-publish.yml -f dry_run=true    # build + sign + verify targets, no deploy
gh workflow run relocation-publish.yml -f dry_run=false   # publish
```

It refuses to deploy unless **every** relocation target already resolves on Central, so the
ordering rule is enforced rather than remembered. Central's rsync to `repo1.maven.org` lags the
Portal by roughly 10–30 minutes after a release, so a run started right after tagging is expected
to fail that check — wait and re-run rather than forcing it.

Central Portal tokens are account-scoped, not namespace-scoped, so the same `MAVEN_CENTRAL_*`
secrets cover the old `io.github.etacassiopeia` namespace the account still owns. Publishing by hand
(`mvn -f relocation/pom.xml -Prelease deploy`) works too, but needs those credentials and the GPG
key locally.

Do not repeat this for later versions: one relocation at the boundary version is the whole
mechanism, and anyone pinned at 0.1.2 or lower is unaffected.
