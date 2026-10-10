# ADR 0024: Klientidentifiering mot arkivservern

## Status
Accepted (1.0.0-rc2, 2026-10-10)

## Context
Arkivet drivs av en privatperson. Appens trafik syntes i serverns loggar som en namnlös klient: HTTP-förfrågningarna hade HTTP-bibliotekets egen User-Agent ("okhttp/…"), och FTP-anslutningarna för HQ hade ingen identitet alls. Den som sköter servern kunde inte se vad trafiken var eller vart den kunde vända sig med frågor.

## Decision
- Appen presenterar sig med en enda sträng, `ClientIdentity.userAgent`:
  `DMZReader/<versionName> (Android; +https://github.com/Rognils/dmz-reader)`.
  Versionen kommer från bygget (`BuildConfig.VERSION_NAME`).
- **HTTP:** en interceptor sätter strängen som User-Agent på varje förfrågan. Den ligger på båda HTTP-klienterna: den för PDF:er, och den för omslag som Coil använder. Appen har ingen annan HTTP-trafik.
- **FTP:** FTP har ingen User-Agent. Efter inloggningen frågar klienten efter serverns funktioner (FEAT). Listar servern CLNT skickas `CLNT <samma sträng>`, en gång per anslutning.
- CLNT är en artighet och får aldrig påverka hämtningen. Saknas FEAT eller CLNT, eller nekar servern kommandot, fortsätter anslutningen som om ingenting hade hänt. Inga omförsök, och inget loggas som fel.
- I debug-bygget loggas den User-Agent som skickas och serverns svar på CLNT.

## Reason
- En igenkännlig klient med en adress är det minsta en app kan ge den som bär kostnaden för trafiken.
- En enda sträng på ett ställe gör att identiteten är densamma i alla protokoll och följer versionen utan handpåläggning.
- CLNT är det vedertagna sättet för en FTP-klient att namnge sig, och arkivets server annonserar det.
- Att fråga FEAT först gör att en server som inte känner till CLNT aldrig får ett kommando den inte förstår.

## Consequences
- Varje ny FTP-anslutning kostar två kommandon till (FEAT och CLNT), några tiotals millisekunder.
- Adressen i strängen pekar på ett repo som ännu inte är publikt. Den blir en fungerande länk när källkoden publiceras.
- Strängen innehåller inget som identifierar användaren eller telefonen: bara appens namn, version och plattform.
- Byter projektet adress ändras den på ett ställe, `ClientIdentity.PROJECT_URL`.
