#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
device_apk="$project_dir/device/build/outputs/apk/debug/device-debug.apk"
embedded_apk="$project_dir/app/build/generated/device-apk/debug/assets/rokid-glass-device.apk"

"$project_dir/gradlew" -p "$project_dir" :device:lintDebug :app:assembleDebug

echo "Device APK: $device_apk"
echo "Device APK vložená do telefonního buildu: $embedded_apk"
