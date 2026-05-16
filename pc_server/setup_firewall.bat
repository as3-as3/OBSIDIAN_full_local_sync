@echo off
:: Tastatur- und Zeichensatz auf UTF-8 stellen
chcp 65001 > nul

echo ======================================================================
echo 🔥 VaultLink - Windows Defender Firewall Port-Freigabe (Port 8080)
echo ======================================================================
echo.

:: Prüfen, ob das Skript mit Administrator-Rechten ausgeführt wird
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [X] FEHLER: Dieses Skript MUSS mit Administrator-Rechten ausgeführt werden!
    echo.
    echo Bitte klicke mit der rechten Maustaste auf diese Datei
    echo und wähle "Als Administrator ausführen" aus.
    echo.
    echo ======================================================================
    pause
    exit /b 1
)

echo [+] Administrator-Rechte erfolgreich bestätigt!
echo [+] Erstelle eingehende Firewall-Regel für Port 8080 (TCP)...
echo.

:: Firewall-Regel hinzufügen
netsh advfirewall firewall add rule name="VaultLink Port 8080" dir=in action=allow protocol=TCP localport=8080 profile=any > nul

if %errorLevel% equ 0 (
    echo [OK] DIE FIREWALL-REGEL WURDE ERFOLGREICH ANGELEGT!
    echo.
    echo Port 8080 (TCP) ist nun für eingehende Verbindungen im Netzwerk freigegeben.
    echo Dein Android-Smartphone kann sich ab jetzt problemlos mit diesem PC verbinden.
) else (
    echo [X] FEHLER: Die Firewall-Regel konnte nicht angelegt werden.
)

echo.
echo ======================================================================
pause
