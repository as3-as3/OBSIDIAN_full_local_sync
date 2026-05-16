package com.antigravity.vaultlink.database

import androidx.room.*

@Entity(tableName = "file_snapshots")
data class FileSnapshot(
    @PrimaryKey val filePath: String,
    val hash: String,
    val size: Long,
    val lastModified: Long,
    val syncStatus: String = "synced"
)

@Entity(tableName = "offline_queue")
data class OfflineOperation(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val filePath: String,
    val operationType: String, // "write", "delete"
    val contentHash: String?,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "conflicts")
data class ConflictFile(
    @PrimaryKey val filePath: String,
    val localHash: String,
    val remoteHash: String,
    val localModified: Long,
    val remoteModified: Long,
    val status: String = "unresolved"
)

@Dao
interface VaultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshot(snapshot: FileSnapshot)

    @Query("SELECT * FROM file_snapshots")
    suspend fun getAllSnapshots(): List<FileSnapshot>

    @Query("DELETE FROM file_snapshots")
    suspend fun deleteAllSnapshots()

    @Insert
    suspend fun enqueueOperation(op: OfflineOperation)

    @Query("SELECT * FROM offline_queue ORDER BY timestamp ASC")
    suspend fun getPendingOperations(): List<OfflineOperation>

    @Delete
    suspend fun removeOperation(op: OfflineOperation)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun reportConflict(conflict: ConflictFile)
    
    @Query("SELECT * FROM conflicts WHERE status = 'unresolved'")
    suspend fun getUnresolvedConflicts(): List<ConflictFile>
}

@Database(entities = [FileSnapshot::class, OfflineOperation::class, ConflictFile::class], version = 1, exportSchema = false)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao

    companion object {
        @Volatile
        private var INSTANCE: VaultDatabase? = null

        fun getDatabase(context: android.content.Context): VaultDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    "vault_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
