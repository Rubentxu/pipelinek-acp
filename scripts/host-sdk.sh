#!/usr/bin/env bash
# =============================================================================
# host-sdk.sh — the pair every row of this gate measures against: the SDK this
# plugin compiles against, and the host that admits it. ONE answer, shared.
# =============================================================================
#
# WHY THE TWO HALVES CANNOT BE PICKED APART. The artifact declares an api range
# derived from both: the floor is the newer of (SDK compiled, host measured) and
# the ceiling is the next minor of the SDK. For the host that will run the
# artifact to be INSIDE that range, the host must sit in the SDK's own minor —
# an older host falls OUTSIDE the range and admission refuses the plugin, a
# later one makes the floor reach the ceiling and the range empty. The host is
# therefore the SDK's train, and a gate that resolved the two halves separately
# would certify a plugin no host can admit.
#
# WHERE THEY COME FROM. The SDK bundle is the INPUT (ACP_SDK_REPO, default
# /tmp/pksdk) and it names its own version. The host is the installed
# `pipelinek` of that version, put in front of the PATH so the build stage and
# every e2e row talk to the same executable. No version number is written in
# this tree: a number written here would be a second answer, and a stale one.
#
# WHAT IT REFUSES, because each refusal is a way this could pass without
# measuring anything:
#
#   * a bundle whose coordinates do not all carry the SAME version: the build
#     resolves several of them, and a bundle updated half way would compile
#     against a MIXED set while declaring the version of whichever coordinate
#     was read first;
#   * a coordinate carrying no version, or several: a guess is not a
#     measurement;
#   * an installed host whose own version is not the one asked for: the range
#     would be derived from a pair that does not exist, and the failure would
#     surface as a domain contract error instead of a named mismatch.
#
# Sourced, not executed.
# =============================================================================

# The SDK bundle the build resolves against: the environment first, then the
# default the gate documents. The environment wins because it is the more
# deliberate of the two, and a bundle is a property of the machine, not of the
# tree.
ACP_SDK_BUNDLE="${ACP_SDK_REPO:-/tmp/pksdk}"

# The version a coordinate of the bundle carries: exactly one, or nothing to
# measure against. Pre-release suffixes count as versions: the build resolves
# them (`sdkVersion.split("-")[0]`), so a rule that ignored them would call a
# resolvable bundle an empty one.
acp_coordinate_version() {
    local coordinate="$1" versions count
    versions="$(ls -1 "$coordinate" 2>/dev/null | grep -E '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$' | sort -u)"
    count="$(printf '%s\n' "$versions" | grep -c . || true)"
    case "$count" in
        1) printf '%s\n' "$versions" ;;
        0)
            echo "the coordinate '$(basename "$coordinate")' carries no versioned SDK" >&2
            return 1
            ;;
        *)
            echo "the coordinate '$(basename "$coordinate")' carries several SDK versions:" >&2
            printf '  %s\n' $versions >&2
            return 1
            ;;
    esac
}

# The version every coordinate of the bundle agrees on.
acp_sdk_version() {
    local root="$ACP_SDK_BUNDLE/dev/rubentxu/pipelinek"
    local coordinate version agreed=""
    if [ ! -d "$root" ]; then
        echo "the SDK bundle '$ACP_SDK_BUNDLE' carries no dev/rubentxu/pipelinek coordinates" >&2
        return 1
    fi
    for coordinate in "$root"/*/; do
        [ -d "$coordinate" ] || continue
        version="$(acp_coordinate_version "$coordinate")" || return 1
        if [ -n "$agreed" ] && [ "$agreed" != "$version" ]; then
            echo "the SDK bundle mixes versions: '$(basename "$coordinate")' carries $version" >&2
            echo "and another coordinate carries $agreed" >&2
            return 1
        fi
        agreed="$version"
    done
    if [ -z "$agreed" ]; then
        echo "the SDK bundle '$ACP_SDK_BUNDLE' carries no versioned SDK" >&2
        return 1
    fi
    printf '%s\n' "$agreed"
}

# Put the host of [sdk_version] in front of the PATH, after checking that the
# installed host really is that version.
#
# The host is found through asdf, the manager that installed it: the tree names
# no installation path, because a path written here names the house of whoever
# wrote it and stops working on the next machine.
acp_use_host() {
    local sdk_version="$1" dir measured
    dir="$(asdf where pipelinek "$sdk_version" 2>/dev/null || true)"
    if [ -z "$dir" ] || [ ! -x "$dir/bin/pipelinek" ]; then
        echo "no installed pipelinek $sdk_version to admit a plugin compiled against SDK $sdk_version" >&2
        echo "install it (asdf install pipelinek $sdk_version), or point ACP_SDK_REPO at the" >&2
        echo "bundle of the host you have installed" >&2
        return 1
    fi
    PATH="$dir/bin:$PATH"
    export PATH
    # The host's own version is what it says it is, not what we asked for: the
    # range the build derives is measured, so it is measured here — and a host
    # that disagrees with the directory it was found in is refused here, with a
    # named mismatch, instead of producing an empty range further down.
    measured="$(pipelinek version | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
    if [ "$measured" != "$sdk_version" ]; then
        echo "the installed host at '$dir' reports version ${measured:-unknown}," >&2
        echo "but the SDK bundle carries $sdk_version" >&2
        return 1
    fi
}

# The version of the host in front of the PATH, measured.
acp_host_version() {
    pipelinek version | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1
}
