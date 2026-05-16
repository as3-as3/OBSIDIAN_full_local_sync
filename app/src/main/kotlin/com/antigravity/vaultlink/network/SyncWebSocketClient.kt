package com.antigravity.vaultlink.network

import android.content.Context
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.antigravity.vaultlink.utils.SyncLogger
import com.antigravity.vaultlink.workers.SyncWorker
import okhttp3.*
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper

class SyncWebSocketClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket needs no timeout
        .build()

    private var webSocket: WebSocket? = null
    private var activeIp: String? = null
    private var isClosedIntentionally = false

    fun connect(serverIp: String) {
        activeIp = serverIp
        isClosedIntentionally = false
        val request = Request.Builder()
            .url("ws://$serverIp:8080/api/ws/sync")
            .build()

        SyncLogger.log("Verbinde mit PC-Server: $serverIp...")
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                SyncLogger.log("Verbunden mit PC-Server!")
                Log.i("VaultLink", "WebSocket Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("VaultLink", "WebSocket Message: $text")
                if (text.contains("TRIGGER_SYNC", ignoreCase = true)) {
                    SyncLogger.log("Remote Sync-Signal empfangen!")
                    triggerImmediateSync()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                SyncLogger.log("Verbindung getrennt.")
                Log.w("VaultLink", "WebSocket Closed: $reason")
                if (!isClosedIntentionally) {
                    retryConnection()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                SyncLogger.log("Verbindungsfehler: ${t.message}")
                Log.e("VaultLink", "WebSocket Error", t)
                if (!isClosedIntentionally) {
                    retryConnection()
                }
            }
        })
    }

    private fun retryConnection() {
        val ip = activeIp ?: return
        SyncLogger.log("Verbindung verloren. Erneuter Versuch in 5 Sekunden...")
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isClosedIntentionally) {
                connect(ip)
            }
        }, 5000)
    }

    private fun triggerImmediateSync() {
        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>().build()
        WorkManager.getInstance(context).enqueue(syncRequest)
    }

    fun disconnect() {
        isClosedIntentionally = true
        webSocket?.close(1000, "App closed")
    }
}
