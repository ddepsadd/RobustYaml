#!/usr/bin/env bash
# Runs the index measurements against a Space Station 14 checkout.
#
#   ./gradlew :compileKotlin
#   tools/measure/run.sh ~/RiderProjects/ss14-wega [MeasureHoles|MeasureReferences|MeasureMigrations]
#
# The indexer companions are called by reflection, so the Rider distribution has to be on the
# classpath. ID.create reaches for PathManager.getHomeDir, hence the empty product-info.json.
set -euo pipefail

content=${1:?usage: run.sh <ss14 checkout> [measurement]}
only=${2:-}

here=$(cd "$(dirname "$0")" && pwd)
project=$(cd "$here/../.." && pwd)
classes=$project/build/classes/kotlin/main

if [ ! -d "$classes" ]; then
  echo "no compiled classes at $classes; run ./gradlew :compileKotlin first" >&2
  exit 1
fi

dist=$(ls -d "$HOME"/.gradle/caches/*/transforms/*/transformed/riderRD-* 2>/dev/null | head -1)
if [ -z "$dist" ]; then
  echo "no unpacked Rider distribution under ~/.gradle/caches" >&2
  exit 1
fi

stdlib=$(find "$HOME/.gradle/caches/modules-2" -name 'kotlin-stdlib-2.*.jar' ! -name '*sources*' 2>/dev/null |
  sort -V | tail -1)
if [ -z "$stdlib" ]; then
  echo "no kotlin-stdlib jar under ~/.gradle/caches/modules-2" >&2
  exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/home"
echo '{}' >"$work/home/product-info.json"

# The stdlib is on the compile path for `Function1` alone: a measurement that hands a lambda to a
# Kotlin function needs the interface, and reflection over plugin classes still needs nothing else.
javac -nowarn -cp "$stdlib" -d "$work" "$here"/*.java

for measurement in ${only:-MeasureHoles MeasureReferences MeasureMigrations MeasureRequired MeasureScalars MeasureLocalization MeasureTags MeasureFlags MeasureUsages MeasureLocaleRename MeasureAliases MeasureEntityLoc MeasureGuidebook MeasureTypedIds MeasureSiblings MeasureDeadLocale}; do
  echo "== $measurement"
  java -Didea.home.path="$work/home" \
    -cp "$work:$classes:$stdlib:$dist/lib/*:$dist/plugins/yaml/lib/*" \
    "$measurement" "$content"
  echo
done
