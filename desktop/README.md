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
