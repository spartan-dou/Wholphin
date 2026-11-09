package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.data.model.ArrType
import com.github.damontecres.wholphin.services.MovieReleaseKind
import com.github.damontecres.wholphin.services.RadarrMovie
import com.github.damontecres.wholphin.services.SonarrEpisode
import com.github.damontecres.wholphin.services.SonarrSeries
import com.github.damontecres.wholphin.services.UpcomingRelease
import com.github.damontecres.wholphin.services.mergeUpcoming
import com.github.damontecres.wholphin.services.resizeTmdbImage
import com.github.damontecres.wholphin.services.toUpcoming
import org.junit.Assert
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class TestArrCalendar {
    private val today = LocalDate.of(2026, 11, 9)
    private val now = Instant.parse("2026-11-09T12:00:00Z")
    private val series = SonarrSeries(title = "Show", tvdbId = 1)

    @Test
    fun `Movie uses its next release`() {
        val movie =
            RadarrMovie(
                title = "Movie",
                inCinemas = "2026-10-01T00:00:00Z",
                digitalRelease = "2026-12-01T00:00:00Z",
                physicalRelease = "2026-11-20T00:00:00Z",
            ).toUpcoming(today)!!
        Assert.assertEquals(LocalDate.of(2026, 11, 20), movie.date)
        Assert.assertEquals(MovieReleaseKind.PHYSICAL, movie.movieKind)
    }

    @Test
    fun `Movie released today is kept and past releases are not`() {
        Assert.assertNotNull(RadarrMovie(title = "Movie", inCinemas = "2026-11-09T00:00:00Z").toUpcoming(today))
        Assert.assertNull(RadarrMovie(title = "Movie", inCinemas = "2026-11-08T00:00:00Z").toUpcoming(today))
    }

    @Test
    fun `Invalid date is ignored`() {
        Assert.assertNull(RadarrMovie(title = "Movie", inCinemas = "unknown").toUpcoming(today))
    }

    private fun episode(
        number: Int,
        air: String,
    ) = SonarrEpisode(2, number, "Episode $number", airDateUtc = air, series = series)

    @Test
    fun `Single episode has its title`() {
        val release = listOf(episode(3, "2026-11-10T20:00:00Z")).toUpcoming(now, ZoneOffset.UTC).single()
        Assert.assertEquals("S02E03", release.episodes)
        Assert.assertEquals("Episode 3", release.episodeTitle)
    }

    @Test
    fun `Episodes of the same day are grouped`() {
        val releases =
            listOf(
                episode(2, "2026-11-10T08:00:00Z"),
                episode(1, "2026-11-10T08:00:00Z"),
                episode(8, "2026-11-17T08:00:00Z"),
            ).toUpcoming(now, ZoneOffset.UTC)
        Assert.assertEquals(listOf("S02E01-E02", "S02E08"), releases.map { it.episodes })
        Assert.assertNull(releases.first().episodeTitle)
    }

    @Test
    fun `Episode already aired today is removed`() {
        Assert.assertTrue(listOf(episode(1, "2026-11-09T08:00:00Z")).toUpcoming(now, ZoneOffset.UTC).isEmpty())
    }

    @Test
    fun `Merge removes duplicates, sorts and limits`() {
        fun release(
            key: String,
            day: Int,
        ) = UpcomingRelease(key, ArrType.RADARR, key, today.plusDays(day.toLong()))

        val merged = mergeUpcoming(listOf(release("c", 3), release("a", 1), release("a", 1), release("b", 2)), 2)
        Assert.assertEquals(listOf("a", "b"), merged.map { it.key })
    }

    @Test
    fun `Only TMDB images are resized`() {
        Assert.assertEquals(
            "https://image.tmdb.org/t/p/w342/a.jpg",
            resizeTmdbImage("https://image.tmdb.org/t/p/original/a.jpg", "w342"),
        )
        Assert.assertEquals(
            "https://artworks.thetvdb.com/a.jpg",
            resizeTmdbImage("https://artworks.thetvdb.com/a.jpg", "w342"),
        )
    }
}
