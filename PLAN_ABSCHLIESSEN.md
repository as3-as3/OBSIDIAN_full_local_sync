# 🏁 Finalisierungs-Plan: VaultLink Android Core

Dieses Dokument dient als präzise Delegations-Anleitung für **Android Studio**. Es beschreibt die letzten verbleibenden Schritte, um die Lücke zwischen unserer stabilen Build-Struktur (Groovy-KTS Migration erfolgreich) und der echten physischen Datei-Synchronisation (File-I/O) über das Android **Storage Access Framework (SAF)** zu schließen.

---

## 🛠️ VERBLIEBENE DELEGATIONS-AUFGABEN

### 1. Storage Access Framework (SAF) & Pfad-Picker einrichten
Da Android ab Version 11 Scoped Storage erzwingt, muss der User einmalig seinen Obsidian-Vault-Ordner über den System-Picker freigeben.

**Zu tun in Android Studio:**
- In `MainActivity.kt` einen Folder-Launcher registrieren.
- Die erhaltene URI permanent im System registrieren, damit der Hintergrunddienst (`WorkManager`) auch im Standby vollen Zugriff auf die Dateien behält.

```kotlin
// In MainActivity.kt integrieren:
val openDocumentTreeLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocumentTree()
) { uri: Uri? ->
    uri?.let {
        // Berechtigungen dauerhaft (persistable) sichern
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(it, takeFlags)
        
        // URI in SharedPreferences speichern
        getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("vault_tree_uri", it.toString())
            .apply()
    }
}
```

---

### 2. Das Datei-Schreib-System (I/O-Klasse) anlegen
Wir benötigen eine Hilfsklasse, die relative Pfade (z.B. `Projekte/Antigravity/Ideen.md`) nimmt und diese physisch auf der SD-Karte / im internen Speicher nachbaut.

**Zu tun in Android Studio:**
- Erstelle eine neue Kotlin-Datei `AndroidVaultWriter.kt` im Package `com.antigravity.vaultlink.database`:

```kotlin
package com.antigravity.vaultlink.database

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream

object AndroidVaultWriter {

    fun getOrCreateDocumentFile(
        context: Context,
        treeUri: Uri,
        relativePath: String,
        mimeType: String = "text/markdown"
    ): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val parts = relativePath.split("/")
        var current = root

        // Rekursiv Ordnerstruktur spiegeln
        for (i in 0 until parts.size - 1) {
            val dirName = parts[i]
            var nextDir = current.findFile(dirName)
            if (nextDir == null || !nextDir.isDirectory) {
                nextDir = current.createDirectory(dirName)
            }
            current = nextDir ?: return null
        }

        val fileName = parts.last()
        var targetFile = current.findFile(fileName)
        if (targetFile == null) {
            targetFile = current.createFile(mimeType, fileName)
        }
        return targetFile
    }

    fun writeDataToFile(context: Context, file: DocumentFile, content: ByteArray) {
        val outputStream: OutputStream? = context.contentResolver.openOutputStream(file.uri)
        outputStream?.use { stream ->
            stream.write(content)
        }
    }
}
```

---

### 3. Background Sync-Worker scharfschalten (`SyncWorker.kt`)
Jetzt verbinden wir den Network-Client mit dem physischen Schreibprozess im Hintergrund-Worker.

**Zu tun in Android Studio:**
- Aktualisiere den `SyncWorker.kt` so, dass er die gespeicherte `treeUri` lädt und den Sync-Vorgang physisch durchführt.

```kotlin
// Im SyncWorker.kt -> doWork() Block:
val prefs = applicationContext.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)
val uriString = prefs.getString("vault_tree_uri", null)

if (uriString != null) {
    val treeUri = Uri.parse(uriString)
    
    delta.toDownload.forEach { path ->
        // 1. Datei vom PC-Server holen (Retrofit Call)
        val responseBody = apiService.downloadFile(path)
        val fileBytes = responseBody.bytes()
        
        // 2. Physisch auf Handy schreiben
        val docFile = AndroidVaultWriter.getOrCreateDocumentFile(applicationContext, treeUri, path)
        docFile?.let {
            AndroidVaultWriter.writeDataToFile(applicationContext, it, fileBytes)
        }
    }
}
```

---

### 4. WorkManager im Hintergrund starten
Damit das Handy vollautomatisch synchronisiert, sobald du im WLAN bist, muss der `WorkManager` beim App-Start mit Constraints konfiguriert werden.

**Zu tun in Android Studio:**
- In `MainActivity.kt` den periodischen Task einreihen:

```kotlin
val syncConstraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.UNMETERED) // Nur im Wi-Fi/WLAN!
    .build()

val syncWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
    .setConstraints(syncConstraints)
    .build()

WorkManager.getInstance(this).enqueueUniquePeriodicWork(
    "VaultLinkAutoSync",
    ExistingPeriodicWorkPolicy.KEEP,
    syncWorkRequest
)
```

---

## 🧭 TEST-FAHRPLAN NACH INTEGRATION

> [!TIP]
> **Testlauf durchführen**:
> 1. Starte den PC-Server (`sync_server_pro.py`). Er läuft im Status "Grün".
> 2. Starte die App auf dem Handy in Android Studio.
> 3. Wähle deinen Obsidian-Vault-Ordner aus.
> 4. Klicke im PC-System-Tray auf **"Jetzt synchronisieren"**.
> 5. Beobachte im Logcat von Android Studio und im Server-Terminal, wie der Web-Socket das Signal durchgibt und die Dateien fließen!

---
*Dokument generiert zur fehlerfreien Koppelung von PC & Android Studio*
