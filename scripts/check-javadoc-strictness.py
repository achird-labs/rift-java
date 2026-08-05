#!/usr/bin/env python3
#
# Fail if any module's EFFECTIVE maven-javadoc-plugin configuration silences javadoc errors.
#
#   ./mvnw -Prelease,... help:effective-pom -Doutput=effective-pom.xml
#   scripts/check-javadoc-strictness.py effective-pom.xml --require rift-java-core,rift-java-bom,...
#   scripts/check-javadoc-strictness.py --self-test
#
# Why this exists (#203): the poison canary in .github/workflows/ci.yml proves *behaviourally* that
# a broken {@link} fails the build, but it only poisons rift-java-core, so it pins the ROOT pom's
# pluginManagement. A per-module override in, say, rift-java-jackson leaves both that canary and the
# main release build green while that module ships broken javadoc to Central — the same
# self-concealing shape #200 removed, relocated one level down. This sweeps every module instead.
#
# Reading the EFFECTIVE pom (not the source poms) is the point: it is the model Maven will actually
# execute, with inheritance, profiles and per-module overrides already merged, so the check cannot be
# defeated by whitespace, a property indirection, or the setting living in a different file.
#
# Every rejection below was verified behaviourally: each one makes a build with a deliberately broken
# {@link} in rift-java-jackson exit 0 instead of failing.
#
# `doclint` and `-Xdoclint` are matched as an ALLOWLIST, not a denylist. Their value space is open
# (`none`, `syntax`, `all,-missing,-reference`, …) and only some values disable the `reference` group
# that produces `error: reference not found`, so enumerating the bad ones is a losing game. The
# repo's intended state is "not configured at all", so anything other than `all` is rejected and
# widening the allowlist is a conscious edit.
#
# Scope, stated honestly: this asserts CONFIGURATION reachable from the POM. It cannot see a
# `-Dmaven.javadoc.failOnError=false` added to a workflow's own mvn command line. The rift-java-core
# poison canary asserts BEHAVIOUR. Each covers the other's blind spot — keep both.

import argparse
import contextlib
import io
import os
import sys
import tempfile
import xml.etree.ElementTree as ET

NS = "{http://maven.apache.org/POM/4.0.0}"
JAVADOC_PLUGIN = "maven-javadoc-plugin"

# Properties are a first-class silencer: maven-javadoc-plugin binds these parameters to user
# properties, so a module that sets one in <properties> needs no <configuration> at all.
#
# failOnError is an allowlist ("true" is the only strict value) rather than a `!= "false"` denylist:
# Maven parses these with Boolean.valueOf, so an EMPTY value is false — i.e. silenced — and a
# denylist would wave it through.
STRICTNESS_PROPERTIES = {
    "maven.javadoc.failOnError": lambda v: v.lower() == "true",
    "maven.javadoc.skip": lambda v: v.lower() != "true",
    "maven.javadoc.skippedModules": lambda v: v.strip() == "",
    "doclint": lambda v: _doclint_is_strict(v),
}

# Both the plural container and the singular scalar parameter pass options through verbatim.
ADDITIONAL_OPTION_TAGS = (
    "additionalJOptions",
    "additionalJOption",
    "additionalparam",
    "additionalOptions",
)


def _doclint_is_strict(value):
    """Only an unset or fully-enabled doclint keeps `error: reference not found` fatal."""
    return value.strip().lower() in ("", "all")


def _joptions_are_strict(text):
    """True unless some -Xdoclint token disables any doclint group."""
    for token in text.split():
        bare = token.strip().lower()
        if bare.startswith("-xdoclint") and bare not in ("-xdoclint", "-xdoclint:all"):
            return False
    return True


def local(tag):
    return tag[len(NS):] if tag.startswith(NS) else tag


def permissive_settings(config):
    """Reasons this <configuration> weakens javadoc strictness."""
    reasons = []
    for child in config:
        name = local(child.tag)
        value = (child.text or "").strip()
        if name == "failOnError" and value.lower() != "true":
            # Empty counts: Boolean.valueOf("") is false, so <failOnError/> silences errors too.
            reasons.append(f"<failOnError>{value or '(empty)'}</failOnError> (only 'true' is strict)")
        elif name == "skip" and value.lower() == "true":
            reasons.append("<skip>true</skip>")
        elif name == "skippedModules" and value:
            reasons.append(f"<skippedModules>{value}</skippedModules>")
        elif name == "doclint" and not _doclint_is_strict(value):
            reasons.append(f"<doclint>{value}</doclint> (only 'all' keeps reference errors fatal)")
        elif name in ADDITIONAL_OPTION_TAGS:
            # Options may be nested (<additionalJOptions><additionalJOption>…</…>) or inline, so
            # flatten the subtree rather than reading .text.
            joined = " ".join(t.strip() for t in child.itertext() if t.strip())
            if not _joptions_are_strict(joined):
                reasons.append(f"<{name}>{joined}</{name}>")
    return reasons


def javadoc_plugins(project):
    """Javadoc plugin elements that actually govern the build.

    Scoped to <build><plugins> and <build><pluginManagement><plugins>. <reporting> is site-only and
    any <profiles> the effective pom retains are, by definition, not the active model — including
    either would raise false alarms, and a guard that cries wolf is a guard that gets deleted.
    """
    found = []
    build = project.find(NS + "build")
    if build is None:
        return found
    containers = [build.find(NS + "plugins")]
    management = build.find(NS + "pluginManagement")
    if management is not None:
        containers.append(management.find(NS + "plugins"))
    for container in containers:
        if container is None:
            continue
        for plugin in container.findall(NS + "plugin"):
            if plugin.findtext(NS + "artifactId") == JAVADOC_PLUGIN:
                found.append(plugin)
    return found


def check_project(project):
    """(artifactId, javadoc_plugin_count, [(artifactId, where, reason)])."""
    artifact_id = project.findtext(NS + "artifactId") or "<unknown>"
    findings = []
    plugins = javadoc_plugins(project)

    for plugin in plugins:
        config = plugin.find(NS + "configuration")
        if config is not None:
            findings += [(artifact_id, "plugin configuration", r) for r in permissive_settings(config)]

        # An execution-level <configuration> overrides the plugin-level one, so it silences errors
        # just as effectively and must be inspected too.
        for execution in plugin.iter(NS + "execution"):
            exec_config = execution.find(NS + "configuration")
            if exec_config is None:
                continue
            exec_id = execution.findtext(NS + "id") or "<no id>"
            findings += [
                (artifact_id, f"execution '{exec_id}'", r) for r in permissive_settings(exec_config)
            ]

    properties = project.find(NS + "properties")
    if properties is not None:
        for name, is_strict in STRICTNESS_PROPERTIES.items():
            value = properties.findtext(NS + name)
            if value is not None and not is_strict(value.strip()):
                findings.append((artifact_id, "properties", f"<{name}>{value.strip()}</{name}>"))

    return artifact_id, len(plugins), findings


def run_check(path, required):
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exc:
        print(f"::error::could not read the effective pom at {path}: {exc}")
        return 1

    projects = list(root.iter(NS + "project"))

    # A check that inspected nothing must never report success — that is the exact self-concealing
    # failure this guard exists to prevent.
    if not projects:
        print(f"::error::no <project> elements found in {path} — the check proved nothing")
        return 1

    findings = []
    without_plugin = []
    inspected = []
    for project in projects:
        artifact_id, plugin_count, project_findings = check_project(project)
        inspected.append(artifact_id)
        findings += project_findings
        if plugin_count == 0:
            without_plugin.append(artifact_id)
        print(f"  checked {artifact_id} ({plugin_count} javadoc-plugin entries)")

    print(f"inspected {len(projects)} modules: {', '.join(inspected)}")

    # A sweep is only as good as its reactor. If a module drops out — a broken profile activation, a
    # deleted <module> — the sweep would still pass while that module publishes unswept. Naming the
    # expected set turns that silent shrink into a red build.
    missing = [m for m in required if m not in inspected]
    if missing:
        print(
            "::error::expected modules absent from the release reactor: "
            + ", ".join(missing)
            + " — they were NOT swept, so javadoc strictness is unproven for them"
        )
        return 1

    if without_plugin:
        print(
            "::error::maven-javadoc-plugin is absent from <build> in: "
            + ", ".join(without_plugin)
            + " — javadoc strictness is unproven for those modules"
        )
        return 1

    if findings:
        print("::error::javadoc strictness is silenced in the effective pom (#203):")
        for artifact_id, where, reason in findings:
            print(f"::error::  {artifact_id}: {where} sets {reason}")
        return 1

    print("javadoc strictness confirmed: no module weakens maven-javadoc-plugin")
    return 0


# A guard that cannot fail is not a guard. If a future edit broke permissive_settings so it always
# returned [], every real sweep would still print "confirmed" — #200's shape relocated into this
# script. These fixtures make that regression loud, and cost no Maven invocation.
_STRICT_FIXTURE = """<projects><project xmlns="http://maven.apache.org/POM/4.0.0">
  <artifactId>fixture-strict</artifactId>
  <build><plugins><plugin><artifactId>maven-javadoc-plugin</artifactId></plugin></plugins></build>
</project></projects>"""

_PERMISSIVE_FIXTURES = {
    "plugin failOnError": "<configuration><failOnError>false</failOnError></configuration>",
    "plugin doclint=none": "<configuration><doclint>none</doclint></configuration>",
    "plugin doclint=syntax": "<configuration><doclint>syntax</doclint></configuration>",
    "plugin skip": "<configuration><skip>true</skip></configuration>",
    "JOption -Xdoclint:none": (
        "<configuration><additionalJOptions>"
        "<additionalJOption>-Xdoclint:none</additionalJOption>"
        "</additionalJOptions></configuration>"
    ),
    "JOption -Xdoclint:all,-missing,-reference": (
        "<configuration><additionalJOptions>"
        "<additionalJOption>-Xdoclint:all,-missing,-reference</additionalJOption>"
        "</additionalJOptions></configuration>"
    ),
    "execution failOnError": (
        "<executions><execution><id>attach-javadocs</id>"
        "<configuration><failOnError>false</failOnError></configuration>"
        "</execution></executions>"
    ),
    # Boolean.valueOf("") is false, so an empty element silences errors exactly like `false`.
    "plugin failOnError empty": "<configuration><failOnError></failOnError></configuration>",
    "singular additionalJOption": (
        "<configuration><additionalJOption>-Xdoclint:none</additionalJOption></configuration>"
    ),
}

_PERMISSIVE_PROPERTY_FIXTURES = {
    "property maven.javadoc.failOnError": "<maven.javadoc.failOnError>false</maven.javadoc.failOnError>",
    "property maven.javadoc.failOnError empty": "<maven.javadoc.failOnError></maven.javadoc.failOnError>",
    "property maven.javadoc.skip": "<maven.javadoc.skip>true</maven.javadoc.skip>",
    "property doclint": "<doclint>none</doclint>",
    "property maven.javadoc.skippedModules": "<maven.javadoc.skippedModules>rift-java-core</maven.javadoc.skippedModules>",
}


def _fixture(plugin_body="", properties=""):
    props = f"<properties>{properties}</properties>" if properties else ""
    return (
        '<projects><project xmlns="http://maven.apache.org/POM/4.0.0">'
        "<artifactId>fixture</artifactId>"
        f"{props}"
        "<build><plugins><plugin><artifactId>maven-javadoc-plugin</artifactId>"
        f"{plugin_body}</plugin></plugins></build>"
        "</project></projects>"
    )


def self_test():
    """Assert the detector still detects. Returns 0 if every fixture behaves."""
    failures = 0

    strict = ET.fromstring(_STRICT_FIXTURE)
    _, _, strict_findings = check_project(next(strict.iter(NS + "project")))
    if strict_findings:
        print(f"::error::self-test: the strict fixture was flagged: {strict_findings}")
        failures += 1
    else:
        print("  ok  strict fixture -> no findings")

    cases = [(n, _fixture(plugin_body=b)) for n, b in _PERMISSIVE_FIXTURES.items()]
    cases += [(n, _fixture(properties=p)) for n, p in _PERMISSIVE_PROPERTY_FIXTURES.items()]
    for name, xml in cases:
        project = next(ET.fromstring(xml).iter(NS + "project"))
        _, _, found = check_project(project)
        if not found:
            print(f"::error::self-test: '{name}' was NOT detected — the checker is disarmed")
            failures += 1
        else:
            print(f"  ok  {name} -> detected")

    # The fixtures above exercise the MATCHER. run_check is the reporting layer, and a weakened
    # `if findings: return 1` or a dropped guard there would leave those green while the real sweep
    # printed "confirmed" — so exercise it end-to-end too, still without a Maven invocation.
    with tempfile.TemporaryDirectory() as tmp:
        strict_path = os.path.join(tmp, "strict.xml")
        permissive_path = os.path.join(tmp, "permissive.xml")
        with open(strict_path, "w", encoding="utf-8") as handle:
            handle.write(_fixture())
        with open(permissive_path, "w", encoding="utf-8") as handle:
            handle.write(_fixture(plugin_body="<configuration><failOnError>false</failOnError></configuration>"))

        roundtrips = [
            ("run_check: clean pom, --require satisfied", strict_path, ["fixture"], 0),
            ("run_check: clean pom, --require missing a module", strict_path, ["absent-module"], 1),
            ("run_check: permissive pom", permissive_path, [], 1),
            ("run_check: unreadable path", os.path.join(tmp, "nope.xml"), [], 1),
        ]
        for name, path, required, expected in roundtrips:
            # run_check narrates every module; keep the self-test's own output readable.
            with contextlib.redirect_stdout(io.StringIO()):
                actual = run_check(path, required)
            if actual != expected:
                print(f"::error::self-test: '{name}' returned {actual}, expected {expected}")
                failures += 1
            else:
                print(f"  ok  {name} -> {actual}")

    if failures:
        print(f"::error::self-test failed: {failures} case(s)")
        return 1
    print(
        f"self-test passed: {len(cases)} silencers detected, "
        f"{len(roundtrips)} run_check cases, strict fixture clean"
    )
    return 0


def main(argv):
    # Parsed strictly, and never leniently: a typo'd flag, a dropped value or an empty list must be
    # a usage error, not a silent skip of the completeness guard. Hand-rolled argv matching made
    # `--requires x` degrade to "no modules required" while still printing "confirmed" — the exact
    # prove-nothing-but-report-success shape this whole script exists to prevent.
    parser = argparse.ArgumentParser(
        prog=os.path.basename(argv[0]),
        description="Fail if any module's effective maven-javadoc-plugin config silences javadoc errors.",
        allow_abbrev=False,
    )
    parser.add_argument("effective_pom", nargs="?", help="output of help:effective-pom")
    parser.add_argument(
        "--require",
        metavar="A,B,C",
        help="comma-separated artifactIds the reactor MUST contain; missing ones fail the check",
    )
    parser.add_argument(
        "--self-test", action="store_true", help="assert the detector still detects, then exit"
    )
    args = parser.parse_args(argv[1:])  # unknown flags / extra positionals exit 2 here

    if args.self_test:
        # `--self-test eff.xml --require x` would otherwise sweep nothing and exit 0 — the same
        # prove-nothing-report-success shape the strict parsing above exists to reject.
        if args.effective_pom or args.require:
            parser.error("--self-test takes no other arguments")
        return self_test()

    if not args.effective_pom:
        parser.error("an effective-pom path is required (or use --self-test)")

    required = []
    if args.require is not None:
        required = [m.strip() for m in args.require.split(",") if m.strip()]
        if not required:
            parser.error("--require was given an empty list; omit the flag or name the modules")

    return run_check(args.effective_pom, required)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
