package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.data.model.ArrType
import com.github.damontecres.wholphin.services.arrUrlCandidates
import org.junit.Assert
import org.junit.Test

class TestArrImport {
    private val seerrUrl = "http://192.168.1.10:5055"

    @Test
    fun `Tries the Seerr host when the hostname is a Docker name`() {
        val urls = arrUrlCandidates(ArrType.RADARR, "radarr", 7878, false, null, null, seerrUrl)
        Assert.assertEquals(listOf("http://radarr:7878", "http://192.168.1.10:7878"), urls)
    }

    @Test
    fun `Keeps ssl and base url`() {
        val urls = arrUrlCandidates(ArrType.SONARR, "tv.local", 443, true, "/sonarr/", null, seerrUrl)
        Assert.assertEquals("https://tv.local/sonarr", urls.first())
    }

    @Test
    fun `Uses the default port and the external url`() {
        val urls = arrUrlCandidates(ArrType.SONARR, null, null, null, null, "https://sonarr.example.com/", seerrUrl)
        Assert.assertEquals(listOf("https://sonarr.example.com", "http://192.168.1.10:8989"), urls)
    }

    @Test
    fun `Tries the external url before the internal hostname`() {
        val urls = arrUrlCandidates(ArrType.RADARR, "radarr", 7878, false, null, "http://192.168.1.20:7878", seerrUrl)
        Assert.assertEquals(listOf("http://192.168.1.20:7878", "http://radarr:7878", "http://192.168.1.10:7878"), urls)
    }

    @Test
    fun `External url without a scheme is usable`() {
        val urls = arrUrlCandidates(ArrType.RADARR, null, 7878, false, null, "radarr.example.com", seerrUrl)
        Assert.assertEquals("http://radarr.example.com", urls.first())
    }

    @Test
    fun `Blank external url is ignored`() {
        val urls = arrUrlCandidates(ArrType.RADARR, "radarr", 7878, false, null, " ", seerrUrl)
        Assert.assertEquals("http://radarr:7878", urls.first())
    }

    @Test
    fun `Does not repeat the same address`() {
        val urls = arrUrlCandidates(ArrType.RADARR, "192.168.1.10", 7878, false, "", "http://192.168.1.10:7878", seerrUrl)
        Assert.assertEquals(listOf("http://192.168.1.10:7878"), urls)
    }

    @Test
    fun `Skips an invalid hostname`() {
        val urls = arrUrlCandidates(ArrType.RADARR, "not a host", 7878, false, null, null, seerrUrl)
        Assert.assertEquals(listOf("http://192.168.1.10:7878"), urls)
    }
}
