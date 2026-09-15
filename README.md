# Rokid Glass

Modulární dvojice Android aplikací pro FAnn asistenta v consumer Rokid Glasses.
Brýle průběžně převádějí českou řeč na text a po kliknutí nebo posunu boční
dotykové plochy zobrazí náhodný produkt z FAnn CRM.

```text
rokid-glass/
├── app/                         telefonní aplikace
├── device/                      spustitelná aplikace v brýlích
└── modules/
    ├── glasses-platform/        Rokid spojení a boční vstupy
    ├── products/                produktové API, modely a cache
    └── transcription/           přepis řeči a odeslání transkripce

app -> Hi Rokid / CXR-L CUSTOMAPP -> device
```

Telefon instaluje, aktualizuje a spouští device APK v brýlích. Potom už device
aplikace používá mikrofon brýlí a český offline model samostatně. Wi-Fi potřebuje
jen pro načtení produktů. Kliknutí, posun dopředu i
posun dozadu odešlou aktuální přepis a vyberou produkt; dvojklik aplikaci zavře
do hlavního menu brýlí.

## Moduly

```text
/home/suku/Workspace/rokid-glass
```

- `app`: telefonní aplikace, balíček `cz.suku.rokidglass`;
- `device`: aplikace v brýlích, balíček
  `cz.suku.rokidglass.device`; obsahuje životní cyklus a sestavení obrazovky;
- `modules:glasses-platform`: `RokidSession`, diagnostické zprávy a sjednocení
  fyzického tlačítka i boční dotykové plochy;
- `modules:products`: model produktu, parser, veřejné FAnn API, náhodný výběr a
  lokální cache posledního produktu;
- `modules:transcription`: výměnné rozhraní přepisu řeči, lokální Vosk
  implementace s českým modelem a výměnný HTTP odesílač transkripce.

Telefon používá `com.rokid.cxr:client-l:1.1.1`. Modul `glasses-platform` používá
`com.rokid.cxr:cxr-service-bridge:1.0-20260715.121510-107`, tedy stejnou bridge
verzi, kterou přináší telefonní CXR-L klient.

## Požadavky

- JDK 17;
- Android SDK Platform 36;
- Android telefon s Androidem 12 / API 31 nebo novějším;
- aplikace Hi Rokid přihlášená a spárovaná s brýlemi;
- Bluetooth pro instalaci a spuštění, Wi-Fi v brýlích pouze pro načítání
  produktů;
- povolený mikrofon pro FAnn asistenta v brýlích;
- USB ladění v telefonu pro instalaci telefonní APK.

Pokud Android SDK není automaticky nalezené, vytvoř v kořeni projektu lokální,
neverzovaný `local.properties`:

```properties
sdk.dir=/home/suku/Android/Sdk
```

## Automatické sestavení obou aplikací

Stačí sestavit telefonní modul:

```bash
cd /home/suku/Workspace/rokid-glass
./gradlew :app:assembleDebug
```

Gradle automaticky provede tento řetězec:

```text
:device:assembleDebug
        ↓
:app:embedDeviceDebugApk
        ↓
:app:assembleDebug
```

Při prvním sestavení modul `transcription` stáhne český model
`vosk-model-small-cs-0.4-rhasspy` (přibližně 46 MB), ověří jeho SHA-256 a vloží
ho do device APK. Archiv se ukládá jen do lokální `.gradle/vosk-models/` cache a
do Gitu se neukládá. Při prvním spuštění nové device verze brýle model jednou
rozbalí; zelená obrazovka proto může několik sekund zůstat prázdná.

Výsledky:

```text
device/build/outputs/apk/debug/device-debug.apk
app/build/generated/device-apk/debug/assets/rokid-glass-device.apk
app/build/outputs/apk/debug/app-debug.apk
```

Device APK se generuje do `build/`, není uložená ve zdrojových `assets` ani v
Gitu. Kompatibilní pomocný příkaz, který navíc spustí device lint:

```bash
./scripts/embed-device-apk.sh
```

Kompletní kontrola všech modulů:

```bash
./gradlew \
  :modules:glasses-platform:lintDebug \
  :modules:products:testDebugUnitTest \
  :modules:products:lintDebug \
  :modules:transcription:testDebugUnitTest \
  :modules:transcription:lintDebug \
  :device:lintDebug \
  :app:lintDebug \
  :app:assembleDebug
```

## Instalace telefonní aplikace

Připoj telefon datovým USB kabelem a ověř jej:

```bash
adb devices
```

Potom aplikaci nainstaluj:

```bash
cd /home/suku/Workspace/rokid-glass
ANDROID_SERIAL=HZQL1838HAL22301864 ./gradlew :app:installDebug
```

Nebo otevři kořenový `rokid-glass` v Android Studiu, vyber konfiguraci modulu
`app`, telefon a klikni na **Run**. Modul `device` do telefonu nespouštěj; jeho
APK nahraje do brýlí telefonní aplikace přes CXR-L.

## První spuštění

1. Zapni Bluetooth a Wi-Fi.
2. V Hi Rokid ověř připojené brýle.
3. Nasaď si brýle a ověř aktivní displej.
4. Spusť `Rokid Glass` v telefonu.
5. Klepni na **Nainstalovat/spustit aplikaci v brýlích**.
6. Při prvním spuštění potvrď oprávnění `DEVICE_MANAGE` v Hi Rokid.
7. Telefon ověří instalaci device APK.
8. Pokud chybí nebo se změnila její verze, nahraje ji do brýlí.
9. Telefon device aplikaci spustí a počká na zprávu `ready`.
10. V brýlích povol aplikaci přístup k mikrofonu.
11. Jakmile se zobrazí `Poslouchám…`, začni mluvit.

Telefonní obrazovka obsahuje také tlačítko pro zastavení device aplikace a
potvrzované tlačítko pro její odinstalaci. Odinstalace odstraní jen balíček
`cz.suku.rokidglass.device`, nikoliv Hi Rokid ani systémové aplikace.

Instalace do brýlí tedy probíhá přes telefon, Hi Rokid a
`appUploadAndInstall()`. USB kabel vede pouze mezi Linuxem a telefonem.

## Aktualizace device aplikace

Po změně modulu `device` nebo kteréhokoliv modulu, který používají brýle:

1. zvyš `versionCode` v `device/build.gradle.kts`;
2. znovu sestav a nainstaluj telefonní modul příkazem
   `ANDROID_SERIAL=HZQL1838HAL22301864 ./gradlew :app:installDebug`;
3. spusť telefonní aplikaci a klepni na hlavní tlačítko.

Telefon porovná verzi vložené APK s naposledy úspěšně nahranou verzí. Novou
verzi automaticky pošle do brýlí. Pro debug sestavení musí zůstat stejný debug
podpis; pro produkci musí všechny device APK používat stále stejný release
keystore.

Debug sestavení telefonu automaticky vloží `device-debug.apk`. Release sestavení
automaticky sestaví a vloží `device-release.apk`. Telefonní i device aplikace se
podepisují stejným trvalým release keystorem z lokálního
`keystore.properties`.

Device APK má vlastní balíček a nenahrazuje Hi Rokid ani systémové Rokid
aplikace. Případný rollback udělej sestavením staršího kódu s novým, vyšším
`versionCode`; Android běžně blokuje downgrade na nižší číslo verze.

## Produkční deploy přes Google Play

Následující postup použij při každém vydání nové verze.

### 1. Změna čísel verzí

Před každým novým nahráním na Google Play zvyš `versionCode` telefonu v
`app/build.gradle.kts`. `versionName` změň na uživatelské označení nové verze:

```kotlin
versionCode = 3
versionName = "1.2"
```

Pokud se změnil modul `device` nebo některý z modulů používaných brýlemi, zvyš
také jeho vlastní `versionCode` a `versionName` v `device/build.gradle.kts`,
například:

```kotlin
versionCode = 12
versionName = "1.11"
```

Platí:

- změna pouze telefonní aplikace: zvyš jen verzi `app`;
- změna aplikace v brýlích: zvyš verzi `app` i `device`;
- změna pouze produktů nebo jiných dat na backendu: Android deploy není potřeba.

### 2. Release keystore

Soubor `keystore.properties` v kořeni projektu je lokální a Git jej ignoruje.
Obsahuje údaje ke stálému release keystoru:

```properties
storeFile=/home/suku/.android/rokid-glass-upload.jks
storePassword=HESLO_KEYSTORU
keyAlias=ALIAS_KLICE
keyPassword=HESLO_KLICE
```

Hesla neposílej do chatu a soubor necommituj. Struktura je také připravená v
`keystore.properties.example`. Místo souboru lze použít proměnné prostředí
`ROKID_UPLOAD_STORE_FILE`, `ROKID_UPLOAD_STORE_PASSWORD`,
`ROKID_UPLOAD_KEY_ALIAS` a `ROKID_UPLOAD_KEY_PASSWORD`.

Keystore neměň a bezpečně jej zálohuj. Telefonní i device aplikace musí při
dalších aktualizacích zachovat svůj podpis.

### 3. Kontrola a sestavení

V kořeni projektu spusť:

```bash
cd /home/suku/Workspace/rokid-glass
./gradlew \
  :modules:glasses-platform:lintRelease \
  :modules:products:lintRelease \
  :modules:transcription:lintRelease \
  :device:lintRelease \
  :app:lintRelease \
  :app:bundleRelease
```

Příkaz automaticky:

1. zkontroluje oba moduly;
2. sestaví a podepíše release APK pro brýle;
3. vloží device APK do telefonní aplikace;
4. sestaví a podepíše telefonní AAB.

Příkaz `clean` není před každým sestavením nutný. Výsledný soubor pro Google
Play vznikne zde:

```text
app/build/outputs/bundle/release/app-release.aab
```

Podpis lze ověřit:

```bash
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
```

Výstup musí obsahovat `jar verified.`.

### 4. Nahrání na Google Play

1. V požadované sekci Google Play Console vytvoř nové vydání.
2. Nahraj pouze `app/build/outputs/bundle/release/app-release.aab`.
3. Doplň poznámky k vydání.
4. Ulož a odešli vydání do interního, uzavřeného nebo produkčního testování.

Samostatné `device-release.apk` do Google Play nenahrávej. Je už vložené uvnitř
telefonní aplikace.

### 5. Aktualizace telefonu a brýlí

Po zpřístupnění nové verze:

1. nainstaluj nebo aktualizuj telefonní aplikaci z Google Play;
2. připoj brýle v Hi Rokid;
3. otevři telefonní aplikaci Rokid Glass;
4. stiskni tlačítko pro instalaci/spuštění aplikace v brýlích;
5. telefon porovná `versionCode` vložené device APK a případnou novou verzi
   nahraje do brýlí.

Pokud se modul `device` nezměnil, jeho aplikace se zbytečně znovu instalovat
nebude.

### Jednorázový přechod z debug verze

Debug a produkční aplikace mají jiné podpisy, a proto se přes sebe nemohou
aktualizovat. Při úplně prvním přechodu na verzi z Google Play:

1. ještě ve stávající debug telefonní aplikaci odinstaluj device aplikaci z
   brýlí;
2. odinstaluj debug aplikaci Rokid Glass z telefonu;
3. nainstaluj telefonní aplikaci z Google Play;
4. připoj brýle a přes novou telefonní aplikaci nainstaluj release device APK.

Tento postup je potřeba jen jednou. Další produkční aktualizace se instalují přes
stávající verzi, pokud zůstane stejný keystore a vždy se zvýší příslušný
`versionCode`.

Rollback se provádí jako nové vydání: sestav starší funkční zdrojový kód, ale
nastav mu vyšší `versionCode`. Google Play ani Android běžně nepovolí instalaci
balíčku s nižším číslem verze.

## Přepis řeči a doporučení produktu

Po otevření FAnn asistenta:

1. aplikace požádá o oprávnění mikrofonu;
2. modul `transcription` rozbalí český Vosk model a spustí lokální poslech
   mikrofonu v brýlích;
3. úvodní obrazovka je prázdná a až skutečně zachycená průběžná nebo finální transkripce se zobrazí v brýlích;
4. kliknutí nebo posun boční plochy v libovolném směru pořídí snapshot textu;
5. transkripce se předá nakonfigurovanému `TranscriptSink`;
6. modul `products` vybere jiný náhodný publikovaný produkt;
7. teprve po dokončení požadavku aplikace skryje přepis, zobrazí produkt a znovu začne poslouchat další rozhovor.

Dvojklik aplikaci ukončí. Pro boční dotykovou plochu se zpracovávají
`KEYCODE_ENTER`, `KEYCODE_DPAD_RIGHT` a `KEYCODE_DPAD_LEFT`. Krátký debounce a
blokace probíhajícího požadavku zabraňují tomu, aby jeden fyzický posun načetl
více produktů.

Na fyzických brýlích přepis používá Vosk přímo nad lokálním mikrofonem při
16 kHz. Průběžné i dokončené věty se zobrazují bez odesílání audia nebo textu do
telefonu či cloudové rozpoznávací služby. Aplikace tedy pro přepis nepotřebuje
Rokid AK/SK, Hi Rokid hlasovou autorizaci ani připojení k internetu. Původní
systémový Android `SpeechRecognizer` zůstává v modulu pouze jako případná
záložní implementace pro obyčejný Android; device aplikace jej nepoužívá.

## Testovací URL pro transkripci

Výchozí URL je záměrně prázdná v:

```text
modules/transcription/src/main/java/cz/suku/rokidglass/transcription/TranscriptionConfig.kt
```

Proto se nyní text neposílá mimo brýle a `HttpTranscriptSink` vrací úspěšný
no-op stav. Až bude cílové API připravené, nastav například:

```kotlin
const val TRANSCRIPT_ENDPOINT = "https://example.test/api/transcript"
```

Odesílač provede `POST` s JSON tělem:

```json
{
  "transcript": "Hledám lehkou večerní vůni"
}
```

## FAnn produktové API

Produktový modul komunikuje bez přihlášení s:

```text
GET https://fann-crm.netlify.app/api/admin/product?limit=100
GET https://fann-crm.netlify.app/api/admin/product/{id}
```

První endpoint poskytne katalog pro náhodný výběr, druhý načte aktuální detail
vybraného produktu. Aplikace zobrazuje název, SKU, kategorii, cenu s DPH, popis,
charakter, hlavní prodejní argument, vyšší variantu, doplněk a alternativy.
Nepublikované produkty se nepoužijí a pokud je v katalogu více možností,
bezprostředně předchozí produkt se znovu nevybere.

Poslední úspěšný produkt je uložený v cache pouze kvůli tomu, aby se při dalším
výběru neopakoval. Po novém spuštění se cache na obrazovce nezobrazuje; obrazovka
zůstane prázdná až do zachycení řeči.

Po úspěšné instalaci telefon není pro přepis ani načítání produktů potřeba.
Samotný přepis funguje offline; Wi-Fi v brýlích je nutná až pro následné načtení
produktu z FAnn API.

## Komunikační protokol

```text
cz.suku.rokidglass.event
    brýle -> telefon, diagnostická hodnota ready nebo stav přímého API
```

`ready` potvrzuje telefonu, že device aplikace běží. Diagnostika může dále poslat
`transcription_ready`, `product_loaded:{id}` nebo stav chyby sítě. Transkripce,
produkty ani příkazy bočního ovládání se přes telefon neposílají.

Telefon používá foreground service s trvalým oznámením pouze pro správu CXR
spojení. Ukončení telefonní aplikace nemá vliv na přepis ani načítání produktů v
již nainstalované device aplikaci.

## Diagnostika

Bezpečně filtrované logy telefonu:

```bash
adb -s HZQL1838HAL22301864 logcat -v time \
  | rg -v -i 'token' \
  | rg 'CXR|CustomApp|rokidglass'
```

Řádky obsahující token nesdílej. CXR-L může do Logcatu vypsat dočasný
autorizační token.

## Co lze ověřit bez fyzických brýlí

- kompilaci všech Gradle modulů;
- jednotkové testy parsování produktového API a prázdného transcript sinku;
- Android lint;
- zabalení device APK uvnitř telefonní APK;
- identitu, verzi a podpis APK.

Instalaci přes `appUploadAndInstall()`, spuštění přes `appStart()`, dostupnost
mikrofonní recognition služby, reálný přepis a fyzické boční vstupy lze potvrdit
až s připojeným telefonem a brýlemi.
