package com.github.damontecres.wholphin.services

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.WholphinApplication
import com.github.damontecres.wholphin.data.ArrServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.ArrServer
import com.github.damontecres.wholphin.data.model.ArrType
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.data.model.UpcomingDisplay
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.ui.joinNotBlank
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

private const val CALENDAR_WINDOW_DAYS = 60L

/**
 * Gets the upcoming releases from the Radarr and Sonarr calendars of the current user
 *
 * @see ArrImportService
 */
@Singleton
class ArrCalendarService
    @Inject
    constructor(
        private val serverRepository: ServerRepository,
        private val arrServerDao: ArrServerDao,
        private val seerrServerRepository: SeerrServerRepository,
        @StandardOkHttpClient okHttpClient: OkHttpClient,
    ) {
        private val client =
            okHttpClient
                .newBuilder()
                .connectTimeout(2.seconds)
                .readTimeout(8.seconds)
                .build()

        /**
         * Fetch the next [limit] releases, soonest first
         *
         * @return null if no instance has been imported
         * @throws IOException if none of the instances could be reached
         */
        suspend fun getUpcoming(limit: Int): List<BaseItem>? {
            val user = serverRepository.currentUser ?: return null
            val servers = arrServerDao.getServers(user.rowId).first()
            if (servers.isEmpty()) return null

            val now = Instant.now()
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            // Radarr dates are at midnight UTC, so start at the beginning of the day to get today's
            val start =
                LocalDate
                    .now(ZoneOffset.UTC)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
            val end = start.plusSeconds(CALENDAR_WINDOW_DAYS * 24 * 60 * 60)
            val results =
                coroutineScope {
                    servers
                        .map { server ->
                            async { fetchReleases(server, start, end, now, today, zone) }
                        }.awaitAll()
                }
            if (results.all { it == null }) {
                throw IOException("Could not reach Radarr or Sonarr")
            }
            val canOpen = seerrServerRepository.active.first()
            val resources = WholphinApplication.instance.resources
            return mergeUpcoming(results.filterNotNull().flatten(), limit)
                .map { it.toBaseItem(today, canOpen, resources) }
        }

        private suspend fun fetchReleases(
            server: ArrServer,
            start: Instant,
            end: Instant,
            now: Instant,
            today: LocalDate,
            zone: ZoneId,
        ): List<UpcomingRelease>? =
            try {
                when (server.type) {
                    ArrType.RADARR -> {
                        getCalendar<List<RadarrMovie>>(server, start, end).mapNotNull { it.toUpcoming(today) }
                    }

                    ArrType.SONARR -> {
                        getCalendar<List<SonarrEpisode>>(server, start, end).toUpcoming(now, zone)
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w("Could not get the calendar of %s: %s", server.name, ex.message)
                null
            }

        @OptIn(ExperimentalSerializationApi::class)
        private suspend inline fun <reified T> getCalendar(
            server: ArrServer,
            start: Instant,
            end: Instant,
        ): T =
            withContext(WholphinDispatchers.IO) {
                val url =
                    "${server.url}/api/v3/calendar"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("start", start.toString())
                        .addQueryParameter("end", end.toString())
                        .addQueryParameter("unmonitored", "false")
                        .addQueryParameter("includeSeries", "true")
                        .build()
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("X-Api-Key", server.apiKey)
                        .build()
                client.newCall(request).execute().use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                    HomeSettingsService.jsonParser.decodeFromStream<T>(it.body.byteStream())
                }
            }
    }

@Serializable
internal data class ArrImage(
    val coverType: String? = null,
    val remoteUrl: String? = null,
)

@Serializable
internal data class RadarrMovie(
    val title: String? = null,
    val overview: String? = null,
    val year: Int? = null,
    val tmdbId: Int? = null,
    val inCinemas: String? = null,
    val digitalRelease: String? = null,
    val physicalRelease: String? = null,
    val images: List<ArrImage>? = null,
)

@Serializable
internal data class SonarrSeries(
    val title: String? = null,
    val overview: String? = null,
    val tvdbId: Int? = null,
    val tmdbId: Int? = null,
    val images: List<ArrImage>? = null,
)

@Serializable
internal data class SonarrEpisode(
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val title: String? = null,
    val overview: String? = null,
    val airDateUtc: String? = null,
    val series: SonarrSeries? = null,
)

internal enum class MovieReleaseKind(
    @param:StringRes val stringId: Int,
) {
    CINEMA(R.string.upcoming_cinema),
    DIGITAL(R.string.upcoming_digital),
    PHYSICAL(R.string.upcoming_physical),
}

internal data class UpcomingRelease(
    val key: String,
    val type: ArrType,
    val title: String,
    val date: LocalDate,
    val movieKind: MovieReleaseKind? = null,
    val episodes: String? = null,
    val episodeTitle: String? = null,
    val overview: String? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val tmdbId: Int? = null,
)

/**
 * Keeps the next release of this movie, since it has one date for each kind of release
 */
internal fun RadarrMovie.toUpcoming(today: LocalDate): UpcomingRelease? {
    val movieTitle = title ?: return null
    val (date, kind) =
        listOf(
            inCinemas to MovieReleaseKind.CINEMA,
            digitalRelease to MovieReleaseKind.DIGITAL,
            physicalRelease to MovieReleaseKind.PHYSICAL,
        ).mapNotNull { (value, kind) ->
            parseInstant(value)
                ?.atOffset(ZoneOffset.UTC)
                ?.toLocalDate()
                ?.takeIf { !it.isBefore(today) }
                ?.let { it to kind }
        }.minByOrNull { it.first } ?: return null
    return UpcomingRelease(
        key = "movie-${tmdbId ?: movieTitle}",
        type = ArrType.RADARR,
        title = movieTitle,
        date = date,
        movieKind = kind,
        overview = overview,
        year = year,
        posterUrl = images.remoteUrl("poster", "w342"),
        backdropUrl = images.remoteUrl("fanart", "w1280"),
        tmdbId = tmdbId,
    )
}

/**
 * Episodes of a series released on the same day are shown as a single release
 */
internal fun List<SonarrEpisode>.toUpcoming(
    now: Instant,
    zone: ZoneId,
): List<UpcomingRelease> =
    mapNotNull { episode ->
        val series = episode.series ?: return@mapNotNull null
        val seriesTitle = series.title ?: return@mapNotNull null
        val airDate = parseInstant(episode.airDateUtc)?.takeIf { !it.isBefore(now) } ?: return@mapNotNull null
        Triple(episode, series.tvdbId?.toString() ?: seriesTitle, airDate.atZone(zone).toLocalDate())
    }.distinctBy { (episode, seriesKey, _) -> Triple(seriesKey, episode.seasonNumber, episode.episodeNumber) }
        .groupBy { (_, seriesKey, date) -> seriesKey to date }
        .map { (group, entries) ->
            val (seriesKey, date) = group
            val episodes =
                entries
                    .map { it.first }
                    .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
            val first = episodes.first()
            val series = first.series!!
            UpcomingRelease(
                key = "series-$seriesKey-$date",
                type = ArrType.SONARR,
                title = series.title!!,
                date = date,
                episodes = episodeRange(first, episodes.last()),
                episodeTitle = first.title.takeIf { episodes.size == 1 },
                overview = first.overview?.takeIf { episodes.size == 1 } ?: series.overview,
                posterUrl = series.images.remoteUrl("poster", "w342"),
                backdropUrl = series.images.remoteUrl("fanart", "w1280"),
                tmdbId = series.tmdbId,
            )
        }

/**
 * Combine the releases of every instance, the same release can be in several of them (eg HD and 4K)
 */
internal fun mergeUpcoming(
    releases: List<UpcomingRelease>,
    limit: Int,
): List<UpcomingRelease> =
    releases
        .distinctBy { it.key }
        .sortedWith(compareBy({ it.date }, { it.title }))
        .take(limit)

/**
 * TMDB images are referenced at their original size which is much larger than what is displayed
 */
internal fun resizeTmdbImage(
    url: String,
    size: String,
): String = url.replace("/t/p/original/", "/t/p/$size/")

private fun List<ArrImage>?.remoteUrl(
    coverType: String,
    tmdbSize: String,
): String? =
    this
        ?.firstOrNull { it.coverType == coverType }
        ?.remoteUrl
        ?.let { resizeTmdbImage(it, tmdbSize) }

private fun episodeRange(
    first: SonarrEpisode,
    last: SonarrEpisode,
): String {
    val start = String.format(Locale.ROOT, "S%02dE%02d", first.seasonNumber ?: 0, first.episodeNumber ?: 0)
    return if (first === last) {
        start
    } else {
        start + String.format(Locale.ROOT, "-E%02d", last.episodeNumber ?: 0)
    }
}

private fun parseInstant(value: String?): Instant? =
    value?.let {
        try {
            OffsetDateTime.parse(it).toInstant()
        } catch (ex: DateTimeParseException) {
            Timber.w("Invalid date from Radarr or Sonarr: %s", it)
            null
        }
    }

private fun UpcomingRelease.toBaseItem(
    today: LocalDate,
    canOpen: Boolean,
    resources: Resources,
): BaseItem {
    val destination =
        tmdbId?.takeIf { canOpen && it > 0 }?.let {
            DiscoverItem(
                id = it,
                type = if (type == ArrType.RADARR) SeerrItemType.MOVIE else SeerrItemType.TV,
                title = title,
                subtitle = null,
                overview = overview,
                availability = SeerrAvailability.UNKNOWN,
                releaseDate = date,
                posterUrl = posterUrl,
                backDropUrl = backdropUrl,
                logoUrl = null,
                jellyfinItemId = null,
            ).destination
        }
    return BaseItem(
        data =
            BaseItemDto(
                id = UUID.nameUUIDFromBytes(key.toByteArray()),
                type = BaseItemKind.FOLDER,
                name = title,
                overview = overview,
                productionYear = year,
            ),
        imageUrlOverride = posterUrl,
        destinationOverride = destination,
        upcoming =
            UpcomingDisplay(
                date = formatUpcomingDate(date, today, resources),
                details = movieKind?.let { resources.getString(it.stringId) } ?: listOf(episodes, episodeTitle).joinNotBlank(" - "),
                backdropUrl = backdropUrl,
            ),
    )
}

private fun formatUpcomingDate(
    date: LocalDate,
    today: LocalDate,
    resources: Resources,
): String =
    when (date) {
        today -> {
            resources.getString(R.string.upcoming_today)
        }

        today.plusDays(1) -> {
            resources.getString(R.string.upcoming_tomorrow)
        }

        else -> {
            val locale = resources.configuration.locales[0]
            DateTimeFormatter
                .ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale)
                .format(date)
        }
    }
