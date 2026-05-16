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
