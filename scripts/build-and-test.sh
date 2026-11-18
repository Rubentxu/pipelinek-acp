#!/usr/bin/env bash
# Compile and unit-test against the sealed published-SDK bundle. The host version the
# provenance range is derived from is measured from the installed `pipelinek` itself, never
# hardcoded — a number written here would be a second answer, and a stale one.
set -eu
cd "$(dirname "$0")/.."
sdk_repo="${ACP_SDK_REPO:-/tmp/pksdk}"
host="$(pipelinek version | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
# The build-guard kills JVM builds launched outside dev-jvm.slice, and the asdf gradle shim
# refuses anything without a pinned version — so the absolute binary, an explicit JAVA_HOME,
# and systemd-run --scope inside the slice, the way every gate row here is paid.
gradle_bin="${GRADLE_BIN:-$HOME/.asdf/installs/gradle/8.14.5/bin/gradle}"
# Forced, not defaulted: the host may hand its step a JAVA_HOME on a JDK Gradle 8.14.5 cannot
# run on (Temurin 25 fails toolchain resolution), and honoring it turns the gate red for the
# wrong reason. ACP_JAVA_HOME overrides when another JDK is deliberately wanted.
export JAVA_HOME="${ACP_JAVA_HOME:-$HOME/.asdf/installs/java/temurin-21.0.8+9.0.LTS}"
systemd-run --user --scope --slice=dev-jvm.slice \
  "$gradle_bin" -q test operatorJar \
  -PsdkRepo="$sdk_repo" \
  -PsdkVersion="$host" \
  -PpipelinekHostVersion="$host"
