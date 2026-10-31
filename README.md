# Chaos Note Viewer

KDE Plasma 6 widget.

## Install

```bash
kpackagetool6 -t Plasma/Applet -i plasmoid      # first install
kpackagetool6 -t Plasma/Applet -u plasmoid      # update
plasmawindowed org.kde.plasma.chaosnoteviewer   # test in a window
plasmashell --replace &                         # restart plasmashell
```

Package: `cd plasmoid && zip -r ../chaosnoteviewer.plasmoid metadata.json contents`
