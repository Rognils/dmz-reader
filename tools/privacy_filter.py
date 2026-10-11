#!/usr/bin/env python3
"""DMZ Reader – maskering av kontaktuppgifter i sökindexets text.

Tidningarna trycker privatpersoners e-postadresser, telefonnummer och postgironummer i
insändare och radannonser. I ett sökindex som delas ut blir de lätta att hitta, så de
byts mot markeringen ••• innan texten läggs i databasen. Fyra kategorier:

    e-postadresser
    telefon- och faxnummer i svenska format, med riktnummer eller +46
    hela nummeruttrycket direkt efter tel, telefon, tfn, fax, telefax, bbs eller modem, också
        utländska nummer och nummer utan riktnummer
    postgironummer som står direkt efter ordet postgiro, postgirot, pg eller pg-nr
    bankgironummer som står direkt efter ordet bankgiro, bankgirot, bg eller bg-nr
    teckenföljder med personnummers form: ÅÅMMDD-NNNN och ÅÅÅÅMMDD-NNNN

Ingenting annat maskeras: inte namn och inte postadresser. Nummer maskeras bara i sitt
sammanhang, aldrig med en regel som tar alla siffergrupper av en viss längd.

Version 2 lade till bankgiro och telefonnummer utan riktnummer. Version 3 tar hela
nummeruttrycket efter tel, fax och liknande ord, i stället för bara dess början.

Markeringen är densamma för alla kategorier och har inga bokstäver eller siffror. FTS-indexet
får därför inga ord av den.

En e-postadress som texttolkningen har dragit isär ("namn@ dator. example.se") maskeras
från sista sammanhängande biten före @ till toppdomänen. Är namndelen utspärrad står
dess första bokstäver kvar, men adressen går inte längre att läsa ut.

Filtret är deterministiskt och har ett versionsnummer, PRIVACY_FILTER_VERSION, som
skrivs i indexets meta. Ändras reglerna höjs versionen.

Reglerna för telefonnummer (version 1):

  riktnummer   0 följt av 1–3 siffror: 08, 031, 0303. Samma regel täcker 010, 020 och 07x.
  skiljetecken - eller /, med högst ett mellanslag på var sida. Texttolkningen ger
               ibland · eller ett långt streck i stället, och de räknas likadant.
               Texttolkningen ger också *. Eller riktnumret inom parentes:
               (08) 12 34 56.
  efter tel, fax, bbs, ring och liknande ord godtas också ett riktnummer följt av
               bara ett mellanslag (Fax 031 12 34 56), ett nummer i ett stycke
               (BBS 0812345678, Tel:01234567 89) och ett nummer hopskrivet med ordet (Tel08-123456).
  abonnentnummer  siffergrupper med ett mellanslag emellan, sammanlagt minst 5 siffror
               och högst 8 efter ett riktnummer på två siffror, 7 efter tre och 6
               efter fyra. Fler siffror än så har svenska nummer inte haft.
  +46          landsnumret, ett eventuellt (0), och därefter 7–9 siffror i grupper.

En grupp räknas inte till numret om den
  - har fler än tre siffror, inte är den första och numret redan har fem siffror
    (ett pris, ett årtal, en hastighet).
    Efter +46 gäller inte den regeln, eftersom riktnumret där är första gruppen.
  - skulle göra numret för långt
  - ser ut som ett riktnummer och följs av - eller / och en siffra (den är nästa nummer)
  - följs av :-, kr, %, st eller en enhet (den är ett pris eller ett antal)
Står abonnentnumret i ett stycke på minst fem siffror tas inga fler grupper.

Ett nummer som texttolkningen har skrivit ihop med ordet före (Datacorner021-12 34 56)
maskeras. Sitter det ihop med en beteckning som har en siffra eller ett $ i sig
(68EC030-25, $D025/53285) lämnas det.

Det som inte maskeras, med avsikt: fyra siffror efter riktnumret (0900-1700 är en
tid och 0283-3379 ett ISSN), nummer utan riktnummer, och utländska nummer.
"""

from __future__ import annotations

import re

PRIVACY_FILTER_VERSION = 3

# En och samma markering för alla kategorier. Den har inga bokstäver eller siffror, så
# FTS-indexet får inga ord av den: en sökning på "telefon" ska inte träffa varje maskerat nummer.
MARK = "•••"
EMAIL_MARK = PHONE_MARK = GIRO_MARK = PERSONAL_NUMBER_MARK = MARK

CATEGORIES = ("email", "phone", "contextPhone", "giro", "bankgiro", "personalNumber")

# Lokal del, @, och en domän med minst en punkt och en toppdomän av bokstäver. Domänen före
# toppdomänen ska ha minst två tecken: x@d.se finns inte som adress, men sex bytes med den
# formen kan uppstå av en slump i en binärfil.
_EMAIL = re.compile(r"[A-Za-z0-9][A-Za-z0-9._%+-]*@(?P<domain>[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*)\.[A-Za-z]{2,}")


def _whole_emails(text: str) -> list[re.Match]:
    return [match for match in _EMAIL.finditer(text) if len(match.group("domain")) >= 2]
# Texttolkningen drar ofta isär adresser: "namn@ dator. example.se". Då godtas mellanslag
# kring @ och inne i domänen, men bara om den slutar på en känd toppdomän inom 25 tecken.
_EMAIL_SPACED = re.compile(
    r"[A-Za-z0-9][A-Za-z0-9._%+-]* ?@ ?[A-Za-z0-9][A-Za-z0-9 .-]{2,25}\. ?"
    r"(?:se|com|org|net|edu|de|uk|fi|no|dk)(?![A-Za-z0-9])")

_MIN_SUBSCRIBER = 5
_MAX_SUBSCRIBER = {2: 8, 3: 7, 4: 6}  # efter riktnumrets längd, nollan inräknad
_MIN_INTERNATIONAL, _MAX_INTERNATIONAL = 7, 9  # siffror efter +46
_MIN_SOLID, _MAX_SOLID = 7, 10  # siffror i ett nummer skrivet utan skiljetecken, nollan inräknad

# Början på ett nummer. Före får det inte stå en siffra, och inte en siffra följd av
# skiljetecken: då är nollan mitten av ett annat tal. En bokstav före godtas ibland, se _glued_to_a_code.
_BEFORE = r"(?<![0-9])(?<![0-9][.,:/-])(?<![0-9][-/] )(?<![0-9] [-/])(?<![0-9] [-/] )"
_LETTER = "A-Za-zÀ-ÖØ-öø-ÿ"
_CODE_BEFORE = re.compile(r"[0-9$][A-Za-z0-9$]*$")
_SEPARATOR = r" ?[-/·*–—] ?"
# Ordet före ett nummer som saknar skiljetecken efter riktnumret: "Fax 031 12 34 56".
_KEYWORD = r"(?<![" + _LETTER + r"])(?:tel|tele|telefon|tfn|fax|telefax|ordertelefon|bbs|ring|mobil|mobiltel)"
_START = re.compile(
    r"(?:"
    + _BEFORE + r"(?P<area>0[0-9]{1,3})" + _SEPARATOR + r"(?=[0-9])"
    # Riktnumret utspärrat av texttolkningen: 0 3 1-12 34 56. Då får det inte stå en siffra strax före.
    r"|" + _BEFORE + r"(?<![0-9] )(?P<spaced>0(?: [0-9]){1,3})" + _SEPARATOR + r"(?=[0-9])"
    r"|" + _BEFORE + r"\((?P<paren>0[0-9]{1,3})\) ?(?=[0-9])"
    r"|" + _BEFORE + r"(?P<plus>\+ ?46)[ -]?(?:\(0\))?[ -]?(?=[0-9])"
    # Efter tel, fax och liknande: ordet hopskrivet med numret, riktnumret följt av bara ett
    # mellanslag, eller hela numret i ett stycke.
    r"|" + _KEYWORD + r"(?P<glued>0[0-9]{1,3})" + _SEPARATOR + r"(?=[0-9])"
    r"|" + _KEYWORD + r"\.? ?:? ?(?P<bare>0[0-9]{1,3}) (?=[0-9])"
    r"|" + _KEYWORD + r"\.? ?:? ?(?P<solid>0[0-9]{4,9})(?![0-9])"
    r")",
    re.I,
)
_GROUP = re.compile(r"[0-9]+")
# Det som visar att en siffergrupp inte hör till numret utan är ett pris eller ett antal.
_NOT_NUMBER_AFTER = re.compile(r":-| ?(?:kr|sek|st|mb|kb|mhz|bps|baud)\b| ?%", re.I)
_AREA_CODE = re.compile(r"0[0-9]{1,3}")
_DECIMAL_AFTER = re.compile(r"[.,][0-9]")
_NEXT_AREA_AFTER = re.compile(_SEPARATOR + r"[0-9]")


def _subscriber_end(text: str, position: int, minimum: int, maximum: int, separators: str,
                    longest_later_group: int = 3, long_first_group_is_all: bool = True) -> int | None:
    """Slutet på abonnentnumret som börjar vid `position`, eller None om det inte är ett nummer."""
    digits = 0
    end = None
    first = True
    while True:
        group = _GROUP.match(text, position)
        if group is None:
            break
        length = len(group.group())
        if digits + length > maximum:
            break
        if not first:
            # En lång grupp hör bara till numret om det annars vore för kort: 054-1 23456.
            if length > longest_later_group and digits >= _MIN_SUBSCRIBER:
                break
            if _AREA_CODE.fullmatch(group.group()) and _NEXT_AREA_AFTER.match(text, group.end()):
                break
            if _NOT_NUMBER_AFTER.match(text, group.end()) or _DECIMAL_AFTER.match(text, group.end()):
                break
        digits += length
        end = group.end()
        if first and long_first_group_is_all and length >= _MIN_SUBSCRIBER:
            break
        first = False
        # Nästa grupp ska stå efter exakt ett skiljetecken.
        if end < len(text) and text[end] in separators and end + 1 < len(text) and text[end + 1].isdigit():
            position = end + 1
        else:
            break
    if end is None or digits < minimum:
        return None
    # Numret får inte fortsätta i ett decimaltal eller i fler siffror.
    if end < len(text) and (text[end].isdigit() or (text[end] in ".," and end + 1 < len(text) and text[end + 1].isdigit())):
        return None
    return end


def _glued_to_a_code(text: str, position: int) -> bool:
    """Sant om nollan vid `position` sitter ihop med en beteckning: 68EC030-25, $D025/53285.

    Texttolkningen skriver ofta ihop ett nummer med ordet före (Datacorner021-12 34 56), och
    de numren ska maskeras. En beteckning känns igen på att biten före har en siffra eller ett $.
    """
    # Bara A–Z räknas här. En beteckning med å, ä eller ö i sig finns inte, och med den regeln ger
    # filtret samma svar på texten som på filens läsbara teckenföljder, där andra tecken delar raden.
    if position == 0 or not re.match("[A-Za-z]", text[position - 1]):
        return False
    return _CODE_BEFORE.search(text, max(0, position - 12), position) is not None


def phone_spans(text: str) -> list[tuple[int, int]]:
    """Var telefonnumren står i en text, som (början, slut). Ett pass från vänster till höger."""
    spans = []
    position = 0
    while True:
        start = _START.search(text, position)
        if start is None:
            return spans
        kind = start.lastgroup
        if kind in ("area", "spaced", "paren", "plus") and _glued_to_a_code(text, start.start()):
            position = start.start() + 1
            continue
        if kind == "solid":
            # Riktnummer och abonnentnummer i ett stycke, ibland med de sista siffrorna för sig.
            end = _subscriber_end(text, start.start("solid"), _MIN_SOLID, _MAX_SOLID, " ",
                                  long_first_group_is_all=False)
        elif kind == "plus":
            end = _subscriber_end(text, start.end(), _MIN_INTERNATIONAL, _MAX_INTERNATIONAL, " -",
                                  longest_later_group=_MAX_INTERNATIONAL)
        else:
            area = start.group(kind).replace(" ", "")
            end = _subscriber_end(text, start.end(), _MIN_SUBSCRIBER, _MAX_SUBSCRIBER[len(area)], " ")
        if end is None:
            position = start.start() + 1
            continue
        # Ordet före numret står kvar.
        begin = start.start(kind) if kind in ("glued", "bare", "solid") else start.start()
        spans.append((begin, end))
        position = end


def _mask_phones_once(text: str) -> tuple[str, int]:
    spans = phone_spans(text)
    if not spans:
        return text, 0
    pieces = []
    last = 0
    for begin, end in spans:
        pieces.append(text[last:begin])
        pieces.append(PHONE_MARK)
        last = end
    pieces.append(text[last:])
    return "".join(pieces), len(spans)


def mask_phones(text: str) -> tuple[str, int]:
    """Byter telefonnummer mot markeringen. Ger den nya texten och antalet bytta nummer."""
    total = 0
    # Ett byte kan frilägga ett nummer som stod tätt intill. Upprepa tills inget mer hittas.
    while True:
        text, count = _mask_phones_once(text)
        if not count:
            return text, total
        total += count


def _blank(text: str, spans: list[tuple[int, int]]) -> str:
    """Texten med varje spann utbytt mot lika många mellanslag, så att lägena står kvar."""
    pieces = []
    last = 0
    for begin, end in spans:
        pieces += [text[last:begin], " " * (end - begin)]
        last = end
    pieces.append(text[last:])
    return "".join(pieces)


def _replace(text: str, spans: list[tuple[int, int]]) -> str:
    pieces = []
    last = 0
    for begin, end in spans:
        pieces += [text[last:begin], MARK]
        last = end
    pieces.append(text[last:])
    return "".join(pieces)


def email_spans(text: str) -> list[tuple[int, int]]:
    """Var e-postadresserna står i en text, som (början, slut)."""
    spans = [match.span() for match in _whole_emails(text)]
    spans += [match.span() for match in _EMAIL_SPACED.finditer(_blank(text, spans))]
    return sorted(spans)


def mask_emails(text: str) -> tuple[str, int]:
    """Byter e-postadresser mot markeringen. Ger den nya texten och antalet bytta adresser."""
    spans = email_spans(text)
    return (_replace(text, spans), len(spans)) if spans else (text, 0)


# Personnummer: ÅÅMMDD-NNNN eller ÅÅÅÅMMDD-NNNN, där MMDD är ett möjligt datum. Bindestrecket
# krävs (eller plustecknet, som används efter hundra års ålder). Sexsiffriga artikelnummer
# och datum utan fyra slutsiffror har inte den formen.
_PERSONAL_NUMBER = re.compile(
    r"(?<![0-9])(?:(?:18|19|20)[0-9]{2}|[0-9]{2})(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])"
    r" ?[-+] ?[0-9]{4}(?![0-9])"
)

# Postgironummer: bara i direkt anslutning till ordet postgiro, postgirot, pg eller pg-nr.
# Numret är två till åtta siffror, bindestreck och en kontrollsiffra: 12 34 56-7. Mellan ordet
# och numret får det stå högst fyra tecken som inte är bokstäver eller siffror. Ordet får sitta
# ihop med ordet före (påPG1234567-8): numrets form räcker för att det inte ska bli fel.
# Texttolkningen kan ha spärrat ut både ordet och siffrorna.
_GIRO = re.compile(
    r"(?:p ?o ?s ?t ?g ?i ?r ?o ?t?|p ?g)"
    r"(?: ?-? ?(?:n ?r|nummer|konto))?[^0-9A-Za-zÀ-ÖØ-öø-ÿ\n•]{0,4}"
    r"(?P<number>[0-9](?: ?[0-9]){1,7} ?- ?[0-9])(?![0-9])",
    re.I,
)


def personal_number_spans(text: str) -> list[tuple[int, int]]:
    """Var teckenföljder med personnummers form står, som (början, slut)."""
    return [match.span() for match in _PERSONAL_NUMBER.finditer(text)]


def giro_spans(text: str) -> list[tuple[int, int]]:
    """Var postgironumren står, som (början, slut). Ordet före numret ingår inte."""
    return [match.span("number") for match in _GIRO.finditer(text)]


def mask_personal_numbers(text: str) -> tuple[str, int]:
    return _PERSONAL_NUMBER.subn(MARK, text)


def mask_giro(text: str) -> tuple[str, int]:
    count = 0
    pieces = []
    last = 0
    for begin, end in giro_spans(text):
        pieces += [text[last:begin], MARK]
        last = end
        count += 1
    pieces.append(text[last:])
    return "".join(pieces), count


# Bankgironummer: bara i direkt anslutning till ordet bankgiro, bankgirot, bg eller bg-nr.
# Numret är tre eller fyra siffror, bindestreck och fyra siffror: 123-4567, 1234-5678.
_BANKGIRO = re.compile(
    r"(?:b ?a ?n ?k ?g ?i ?r ?o ?t?|(?<![" + _LETTER + r"])b ?g)"
    r"(?: ?-? ?(?:n ?r|nummer|konto))?[^0-9A-Za-zÀ-ÖØ-öø-ÿ\n•]{0,4}"
    r"(?P<number>[0-9](?: ?[0-9]){2,3} ?- ?[0-9](?: ?[0-9]){3})(?![0-9])(?! ?- ?[0-9])",
    re.I,
)

# Nummer som känns igen på sammanhanget: direkt efter ordet tel, telefon, tfn, fax, telefax, bbs
# eller modem, med eventuell punkt och eventuellt kolon. Ordet ska stå för sig: telefonmodem
# och BBS-program räknas inte. Här maskeras hela nummeruttrycket, också utländska nummer och
# nummer utan riktnummer: plustecken, landsnummer, riktnummer inom parentes, och siffergrupper
# med mellanslag, bindestreck eller snedstreck emellan, tills uttrycket tar slut.
_CONTEXT_KEYWORD = re.compile(
    r"(?<![" + _LETTER + r"0-9-])(?:telefon|telefax|tel|tfn|fax|bbs|modem)(?![" + _LETTER + r"0-9-])\.? ?:? ?(?=[+(0-9])",
    re.I,
)
_MIN_CONTEXT, _MAX_CONTEXT = 5, 15  # siffror. Femton är det längsta ett telefonnummer kan vara.
_CONTEXT_ELEMENT = re.compile(r"\((?P<inside>[0-9]{1,5})\)|(?P<digits>[0-9]+)")
_CONTEXT_SEPARATOR = re.compile(r" ?[-/·*] ?| ")
# Modemhastigheter står ofta just efter orden modem, fax och bbs.
_SPEEDS = frozenset({"300", "1200", "2400", "4800", "7200", "9600", "12000", "14400", "16800", "19200", "21600",
                     "24000", "26400", "28800", "31200", "33600", "38400", "57600", "64000", "115200"})
# Det som visar att en siffergrupp är ett pris, ett mått, en tid eller en version och inte hör till numret.
_NOT_NUMBER_GROUP_AFTER = re.compile(
    r":-| ?(?:kr|sek|st|mb|kb|mhz|bps|baud|bit|tkn|cps|dpi|hz|khz|ms|tim|timmar|år)\b| ?%| ?/ ?[" + _LETTER + r"]|[.,:][0-9]|x[0-9]",
    re.I,
)
_YEAR = re.compile(r"(?:19|20)[0-9]{2}")
_YEAR_RANGE = re.compile(r"(?:19|20)[0-9]{2} ?[-/] ?[0-9]{2,4}")


def bankgiro_spans(text: str) -> list[tuple[int, int]]:
    """Var bankgironumren står, som (början, slut). Ordet före numret ingår inte."""
    return [match.span("number") for match in _BANKGIRO.finditer(text)]


def context_phone_spans(text: str, international_only: bool = False) -> list[tuple[int, int]]:
    """Var nummer står direkt efter tel, fax och liknande ord, som (början, slut). Hela uttrycket tas.

    Med `international_only` tas bara uttryck som börjar med +, en parentes eller utlandsprefixet 00.
    De tas före den vanliga telefonregeln, som annars skulle ta bara början av dem.
    """
    spans = []
    for keyword in _CONTEXT_KEYWORD.finditer(text):
        begin = position = keyword.end()
        if international_only and not (text[begin] in "+(" or text.startswith("00", begin)):
            continue
        if text[position:position + 1] == "+":
            position += 1
            if text[position:position + 1] == " ":
                position += 1
        international = text[begin] == "+" or text.startswith("00", begin)
        digits = 0
        end = None
        groups: list[str] = []
        after_break = False
        while True:
            element = _CONTEXT_ELEMENT.match(text, position)
            if element is None:
                break
            group = element.group("inside") or element.group("digits")
            if digits + len(group) > _MAX_CONTEXT:
                break
            if _NOT_NUMBER_GROUP_AFTER.match(text, element.end()):
                break
            if groups:
                # Efter ett mellanslag eller snedstreck kan nästa grupp vara något annat: en hastighet,
                # ett årtal eller nästa nummers riktnummer.
                if after_break and _YEAR.fullmatch(group) and digits >= _MIN_CONTEXT:
                    break
                # En hastighet efter ett inhemskt nummer är en annan kolumn. I ett utländskt nummer kan
                # en grupp råka se ut som en hastighet, och där avgör det som följer efter gruppen.
                if after_break and group in _SPEEDS and not international:
                    break
                if digits >= _MIN_CONTEXT and _AREA_CODE.fullmatch(group) and _NEXT_AREA_AFTER.match(text, element.end()):
                    break
            groups.append(group)
            digits += len(group)
            end = element.end()
            # Ett inhemskt nummer i ett stycke är färdigt: det som följer är en annan kolumn.
            if len(groups) == 1 and len(group) >= 6 and not group.startswith("00") and text[begin] != "+":
                break
            separator = _CONTEXT_SEPARATOR.match(text, end)
            if separator is not None and _CONTEXT_ELEMENT.match(text, separator.end()) is not None:
                # Ett mellanslag eller ett snedstreck kan skilja numret från något annat. Ett bindestreck gör det inte.
                after_break = "-" not in separator.group()
                position = separator.end()
            elif (element.group("inside") is not None and text[end:end + 1].isdigit()) or text[end:end + 1] == "(":
                # En parentes sitter ofta tätt intill siffrorna: +49(0)5241 och (0)5241.
                if _CONTEXT_ELEMENT.match(text, end) is None:
                    break
                after_break = False
                position = end
            else:
                break
        if end is None or not _MIN_CONTEXT <= digits <= _MAX_CONTEXT:
            continue
        expression = text[begin:end]
        # Hastigheter och årtal efter ordet är inte nummer: modem 14400, modem 2400/1200, tel 1993-94.
        if groups[0] in _SPEEDS or _YEAR_RANGE.fullmatch(expression):
            continue
        if text[end:end + 1].isdigit():
            continue
        spans.append((begin, end))
    return spans


def mask_bankgiro(text: str) -> tuple[str, int]:
    spans = bankgiro_spans(text)
    return (_replace(text, spans), len(spans)) if spans else (text, 0)


def mask_context_phones(text: str, international_only: bool = False) -> tuple[str, int]:
    spans = context_phone_spans(text, international_only)
    return (_replace(text, spans), len(spans)) if spans else (text, 0)


def mask(text: str) -> tuple[str, dict[str, int]]:
    """Maskerar en text. Ger texten och antalet träffar per kategori."""
    # Personnummer och giro först: de har en egen form, som telefonregeln annars kunde ta en bit av.
    text, personal = mask_personal_numbers(text)
    text, giro = mask_giro(text)
    text, bankgiro = mask_bankgiro(text)
    text, emails = mask_emails(text)
    # Utländska nummer efter tel, fax och liknande ord tas hela, före den vanliga telefonregeln.
    text, international = mask_context_phones(text, international_only=True)
    text, phones = mask_phones(text)
    # Sist: det som står kvar direkt efter orden, till exempel nummer utan riktnummer.
    text, context = mask_context_phones(text)
    return text, {"email": emails, "phone": phones, "contextPhone": international + context, "giro": giro, "bankgiro": bankgiro,
                  "personalNumber": personal}


def all_spans(text: str) -> list[tuple[str, int, int]]:
    """Alla träffar i en text som (kategori, början, slut), med lägen i den ursprungliga texten.

    Kategorierna tas i samma ordning som i mask(), och det som redan är taget fylls med
    markeringens tecken innan nästa kategori söks. För stickprov och kontroller.
    """
    found = []
    for kind, spans_of in (("personalNumber", personal_number_spans), ("giro", giro_spans),
                           ("bankgiro", bankgiro_spans), ("email", email_spans),
                           ("contextPhone", lambda value: context_phone_spans(value, international_only=True)),
                           ("phone", phone_spans), ("contextPhone", context_phone_spans)):
        spans = spans_of(text)
        found += [(kind, start, end) for start, end in spans]
        pieces = []
        last = 0
        for start, end in sorted(spans):
            pieces += [text[last:start], MARK[0] * (end - start)]
            last = end
        text = "".join(pieces) + text[last:]
    return found


def find(text: str) -> dict[str, int]:
    """Antalet träffar per kategori i en text, utan att ändra den. Används av kontrollerna."""
    return mask(text)[1]


def is_clean(text: str) -> bool:
    """Sant om filtret inte hittar något i texten."""
    return not any(find(text).values())
