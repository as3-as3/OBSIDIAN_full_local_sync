package com.antigravity.vaultlink.network

import okhttp3.MultipartBody
import retrofit2.http.*

interface VaultLinkApi {
    @GET("/api/status")
    suspend fun getStatus(): Map<String, String>

    @GET("/api/sync/scan")
    suspend fun getScan(): ScanResponseDto

    @Multipart
    @POST("/api/sync/push")
    suspend fun uploadFile(
        @Query("rel_path") relPath: String,
        @Query("last_modified") lastModified: Float,
        @Part file: MultipartBody.Part
    ): Map<String, String>

    @GET("/api/sync/download")
    suspend fun downloadFile(@Query("rel_path") relPath: String): okhttp3.ResponseBody

    @DELETE("/api/sync/delete")
    suspend fun deleteFile(@Query("rel_path") relPath: String): Map<String, String>
}

data class ScanResponseDto(
    val status: String,
    val files: List<FileInfoDto>,
    val timestamp: Double
)

data class FileInfoDto(
    val rel_path: String,
    val hash: String,
    val size: Long,
    val modified: Double
)
