# BTBF - Born To Be Fucked App

Eine benutzerfreundliche Android-App für FireTV und Smartphones zum Streamen von Videos von borntobefuck.com mit Werbeblocker und Download-Funktion.

## 📱 Features

### Kernfunktionen
- ✅ **FireTV & Smartphone Support** - Optimiert für beide Plattformen
- ✅ **Integrierter Werbeblocker** - Blockiert Werbung, Popups und Cookie-Banner automatisch
- ✅ **Video-Download** - Lade Videos für Offline-Ansicht herunter
- ✅ **Fullscreen-Player** - Vollbild-Video-Wiedergabe mit ExoPlayer
- ✅ **Fernbedienung-Support** - D-Pad Navigation für FireTV
- ✅ **Automatische Builds** - GitHub Actions erstellt APKs automatisch

### Komfort-Funktionen
- 🎯 **Quick-Access Kategorien** - Home, Neu, Top, Zufall mit einem Klick
- 👆 **Swipe-Gesten** - Wischen zum Anzeigen/Ausblenden der Kategorien
- 🎬 **Auto-Vollbild** - Videos starten automatisch im Vollbildmodus
- 📱 **Touch-Optimierung** - Größere Bedienelemente für bessere Handhabung
- 🚀 **Smooth Scrolling** - Weiches Scrollen für bessere UX
- 🍪 **Cookie-Banner-Blocker** - Störende Banner werden ausgeblendet
- ⭐ **Favoriten-System** - Videos und Darsteller als Favoriten speichern

## 🏗️ Build

### Voraussetzungen

- Android Studio Arctic Fox oder höher
- JDK 17
- Android SDK 34

### Build lokal

```bash
# Debug APK erstellen
./gradlew assembleDebug

# Release APK erstellen
./gradlew assembleRelease

# App installieren und testen
./gradlew installDebug
```

### GitHub Actions Build

Die App wird automatisch bei jedem Push auf `main` gebaut:

1. Gehe zu **Actions** Tab auf GitHub
2. Wähle "Android Build" Workflow
3. Lade das APK unter "Artifacts" herunter

## 📥 Installation

### FireTV

1. **Einstellungen** → **Gerät** → **Entwickleroptionen**
2. **ADB-Debugging** aktivieren
3. APK auf FireTV installieren:
   ```bash
   adb connect <firetv-ip>
   adb install app-debug.apk
   ```

### Smartphone

1. APK herunterladen
2. Installation aus unbekannten Quellen erlauben
3. APK installieren und starten

## 🎮 Bedienung

### FireTV Fernbedienung

| Taste | Funktion |
|-------|----------|
| **D-Pad Hoch** | Kategorien-Leiste anzeigen |
| **D-Pad Runter/Links/Rechts** | Navigation durch die Website |
| **D-Pad Center** | Auswahl bestätigen |
| **Zurück** | Zurück zur vorherigen Seite |
| **Menü (kurz)** | Favoriten-Menü öffnen |
| **Menü (lang)** | Video zu Favoriten hinzufügen |
| **Play/Pause** | Video abspielen/pausieren |

### Smartphone Touch

| Geste | Funktion |
|-------|----------|
| **Swipe nach unten** | Kategorien-Leiste anzeigen |
| **Swipe nach oben** | Kategorien-Leiste ausblenden |
| **Einmal tippen** | Button-Leiste für 3s anzeigen |

### Button-Funktionen

| Button | Beschreibung |
|--------|-------------|
| 🏠 **Home** | Zur Startseite |
| 🔥 **Neu** | Neueste Videos |
| ⭐ **Top** | Top bewertete Videos |
| 🎲 **Zufall** | Zufälliges Video |
| ⬅️ **Zurück** | Vorherige Seite |
| 🔄 **Refresh** | Seite neu laden |
| ⬇️ **Download** | Aktuelles Video herunterladen |
| ⛶ **Fullscreen** | Vollbild umschalten |
| ⭐ **Favoriten** | Favoriten-Menü öffnen |

## ⭐ Favoriten-System

### Videos als Favoriten speichern

**FireTV:**
- **Menü-Taste (lang drücken)** → Aktuelles Video zu Favoriten hinzufügen
- **Menü-Taste (kurz drücken)** → Favoriten-Menü öffnen

**Smartphone:**
- **Favoriten-Button** in der Navigationsleiste tippen

### Favoriten-Menü

Das Favoriten-Menü bietet:
- **Videos Tab** - Alle gespeicherten Videos
- **Darsteller Tab** - Alle favorisierten Darsteller
- **Export** - Favoriten als JSON exportieren
- **Alle löschen** - Favoriten zurücksetzen

### Favoriten verwalten

- ✅ Favoriten werden lokal gespeichert
- ✅ Funktioniert offline
- ✅ Export/Import über JSON
- ✅ Schneller Zugriff über Menü

## 📁 Projektstruktur

```
BTBF/
├── app/
│   ├── src/main/
│   │   ├── java/com/btbf/app/
│   │   │   └── MainActivity.kt      # Hauptaktivität
│   │   ├── res/
│   │   │   ├── layout/              # UI-Layouts
│   │   │   ├── values/              # Ressourcen
│   │   │   └── drawable/            # Bilder
│   │   └── AndroidManifest.xml      # App-Konfiguration
│   └── build.gradle.kts             # App-Build-Konfiguration
├── .github/workflows/
│   └── android-build.yml            # CI/CD Pipeline
├── build.gradle.kts                 # Projekt-Build-Konfiguration
└── settings.gradle.kts              # Projekt-Einstellungen
```

## 🔧 Konfiguration

### Website URL ändern

In `MainActivity.kt`:
```kotlin
private val websiteUrl = "https://de.borntobefuck.com/"
```

### Berechtigungen

Die App benötigt folgende Berechtigungen:

- `INTERNET` - Website laden
- `WRITE_EXTERNAL_STORAGE` - Videos speichern
- `READ_MEDIA_VIDEO` - Auf Videos zugreifen (Android 13+)

## ⚠️ Rechtlicher Hinweis

Diese App dient ausschließlich zu Bildungszwecken. Die Nutzung der App erfolgt auf eigene Verantwortung. Bitte beachte die Nutzungsbedingungen der Website und geltende Urheberrechtsgesetze.

## 📄 Lizenz

Dieses Projekt ist Open Source.

## 🤝 Beitrag

Pull Requests sind willkommen!

## 📞 Support

Bei Problemen bitte ein Issue auf GitHub erstellen.
