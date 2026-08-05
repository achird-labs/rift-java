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
# Every rejection below was verified behaviourally — each makes a build with a deliberately broken
# {@link} in rift-java-jackson exit 0 instead of failing — except `skippedModules`, which the
# non-aggregate `jar` goal never reads (see REQUIRED_PIN) and is rejected pre-emptively.
#
# `doclint` and `-Xdoclint` are matched as an ALLOWLIST, not a denylist. Their value space is open
# (`none`, `syntax`, `all,-missing,-reference`, …) and only some values disable the `reference` group
# that produces `error: reference not found`, so enumerating the bad ones is a losing game. The
# repo's intended state is "not configured at all", so anything other than `all` is rejected and
# widening the allowlist is a conscious edit.
#
# Scope, stated honestly: this asserts CONFIGURATION; the rift-java-core poison canary asserts
# BEHAVIOUR. Each covers the other's blind spot — keep both.
#
# The command-line vector (`-Dmaven.javadoc.failOnError=false` on a workflow's own mvn invocation,
# invisible to any POM-reading check) is not defended here by detection but closed by construction:
# the root pluginManagement pins the parameters explicitly, and a POM <configuration> value beats a
# user property, so the flag has no effect (#205). REQUIRED_PIN below is what keeps that pin in place.

import argparse
import contextlib
import io
import os
import sys
import tempfile
import xml.etree.ElementTree as ET
from typing import NamedTuple

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

# Every module must set these EXPLICITLY, not merely leave them at their strict defaults (#205).
# These parameters bind to user properties, and a property only supplies the value when the POM does
# not — so an unconfigured plugin is silently overridable by `-Dmaven.javadoc.failOnError=false` on
# any mvn command line, including publish.yml's real deploy. Pinning them in the POM makes that
# override impossible; requiring the pin here is what stops someone quietly deleting it again.
#
# maven.javadoc.skippedModules is deliberately NOT pinned even though it is rejected below: in
# 3.11.2 it is only consulted by the aggregate goals, and the release profile binds the
# non-aggregate `jar` goal, so it is inert here. Rejecting it is cheap insurance for the day someone
# switches to aggregate-jar; pinning a parameter the build never reads would just be noise.
REQUIRED_PIN = {"failOnError": "true", "skip": "false", "doclint": "all"}


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


EXECUTING = "build/plugins"
MANAGED = "pluginManagement"


def javadoc_plugins(project):
    """[(where, plugin)] for javadoc entries that govern the build.

    Scoped to <build><plugins> and <build><pluginManagement><plugins>. <reporting> is site-only and
    any <profiles> the effective pom retains are, by definition, not the active model — including
    either would raise false alarms, and a guard that cries wolf is a guard that gets deleted.

    The two containers are tagged rather than merged because they are not interchangeable: only the
    EXECUTING entry decides what Maven runs. A module can carry `<configuration combine.self=
    "override">` in its own <build><plugins> entry, which drops the inherited pin from the executing
    config while the pluginManagement copy still shows it — verified against this repo.
    """
    found = []
    build = project.find(NS + "build")
    if build is None:
        return found
    plugins = build.find(NS + "plugins")
    if plugins is not None:
        for plugin in plugins.findall(NS + "plugin"):
            if plugin.findtext(NS + "artifactId") == JAVADOC_PLUGIN:
                found.append((EXECUTING, plugin))
    management = build.find(NS + "pluginManagement")
    if management is not None:
        managed = management.find(NS + "plugins")
        if managed is not None:
            for plugin in managed.findall(NS + "plugin"):
                if plugin.findtext(NS + "artifactId") == JAVADOC_PLUGIN:
                    found.append((MANAGED, plugin))
    return found


def pin_gaps(config):
    """Which REQUIRED_PIN entries this <configuration> fails to set to the required value."""
    gaps = []
    for name, expected in REQUIRED_PIN.items():
        element = config.find(NS + name)
        actual = (element.text or "").strip().lower() if element is not None else None
        if actual != expected:
            gaps.append(f"<{name}> is {actual if actual is not None else 'unset'}, must be {expected}")
    return gaps


def _binds_javadoc_jar(plugin):
    """True if some execution of this entry will actually run the `jar` goal.

    `<phase>none</phase>` on an inherited execution id is Maven's documented idiom for unbinding it.
    The plugin-level <configuration> — pin included — stays visible in the effective pom, so the
    module reads as compliant while javadoc never runs for it and no javadoc jar is produced. A
    pinned value that is never consulted proves nothing, which is the failure this file exists to
    stop. An absent <phase> is normal: the goal's own default binding applies.
    """
    for execution in plugin.iter(NS + "execution"):
        goals = [goal.text.strip() for goal in execution.iter(NS + "goal") if goal.text]
        if "jar" not in goals:
            continue
        if (execution.findtext(NS + "phase") or "").strip().lower() != "none":
            return True
    return False


def _is_pinned(plugin):
    """True only if this entry pins the parameters for the plugin AND for every execution of it.

    An execution's own <configuration> overrides the plugin-level one at run time, so
    `<execution><configuration combine.self="override">` drops the inherited pin for that execution
    while leaving the plugin-level pin visible in the effective pom. Verified: that shape ships
    broken javadoc under -Dmaven.javadoc.failOnError=false while every other check stays green.
    An execution with no <configuration> of its own simply inherits the plugin-level pin.
    """
    config = plugin.find(NS + "configuration")
    if config is None or pin_gaps(config):
        return False
    for execution in plugin.iter(NS + "execution"):
        exec_config = execution.find(NS + "configuration")
        if exec_config is not None and pin_gaps(exec_config):
            return False
    return True


class ProjectCheck(NamedTuple):
    """Per-module result. Named because the checks keep growing and positional unpacking rots."""

    artifact_id: str
    executing_entries: int
    findings: list
    pinned: bool
    bound: bool


def check_project(project):
    """Inspect one effective <project>; see ProjectCheck."""
    artifact_id = project.findtext(NS + "artifactId") or "<unknown>"
    findings = []
    plugins = javadoc_plugins(project)
    executing = [plugin for where, plugin in plugins if where == EXECUTING]

    # EVERY executing entry must carry the pin, and there must be at least one. "Any entry passes"
    # would let an inert pluginManagement copy vouch for an executing entry that overrode it away.
    pinned = bool(executing) and all(_is_pinned(plugin) for plugin in executing)

    for where, plugin in plugins:
        config = plugin.find(NS + "configuration")
        if config is not None:
            findings += [
                (artifact_id, f"{where} configuration", r) for r in permissive_settings(config)
            ]

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

    # Count the EXECUTING entries: a module with only a pluginManagement copy never runs javadoc,
    # which the "absent from <build>" guard should report rather than the vaguer "not pinned".
    bound = bool(executing) and any(_binds_javadoc_jar(plugin) for plugin in executing)
    return ProjectCheck(artifact_id, len(executing), findings, pinned, bound)


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
    unpinned = []
    unbound = []
    inspected = []
    for project in projects:
        result = check_project(project)
        inspected.append(result.artifact_id)
        findings += result.findings
        if result.executing_entries == 0:
            without_plugin.append(result.artifact_id)
        elif not result.pinned:
            unpinned.append(result.artifact_id)
        elif not result.bound:
            unbound.append(result.artifact_id)
        print(
            f"  checked {result.artifact_id} ({result.executing_entries} javadoc-plugin entries,"
            f" pinned={result.pinned}, jar-bound={result.bound})"
        )

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

    # Report the precise silencer before the vaguer "not pinned". Changing the pin to a permissive
    # value (say <doclint>none</doclint>) trips BOTH, and the specific diagnostic is the useful one.
    if findings:
        print("::error::javadoc strictness is silenced in the effective pom (#203):")
        for artifact_id, where, reason in findings:
            print(f"::error::  {artifact_id}: {where} sets {reason}")
        return 1

    # Leaving the parameters at their strict DEFAULTS is not enough: a default is what a user
    # property overrides, so an unpinned module can be silenced by a `-D` on any mvn command line
    # without touching a single file in the repo (#205).
    if unpinned:
        expected = ", ".join(f"<{k}>{v}</{k}>" for k, v in REQUIRED_PIN.items())
        print(
            "::error::maven-javadoc-plugin is not pinned in: "
            + ", ".join(unpinned)
            + f" — the root pluginManagement <configuration> must set {expected}, otherwise"
            " -Dmaven.javadoc.failOnError=false on any command line silences javadoc (#205)"
        )
        return 1

    # A pin nothing consults proves nothing: an execution unbound with <phase>none</phase> keeps the
    # config visible while javadoc never runs, so the module publishes no javadoc jar at all.
    if unbound:
        print(
            "::error::the javadoc `jar` goal is not bound to a lifecycle phase in: "
            + ", ".join(unbound)
            + " — javadoc never runs for them, so the pin above is never consulted"
        )
        return 1

    print("javadoc strictness confirmed: no module weakens maven-javadoc-plugin")
    return 0


# A guard that cannot fail is not a guard. If a future edit broke permissive_settings so it always
# returned [], every real sweep would still print "confirmed" — #200's shape relocated into this
# script. These fixtures make that regression loud, and cost no Maven invocation.
_PIN = "<configuration><failOnError>true</failOnError><skip>false</skip><doclint>all</doclint></configuration>"

_STRICT_FIXTURE = f"""<projects><project xmlns="http://maven.apache.org/POM/4.0.0">
  <artifactId>fixture-strict</artifactId>
  <build><plugins><plugin><artifactId>maven-javadoc-plugin</artifactId>
    {_PIN}
  </plugin></plugins></build>
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


# A pin that sets only some of the three is not a pin: the unset ones stay property-overridable.
_PARTIAL_PIN_FIXTURES = {
    "pin missing <skip> and <doclint>": "<configuration><failOnError>true</failOnError></configuration>",
    "pin missing <doclint>": (
        "<configuration><failOnError>true</failOnError><skip>false</skip></configuration>"
    ),
    "pin with wrong <doclint> value": (
        "<configuration><failOnError>true</failOnError><skip>false</skip>"
        "<doclint>all,-missing</doclint></configuration>"
    ),
}


def _fixture(plugin_body="", properties="", managed_body=None):
    """A one-module effective pom.

    `managed_body` adds a coexisting <pluginManagement> entry — the shape EVERY real module has,
    since the pin lives in the root pluginManagement and is inherited. Fixtures without it cannot
    exercise the interaction that actually decides `pinned`.
    """
    props = f"<properties>{properties}</properties>" if properties else ""
    managed = ""
    if managed_body is not None:
        managed = (
            "<pluginManagement><plugins><plugin>"
            "<artifactId>maven-javadoc-plugin</artifactId>"
            f"{managed_body}</plugin></plugins></pluginManagement>"
        )
    return (
        '<projects><project xmlns="http://maven.apache.org/POM/4.0.0">'
        "<artifactId>fixture</artifactId>"
        f"{props}"
        "<build><plugins><plugin><artifactId>maven-javadoc-plugin</artifactId>"
        f"{plugin_body}</plugin></plugins>{managed}</build>"
        "</project></projects>"
    )


def self_test():
    """Assert the detector still detects. Returns 0 if every fixture behaves."""
    failures = 0

    strict = ET.fromstring(_STRICT_FIXTURE)
    strict_result = check_project(next(strict.iter(NS + "project")))
    strict_findings, strict_pinned = strict_result.findings, strict_result.pinned
    if strict_findings:
        print(f"::error::self-test: the strict fixture was flagged: {strict_findings}")
        failures += 1
    elif not strict_pinned:
        print("::error::self-test: the strict fixture was not recognised as pinned")
        failures += 1
    else:
        print("  ok  strict fixture -> no findings, pinned")

    # An unconfigured plugin is the pre-#205 state: strict by default, but silently overridable by
    # -D. It must read as NOT pinned, or removing the pin would sail through.
    bare_pinned = check_project(next(ET.fromstring(_fixture()).iter(NS + "project"))).pinned
    if bare_pinned:
        print("::error::self-test: an unconfigured plugin was reported as pinned")
        failures += 1
    else:
        print("  ok  unconfigured plugin -> not pinned")

    # The regression that motivated scoping the pin to the EXECUTING entry: a module overrides its
    # own <configuration combine.self="override">, dropping the inherited pin, while the inert
    # pluginManagement copy still carries it. Verified against this repo — the sweep used to pass.
    _EXEC_OVERRIDE = (
        "<executions><execution><id>attach-javadocs</id>"
        "<configuration><quiet>true</quiet></configuration></execution></executions>"
    )
    # The real shape: id attach-javadocs, goal jar, no explicit <phase> (default binding applies).
    _EXEC_BOUND = (
        "<executions><execution><id>attach-javadocs</id>"
        "<goals><goal>jar</goal></goals></execution></executions>"
    )
    _EXEC_PHASE_NONE = (
        "<executions><execution><id>attach-javadocs</id><phase>none</phase>"
        "<goals><goal>jar</goal></goals></execution></executions>"
    )
    two_container = [
        ("executing entry overrides the pin away", "<configuration><quiet>true</quiet></configuration>", _PIN, False),
        ("executing entry keeps the pin", _PIN, _PIN, True),
        ("only pluginManagement is pinned", "", _PIN, False),
        # An execution config overrides the plugin-level one at run time, so a pinned plugin with an
        # unpinned execution is NOT pinned — verified exploitable before this case existed.
        ("execution overrides the pin away", _PIN + _EXEC_OVERRIDE, _PIN, False),
        ("execution with no config of its own", _PIN + _EXEC_BOUND, _PIN, True),
    ]

    # <phase>none</phase> unbinds the execution: the pin stays visible but javadoc never runs, so a
    # pinned-looking module publishes no javadoc at all.
    bound_cases = [
        ("jar goal bound to its default phase", _PIN + _EXEC_BOUND, True),
        ("jar goal unbound with <phase>none</phase>", _PIN + _EXEC_PHASE_NONE, False),
    ]
    for name, body, expected in bound_cases:
        actual = check_project(
            next(ET.fromstring(_fixture(plugin_body=body, managed_body=_PIN)).iter(NS + "project"))
        ).bound
        if actual != expected:
            print(f"::error::self-test: '{name}' reported bound={actual}, expected {expected}")
            failures += 1
        else:
            print(f"  ok  {name} -> bound={actual}")
    for name, body, managed, expected in two_container:
        actual = check_project(
            next(ET.fromstring(_fixture(plugin_body=body, managed_body=managed)).iter(NS + "project"))
        ).pinned
        if actual != expected:
            print(f"::error::self-test: '{name}' reported pinned={actual}, expected {expected}")
            failures += 1
        else:
            print(f"  ok  {name} -> pinned={actual}")

    for name, partial in _PARTIAL_PIN_FIXTURES.items():
        part_pinned = check_project(
            next(ET.fromstring(_fixture(plugin_body=partial)).iter(NS + "project"))
        ).pinned
        if part_pinned:
            print(f"::error::self-test: '{name}' was reported as pinned")
            failures += 1
        else:
            print(f"  ok  {name} -> not pinned")

    cases = [(n, _fixture(plugin_body=b)) for n, b in _PERMISSIVE_FIXTURES.items()]
    cases += [(n, _fixture(properties=p)) for n, p in _PERMISSIVE_PROPERTY_FIXTURES.items()]
    for name, xml in cases:
        project = next(ET.fromstring(xml).iter(NS + "project"))
        found = check_project(project).findings
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
        unpinned_path = os.path.join(tmp, "unpinned.xml")
        with open(strict_path, "w", encoding="utf-8") as handle:
            handle.write(_fixture(plugin_body=_PIN + _EXEC_BOUND))
        with open(permissive_path, "w", encoding="utf-8") as handle:
            handle.write(_fixture(plugin_body="<configuration><failOnError>false</failOnError></configuration>"))
        # No permissive VALUE anywhere — just the pin deleted. This is the #205 regression, and the
        # whole reason the pin must be required rather than merely tolerated.
        with open(unpinned_path, "w", encoding="utf-8") as handle:
            handle.write(_fixture())

        roundtrips = [
            ("run_check: pinned pom, --require satisfied", strict_path, ["fixture"], 0),
            ("run_check: pinned pom, --require missing a module", strict_path, ["absent-module"], 1),
            ("run_check: permissive pom", permissive_path, [], 1),
            ("run_check: pin deleted (#205 regression)", unpinned_path, [], 1),
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
