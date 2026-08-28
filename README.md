# RobustYaml

Rider support for the YAML prototypes of [Robust Toolbox](https://github.com/space-wizards/RobustToolbox) —
the format the content of Space Station 14 is written in.

The plugin reads the C# of the engine and of the content, so what it knows about a key is what the
game will do with that key: the type of the datafield, the prototype kind behind an id, the members
of an enum, the rules the engine's own serializers apply to a value.

## Install

Settings → Plugins → ⚙ → **Manage Plugin Repositories** → `+`, and paste:

```
https://raw.githubusercontent.com/ddepsadd/RobustYaml/gh-pages/updatePlugins.xml
```

The plugin then appears in the Marketplace tab and updates the usual way. The zip of every release is
also attached to the [releases page](https://github.com/ddepsadd/RobustYaml/releases) if you would
rather install from disk.

There is no JetBrains Marketplace listing, and none for ReSharper: the features live in the IDE half
of Rider, so Visual Studio has nothing to run them on.

Requires **Rider 2026.2**. `Resources` and the prototype directories do not have to be part of the
solution — the plugin indexes them either way, which is what opening an SS14 checkout normally looks
like.

## What it does

**Completion and validation.** Component names, prototype kinds, datafields, prototype ids by the
kind the field declares, enum members, `[FlagsFor]` flags, `ConstantSerializer` constants, colours,
vectors, angles, numbers, booleans, `TimeSpan`, sprite states, localization ids, required datafields,
duplicate ids, polymorphic `!type:` tags. Every value is judged by the rule the engine uses to read
it — `float` and `TimeSpan` disagree about a comma, and so does the plugin.

**Navigation.** Ctrl+click from `parent:` to the declaration, from `type: Sprite` to
`SpriteComponent.cs`, from a resource path to the file, from a state to the frame inside the `.rsi`,
and from a C# string literal straight into the content. Find Usages and Rename cover prototype ids
and localization keys across YAML, `.ftl`, `.cs`, XAML and the guidebook in one refactoring. Ctrl+H
walks the inheritance chain in both directions.

**What the player will see.** The sprite in the gutter and in the hover, a grid of states for an
`.rsi` directory, the entity name and the text of a localization message as inline hints, the
localized name, description and suffix of a prototype in the popup — with Robust markup rendered
rather than printed.

**Editing.** Sequence indentation the way SS14 writes it, live templates, folding, Structure View,
a colour picker for Robust hex, and quick fixes that suggest the nearest real name — including the
one `migration.yml` says the id was renamed to.

## How it works

Two halves. The **frontend** (Kotlin) keeps `FileBasedIndex` indices over the checkout — prototype
ids by kind, datafields, localization messages and their uses, references — and everything that
works without a solution is built on those. The **backend** (C#) is a ReSharper component reached
over the rd protocol; it answers what only a real symbol cache can: the type of a datafield, its
generic substitution along the inheritance chain, the prototype kind behind `ProtoId<T>`, the
inheritors of an abstract type.

Anything that must not wait — the annotator, completion, typed handlers — reads the cached answer
and stays silent until it arrives, because blocking the daemon thread on the protocol scheduler
deadlocks it.

## How this was written

Most of this plugin was written with an AI assistant, and the Kotlin frontend entirely so. The author
checked it as far as someone who does not write Kotlin or Java can — which is not the same thing as a
line-by-line review by someone who does, and is worth saying plainly rather than leaving to be found
out.

What stands in place of that review is measurement. Rules that depend on data are not argued about:
they are run against a full content checkout — some 30 000 prototypes and 56 000 localization
messages — and kept only once the false positives reach zero. Several of those runs are guards that
fail if they ever stop reaching it, which is why they are in CI and in the release. Rules that depend
on the shape of the tree, where a measurement is blind, are covered by 220 tests. And every decision
that reads as arbitrary has its reason and the number it was measured on written down in
`.claude/CLAUDE.md`, which is checked in for exactly that purpose.

The C# backend has been through a review pass; what it found and what was changed is in the history.

If you write Kotlin, a review is the most useful thing this repository can be given.

## Building

```
./gradlew :runIde        # sandbox
./gradlew :test          # 220 cases over PSI and the editor handlers
tools/measure/run.sh <path to an SS14 checkout>
```

The measurements call the shipping code by reflection rather than a copy of it, and several of them
are guards that exit non-zero. [CONTRIBUTING.md](CONTRIBUTING.md) explains what each kind of check is
for and how to sign off a commit.

## Licence

[GPL-3.0-or-later](LICENSE).
