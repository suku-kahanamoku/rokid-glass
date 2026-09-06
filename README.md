# Rokid Glass

Android aplikace v Kotlinu, která přes oficiální CXR-L SDK a aplikaci Hi Rokid
zobrazuje vlastní `CUSTOMVIEW` v consumer Rokid Glasses.

Po stisknutí tlačítka **Zobrazit náhodný profil v brýlích** aplikace načte
profil z ostrého Zoo CRM API a zobrazí jej jako kartu v brýlích. Dalším
stisknutím načte jiný náhodný profil.

## Jak spojení funguje

```text
Rokid Glass aplikace v telefonu
        ↓ CXR-L
     Hi Rokid
        ↓ Bluetooth / Wi-Fi
  displej Rokid Glasses
```

Naše APK běží v telefonu. Neinstaluje se přímo do brýlí. Hi Rokid slouží jako
komunikační prostředník a předává do brýlí popis obrazovky `CUSTOMVIEW`.

USB kabel je potřeba pouze pro instalaci a ladění aplikace v telefonu. Po
instalaci může telefon komunikovat s brýlemi bez připojení k počítači.

## Požadavky

- JDK 17 nebo novější
- Android SDK Platform 36
- Android SDK Build Tools a Platform-Tools
- Android telefon s Androidem 12 / API 31 nebo novějším
- nainstalovaná a přihlášená aplikace Hi Rokid
- brýle spárované v Hi Rokid
- zapnuté Bluetooth a Wi-Fi
- pro ladění přes kabel zapnuté **Ladění USB**

Pokud Android SDK není automaticky nalezené, vytvoř lokální a neverzovaný
soubor `local.properties`:

```properties
sdk.dir=/home/suku/Android/Sdk
```

## Co je v projektu nastavené

V `settings.gradle.kts` je přidaný veřejný Rokid Maven repozitář:

```kotlin
maven {
    url = uri("https://maven.rokid.com/repository/maven-public/")
}
```

V `app/build.gradle.kts` je nastavené API 31 jako minimum a CXR-L SDK:

```kotlin
minSdk = 31
```

```kotlin
implementation("com.rokid.cxr:client-l:1.1.1")
```

V `app/src/main/AndroidManifest.xml` je internetové oprávnění a deklarace,
které umožňují najít globální i čínskou variantu Rokid aplikace a její
autorizační a mediální službu.

Hlavní integrace je v
`app/src/main/java/cz/suku/rokidglass/MainActivity.kt`:

1. vytvoří jeden `CXRLink`;
2. nastaví relaci `CXRSessionType.CUSTOMVIEW`;
3. ověří, že je nainstalovaná podporovaná aplikace Hi Rokid;
4. požádá přes Hi Rokid o autorizaci `DEVICE_MANAGE`;
5. převezme autorizační token;
6. zavolá `cxrLink.connect(token)`;
7. počká současně na CXR spojení a Bluetooth spojení s brýlemi;
8. načte profily z ostrého Zoo CRM API;
9. vybere náhodný vyplněný profil a zavolá `customViewOpen(...)` s jeho JSON
   obrazovkou;
10. při dalším stisknutí použije `customViewUpdate(...)` pro jiný profil;
11. zpracuje otevření, aktualizaci, zavření a případnou chybu pohledu.

Profil se neposílá, dokud nejsou připravené obě části spojení:

```kotlin
if (!cxrConnected || !glassesConnected || viewRequested) return
```

## Nejsnazší spuštění přes Android Studio

1. Zapni Bluetooth a Wi-Fi v telefonu.
2. Otevři Hi Rokid a ověř, že jsou brýle připojené.
3. Rozlož a nasaď si brýle; jejich displej musí být aktivní.
4. Připoj telefon k počítači datovým USB kabelem.
5. V Android Studiu vyber připojený telefon.
6. Klikni na zelené **Run ▶**.
7. V aplikaci v telefonu klikni na **Zobrazit náhodný profil v brýlích**.
8. Při prvním spuštění potvrď autorizaci v Hi Rokid.
9. Počkej na stav **Profil je zobrazený. Kliknutím načteš další.**

Android Studio automaticky aplikaci sestaví, nainstaluje do telefonu a spustí.

## Spuštění přes terminál

Přejdi do projektu a ověř telefon:

```bash
cd /home/suku/Workspace/rokid-glass
adb devices
```

Telefon musí mít stav `device`, například:

```text
HZQL1838HAL22301864    device
```

Sestav a staticky zkontroluj debug verzi:

```bash
./gradlew lintDebug assembleDebug
```

Výsledné APK vznikne zde:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Nainstaluj nebo aktualizuj aplikaci v telefonu:

```bash
adb -s HZQL1838HAL22301864 install -r \
  app/build/outputs/apk/debug/app-debug.apk
```

Spusť aplikaci:

```bash
adb -s HZQL1838HAL22301864 shell am start \
  -n cz.suku.rokidglass/.MainActivity
```

Sestavení a instalaci lze provést také jedním příkazem:

```bash
ANDROID_SERIAL=HZQL1838HAL22301864 ./gradlew installDebug
```

Sériové číslo nahraď hodnotou, kterou na tvém počítači vypíše `adb devices`.

## Zoo CRM API

Aplikace bez přihlášení volá:

```text
https://zoo-crm.netlify.app/api/admin/client?limit=100&projection=first_name,last_name,profile,client_type
```

Parametr `projection` záměrně omezuje data. Do telefonu ani brýlí se nestahuje
e-mail, telefon nebo heslo. Z odpovědi se vybírají jen záznamy s vyplněným
objektem `profile`.

Adresa endpointu je v
`app/src/main/java/cz/suku/rokidglass/MainActivity.kt` v konstantě:

```kotlin
const val PROFILES_URL = "https://zoo-crm.netlify.app/api/admin/client..."
```

Vzhled karty vytváří metoda `createProfileView()`. Zobrazuje podle dostupnosti:

- jméno a typ klienta;
- shrnutí, auru a chování;
- oblíbená zvířata;
- obchodní potenciál.

Texty jsou zkrácené na délku vhodnou pro malý displej brýlí. JSON se vytváří
přes `JSONObject`, takže uvozovky a další znaky z API nemohou poškodit popis
`CUSTOMVIEW`.

Pro rychlé ověření dostupnosti API z Linuxu:

```bash
curl -fsSL \
  'https://zoo-crm.netlify.app/api/admin/client?limit=1&projection=first_name,last_name,profile,client_type'
```

## Stavové callbacky

- `onCXRLConnected()` oznamuje spojení s CXR službou.
- `onGlassBtConnected()` oznamuje Bluetooth spojení s brýlemi.
- `onCustomViewOpened()` potvrzuje zobrazení pohledu.
- `onCustomViewClosed()` oznamuje, že brýle pohled zavřely.
- `onCustomViewError()` vrací chybu vykreslení nebo přenosu.

Po úspěšném otevření se tlačítko znovu povolí. Další stisknutí stáhne seznam,
vybere jiný profil a aktualizuje už otevřený pohled přes `customViewUpdate()`.

## Diagnostika

Bezpečně filtrované CXR-L logy:

```bash
adb -s HZQL1838HAL22301864 logcat -v time \
  | rg -v -i 'token' \
  | rg 'CXRLink|Custom_View|CXRLinkService'
```

Filtr odstraňující řádky s `token` je důležitý. Rokid SDK může do Logcatu
vypsat dočasný autorizační token, proto neupravené logy veřejně nesdílej.

### Telefon není v ADB

Pokud `adb devices` nic nevypíše:

1. odemkni telefon;
2. nastav USB režim **Přenos souborů**;
3. ověř, že je zapnuté **Ladění USB**;
4. potvrď dialog **Povolit ladění USB**;
5. zkus znovu `adb devices`.

Stav `unauthorized` znamená, že ještě nebyl potvrzen dialog v telefonu.

### Pohled se ihned zavře

Brýle mohou poslat `Custom_View_Closed`, pokud je detekce nošení vyhodnotí
jako nenasazené. Rozlož je, nasaď si je a ověř aktivní displej. Pokud problém
pokračuje, lze v Hi Rokid dočasně vypnout **Settings → Device → Wear
Detection** a test zopakovat.

## Ověřený stav

- sestavení `assembleDebug`: úspěšné
- kontrola `lintDebug`: úspěšná
- instalace přes ADB na Nokia 3.4 s Androidem 12: úspěšná
- autorizace přes globální Hi Rokid: úspěšná
- fyzické zobrazení původního `Hello world Rokid!` v brýlích: úspěšně ověřené
- Zoo API odpověď a dostupnost profilů: úspěšně ověřené
- sestavení a lint nové profilové verze: úspěšné
- fyzické zobrazení nové profilové karty: čeká na test s připojeným telefonem
