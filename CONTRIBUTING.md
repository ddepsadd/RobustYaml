# Contributing

## Signing off

This project uses the [Developer Certificate of Origin 1.1](https://developercertificate.org/).
It is not a copyright assignment: you keep the copyright on what you write, and you state that you
have the right to contribute it under the licence of this project.

Sign off every commit:

```
git commit -s
```

which appends a `Signed-off-by: Name <email>` line. Contributions are accepted under
**GPL-3.0-or-later**, the licence in [LICENSE](LICENSE).

## Building

```
./gradlew :runIde
```

Never with `-x`: skipping `compileKotlin` or `compileDotNet` leaves the sandbox running the previous
build, and it looks exactly like a change that does not work.

The .NET side is regenerated separately after a change to the rd model:

```
./gradlew :protocol:rdgen
```

## What guards what

Two kinds of checks, and they are not interchangeable.

**Tests** (`./gradlew :test`) cover everything structural: which node a tag hangs on, what counts as
a segment of a path, where a dash lands after Enter. These run without a project, an index or the
backend — a plain `ParsingTestCase` over the YAML parser.

**Measurements** (`tools/measure/run.sh <path to an SS14 checkout>`) cover everything that depends on
data: the indices, the rules for reading values, references, localization. They call the shipping
code by reflection rather than a re-implementation of it, so a measurement that disagrees with the
plugin is a bug in one of the two, never a difference of opinion. Several of them are guards and
exit non-zero — `MISSING`, `LEFTOVER`, `FALSE POSITIVES`, `NOT AN ID`, `SPELLED OUT SOMEWHERE`.

A change to a rule that reads content is expected to come with the number it was measured on.

## Platform APIs are checked, not remembered

Signatures are verified against the Rider distribution rather than recalled:

```
unzip -Z1 <riderRD-*.zip>            # find the jar
javap -c -p -cp <jar> <class>        # read the bytecode
```

Most of the non-obvious decisions in this plugin exist because the platform does something other
than what its name suggests, and each of those is written down in `.claude/CLAUDE.md` together with
the evidence. Read the entry before changing the code it describes; if the entry turns out to be
wrong, correct it in the same commit.
