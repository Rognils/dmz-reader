#!/usr/bin/env bash
# DMZ Reader – omstartstestet för lässtatus (FAS 4).
#
# Läser några sidor i 1986 nr 1, tvångsstoppar appen medan läsaren är öppen och
# kontrollerar i en ny process att Fortsätt läsa öppnar rätt sida. Ett
# instrumenterat test kan inte stoppa sin egen process, så de två delarna körs
# härifrån med ett "am force-stop" emellan.
#
# Kräver en ansluten telefon med appen och testpaketet installerade:
#   ./gradlew :app:installDebug :app:installDebugAndroidTest
set -euo pipefail

PACKAGE=se.c64.dmzreader
RUNNER=$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner
CLASS=$PACKAGE.ReadingStateDeviceTest

run_phase() {
    local output
    output=$(adb shell am instrument -w -e class "$CLASS#$2" -e dmzPhase "$1" "$RUNNER")
    echo "$output"
    grep -q "^OK (1 test)" <<<"$output" || { echo "Del $1 misslyckades." >&2; exit 1; }
}

run_phase 1 restartPhase1ReadPages
adb shell am force-stop "$PACKAGE"
echo "Appen är tvångsstoppad."
run_phase 2 restartPhase2ContinueReading
echo "Omstartstestet gick igenom."
