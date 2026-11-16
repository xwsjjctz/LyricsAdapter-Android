#!/usr/bin/env bash
# Fails unless an APK is signed, carries the version in gradle.properties and
# loads on 16 KB page devices. Both workflows run it on what they build.
#
#   ANDROID_HOME=<sdk> tools/ci/verify_apk.sh app/build/outputs/apk/release/app-release.apk
set -euo pipefail

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  echo "usage: $0 <apk>" >&2
  exit 2
fi
apk="$1"
root="$(cd "$(dirname "$0")/../.." && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ]; then
  echo "::error::ANDROID_HOME is not set." >&2
  exit 2
fi
build_tools="$(ls -d "$sdk"/build-tools/* | sort -V | tail -n 1)"
echo "Build tools: $build_tools"

# apksigner exits non-zero for an unsigned APK. How it words the signer line
# differs between versions, so only the digest label is looked for.
certificates="$("$build_tools/apksigner" verify --print-certs "$apk")"
echo "$certificates" | grep -E 'certificate (DN|SHA-256 digest)' || {
  echo "::error::apksigner reported no signing certificate for $apk."
  echo "$certificates"
  exit 1
}

expected="$(sed -n 's/^appVersionName=//p' "$root/gradle.properties")"
built="$("$build_tools/aapt2" dump badging "$apk" | sed -n "s/^package:.* versionName='\([^']*\)'.*/\1/p")"
if [ -z "$expected" ] || [ "$built" != "$expected" ]; then
  echo "::error::The APK reports version '$built', gradle.properties says '$expected'."
  exit 1
fi
echo "Version: $built"

python3 "$root/tools/ci/check_page_size.py" "$apk"
