#!/bin/sh
# Builds the jlink-based distribution into target/dist/jriver:
#   river  bin/  lib/  drivers/
# bin/ and lib/ are a minimal Java runtime made by jlink (modules found with jdeps) plus lib/river.jar.
# Needs a JDK (jlink, jdeps) and target/river.jar (mvn package). The runtime is the JDK that runs this script.
set -eu
cd "$(dirname "$0")"
jar=target/river.jar
[ -f "$jar" ] || { echo "dist: $jar not found, run mvn package first" >&2; exit 2; }
out=target/dist/jriver
rm -rf "$out"

modules=$(jdeps --ignore-missing-deps --multi-release base --print-module-deps "$jar")
# jdeps does not see what is loaded reflectively: charsets, the TLS provider, jdk.crypto.ec
modules="$modules,jdk.charsets,jdk.crypto.ec,jdk.localedata"

jlink --add-modules "$modules" --output "$out" \
  --strip-debug --no-header-files --no-man-pages --compress zip-6
# the runtime image is the distribution root: bin/ lib/ (conf/ legal/ release are part of it)
cp "$jar" "$out/lib/river.jar"
cp river "$out/river"
cp -R drivers "$out/drivers"
chmod +x "$out/river" "$out"/drivers/*/*
echo "$out"
