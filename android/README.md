# Chaos Note Viewer for Android

Your notes on Android, local-first: they live in a database on the phone and work without an account or a network.
Signed in, they are encrypted on the device and synced with the desktop app through Firebase.

Kotlin, Jetpack Compose, Room, WorkManager, Glance (home screen widget), Firebase Auth. Needs Android 9 (API 28) or newer.

## What it does

- Notes with a deadline, filters (open, completed, all, important), sorting, drag to reorder, links in the text.
- Account with email verification; the notes on a device belong to one account.
- Encrypted sync (ChaCha20-Poly1305, the same format as the desktop app, see `../core/crypto/`). The key is created on the
  first device and typed in on the others, or scanned from its QR code (desktop app or another phone: Settings, Show key);
  it is never stored in the cloud. Scanning uses Google's code scanner, so the app needs no camera permission.
- Sync soon after a change, every 30 s while the app is open, about every 15 minutes while it is closed, and as soon as the
  network is back after changes made offline.
- Home screen widget and a morning reminder: one notification for every note that is due today or overdue, with a Done button.

## Build and run

Needs JDK 17 or newer (21 works) and the Android SDK (Android Studio brings both). Open this folder in Android Studio, or:

```bash
cd android
./gradlew installDebug                  # a connected phone or a running emulator
```

Without `app/google-services.json` a debug build talks to the local Firebase emulator (`../core/firebase`,
`npm run emulators`; with a USB phone run `adb reverse tcp:43099 tcp:43099` and `adb reverse tcp:43080 tcp:43080`). With the file, which you
download from the Firebase console (Project settings, your Android app `com.chaosnoteviewer`), it uses the real project.
The file is not committed.

More about running, debugging and the emulator: `_NOTES.md`.

## Tests

```bash
./gradlew testDebugUnitTest            # on the computer
./gradlew connectedDebugAndroidTest    # on a phone or emulator: database, encryption, sync, notifications
```

The encryption is checked against the vectors shared with the desktop app (`../core/crypto/vectors.json`).

## Releasing

A release is a tag on the `android` branch; the workflow `.github/workflows/release-android.yml` builds the signed APK and
AAB and attaches them to a GitHub release.

| Tag | Release |
|---|---|
| `android-vX.Y.0` | full release, created as a draft |
| `android-vX.Y.Z`, Z > 0 | nightly build, published at once as a pre-release |

The version name is the tag's version, the version code is `major*10000 + minor*100 + patch`.

The workflow needs these repository secrets: `ANDROID_KEYSTORE` (the release keystore, base64), `ANDROID_KEYSTORE_PASSWORD` and
`GOOGLE_SERVICES_JSON` (`app/google-services.json`, base64).

A release build can also be made locally: copy `keystore.properties.example` to `keystore.properties` (ignored by git), fill
it in and run `./gradlew assembleRelease bundleRelease`. Without it the release APK is built unsigned. The release build is
minified with R8.
