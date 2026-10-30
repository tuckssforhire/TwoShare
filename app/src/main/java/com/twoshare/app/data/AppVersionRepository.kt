package com.twoshare.app.data

import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppVersionInfo(
    val id: Int,
    @SerialName("latest_version_code") val latestVersionCode: Int,
    @SerialName("latest_version_name") val latestVersionName: String,
    @SerialName("download_url") val downloadUrl: String
)

class AppVersionRepository {
    private val supabase = SupabaseProvider.client

    suspend fun latestVersion(): AppVersionInfo? =
        supabase.from("app_version")
            .select {
                filter { eq("id", 1) }
            }
            .decodeList<AppVersionInfo>()
            .firstOrNull()
}
