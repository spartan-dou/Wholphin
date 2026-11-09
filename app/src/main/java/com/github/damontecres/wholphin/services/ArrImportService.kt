package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.ArrServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.ArrServer
import com.github.damontecres.wholphin.data.model.ArrType
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Imports the Radarr and Sonarr instances configured in Seerr, so their API keys don't need to be typed
 */
@Singleton
class ArrImportService
    @Inject
    constructor(
        private val seerrApi: SeerrApi,
        private val seerrServerRepository: SeerrServerRepository,
        private val serverRepository: ServerRepository,
        private val arrServerDao: ArrServerDao,
        @StandardOkHttpClient okHttpClient: OkHttpClient,
    ) {
        private val client =
            okHttpClient
                .newBuilder()
                .connectTimeout(2.seconds)
                .readTimeout(5.seconds)
                .build()

        /**
         * The instances imported for the current user
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val servers: Flow<List<ArrServer>> =
            serverRepository.currentUserFlow.flatMapLatest { user ->
                user?.let { arrServerDao.getServers(it.rowId) } ?: flowOf(emptyList())
            }

        /**
         * Replaces the current user's instances with the ones configured in Seerr. An instance that
         * could not be reached is still saved using the address from Seerr.
         */
        suspend fun importFromSeerr(): List<ArrImportResult> {
            val user = checkNotNull(serverRepository.currentUser) { "No current user" }
            val seerrUrl = checkNotNull(seerrServerRepository.current.first()) { "Seerr is not connected" }.server.url
            val settingsApi = seerrApi.api.settingsApi
            val radarr =
                settingsApi.settingsRadarrGet().map {
                    ArrInstance(
                        type = ArrType.RADARR,
                        name = it.name,
                        apiKey = it.apiKey,
                        urls = arrUrlCandidates(ArrType.RADARR, it.hostname, it.port, it.useSsl, it.baseUrl, it.externalUrl, seerrUrl),
                    )
                }
            val sonarr =
                settingsApi.settingsSonarrGet().map {
                    ArrInstance(
                        type = ArrType.SONARR,
                        name = it.name,
                        apiKey = it.apiKey,
                        urls = arrUrlCandidates(ArrType.SONARR, it.hostname, it.port, it.useSsl, it.baseUrl, it.externalUrl, seerrUrl),
                    )
                }
            val results =
                (radarr + sonarr).mapNotNull { instance ->
                    val apiKey = instance.apiKey?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val reachableUrl = instance.urls.firstOrNull { isReachable(it, apiKey) }
                    val url = reachableUrl ?: instance.urls.firstOrNull() ?: return@mapNotNull null
                    ArrImportResult(
                        ArrServer(
                            jellyfinUserRowId = user.rowId,
                            type = instance.type,
                            name = instance.name,
                            url = url,
                            apiKey = apiKey,
                        ),
                        reachable = reachableUrl != null,
                    )
                }
            arrServerDao.replaceServers(user.rowId, results.map { it.server })
            return results
        }

        private suspend fun isReachable(
            url: String,
            apiKey: String,
        ): Boolean =
            withContext(WholphinDispatchers.IO) {
                val request =
                    Request
                        .Builder()
                        .url("$url/api/v3/system/status")
                        .header("X-Api-Key", apiKey)
                        .build()
                try {
                    // A proxy's login page can answer 200, so also require JSON
                    client.newCall(request).execute().use {
                        it.isSuccessful && it.body.contentType()?.subtype == "json"
                    }
                } catch (ex: IOException) {
                    Timber.d("Could not reach %s: %s", url, ex.message)
                    false
                }
            }
    }

data class ArrImportResult(
    val server: ArrServer,
    val reachable: Boolean,
)

private class ArrInstance(
    val type: ArrType,
    val name: String,
    val apiKey: String?,
    val urls: List<String>,
)

/**
 * The addresses to try for an instance, best first. The hostname stored in Seerr is often only valid
 * from Seerr itself (eg a Docker name), so it falls back to the same port on the Seerr host, then to
 * the external URL.
 */
internal fun arrUrlCandidates(
    type: ArrType,
    hostname: String?,
    port: Int?,
    useSsl: Boolean?,
    baseUrl: String?,
    externalUrl: String?,
    seerrUrl: String,
): List<String> {
    fun build(host: String?): String? {
        if (host.isNullOrBlank()) return null
        return try {
            HttpUrl
                .Builder()
                .scheme(if (useSsl == true) "https" else "http")
                .host(host)
                .port(port ?: type.defaultPort)
                .addPathSegments(baseUrl.orEmpty().trim('/'))
                .build()
                .toString()
                .removeSuffix("/")
        } catch (ex: IllegalArgumentException) {
            Timber.w("Invalid host in Seerr settings: %s", host)
            null
        }
    }

    return listOfNotNull(
        build(hostname),
        build(seerrUrl.toHttpUrlOrNull()?.host),
        externalUrl
            ?.trim()
            ?.toHttpUrlOrNull()
            ?.toString()
            ?.removeSuffix("/"),
    ).distinct()
}
