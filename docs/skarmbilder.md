# Skärmbilderna finns inte i repot

Rapporterna i `docs/reports/` och en ADR hänvisar till skärmbilder under `docs/screenshots/`. De togs på telefonen när varje fas verifierades.

Bilderna ligger inte i git. De flesta visar tidningssidor och omslag, som inte omfattas av projektets licens (se `NOTICE.md`), och de var fyra femtedelar av repots storlek. De finns kvar lokalt hos projektets ägare, 172 bilder på sammanlagt 109 MB, med samma katalognamn som i hänvisningarna: `fas1` till `fas11`, `rc1` till `rc3`, `covers` och `search-privacy`.

En hänvisning som `docs/screenshots/fas9/13-lasaren-traff.png` pekar alltså på en fil som inte följer med. Rapporternas text beskriver vad bilderna visade.

Detsamma gäller två granskningsfiler med korta textutdrag ur tidningarna, `data/normalizer_review_v1.json` och `_v2.json`. De användes när normaliseringen av söktexten bedömdes (`docs/reports/fas-8b.md`) och behövs inte av något test. Kommandot `tools/search_quality.py sample --check` kräver dem.

Katalogen `docs/screenshots/` är ignorerad av git, så nya skärmbilder som läggs där följer inte med av misstag.
