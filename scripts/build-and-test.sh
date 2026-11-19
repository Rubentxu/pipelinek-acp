#!/usr/bin/env bash
# Compile and unit-test against the published-SDK bundle. The SDK the plugin compiles
# against and the host that admits it are ONE pair, resolved by scripts/host-sdk.sh:
# the bundle names the SDK version, and the host is the installed one of that version.
# The host version the provenance range is derived from is MEASURED from that host, never
# hardcoded here — a number written in this file would be a second answer, and a stale one.
set -eu
cd "$(dirname "$0")/.."
. scripts/host-sdk.sh

sdk_version="$(acp_sdk_version)"
host_version="$(acp_select_host "$sdk_version")"
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
  -PsdkRepo="$ACP_SDK_BUNDLE" \
  -PsdkVersion="$sdk_version" \
  -PpipelinekHostVersion="$host_version"
