# Device modul

Android aplikace `cz.suku.rokidglass.device` určená pro běh přímo v Rokid
Glasses. Profily `11..20` načítá samostatně z FAnn CRM přes Wi-Fi brýlí.

Samostatné sestavení a kontrola:

```bash
./gradlew :device:lintDebug :device:assembleDebug
```

APK vznikne v:

```text
device/build/outputs/apk/debug/device-debug.apk
```

Device APK se neinstaluje do telefonu přes Android Studio. Při sestavení modulu
`app` se automaticky vloží do jeho assets a telefonní aplikace ji následně
nainstaluje do brýlí přes Hi Rokid a CXR-L.

Před každou aktualizací brýlí zvyš `versionCode` v `device/build.gradle.kts`.
