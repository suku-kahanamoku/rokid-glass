# Rokid Glass

Dvojice Android aplikací, která zobrazuje profily z FAnn CRM přímo v consumer
Rokid Glasses.

```text
rokid-glass (telefon)
  -> Hi Rokid / CXR-L CUSTOMAPP
  -> rokid-glass-device (aplikace v brýlích)
```

Telefon instaluje, aktualizuje a spouští device APK v brýlích. Device aplikace
se potom přes Wi-Fi připojuje k FAnn API sama, profil vykreslí, posunem dopředu
nebo dozadu listuje profily a dvojklikem se zavře do hlavního menu brýlí.

## Projekty

```text
/home/suku/Workspace/rokid-glass
/home/suku/Workspace/rokid-glass-device
```

- `rokid-glass`: telefonní aplikace, balíček `cz.suku.rokidglass`;
- `rokid-glass-device`: aplikace v brýlích, balíček
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

Pokud Android SDK není automaticky nalezené, vytvoř v obou projektech lokální,
neverzovaný `local.properties`:

```properties
sdk.dir=/home/suku/Android/Sdk
```

## Sestavení obou aplikací

Nejdříve sestav device APK a vlož ji do assets telefonu:

```bash
cd /home/suku/Workspace/rokid-glass
./scripts/embed-device-apk.sh
```

Skript provede kontrolu a sestavení projektu `../rokid-glass-device` a vytvoří:

```text
rokid-glass-device/app/build/outputs/apk/debug/app-debug.apk
rokid-glass/app/src/main/assets/rokid-glass-device.apk
```

Potom sestav telefonní aplikaci:

```bash
./gradlew lintDebug assembleDebug
```

Telefonní APK vznikne zde:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Instalace telefonní aplikace

Připoj telefon datovým USB kabelem a ověř jej:

```bash
adb devices
```

Potom aplikaci nainstaluj:

```bash
cd /home/suku/Workspace/rokid-glass
ANDROID_SERIAL=HZQL1838HAL22301864 ./gradlew installDebug
```

Nebo otevři `rokid-glass` v Android Studiu, vyber telefon a klikni na **Run**.
Device projekt se tlačítkem Run do telefonu neinstaluje.

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

Po změně projektu `rokid-glass-device`:

1. zvyš `versionCode` v `rokid-glass-device/app/build.gradle.kts`;
2. spusť `./scripts/embed-device-apk.sh` v telefonním projektu;
3. znovu sestav a nainstaluj telefonní aplikaci;
4. spusť ji a klepni na hlavní tlačítko.

Telefon porovná verzi vložené APK s naposledy úspěšně nahranou verzí. Novou
verzi automaticky pošle do brýlí. Pro debug sestavení musí zůstat stejný debug
podpis; pro produkci musí všechny device APK používat stále stejný release
keystore.

Device APK má vlastní balíček a nenahrazuje Hi Rokid ani systémové Rokid
aplikace. Případný rollback udělej sestavením staršího kódu s novým, vyšším
`versionCode`; Android běžně blokuje downgrade na nižší číslo verze.

## Samostatný provoz a FAnn CRM API

Device aplikace v brýlích bez přihlášení načítá produkční ID `11..20`
chronologicky a volá například:

```text
https://fann-crm.netlify.app/api/admin/profile/11
```

Při prvním spuštění začne ID `11`. Posun dopředu používá pořadí
`11 → 12 → … → 20 → 11`, posun dozadu opačné pořadí. Do brýlí posílá:

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

- kompilaci obou projektů;
- Android lint;
- zabalení device APK uvnitř telefonní APK;
- identitu, verzi a podpis APK.

Instalaci přes `appUploadAndInstall()`, spuštění přes `appStart()`, vykreslení a
přesně jedno fyzické klepnutí lze potvrdit až s připojeným telefonem a brýlemi.
