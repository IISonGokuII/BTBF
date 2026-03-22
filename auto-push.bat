@echo off
REM ============================================================
REM Automatischer GitHub Push für BTBF App
REM ============================================================

echo.
echo ============================================================
echo  BTBF App - Automatischer GitHub Upload
echo ============================================================
echo.

REM GitHub Token (NEUES TOKEN HIER EINFÜGEN!)
set GITHUB_TOKEN=DEIN_NEUES_TOKEN_HIER_EINFUEGEN

REM GitHub Username
set GITHUB_USER=IISonGokuII

REM Repository Name
set REPO_NAME=BTBF

echo [1/5] Prüfe Git Installation...
git --version >nul 2>&1
if %errorlevel% neq 0 (
    echo FEHLER: Git ist nicht installiert!
    echo Bitte installiere Git von: https://git-scm.com/download/win
    pause
    exit /b 1
)
echo OK: Git ist installiert
echo.

echo [2/5] Initialisiere Git Repository...
cd /d "%~dp0"

if not exist ".git" (
    git init
    echo OK: Git Repository initialisiert
) else (
    echo OK: Git Repository existiert bereits
)
echo.

echo [3/5] Fuege alle Dateien hinzu...
git add .
echo OK: Alle Dateien hinzugefuegt
echo.

echo [4/5] Erstelle Commit...
git commit -m "Complete BTBF Android App

Features:
- FireTV & Smartphone support with improved navigation
- Advanced ad blocker (30+ ad types)
- Favorites system (videos & actors)
- Mouse mode with visual highlights
- Quick access categories
- Video download functionality
- GitHub Actions auto-build
- Swipe gestures for controls
- D-Pad navigation optimization"
echo OK: Commit erstellt
echo.

echo [5/5] Pushe zu GitHub...

REM Remote entfernen falls vorhanden
git remote remove origin 2>nul

REM Remote hinzufügen mit Token-Authentifizierung
git remote add origin https://%GITHUB_USER%:%GITHUB_TOKEN%@github.com/%GITHUB_USER%/%REPO_NAME%.git

REM Branch zu main umbenennen
git branch -M main

REM Push ausführen
git push -u origin main --force

if %errorlevel% equ 0 (
    echo.
    echo ============================================================
    echo  ERFOLG! Repository wurde gepusht!
    echo ============================================================
    echo.
    echo Besuche: https://github.com/%GITHUB_USER%/%REPO_NAME%
    echo.
    echo Actions (Build Status): https://github.com/%GITHUB_USER%/%REPO_NAME%/actions
    echo.
) else (
    echo.
    echo ============================================================
    echo  FEHLER! Push fehlgeschlagen!
    echo ============================================================
    echo.
    echo Moegliche Ursachen:
    echo - Falsches oder abgelaufenes Token
    echo - Token hat keine repo-Berechtigung
    echo - GitHub Username falsch
    echo.
    echo Bitte Token ueberpruefen: https://github.com/settings/tokens
    echo.
)

pause
