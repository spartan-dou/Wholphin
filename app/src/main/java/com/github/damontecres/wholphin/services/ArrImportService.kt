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
                    val failures = mutableListOf<ArrUrlFailure>()
                    val reachableUrl =
                        instance.urls.firstOrNull { url ->
                            val problem = findProblem(url, apiKey)
                            if (problem != null) failures.add(ArrUrlFailure(url, problem))
                            problem == null
                        }
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
                        failures = failures,
                    )
                }
            arrServerDao.replaceServers(user.rowId, results.map { it.server })
            return results
        }

        /**
         * @return null if [url] answers with this key, otherwise why it doesn't
         */
        private suspend fun findProblem(
            url: String,
            apiKey: String,
        ): String? =
            withContext(WholphinDispatchers.IO) {
                val request =
                    Request
                        .Builder()
                        .url("$url/api/v3/system/status")
                        .header("X-Api-Key", apiKey)
                        .build()
                try {
                    client.newCall(request).execute().use {
                        when {
                            !it.isSuccessful -> "HTTP ${it.code}"

                            // A proxy's login page can answer 200, so also require JSON
                            it.body.contentType()?.subtype != "json" -> "HTTP ${it.code}, ${it.body.contentType()}"

                            else -> null
                        }
                    }
                } catch (ex: IOException) {
                    ex.message ?: ex.javaClass.simpleName
                }
            }
    }

data class ArrImportResult(
    val server: ArrServer,
    val reachable: Boolean,
    val failures: List<ArrUrlFailure> = emptyList(),
)

data class ArrUrlFailure(
    val url: String,
    val problem: String,
)

private class ArrInstance(
    val type: ArrType,
    val name: String,
    val apiKey: String?,
    val urls: List<String>,
)

/**
 * The addresses to try for an instance, best first. The external URL is meant to be used from other
 * devices, while the hostname stored in Seerr is often only valid from Seerr itself (eg a Docker
 * name), so that one falls back to the same port on the Seerr host.
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

    val external =
        externalUrl
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { if ("://" in it) it else "http://$it" }
            ?.toHttpUrlOrNull()
            ?.toString()
            ?.removeSuffix("/")

    return listOfNotNull(
        external,
        build(hostname),
        build(seerrUrl.toHttpUrlOrNull()?.host),
    ).distinct()
}
