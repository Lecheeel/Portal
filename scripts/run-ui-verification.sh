#!/usr/bin/env bash
# Keep screenshot collection in the same shell as the test command, including on failure.
set -uo pipefail
test_status=0
./gradlew :app:connectedX64DebugAndroidTest --stacktrace || test_status=$?
adb pull /sdcard/Download/Portal-ui-captures app/build/ui-captures || true
# This script runs only on the designated UI test device/emulator. Preserve captures
# before clearing its test app, then start a new process without consenting fixtures.
runner='com.system.location.service.test/com.system.location.service.PortalTestRunner'
# Connected tests may uninstall their APKs after finishing.
if ! adb install -r app/build/outputs/apk/x64/debug/app-x64-debug.apk || \
    ! adb install -r app/build/outputs/apk/androidTest/x64/debug/app-x64-debug-androidTest.apk; then
    exit 1
fi
if ! adb shell pm list instrumentation | grep -Fq "$runner"; then
    echo "Packaged PortalTestRunner is not installed" >&2
    exit 1
fi
if ! adb shell pm clear com.system.location.service | grep -q Success; then
    echo "Could not clear the UI test application" >&2
    exit 1
fi
mkdir -p app/build
privacy_status=0
adb shell am instrument -w -r -e freshPrivacy true \
    -e class com.system.location.service.SdkPrivacyUiTest "$runner" \
    2>&1 | tee app/build/privacy-instrumentation.txt || privacy_status=$?
# am instrument may return zero even when a test fails or is skipped.
if ! grep -q 'OK (1 test)' app/build/privacy-instrumentation.txt || \
    grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[234]' app/build/privacy-instrumentation.txt; then
    privacy_status=1
fi
if [ "$test_status" -ne 0 ]; then exit "$test_status"; fi
exit "$privacy_status"
