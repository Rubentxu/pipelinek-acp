#!/usr/bin/env bash
# =============================================================================
# host-sdk.sh — the pair every row of this gate measures against: the SDK this
# plugin compiles against, and the host that admits it. ONE answer, shared.
# =============================================================================
#
# WHY THE TWO HALVES CANNOT BE PICKED APART. The artifact declares an api range
# derived from both: the floor is the newer of (SDK compiled, host measured) and
# the ceiling is the next minor of the SDK. That range is EMPTY unless the host
# sits in the SDK's own minor — a host from a later minor has a floor above the
# ceiling, and admission refuses a plugin whose range admits nothing. The host is
# therefore the SDK's train, and a gate that resolved the two halves separately
# would certify a plugin no host can admit.
#
# WHERE THEY COME FROM. The SDK bundle is the INPUT (ACP_SDK_REPO, default
# /tmp/pksdk) and it names its own version: the artefacts carry it in their path.
# The host is the installed `pipelinek` of that version, put in front of the PATH
# so the build stage and every e2e row talk to the same executable. No version
# number is written in this tree: a number written here would be a second answer,
# and a stale one.
#
# WHAT IT REFUSES, because each refusal is a way this could pass without
# measuring anything:
#
#   * a bundle with no version, or with several: the gate would be compiling
#     against an SDK nobody chose — and "the first one" is a guess, not a
#     measurement;
#   * a bundle whose version has no installed host: the artifact would declare a
#     range no host on this machine can be inside of, and the e2e rows would run
#     against a different host than the build measured.
#
# Sourced, not executed.
# =============================================================================

# The SDK bundle the build resolves against: the environment first, then the
# default the gate documents. The environment wins because it is the more
# deliberate of the two, and a bundle is a property of the machine, not of the
# tree.
ACP_SDK_BUNDLE="${ACP_SDK_REPO:-/tmp/pksdk}"

# The version the bundle carries: exactly one, or nothing to measure against.
acp_sdk_version() {
    local coordinate="$ACP_SDK_BUNDLE/dev/rubentxu/pipelinek/pipelinek-domain"
    local versions count
    versions="$(ls -1 "$coordinate" 2>/dev/null | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' || true)"
    count="$(printf '%s\n' "$versions" | grep -c . || true)"
    case "$count" in
        1) printf '%s\n' "$versions" ;;
        0)
            echo "the SDK bundle '$ACP_SDK_BUNDLE' carries no versioned SDK" >&2
            return 1
            ;;
        *)
            echo "the SDK bundle '$ACP_SDK_BUNDLE' carries several SDK versions:" >&2
            printf '  %s\n' $versions >&2
            echo "the gate cannot guess which one the operator meant" >&2
            return 1
            ;;
    esac
}

# Put the host of [sdk_version] in front of the PATH and print its version.
#
# The host is found through asdf, the manager that installed it: the tree names
# no installation path, because a path written here names the house of whoever
# wrote it and stops working on the next machine.
acp_select_host() {
    local sdk_version="$1" dir
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
    # range the build derives is measured, so it is measured here.
    pipelinek version | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1
}
