package com.antigravity.vaultlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.vaultlink.utils.SyncLogger

@Composable
fun VaultLinkDashboard(syncStatus: String, pendingOperations: Int, onSyncNow: () -> Unit) {
    val logs by SyncLogger.logs.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Vault-Link Pro") }) }
    ) { padding ->
        Column(modifier = Modifier
            .padding(padding)
            .padding(16.dp)) {
            
            // Status Card
            Card(elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Status", style = MaterialTheme.typography.h6)
                    Text(syncStatus, color = if (syncStatus == "Bereit") Color(0xFFD4AF37) else Color.Red)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Wartende Änderungen: $pendingOperations")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = onSyncNow, 
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD4AF37))
            ) {
                Text("Jetzt Synchronisieren", color = Color.Black)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Worker Terminal
            Text("Worker Terminal", style = MaterialTheme.typography.subtitle1, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF121212), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                LazyColumn {
                    items(logs) { log ->
                        Text(
                            text = log,
                            color = Color(0xFF00FF00), // Terminal Green
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConflictDialog(fileName: String, localTime: String, remoteTime: String, onResolve: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Konflikt in $fileName") },
        text = {
            Column {
                Text("Diese Datei wurde auf beiden Geräten geändert.")
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { onResolve(true) }) { Text("Handy-Version behalten ($localTime)") }
                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = { onResolve(false) }) { Text("PC-Version behalten ($remoteTime)") }
            }
        },
        confirmButton = {}
    )
}
