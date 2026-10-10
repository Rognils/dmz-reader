#!/usr/bin/env bash
# DMZ Reader – kontroll av hela historiken före en push.
#
# Går igenom varje fil i varje commit på alla grenar och taggar, och stoppar för:
#
#   - *.db, utom testdatabaserna nedan, med exakt de sökvägarna och kontrollsummorna
#   - *.pdf
#   - nyckelfiler: *.jks, *.keystore, *.p12, *.pem, *.key
#   - säkerhetskopior ur appen: filnamnet dmz-reader-backup*, eller en JSON-fil med
#     fältet "backupFormat" på översta nivån
#   - *.apk och *.aab
#   - filer över 50 MB
#
# Undantagen är appens två testdatabaser. De nuvarande versionerna har bara påhittad text
# (docs/reports/rc3.md). De tre äldre versionerna, som ligger kvar i historiken, innehåller
# korta ordagranna utdrag ur tidningarna och inga personuppgifter (docs/reports/gates.md).
# En ny version av någon av dem måste läggas till här för hand, efter att innehållet har
# granskats: ingen text ur tidningarna.
#
# Hemligheter söks inte här. Kör gitleaks för det.
#
# Användning:  tools/prepush_check.sh [grenar och taggar]
#
# Utan argument granskas alla lokala grenar och taggar. Med argument granskas bara det som
# nås från dem, till exempel det som ska pushas:  tools/prepush_check.sh main --tags
#
# Avslutar med 0 om historiken är ren, annars 1.
set -euo pipefail

MAX_BYTES=$((50 * 1024 * 1024))

# sökväg och SHA-256, åtskilda av ett mellanslag
ALLOWED_DB="
app/src/debug/assets/fts_test.db 0c689be27c9766237bf909d429b2f536076d43fac7778181b670aed30184da73
app/src/debug/assets/search_test.db d82ecd840e1cc536b102fc6b9ea8e0bfbdd36a9602a2db8bc33b055b2b6034bc
app/src/debug/assets/fts_test.db fd2c4f406d47f6ba801362723400cfe86060fd10971907c1aa92a2fd4ee3a9b1
app/src/debug/assets/search_test.db 4ca5e7676674b33c47adb70ab3e7410bdbdd2793bc3a6cb30985df128e90bf10
app/src/debug/assets/search_test.db ab45b068dc56678022dc128e70595b38ae6e8d7a43ad2af777c8297f0d124c27
"

cd "$(git rev-parse --show-toplevel)"

# Vad som granskas: argumenten till git rev-list.
if [ $# -gt 0 ]; then REVS=("$@"); else REVS=(--all); fi

problems=0
problem() {
    echo "STOPP: $*" >&2
    problems=$((problems + 1))
}

commits=$(git rev-list "${REVS[@]}" | wc -l)
blobs=0
allowed=0

# Varje par av innehåll och sökväg i någon commit. Samma innehåll under två namn prövas en gång per namn.
while read -r hash size path; do
    blobs=$((blobs + 1))
    name=${path##*/}
    lower=$(printf '%s' "$name" | tr '[:upper:]' '[:lower:]')

    if [ "$size" -gt "$MAX_BYTES" ]; then
        problem "$path är $size byte, över 50 MB ($hash)"
    fi

    case "$lower" in
        *.db)
            sum=$(git cat-file blob "$hash" | sha256sum | cut -d" " -f1)
            if grep -qxF "$path $sum" <<<"$ALLOWED_DB"; then
                allowed=$((allowed + 1))
            else
                problem "$path är en databas som inte är undantagen (SHA-256 $sum)"
            fi
            ;;
        *.pdf) problem "$path är en PDF ($hash)" ;;
        *.jks | *.keystore | *.p12 | *.pem | *.key) problem "$path är en nyckelfil ($hash)" ;;
        *.apk | *.aab) problem "$path är ett appbygge ($hash)" ;;
        dmz-reader-backup*) problem "$path har namnet på en säkerhetskopia ur appen ($hash)" ;;
        *.json)
            # En säkerhetskopia har backupFormat som fält på översta nivån.
            if git cat-file blob "$hash" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(1)
sys.exit(0 if isinstance(data, dict) and "backupFormat" in data else 1)
'; then
                problem "$path är en säkerhetskopia ur appen ($hash)"
            fi
            ;;
    esac
done < <(git rev-list "${REVS[@]}" | while read -r commit; do
    git -c core.quotePath=false ls-tree -r -l "$commit"
done | awk '$2 == "blob" { path = $0; sub(/^[^\t]*\t/, "", path); print $3, $4, path }' | sort -u)

echo "$commits commits och $blobs filversioner genomsökta (${REVS[*]}), $allowed undantagna testdatabaser."
if [ "$problems" -gt 0 ]; then
    echo "$problems fynd. Pusha inte." >&2
    exit 1
fi
echo "Historiken är ren."
