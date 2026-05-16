package com.antigravity.vaultlink

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.work.*
import com.antigravity.vaultlink.network.SyncWebSocketClient
import com.antigravity.vaultlink.ui.VaultLinkDashboard
import com.antigravity.vaultlink.workers.SyncWorker
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private var wsClient: SyncWebSocketClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        wsClient = SyncWebSocketClient(this)
        // In einer echten Umgebung würde man die IP dynamisch aus den Settings laden
        // Hier als Beispiel die Standard-IP (muss ggf. angepasst werden)
        val testIp = "192.168.178.144"
        wsClient?.connect(testIp) 
        
        getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("server_ip", testIp)
            .apply() 

        setContent {
            val context = this
            val prefs = remember { context.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE) }
            var vaultUriString by remember { mutableStateOf(prefs.getString("vault_tree_uri", null)) }

            val folderPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { uri: Uri? ->
                uri?.let {
                    // Berechtigungen dauerhaft (persistable) sichern
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(it, takeFlags)
                    
                    // URI in SharedPreferences speichern
                    prefs.edit().putString("vault_tree_uri", it.toString()).apply()
                    vaultUriString = it.toString()
                    
                    // WorkManager starten, sobald Ordner gewählt wurde
                    enqueueSyncWork(context)
                }
            }

            VaultLinkDashboard(
                syncStatus = if (vaultUriString == null) "Obsidian-Vault wählen" else "Bereit",
                pendingOperations = 0,
                onSyncNow = {
                    if (vaultUriString == null) {
                        folderPickerLauncher.launch(null)
                    } else {
                        // Sofortiger Sync-Trigger (OneTimeWorkRequest) könnte hier hin
                        val oneTimeRequest = OneTimeWorkRequestBuilder<SyncWorker>().build()
                        WorkManager.getInstance(context).enqueue(oneTimeRequest)
                    }
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wsClient?.disconnect()
    }

    private fun enqueueSyncWork(context: Context) {
        val syncConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // Nur im Wi-Fi/WLAN!
            .build()

        val syncWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(syncConstraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "VaultLinkAutoSync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncWorkRequest
        )
    }
}
