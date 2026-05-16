package com.antigravity.vaultlink.workers

import com.antigravity.vaultlink.database.FileSnapshot
import com.antigravity.vaultlink.database.ConflictFile

class SyncEngine {
    
    data class SyncDelta(
        val toUpload: List<String> = emptyList(),
        val toDownload: List<String> = emptyList(),
        val toDeleteLocal: List<String> = emptyList(),
        val toDeleteRemote: List<String> = emptyList(),
        val conflicts: List<ConflictFile> = emptyList()
    )

    fun calculateDelta(local: Map<String, FileSnapshot>, remote: Map<String, FileSnapshot>, lastSync: Map<String, FileSnapshot>): SyncDelta {
        val toUpload = mutableListOf<String>()
        val toDownload = mutableListOf<String>()
        val toDeleteLocal = mutableListOf<String>()
        val toDeleteRemote = mutableListOf<String>()
        val conflicts = mutableListOf<ConflictFile>()

        val allPaths = (local.keys + remote.keys + lastSync.keys).distinct()

        for (path in allPaths) {
            val l = local[path]
            val r = remote[path]
            val s = lastSync[path]

            when {
                // CASE 1: New on both with different content
                l != null && r != null && s == null && l.hash != r.hash -> {
                    conflicts.add(ConflictFile(path, l.hash, r.hash, l.lastModified, r.lastModified))
                }
                // CASE 2: New on Local
                l != null && r == null && s == null -> toUpload.add(path)
                // CASE 3: New on Remote
                r != null && l == null && s == null -> toDownload.add(path)
                // CASE 4: Deleted on Local
                l == null && s != null && r != null && r.hash == s.hash -> toDeleteRemote.add(path)
                // CASE 5: Deleted on Remote
                r == null && s != null && l != null && l.hash == s.hash -> toDeleteLocal.add(path)
                // CASE 6: Changed on both (Conflict)
                l != null && r != null && s != null && l.hash != s.hash && r.hash != s.hash -> {
                    if (l.hash != r.hash) {
                         conflicts.add(ConflictFile(path, l.hash, r.hash, l.lastModified, r.lastModified))
                    }
                }
                // CASE 7: Changed on Local only
                l != null && r != null && s != null && l.hash != s.hash && r.hash == s.hash -> toUpload.add(path)
                // CASE 8: Changed on Remote only
                l != null && r != null && s != null && r.hash != s.hash && l.hash == s.hash -> toDownload.add(path)
            }
        }
        return SyncDelta(toUpload, toDownload, toDeleteLocal, toDeleteRemote, conflicts)
    }
}
