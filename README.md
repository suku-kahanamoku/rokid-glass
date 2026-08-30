# Rokid Glass

Minimal Android/Kotlin app for displaying a Custom View on consumer Rokid
Glasses through the Hi Rokid companion app.

The app uses CXR-L. Pressing **Zobrazit v brýlích** starts authorization in Hi
Rokid, connects a `CUSTOMVIEW` session, and opens **Hello world Rokid!** on the
glasses display.

## Requirements

- JDK 17+
- Android SDK Platform 36 (or adjust `compileSdk` to an installed platform)
- Android SDK Build Tools and Platform-Tools
- Android phone with Android 12 / API 31+
- Hi Rokid installed, signed in, and paired with the glasses
- Bluetooth and Wi-Fi enabled

## Build

If the Android SDK is not detected automatically, create an untracked
`local.properties` file containing:

```properties
sdk.dir=/home/suku/Android/sdk
```

Then build the debug APK:

```bash
./gradlew assembleDebug
```

The APK is created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it on a connected phone or emulator:

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open this directory in Android Studio, select the phone, and press **Run**.

## Test with glasses

1. Confirm that the glasses are connected in Hi Rokid.
2. Start this app on the same phone.
3. Press **Zobrazit v brýlích**.
4. Approve the authorization request in Hi Rokid.
5. Wait for the app status **Text je zobrazený v brýlích.**
