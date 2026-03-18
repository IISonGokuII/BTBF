# 📤 PUSH ANLEITUNG FÜR GITHUB

## Problem: Repository ist leer

Das GitHub Repository unter https://github.com/IISonGokuII/BTBF ist aktuell leer.
Du musst die Dateien von deinem PC aus hochladen.

---

## ✅ OPTION 1: Mit Git Bash (Empfohlen)

### Schritt 1: Git installieren (falls nicht vorhanden)
Download: https://git-scm.com/download/win

### Schritt 2: Git Bash öffnen
Rechtsklick auf den Ordner `C:\Users\WhatsappBot\Desktop\BTBF`
→ "Git Bash Here" auswählen

### Schritt 3: Befehle ausführen

```bash
# Git initialisieren
git init

# Alle Dateien hinzufügen
git add .

# Ersten Commit erstellen
git commit -m "Initial commit: BTBF Android App with improved navigation

Features:
- FireTV & Smartphone support
- Advanced ad blocker
- Favorites system (videos & actors)
- Improved D-Pad navigation with mouse mode
- Quick access categories
- Video download functionality
- GitHub Actions auto-build
- Swipe gestures for controls"

# Mit GitHub verbinden
git remote add origin https://github.com/IISonGokuII/BTBF.git

# Auf GitHub pushen (du wirst nach Username/Passwort gefragt)
git push -u origin main
```

### Schritt 4: GitHub Credentials eingeben
- **Username:** Dein GitHub Username
- **Password:** Dein GitHub Personal Access Token (nicht dein normales Passwort!)

Token erstellen: https://github.com/settings/tokens
- Token mit "repo" Berechtigung erstellen
- Token kopieren und als Passwort verwenden

---

## ✅ OPTION 2: GitHub Desktop (Einfachste Methode)

### Schritt 1: GitHub Desktop installieren
Download: https://desktop.github.com/

### Schritt 2: Ordner hinzufügen
1. GitHub Desktop öffnen
2. File → Add Local Repository
3. Ordner auswählen: `C:\Users\WhatsappBot\Desktop\BTBF`
4. "Add Repository" klicken

### Schritt 3: Mit GitHub verbinden
1. Publish repository klicken
2. Repository Name: `BTBF`
3. Haken bei "Keep this code private" wenn gewünscht
4. "Publish Repository" klicken

---

## ✅ OPTION 3: GitHub Web Upload (Ohne Git)

### Schritt 1: ZIP erstellen
1. Alle Dateien im Ordner `C:\Users\WhatsappBot\Desktop\BTBF` markieren
2. Rechtsklick → Senden an → ZIP-komprimierter Ordner

### Schritt 2: Auf GitHub hochladen
1. Gehe zu https://github.com/IISonGokuII/BTBF
2. "uploading an existing file" klicken
3. ZIP-Datei reinziehen
4. "Commit changes" klicken

**Nachteil:** ZIP muss manuell entpackt werden, nicht empfohlen!

---

## 🔍 ÜBERPRÜFEN OBGE ALLES DA IST

Nach dem Pushen solltest du diese Dateien auf GitHub sehen:

```
✅ .gitignore
✅ .github/workflows/android-build.yml
✅ README.md
✅ APP_LAYOUT.asc
✅ NAVIGATION_ERKLAERUNG.asc
✅ build.gradle.kts
✅ settings.gradle.kts
✅ gradle.properties
✅ gradlew.bat
✅ gradle/wrapper/gradle-wrapper.properties
✅ app/build.gradle.kts
✅ app/proguard-rules.pro
✅ app/src/main/AndroidManifest.xml
✅ app/src/main/java/com/btbf/app/MainActivity.kt
✅ app/src/main/java/com/btbf/app/FavoritesManager.kt
✅ app/src/main/res/layout/activity_main.xml
✅ app/src/main/res/layout/dialog_favorites.xml
✅ app/src/main/res/layout/item_favorite_video.xml
✅ app/src/main/res/layout/item_favorite_actor.xml
✅ app/src/main/res/values/strings.xml
✅ app/src/main/res/values/colors.xml
✅ app/src/main/res/values/themes.xml
✅ app/src/main/res/values-night/themes.xml
✅ app/src/main/res/drawable/*.xml
✅ app/src/main/res/mipmap-*/ic_launcher*.xml
```

---

## 🚀 NACH DEM PUSH

1. Gehe zu https://github.com/IISonGokuII/BTBF/actions
2. Warte bis der Build durchläuft (~5 Minuten)
3. Lade das APK unter "Artifacts" herunter

---

## ⚠️ HÄUFIGE FEHLER

### Fehler: "Authentication failed"
**Lösung:** Personal Access Token verwenden, nicht normales Passwort
https://github.com/settings/tokens

### Fehler: "remote origin already exists"
**Lösung:** 
```bash
git remote remove origin
git remote add origin https://github.com/IISonGokuII/BTBF.git
```

### Fehler: "src refspec main does not match any"
**Lösung:**
```bash
git branch -M main
git push -u origin main
```

### Fehler: "Permission denied (publickey)"
**Lösung:** SSH Key einrichten ODER HTTPS verwenden:
```bash
git remote set-url origin https://github.com/IISonGokuII/BTBF.git
```

---

## 📞 SUPPORT

Wenn nichts funktioniert:
1. GitHub Desktop probieren (einfachste Methode)
2. Oder manuell über Web-Upload (Option 3)
