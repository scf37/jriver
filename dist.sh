#!/bin/sh
# Builds the jlink distribution into target/dist/jriver:
#   river  bin/  lib/  drivers/
# The application is a module (river.jar made modular here) linked into the runtime image, so lib/modules holds
# both the JDK modules and jriver; bin/ and lib/ come from jlink. river is a plain shell script that starts
# bin/java with the module (the launchers jlink can generate are not used on purpose).
# Needs a JDK (jlink, jdeps, javac) and target/river.jar (mvn package). The runtime is the JDK that runs this script.
set -eu
cd "$(dirname "$0")"
jar=target/river.jar
[ -f "$jar" ] || { echo "dist: $jar not found, run mvn package first" >&2; exit 2; }
out=target/dist/jriver
work=target/dist-work
mod=io.github.scf37.river
rm -rf "$out" "$work"
mkdir -p "$work/classes" "$work/mods"

# 1. river.jar -> modular jar. The shaded jar has no module descriptor of its own (the one from snakeyaml is stale).
(cd "$work/classes" && jar xf "../../../$jar" && rm -rf META-INF/versions/9/module-info.class module-info.class)
# java.desktop is only snakeyaml's java.beans for bean mapping, which jriver never uses (SafeConstructor, plain maps)
requires=$(jdeps --ignore-missing-deps --multi-release base --print-module-deps "$jar" \
  | tr ',' '\n' | grep -v -e '^java.desktop$' -e '^java.datatransfer$' | sed 's/^/  requires /; s/$/;/')
cat > "$work/module-info.java" <<MI
open module $mod {
$requires
}
MI
javac -Xlint:-module --release 25 -d "$work/classes" --patch-module "$mod=$work/classes" "$work/module-info.java"
jar --create --file "$work/mods/river.jar" --main-class io.github.scf37.river.Main -C "$work/classes" .

# 2. runtime image with the module in it (charsets and locale data are only found as service providers)
jlink --module-path "$work/mods" --add-modules "$mod,jdk.charsets,jdk.localedata" --output "$out" \
  --strip-debug --no-header-files --no-man-pages --compress zip-6
cp river "$out/river"
cp -R drivers "$out/drivers"
chmod +x "$out/river" "$out"/drivers/*/*
rm -rf "$work"
echo "$out"
