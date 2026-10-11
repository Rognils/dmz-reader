#!/usr/bin/env bash
# DMZ Reader – manuell kontroll mot den riktiga arkivservern.
#
# Det här är det enda i repot som med avsikt kontaktar dmzarkivet.se utanför appen.
# Automatiska tester får aldrig göra det (CLAUDE.md, regel 12). Skriptet körs för hand,
# när det finns skäl att tro att arkivets adresser eller svar har ändrats, och det
# anropas aldrig från något annat skript.
#
# Det gör tre små förfrågningar för 1986 nr 1, med adresserna ur data/archive_inventory.json:
#   1. omslaget över HTTPS: de första 16 kB
#   2. en PDF över HTTPS: 4 kB ur mitten, med Range. Svaret ska vara 206.
#   3. en HQ-sida över FTP: de första 16 kB
# Hela filer hämtas aldrig. Sammanlagt blir det under 40 kB, och antalet hämtade byte
# skrivs ut. Ange mängden i rapporten när skriptet har körts.
#
# Användning:  tools/live_archive_smoke_test.sh
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ISSUE=datormagazin-1986-01
AGENT="DMZReader-smoke-test (manuell kontroll; +https://github.com/Rognils/dmz-reader)"
PART=16383          # sista byten i de första 16 kB
RANGE_FROM=1000000  # 4 kB ur mitten av PDF:en
RANGE_TO=1004095

echo "LIVE TEST – kontaktar dmzarkivet.se"
echo "Tre små förfrågningar för $ISSUE: omslag, PDF och HQ-sida. Under 40 kB sammanlagt."
read -r -p "Skriv ja för att fortsätta: " answer
if [ "$answer" != "ja" ]; then
    echo "Avbrutet. Ingenting hämtades."
    exit 1
fi

read -r COVER PDF SCAN FTP_USER FTP_PASSWORD < <(python3 - "$ROOT" "$ISSUE" <<'PY'
import json, sys
root, issue_id = sys.argv[1], sys.argv[2]
inventory = json.load(open(f"{root}/data/archive_inventory.json", encoding="utf-8"))
issue = next(item for item in inventory["issues"] if item["issueId"] == issue_id)
config = json.load(open(f"{root}/tools/config.json", encoding="utf-8"))["ftp"]
scan = issue["scanPagePattern"].replace("{page:03d}", "001")
print(issue["coverUrl"], issue["ocrPdfUrl"], scan, config["user"], config["password"])
PY
)

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
total=0

# fetch NAMN VÄNTAD_KOD curl-argument…
fetch() {
    local name=$1 expected=$2 code size
    shift 2
    code=$(curl --silent --show-error --max-time 30 --user-agent "$AGENT" --output "$WORK/$name" --write-out "%{http_code}" "$@") || {
        echo "  $name: MISSLYCKADES (curl avslutades med fel)"
        return 1
    }
    size=$(stat -c %s "$WORK/$name")
    total=$((total + size))
    if [ "$code" = "$expected" ]; then
        echo "  $name: svar $code, $size byte"
    else
        echo "  $name: svar $code, väntat $expected, $size byte"
        return 1
    fi
}

status=0
echo "1. Omslaget, de första 16 kB:"
fetch omslag 206 --range "0-$PART" "$COVER" || status=1
echo "2. PDF:en, 4 kB med Range:"
fetch pdf 206 --range "$RANGE_FROM-$RANGE_TO" "$PDF" || status=1
echo "3. HQ-sidan över FTP, de första 16 kB:"
# FTP svarar 226 när överföringen är klar, också för en del av filen.
fetch hq 226 --range "0-$PART" --user "$FTP_USER:$FTP_PASSWORD" "$SCAN" || status=1

echo "Hämtat från dmzarkivet.se: $total byte."
[ "$status" = 0 ] && echo "Alla tre svarade som väntat." || echo "Något svarade inte som väntat. Se ovan."
exit $status
