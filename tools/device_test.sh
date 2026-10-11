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
# Testerna når aldrig arkivservern (CLAUDE.md, regel 12) och behöver ingen arkivfil.
# De läser i ett påhittat nummer på 28 sidor (app/src/androidTest/assets/test_issue.pdf,
# gjort av tools/make_test_media.py), som testköraren lägger i testkörningens läs-cache.
# Testerna av nedladdning och omslag går mot en lokal server på telefonen med samma
# påhittade material. Skriptet läser ingenting ur datorns cache/.
#
# Skriptet kontrollerar det efter körningen, på två sätt som inte beror av varandra:
#   - i appens logg räknas förfrågningar till dmzarkivet.se och försök som testernas
#     spärr har stoppat. Båda ska vara 0. Raderna sparas i KATALOG/arkivkontroll.txt.
#   - i systemets nätstatistik (dumpsys netstats) läses hur många byte testappen har
#     tagit emot över wifi och mobilnät under körningen. Trafiken till den lokala
#     servern går inte över något nät och räknas inte. Mer än 1 MB räknas som fel:
#     ett enda omslag ur arkivet är 5 MB och en PDF minst 15.
#
# Testerna körs i utvecklingsbygget (se.c64.dmzreader.debug), som ligger bredvid
# användarens app (se.c64.dmzreader) och inte delar något med den. Fingeravtrycket
# tas av användarens app, och kräver att den går att läsa med run-as (ett debug-bygge).
# Är användarens app ett release-bygge tas i stället ett yttre fingeravtryck (version,
# installationstid, signatur, senaste start och om processen är igång), och det sägs i utskriften.
#
# Kräver en ansluten telefon med utvecklingsbygget och testpaketet installerade:
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
#              tools/device_test.sh KATALOG --fingerprint NAMN
# tar bara ett fingeravtryck till KATALOG/NAMN.txt, för körningar utanför skriptet.
set -euo pipefail

# Användarens app, vars data fingeravtrycket gäller, och utvecklingsbygget som testerna körs i.
# De har olika app-ID och delar ingenting på telefonen.
PACKAGE=se.c64.dmzreader
TEST_PACKAGE=se.c64.dmzreader.debug
NAMESPACE=se.c64.dmzreader
RUNNER=$TEST_PACKAGE.test/$NAMESPACE.IsolatedTestRunner
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
    if ! adb shell "run-as $PACKAGE true" >/dev/null 2>&1; then
        # Ett release-bygge går inte att läsa inifrån. Då tas ett yttre fingeravtryck: det visar
        # om appen har bytts ut, uppdaterats, startats eller fått sin data rensad, men inte
        # innehållet i dess filer. Testerna körs i en annan app, som inte kommer åt dem.
        {
            echo "yttre fingeravtryck: $PACKAGE går inte att läsa med run-as (release-bygge)"
            echo "== paketet (version, installation, signatur)"
            adb shell dumpsys package "$PACKAGE" | grep -E "versionCode=|versionName=|firstInstallTime=|lastUpdateTime=|signatures=|dataDir=" | sed 's/^ *//' | sort -u
            echo "== senaste gången appen syntes eller stoppades (usagestats)"
            adb shell dumpsys usagestats | grep -E "package=$PACKAGE " | grep -E "type=(ACTIVITY_RESUMED|ACTIVITY_PAUSED|ACTIVITY_STOPPED)" | tail -1 | sed 's/^ *//' | cut -d" " -f1-4
            echo "== processen (ska inte vara igång)"
            adb shell pidof "$PACKAGE" || echo "ingen process"
        } > "$OUT/$name.txt"
        return
    fi
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
        adb shell "run-as $TEST_PACKAGE ls app_dmz_test 2>/dev/null" || true
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

# Bara ett fingeravtryck, för körningar som görs utanför skriptet (till exempel av ett annat bygge).
if [ "${2:-}" = "--fingerprint" ]; then
    fingerprint "${3:?namn saknas}"
    echo "$OUT/$3.txt"
    exit 0
fi

echo "Fingeravtryck före…"
fingerprint before
adb logcat -G 16M
adb logcat -c

status=0

# Sparar testkörningens rader om arkivet ur loggen. Anropas innan loggen töms och när allt är klart.
: > "$OUT/arkivkontroll.txt"
collect_archive_log() {
    adb logcat -d -s DmzGuard DmzNet DmzCovers DmzHq 2>/dev/null \
        | grep -E "DmzGuard|dmzarkivet\.se|FTP ansluten" >> "$OUT/arkivkontroll.txt" || true
}

# Antal byte testappen har tagit emot över wifi och mobilnät, enligt systemets statistik per app.
# Loopback, där den lokala testservern svarar, finns inte i den statistiken.
network_received() {
    local uid
    uid=$(adb shell dumpsys package "$TEST_PACKAGE" | grep -m1 -oE "uid=[0-9]+" | cut -d= -f2)
    adb shell dumpsys netstats --poll >/dev/null 2>&1 || true
    adb shell dumpsys netstats detail 2>/dev/null | awk -v uid="uid=$uid " '
        /ident=/ { wanted = index($0, uid) && index($0, "tag=0x0") }
        wanted && /rb=/ { for (i = 1; i <= NF; i++) if ($i ~ /^rb=/) { sub("rb=", "", $i); sum += $i } }
        END { print sum + 0 }'
}
RECEIVED_BEFORE=$(network_received)
MAX_NETWORK_BYTES=1000000

# Inga förfrågningar till arkivet, inga försök som spärren har fått stoppa, och ingen nämnvärd trafik över nätet.
check_archive_untouched() {
    local requests blocked received
    requests=$(grep -cE "(GET|HEAD|POST) dmzarkivet\.se|FTP ansluten till dmzarkivet" "$OUT/arkivkontroll.txt" || true)
    blocked=$(grep -c "SPÄRRAD" "$OUT/arkivkontroll.txt" || true)
    received=$(( $(network_received) - RECEIVED_BEFORE ))
    echo "ARKIVET: $requests förfrågningar till dmzarkivet.se, $blocked spärrade försök. Mottaget över wifi och mobilnät: $received byte."
    echo "netstats: $received byte mottaget över wifi och mobilnät under körningen" >> "$OUT/arkivkontroll.txt"
    if [ "$requests" != "0" ] || [ "$blocked" != "0" ]; then
        echo "TESTERNA FÖRSÖKTE NÅ ARKIVSERVERN. Se $OUT/arkivkontroll.txt" >&2
        status=1
    fi
    if [ "$received" -gt "$MAX_NETWORK_BYTES" ]; then
        echo "TESTAPPEN TOG EMOT $received BYTE ÖVER NÄTET. Testerna ska inte hämta något utifrån." >&2
        status=1
    fi
}

finish() {
    collect_archive_log
    check_archive_untouched
    adb shell "rm -rf $SEEDS"
    echo "Fingeravtryck efter…"
    fingerprint after
    if diff -u "$OUT/before.txt" "$OUT/after.txt" > "$OUT/diff.txt"; then
        if grep -q "^yttre fingeravtryck" "$OUT/before.txt"; then
            echo "ANVÄNDARENS APP ÄR INTE STARTAD, UPPDATERAD ELLER UTBYTT (yttre fingeravtryck, identiska): $OUT/before.txt"
            echo "  Innehållet i appens filer går inte att läsa i ett release-bygge. Testerna körs i $TEST_PACKAGE."
        else
            echo "ANVÄNDARENS DATA ÄR OFÖRÄNDRAD (fingeravtrycken är identiska): $OUT/before.txt"
        fi
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

# Arkivfiler som en manuell kontroll kan ha lämnat kvar tas bort, så att sviten läser i testnumret.
# Med --only används de: de manuella kontrollerna mot arkivet lägger sina filer på telefonen för hand.
adb shell "rm -rf $SEEDS"

echo "Hela sviten…"
run || status=1

echo "Omstartstestet (tvångsstopp mellan delarna)…"
CLASS=$NAMESPACE.ReadingStateDeviceTest
run -e dmzTestRoot restart -e dmzKeepRoot true -e dmzPhase 1 -e class "$CLASS#restartPhase1ReadPages" || status=1
adb shell am force-stop "$TEST_PACKAGE"
run -e dmzTestRoot restart -e dmzKeepRoot true -e dmzPhase 2 -e class "$CLASS#restartPhase2ContinueReading" || status=1
# En sista körning utan dmzKeepRoot tar bort katalogen.
run -e dmzTestRoot restart -e class "$NAMESPACE.BackupVersionDeviceTest" >/dev/null || status=1

echo "Lässession vid tvångsstopp…"
STATS=$NAMESPACE.StatisticsDeviceTest
collect_archive_log
adb logcat -c
# Utskriften sparas, så att det syns varför läsaren inte öppnades om det skulle hända.
adb shell am instrument -w -e dmzTestRoot forcestop -e dmzKeepRoot true -e dmzPhase 1 \
    -e class "$STATS#forceStopPhase1Read" "$RUNNER" > "$OUT/forcestop-del1.txt" 2>&1 &
phase1=$!
started=""
for _ in $(seq 1 240); do
    started=$(adb logcat -d -s DmzStatistics | grep -c "LÄSER sidbyte" || true)
    [ "$started" -ge 1 ] && break
    # Har testet redan avslutats utan att läsa har det fallit. Då är det ingen idé att vänta.
    kill -0 $phase1 2>/dev/null || break
    sleep 1
done
if [ "${started:-0}" -ge 1 ]; then
    # Läsningen började 18 s före sidbytet. Läs 12 s till och stoppa sedan appen mitt i läsningen.
    sleep 12
    adb shell am force-stop "$TEST_PACKAGE"
    wait $phase1 2>/dev/null || true
    echo "Appen är tvångsstoppad efter cirka 30 s läsning."
    run -e dmzTestRoot forcestop -e dmzKeepRoot true -e dmzPhase 2 -e dmzReadSeconds 30 \
        -e class "$STATS#forceStopPhase2SessionSurvived" || status=1
    adb logcat -d -s DmzStatistics | grep -o "TVÅNGSSTOPP.*" || true
else
    echo "Läsaren öppnades aldrig i den första delen." >&2
    echo "--- testets utskrift:" >&2
    cat "$OUT/forcestop-del1.txt" >&2 || true
    echo "--- loggen (DmzStatistics och DmzTest):" >&2
    adb logcat -d -s DmzStatistics DmzTest | tail -15 >&2 || true
    adb shell am force-stop "$TEST_PACKAGE"
    status=1
fi
run -e dmzTestRoot forcestop -e class "$NAMESPACE.BackupVersionDeviceTest" >/dev/null || status=1


finish
