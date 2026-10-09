#!/usr/bin/env bash
# DMZ Reader – kör hela den instrumenterade sviten på telefonen och visar att
# användarens data är orörd.
#
# Testerna körs med se.c64.dmzreader.IsolatedTestRunner, som ger appen en egen
# databas, egna inställningar, eget filesDir och eget cacheDir under
# app_dmz_test/. Skriptet tar ett fingeravtryck av den riktiga datan före och
# efter körningen och jämför dem:
#
#   - SHA-256 för dmz-reader.db efter checkpoint (görs på en kopia på datorn)
#   - SHA-256 för databasfilerna och inställningsfilen som de ligger på telefonen
#   - radantalet i varje tabell
#   - namn, storlek, tid och SHA-256 för varje fil i läs-cachen, nedladdningarna
#     och filesDir/data
#
# PDF:en som testerna läser i (1986 nr 1) läggs på telefonen från datorns
# cache/pdf/, så att arkivservern inte belastas mer än nödvändigt. Två tester
# prövar själva nedladdningen och hämtar den filen från arkivet (15 MB var).
#
# Kräver en ansluten telefon med appen och testpaketet installerade:
#   ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
#   adb install -r app/build/outputs/apk/debug/app-debug.apk
#   adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
#
# Användning:  tools/device_test.sh [katalog för fingeravtrycken]
#              tools/device_test.sh KATALOG --only [argument till am instrument]
#
# Med --only körs bara det som argumenten väljer, till exempel en testklass, med
# samma fingeravtryck före och efter:
#   tools/device_test.sh /tmp/hq --only -e class se.c64.dmzreader.HqDeviceTest
# Fler PDF:er än 1986 nr 1 läggs på telefonen med DMZ_SEED_ISSUES="issueId issueId".
set -euo pipefail

PACKAGE=se.c64.dmzreader
RUNNER=$PACKAGE.test/$PACKAGE.IsolatedTestRunner
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${1:-$(mktemp -d)}
ONLY=()
if [ "${2:-}" = "--only" ]; then
    ONLY=("${@:3}")
fi
SEEDS=/data/local/tmp/dmz-test-pdfs
mkdir -p "$OUT"

# Fingeravtrycket av användarens data. Appen ska vara stoppad, så att filerna är stilla.
fingerprint() {
    local name=$1 work
    adb shell am force-stop "$PACKAGE"
    work=$(mktemp -d)
    for file in dmz-reader.db dmz-reader.db-wal dmz-reader.db-shm; do
        adb exec-out run-as "$PACKAGE" cat "databases/$file" > "$work/$file" 2>/dev/null || true
    done
    {
        echo "== databasfilerna på telefonen (SHA-256, storlek)"
        adb shell "run-as $PACKAGE sh -c 'cd databases && sha256sum dmz-reader.db dmz-reader.db-wal 2>/dev/null; ls -l dmz-reader.db dmz-reader.db-wal 2>/dev/null'" | awk '{ if (NF > 2) print $5, $NF; else print }'
        echo "== dmz-reader.db efter checkpoint, och radantal"
        python3 - "$work/dmz-reader.db" <<'PY'
import hashlib, sqlite3, sys
path = sys.argv[1]
db = sqlite3.connect(path)
db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
tables = [row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")]
counts = {table: db.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0] for table in tables}
db.close()
print(hashlib.sha256(open(path, "rb").read()).hexdigest(), "dmz-reader.db (efter checkpoint)")
for table, count in counts.items():
    print(f"{count:>6}  {table}")
PY
        echo "== filer (SHA-256, storlek, ändrad, namn)"
        adb shell "run-as $PACKAGE sh -c 'for d in cache/pdf files/issues files/data files/datastore; do [ -d \$d ] || { echo \"\$d: finns inte\"; continue; }; for f in \$d/*; do [ -f \"\$f\" ] || continue; echo \"\$(sha256sum \"\$f\" | cut -d\" \" -f1) \$(stat -c \"%s %y\" \"\$f\" | cut -d. -f1) \$f\"; done; done'"
        echo "== testkataloger (ska vara tomt)"
        adb shell "run-as $PACKAGE ls app_dmz_test 2>/dev/null" || true
    } > "$OUT/$name.txt"
    rm -rf "$work"
}

run() {
    local output
    output=$(adb shell am instrument -w "$@" "$RUNNER")
    # Vid ett fel visas också felmeddelandet och var det uppstod.
    echo "$output" | grep -E -A4 "^[0-9]+\) " || true
    echo "$output" | grep -E "^OK|^FAILURES|^Tests run" || true
    grep -q "^OK (" <<<"$output"
}

echo "Fingeravtryck före…"
fingerprint before

echo "Lägger testernas PDF på telefonen…"
adb shell "rm -rf $SEEDS && mkdir -p $SEEDS"
for issue in datormagazin-1986-01 ${DMZ_SEED_ISSUES:-}; do
    if [ -f "$ROOT/cache/pdf/$issue.pdf" ]; then
        adb push "$ROOT/cache/pdf/$issue.pdf" "$SEEDS/$issue.pdf" >/dev/null
    else
        echo "  $issue finns inte i datorns cache/pdf/. Testerna hämtar den från arkivet."
    fi
done
adb shell "chmod 644 $SEEDS/*.pdf 2>/dev/null" || true

status=0
finish() {
    adb shell "rm -rf $SEEDS"
    echo "Fingeravtryck efter…"
    fingerprint after
    if diff -u "$OUT/before.txt" "$OUT/after.txt" > "$OUT/diff.txt"; then
        echo "ANVÄNDARENS DATA ÄR OFÖRÄNDRAD (fingeravtrycken är identiska): $OUT/before.txt"
    else
        echo "FINGERAVTRYCKEN SKILJER SIG:" >&2
        cat "$OUT/diff.txt" >&2
        status=1
    fi
    exit $status
}

if [ ${#ONLY[@]} -gt 0 ]; then
    echo "Kör: ${ONLY[*]}"
    run "${ONLY[@]}" || status=1
    finish
fi

echo "Hela sviten…"
run || status=1

echo "Omstartstestet (tvångsstopp mellan delarna)…"
CLASS=$PACKAGE.ReadingStateDeviceTest
run -e dmzTestRoot restart -e dmzKeepRoot true -e dmzPhase 1 -e class "$CLASS#restartPhase1ReadPages" || status=1
adb shell am force-stop "$PACKAGE"
run -e dmzTestRoot restart -e dmzKeepRoot true -e dmzPhase 2 -e class "$CLASS#restartPhase2ContinueReading" || status=1
# En sista körning utan dmzKeepRoot tar bort katalogen.
run -e dmzTestRoot restart -e class "$PACKAGE.BackupVersionDeviceTest" >/dev/null || status=1

echo "Lässession vid tvångsstopp…"
STATS=$PACKAGE.StatisticsDeviceTest
adb logcat -c
adb shell am instrument -w -e dmzTestRoot forcestop -e dmzKeepRoot true -e dmzPhase 1 \
    -e class "$STATS#forceStopPhase1Read" "$RUNNER" >/dev/null 2>&1 &
phase1=$!
started=""
for _ in $(seq 1 240); do
    started=$(adb logcat -d -s DmzStatistics | grep -c "LÄSER sidbyte" || true)
    [ "$started" -ge 1 ] && break
    sleep 1
done
if [ "${started:-0}" -ge 1 ]; then
    # Läsningen började 18 s före sidbytet. Läs 12 s till och stoppa sedan appen mitt i läsningen.
    sleep 12
    adb shell am force-stop "$PACKAGE"
    wait $phase1 2>/dev/null || true
    echo "Appen är tvångsstoppad efter cirka 30 s läsning."
    run -e dmzTestRoot forcestop -e dmzKeepRoot true -e dmzPhase 2 -e dmzReadSeconds 30 \
        -e class "$STATS#forceStopPhase2SessionSurvived" || status=1
    adb logcat -d -s DmzStatistics | grep -o "TVÅNGSSTOPP.*" || true
else
    echo "Läsaren öppnades aldrig i den första delen." >&2
    adb shell am force-stop "$PACKAGE"
    status=1
fi
run -e dmzTestRoot forcestop -e class "$PACKAGE.BackupVersionDeviceTest" >/dev/null || status=1


finish
