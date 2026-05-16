package com.antigravity.vaultlink.workers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.antigravity.vaultlink.database.VaultDatabase
import com.antigravity.vaultlink.database.FileSnapshot
import com.antigravity.vaultlink.database.AndroidVaultWriter
import com.antigravity.vaultlink.utils.SyncLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.InputStream
import java.security.MessageDigest

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val db = VaultDatabase.getDatabase(context)
    private val engine = SyncEngine()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            SyncLogger.log("Sync-Zyklus gestartet...")
            Log.i("VaultLink", "Sync-Zyklus gestartet...")

            val prefs = applicationContext.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)
            val serverIp = prefs.getString("server_ip", "192.168.178.144") ?: "192.168.178.144"
            val uriString = prefs.getString("vault_tree_uri", null)

            if (uriString == null) {
                SyncLogger.log("FEHLER: Kein lokaler Vault-Ordner ausgewählt!")
                Log.e("VaultLink", "Kein vault_tree_uri in SharedPreferences")
                return@withContext Result.failure()
            }
            val vaultUri = Uri.parse(uriString)

            // 1. Initialize Retrofit Client dynamically
            val retrofit = Retrofit.Builder()
                .baseUrl("http://$serverIp:8080/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            val apiService = retrofit.create(com.antigravity.vaultlink.network.VaultLinkApi::class.java)

            // 2. Fetch lastSyncMap (the database snapshots from the last successful sync)
            SyncLogger.log("Lade Verlauf aus lokaler DB...")
            val lastSyncMap = db.vaultDao().getAllSnapshots().associateBy { it.filePath }

            // 3. Scan physical local files in Scoped Storage to build localMap
            SyncLogger.log("Scanne lokale Dateien...")
            val localMap = scanLocalVault(applicationContext, vaultUri)
            SyncLogger.log("Lokaler Scan abgeschlossen. ${localMap.size} Dateien gefunden.")

            // 4. Scan PC files via Retrofit to build remoteMap
            SyncLogger.log("Scanne PC-Dateien...")
            val scanResponse = apiService.getScan()
            val remoteMap = scanResponse.files.map { dto ->
                FileSnapshot(
                    filePath = dto.rel_path,
                    hash = dto.hash,
                    size = dto.size,
                    lastModified = (dto.modified * 1000).toLong() // Convert seconds to ms
                )
            }.associateBy { it.filePath }
            SyncLogger.log("PC-Scan abgeschlossen. ${remoteMap.size} Dateien auf dem PC.")

            // 5. Delta berechnen
            val delta = engine.calculateDelta(localMap, remoteMap, lastSyncMap)
            SyncLogger.log("Delta berechnet: ${delta.toDownload.size} Downloads, ${delta.toUpload.size} Uploads, ${delta.toDeleteLocal.size} lokale Löschungen, ${delta.toDeleteRemote.size} remote Löschungen.")

            // 6. Uploads ausführen (toUpload)
            delta.toUpload.forEach { path ->
                SyncLogger.log("Sende an PC: $path")
                Log.d("VaultLink", "Uploading: $path")
                try {
                    val fileSnapshot = localMap[path] ?: return@forEach
                    val root = DocumentFile.fromTreeUri(applicationContext, vaultUri) ?: return@forEach
                    val docFile = findDocumentFileByPath(root, path) ?: return@forEach
                    
                    val inputStream = applicationContext.contentResolver.openInputStream(docFile.uri)
                    val bytes = inputStream?.use { it.readBytes() } ?: byteArrayOf()
                    
                    val requestFile = RequestBody.create(
                        "application/octet-stream".toMediaTypeOrNull(),
                        bytes
                    )
                    val body = MultipartBody.Part.createFormData("file", docFile.name ?: "file", requestFile)
                    
                    apiService.uploadFile(
                        relPath = path,
                        lastModified = (fileSnapshot.lastModified / 1000f), // convert ms to seconds float
                        file = body
                    )
                    SyncLogger.log("Erfolgreich hochgeladen: $path")
                } catch (e: Exception) {
                    SyncLogger.log("Upload fehlgeschlagen ($path): ${e.message}")
                    Log.e("VaultLink", "Upload failed for $path", e)
                }
            }

            // 7. Downloads ausführen (toDownload)
            delta.toDownload.forEach { path ->
                SyncLogger.log("Empfange vom PC: $path")
                Log.d("VaultLink", "Downloading: $path")
                try {
                    val remoteFile = remoteMap[path] ?: return@forEach
                    val responseBody = apiService.downloadFile(path)
                    val bytes = responseBody.bytes()
                    
                    val fileDoc = AndroidVaultWriter.getOrCreateDocumentFile(applicationContext, vaultUri, path)
                    if (fileDoc != null) {
                        AndroidVaultWriter.writeDataToFile(applicationContext, fileDoc, bytes)
                        SyncLogger.log("Datei empfangen & geschrieben: $path")
                    }
                } catch (e: Exception) {
                    SyncLogger.log("Download fehlgeschlagen ($path): ${e.message}")
                    Log.e("VaultLink", "Download failed for $path", e)
                }
            }

            // 8. Lokale Löschungen ausführen (toDeleteLocal)
            delta.toDeleteLocal.forEach { path ->
                SyncLogger.log("Lösche lokal: $path")
                Log.d("VaultLink", "Deleting locally: $path")
                try {
                    val root = DocumentFile.fromTreeUri(applicationContext, vaultUri) ?: return@forEach
                    val docFile = findDocumentFileByPath(root, path)
                    if (docFile != null && docFile.exists()) {
                        docFile.delete()
                        SyncLogger.log("Lokal gelöscht: $path")
                    }
                } catch (e: Exception) {
                    SyncLogger.log("Lokales Löschen fehlgeschlagen ($path): ${e.message}")
                }
            }

            // 9. Remote Löschungen ausführen (toDeleteRemote)
            delta.toDeleteRemote.forEach { path ->
                SyncLogger.log("Lösche auf PC: $path")
                Log.d("VaultLink", "Deleting on PC: $path")
                try {
                    apiService.deleteFile(path)
                    SyncLogger.log("Auf PC gelöscht: $path")
                } catch (e: Exception) {
                    SyncLogger.log("PC-Löschen fehlgeschlagen ($path): ${e.message}")
                }
            }

            // 10. Konflikte melden
            delta.conflicts.forEach { conflict ->
                SyncLogger.log("KONFLIKT: ${conflict.filePath}")
                db.vaultDao().reportConflict(conflict)
            }

            // 11. Rebuild Room db snapshots using a post-sync local scan
            SyncLogger.log("Aktualisiere lokale Datenbank...")
            val finalLocalMap = scanLocalVault(applicationContext, vaultUri)
            
            // Clear current snapshots and replace with post-sync actual physical state
            db.vaultDao().deleteAllSnapshots()
            finalLocalMap.values.forEach { snapshot ->
                db.vaultDao().upsertSnapshot(snapshot)
            }

            SyncLogger.log("Sync erfolgreich beendet.")
            Log.i("VaultLink", "Sync erfolgreich beendet.")
            Result.success()
        } catch (e: Exception) {
            SyncLogger.log("FEHLER: ${e.message}")
            Log.e("VaultLink", "Fehler im SyncWorker: ${e.message}", e)
            Result.retry()
        }
    }

    private fun scanLocalVault(context: Context, treeUri: Uri): Map<String, FileSnapshot> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyMap()
        val localFiles = mutableMapOf<String, FileSnapshot>()
        scanDirectoryRecursive(context, root, "", localFiles)
        return localFiles
    }

    private fun scanDirectoryRecursive(
        context: Context,
        dir: DocumentFile,
        currentRelPath: String,
        result: MutableMap<String, FileSnapshot>
    ) {
        val files = dir.listFiles()
        for (file in files) {
            val name = file.name ?: continue
            if (name.startsWith(".") || name == ".trash") continue
            val relPath = if (currentRelPath.isEmpty()) name else "$currentRelPath/$name"

            if (file.isDirectory) {
                scanDirectoryRecursive(context, file, relPath, result)
            } else {
                val hash = calculateFileHash(context, file.uri)
                result[relPath] = FileSnapshot(
                    filePath = relPath,
                    hash = hash,
                    size = file.length(),
                    lastModified = file.lastModified()
                )
            }
        }
    }

    private fun calculateFileHash(context: Context, uri: Uri): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(4096)
                var bytesRead = input.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = input.read(buffer)
                }
            }
            val hashBytes = digest.digest()
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    private fun findDocumentFileByPath(root: DocumentFile, relativePath: String): DocumentFile? {
        val parts = relativePath.split("/")
        var current: DocumentFile? = root
        for (part in parts) {
            current = current?.findFile(part)
            if (current == null) return null
        }
        return current
    }
}
