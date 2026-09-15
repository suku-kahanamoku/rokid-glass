# Rokid Glass

Dvojice Android aplikací, která zobrazuje profily z FAnn CRM přímo v consumer
Rokid Glasses.

```text
rokid-glass/
├── app/       telefonní aplikace
└── device/    aplikace v brýlích

app -> Hi Rokid / CXR-L CUSTOMAPP -> device
```

Telefon instaluje, aktualizuje a spouští device APK v brýlích. Device aplikace
se potom přes Wi-Fi připojuje k FAnn API sama, profil vykreslí, posunem dopředu
nebo dozadu listuje profily a dvojklikem se zavře do hlavního menu brýlí.

## Moduly

```text
/home/suku/Workspace/rokid-glass
```

- `app`: telefonní aplikace, balíček `cz.suku.rokidglass`;
- `device`: aplikace v brýlích, balíček
  `cz.suku.rokidglass.device`.

Telefon používá `com.rokid.cxr:client-l:1.1.1`. Device aplikace používá
`com.rokid.cxr:cxr-service-bridge:1.0-20260715.121510-107`, tedy stejnou bridge
verzi, kterou přináší telefonní CXR-L klient.

## Požadavky

- JDK 17;
- Android SDK Platform 36;
- Android telefon s Androidem 12 / API 31 nebo novějším;
- aplikace Hi Rokid přihlášená a spárovaná s brýlemi;
- Bluetooth pro instalaci a spuštění, Wi-Fi v brýlích pro načítání profilů;
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

Kompletní kontrola obou modulů:

```bash
./gradlew :device:lintDebug :app:lintDebug :app:assembleDebug
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
10. Device aplikace si sama načte profil z FAnn API přes Wi-Fi brýlí.

Telefonní obrazovka obsahuje také tlačítko pro zastavení device aplikace a
potvrzované tlačítko pro její odinstalaci. Odinstalace odstraní jen balíček
`cz.suku.rokidglass.device`, nikoliv Hi Rokid ani systémové aplikace.

Instalace do brýlí tedy probíhá přes telefon, Hi Rokid a
`appUploadAndInstall()`. USB kabel vede pouze mezi Linuxem a telefonem.

## Aktualizace device aplikace

Po změně modulu `device`:

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

Pokud se změnil také modul `device`, zvyš jeho vlastní `versionCode` a
`versionName` v `device/build.gradle.kts`, například:

```kotlin
versionCode = 12
versionName = "1.11"
```

Platí:

- změna pouze telefonní aplikace: zvyš jen verzi `app`;
- změna aplikace v brýlích: zvyš verzi `app` i `device`;
- změna pouze profilů nebo dat na backendu: Android deploy není potřeba.

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
./gradlew :app:lintRelease :device:lintRelease :app:bundleRelease
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

## Samostatný provoz a FAnn CRM API

Device aplikace v brýlích bez přihlášení načítá produkční ID `11..20`
chronologicky a volá například:

```text
https://fann-crm.netlify.app/api/admin/profile/11
```

Při prvním spuštění začne ID `11`. Posun dopředu používá pořadí
`11 → 12 → … → 20 → 11`, posun dozadu opačné pořadí. Aplikace zobrazuje:

- číslo a název profilu;
- potřebu zákazníka;
- první tři prodejní otázky;
- první dvě námitky.

Neplatné, nepublikované nebo nedostupné ID aplikace přeskočí. Poslední úspěšně
načtený profil ukládá přímo v brýlích. Po novém otevření jej ihned zobrazí a na
pozadí se jej pokusí aktualizovat. Při výpadku Wi-Fi zůstane uložený profil
zobrazený a posun lze po obnovení připojení zopakovat.

Po úspěšné instalaci a prvním spuštění už telefon není pro zobrazování ani
listování profilů potřeba. Brýle ale musí mít přístup k Wi-Fi.

## Komunikační protokol

```text
cz.suku.rokidglass.event
    brýle -> telefon, diagnostická hodnota ready nebo stav přímého API
```

`ready` potvrzuje telefonu, že device aplikace běží. Profil ani příkazy pro
listování se přes telefon neposílají. Posun zpracuje device aplikace přímo a
dvojklik ukončí její Activity a vrátí systémové hlavní menu brýlí.

Telefon používá foreground service s trvalým oznámením pouze pro správu CXR
spojení. Ukončení telefonní aplikace nemá vliv na načítání ani listování profilů
v již nainstalované device aplikaci.

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

- kompilaci obou modulů;
- Android lint;
- zabalení device APK uvnitř telefonní APK;
- identitu, verzi a podpis APK.

Instalaci přes `appUploadAndInstall()`, spuštění přes `appStart()`, vykreslení a
přesně jedno fyzické klepnutí lze potvrdit až s připojeným telefonem a brýlemi.
