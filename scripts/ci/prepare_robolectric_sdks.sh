#!/usr/bin/env bash
set -euo pipefail

sdk_dir=${1:?Usage: prepare_robolectric_sdks.sh SDK_DIR}
mkdir -p "$sdk_dir"
staging=$(mktemp -d "$sdk_dir/.download.XXXXXXXX")
trap 'rm -rf -- "$staging"' EXIT

verify() {
    printf '%s  %s\n' "$1" "$2" | sha512sum --check --status
}

fetch() {
    local version=$1 checksum=$2
    local jar="android-all-instrumented-$version.jar"
    local artifact="org/robolectric/android-all-instrumented/$version/$jar"
    local repo

    if [[ -f "$sdk_dir/$jar" ]] && verify "$checksum" "$sdk_dir/$jar"; then
        return
    fi

    for repo in https://repo.maven.apache.org/maven2 https://maven-central.storage-download.googleapis.com/maven2; do
        if curl --fail --silent --show-error --location --retry 3 --retry-max-time 90 \
            --connect-timeout 15 --max-time 180 --output "$staging/$jar" "$repo/$artifact"; then
            if verify "$checksum" "$staging/$jar"; then
                mv -- "$staging/$jar" "$sdk_dir/$jar"
                return
            fi
            echo "Checksum mismatch for $jar from $repo" >&2
        fi
    done
    echo "Could not prepare Robolectric SDK $version" >&2
    return 1
}

# Android APIs 29, 33, 34 and 35 used by the tests with Robolectric 4.15.1.
# Update alongside the Robolectric version or when tests start using another API.
fetch 10-robolectric-5803371-i7 d8907eec0646ae1b71ec306cc61449c76c61a7a08eaca44eb4aa30937cfcf5dcaf7d0938cbea76b3bd85a83646b012ff499faa11cea45e32df7aff62e791951f
fetch 13-robolectric-9030017-i7 0ffcb15c867dcfd87e57b6eef601067de84d23db0e818f8ea8a6035745482a144e91eb65a7f532dd9614ce27eba67204b117b7185058c2aac074dcb26e62acc9
fetch 14-robolectric-10818077-i7 7cdc0b1306e7865f1acd25a363073149654caee5b8e278c5ae37ff4724185724eff39f0b0111800b521e640bb28698d9d2e655134a801569a0f07470e7b5f455
fetch 15-robolectric-12650502-i7 87bdd0341838f5f630e1ff412ab29cb2f06e0f8cb7751f14679d11b4ee6084873001f8f6a8581ae6e139fa7c9a654b632412827430f984ebecc3c2588674f7e7
