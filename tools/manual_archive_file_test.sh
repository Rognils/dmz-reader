#!/usr/bin/env bash
# DMZ Reader – manuell kontroll av läsaren mot en riktig arkivfil.
#
# KRÄVER EN ARKIVFIL SOM DU HAR SKAFFAT SJÄLV. Ingen sådan fil finns i repot, och
# skriptet hämtar ingen: du anger sökvägen till en PDF som redan ligger på datorn.
#
# Den vanliga testsviten (tools/device_test.sh) läser i ett påhittat nummer och
# behöver ingen arkivfil. Det här skriptet prövar det som bara en skannad tidning
# kan visa: att telefonens PDF-motor öppnar arkivets filer, hur lång tid sidorna
# tar att rendera, zoom i en skannad sida, uppslag i liggande läge och minnet i de
# tyngsta numren. Resultatet är mätvärden i loggen som en människa bedömer, inte
# bara godkänt eller underkänt.
#
# Skriptet körs för hand. Det anropas aldrig från device_test.sh, från någon
# Gradle-uppgift eller från något annat skript.
#
# Testerna körs i utvecklingsbygget (se.c64.dmzreader.debug) med isolerad lagring,
# som i device_test.sh, och med spärren mot arkivservern påslagen: ingenting hämtas.
# Filen läggs tillfälligt i /data/local/tmp/dmz-test-pdfs/ och tas bort efteråt.
#
# Användning:  tools/manual_archive_file_test.sh PDF-FIL [issueId] [katalog för resultatet]
#
#   tools/manual_archive_file_test.sh ~/arkiv/datormagazin-1986-01.pdf
#   tools/manual_archive_file_test.sh ~/arkiv/199515.pdf datormagazin-1995-15
#
# Utan issueId tas det ur filnamnet. Tre nummer har kontroller:
#   datormagazin-1986-01   genomläsning med renderingstider, zoom, uppslag och rotation,
#                          och HQ i liggande läge
#   datormagazin-1995-14   genomläsning, zoom och förrendering (92 sidor)
#   datormagazin-1995-15   belastning och minne, bland annat arkivets största sida
#
# Kräver en ansluten telefon med utvecklingsbygget och testpaketet installerade:
#   ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
#   adb install -r app/build/outputs/apk/debug/app-debug.apk
#   adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
set -euo pipefail

if [ $# -lt 1 ]; then
    sed -n '2,35p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
fi

FILE=$1
ISSUE=${2:-$(basename "$FILE" .pdf)}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${3:-$(mktemp -d)}
TEST_PACKAGE=se.c64.dmzreader.debug
NAMESPACE=se.c64.dmzreader
RUNNER=$TEST_PACKAGE.test/$NAMESPACE.IsolatedTestRunner
SEEDS=/data/local/tmp/dmz-test-pdfs
READER=$NAMESPACE.ReaderDeviceTest
STATE=$NAMESPACE.ReadingStateDeviceTest

if [ ! -f "$FILE" ]; then
    echo "Filen finns inte: $FILE" >&2
    echo "Skriptet kräver en arkivfil som du har skaffat själv. Det hämtar ingenting." >&2
    exit 2
fi
if [ "$(head -c 5 "$FILE")" != "%PDF-" ]; then
    echo "Filen är ingen PDF: $FILE" >&2
    exit 2
fi

case "$ISSUE" in
    datormagazin-1986-01)
        TESTS="$READER#verifyReadThrough198601,$READER#verifyZoom198601,$READER#verifyLandscapeSpreadAndRotation,$NAMESPACE.HqDeviceTest#hqInLandscapeShowsSinglePage" ;;
    datormagazin-1995-14)
        TESTS="$READER#verifyReadThrough199514,$READER#verifyZoom199514,$STATE#verifyPrerender199514" ;;
    datormagazin-1995-15)
        TESTS="$READER#verifyLoad199515,$READER#verifyMemoryOnPage15,$READER#verifyMemoryOpenedOnPage15,$READER#verifyMemoryPageByPage" ;;
    *)
        echo "Det finns inga manuella kontroller för $ISSUE." >&2
        echo "Välj datormagazin-1986-01, datormagazin-1995-14 eller datormagazin-1995-15." >&2
        exit 2 ;;
esac

# Filen ska vara den som arkivindexet beskriver. Annars kastar läsaren den och försöker hämta om.
EXPECTED=$(python3 - "$ROOT" "$ISSUE" <<'PY'
import json, sys
inventory = json.load(open(f"{sys.argv[1]}/data/archive_inventory.json", encoding="utf-8"))
print(next(item for item in inventory["issues"] if item["issueId"] == sys.argv[2])["ocrPdfSize"])
PY
)
SIZE=$(stat -c %s "$FILE")
if [ "$SIZE" != "$EXPECTED" ]; then
    echo "Filen är $SIZE byte, men arkivindexet anger $EXPECTED byte för $ISSUE." >&2
    echo "Det är inte den version av arkivfilen som appen väntar sig." >&2
    exit 2
fi

mkdir -p "$OUT"
echo "MANUELL KONTROLL med en arkivfil du har skaffat själv: $FILE ($SIZE byte) som $ISSUE"
echo "Ingenting hämtas från arkivservern."

"$ROOT/tools/device_test.sh" "$OUT" --fingerprint before >/dev/null
adb logcat -G 16M
adb logcat -c
cleanup() { adb shell "rm -rf $SEEDS" >/dev/null 2>&1 || true; }
trap cleanup EXIT
adb shell "rm -rf $SEEDS && mkdir -p $SEEDS"
adb push "$FILE" "$SEEDS/$ISSUE.pdf" >/dev/null
adb shell "chmod 644 $SEEDS/$ISSUE.pdf"

status=0
output=$(adb shell am instrument -w -e dmzVerify true -e class "$TESTS" "$RUNNER")
echo "$output" > "$OUT/instrument.txt"
echo "$output" | grep -E -A4 "^[0-9]+\) " || true
echo "$output" | grep -E "^OK|^FAILURES|^Tests run" || true
grep -q "^OK (" <<<"$output" || status=1

# Mätvärdena: renderingstider, zoom, förrendering och minne.
adb logcat -d -s DmzVerify > "$OUT/matvarden.txt" 2>/dev/null || true
adb logcat -d -s DmzGuard DmzNet > "$OUT/arkivkontroll.txt" 2>/dev/null || true
blocked=$(grep -c "SPÄRRAD" "$OUT/arkivkontroll.txt" || true)
requests=$(grep -cE "(GET|HEAD|POST) dmzarkivet\.se" "$OUT/arkivkontroll.txt" || true)
echo "ARKIVET: $requests förfrågningar till dmzarkivet.se, $blocked spärrade försök."
if [ "$requests" != "0" ] || [ "$blocked" != "0" ]; then
    echo "KONTROLLEN FÖRSÖKTE NÅ ARKIVSERVERN. Se $OUT/arkivkontroll.txt" >&2
    status=1
fi

cleanup
"$ROOT/tools/device_test.sh" "$OUT" --fingerprint after >/dev/null
if diff -u "$OUT/before.txt" "$OUT/after.txt" > "$OUT/diff.txt"; then
    echo "Användarens app är orörd (fingeravtrycken är identiska)."
else
    echo "FINGERAVTRYCKEN SKILJER SIG:" >&2
    cat "$OUT/diff.txt" >&2
    status=1
fi
echo "Resultat och mätvärden (matvarden.txt): $OUT"
exit $status
