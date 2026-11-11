# Desktop client (Linux + Windows)

Qt 6 / QML + C++ 17 tray app. Built with CMake.

## Build and run

```
cmake -S . -B build
cmake --build build
./build/chaosnoteviewer
```

Requires Qt 6.5+ (Quick, QuickControls2).

## Plasma widget

```
./install.sh
```

Installs or upgrades `plasmoid/`. It first copies the shared SQL migrations from
`../core/storage/migrations/` into `plasmoid/contents/ui/Migrations.js` (generated, not committed),
because the widget cannot read `core/` at runtime. Run it again after every change in
`core/storage/migrations/`. If the widget does not refresh: `plasmashell --replace &`.

### Sign-in (Firebase)

The widget works without an account. To sign in and sync, give it your Firebase Web API key
(Firebase console, Project settings, General, Your apps). The key is not committed:

```
cp firebase.local.example.json firebase.local.json     # then paste the apiKey into it
./install.sh
```

`install.sh` combines it with the project id from `../core/firebase/.firebaserc` into
`plasmoid/contents/ui/FirebaseConfig.js` (generated, not committed). Set `"emulator": true` in
`firebase.local.json` to use the local Auth emulator (`npm run emulators` in `core/firebase`) instead.

The first account that signs in owns the notes on that device. Signing out keeps the notes,
and only that account can sign in on the device again. The refresh token that keeps you signed in
is stored in the local database without encryption.
