#!/usr/bin/env bash
set -euo pipefail

phone_project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
device_project_dir="$(cd "$phone_project_dir/../rokid-glass-device" && pwd)"
device_apk="$device_project_dir/app/build/outputs/apk/debug/app-debug.apk"
target_dir="$phone_project_dir/app/src/main/assets"
target_apk="$target_dir/rokid-glass-device.apk"

"$device_project_dir/gradlew" -p "$device_project_dir" lintDebug assembleDebug

mkdir -p "$target_dir"
cp "$device_apk" "$target_apk"

echo "Device APK vložena do: $target_apk"
