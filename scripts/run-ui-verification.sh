#!/usr/bin/env bash
# Keep screenshot collection in the same shell as the test command, including on failure.
set -uo pipefail
test_status=0
./gradlew :app:connectedX64DebugAndroidTest --stacktrace || test_status=$?
adb pull /sdcard/Download/Portal-ui-captures app/build/ui-captures || true
exit "$test_status"
