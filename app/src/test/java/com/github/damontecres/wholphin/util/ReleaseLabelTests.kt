package com.github.damontecres.wholphin.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseLabelTests {
    @Test
    fun `remux wins over bluray`() {
        assertEquals("Remux 2160p", releaseLabel("/movies/John.Wick.2014.2160p.BluRay.REMUX.HEVC.DV.mkv"))
    }

    @Test
    fun `web variants`() {
        assertEquals("WEB-DL 1080p", releaseLabel("Film.2020.1080p.WEB-DL.DD5.1.H264.mkv"))
        assertEquals("WEB-DL 2160p", releaseLabel("Film 2020 2160p WEBDL.mkv"))
        assertEquals("WEBRip 720p", releaseLabel("Film.2020.720p.WEBRip.x264.mkv"))
        assertEquals("WEB 1080p", releaseLabel("Film.2020.1080p.WEB.h264.mkv"))
    }

    @Test
    fun `other sources`() {
        assertEquals("BluRay 1080p", releaseLabel("Film.2020.1080p.Blu-ray.x265.mkv"))
        assertEquals("BDRip 720p", releaseLabel("Film.2020.720p.BDRip.mkv"))
        assertEquals("HDLight 1080p", releaseLabel("Film.2020.MULTi.1080p.HDLight.x265.mkv"))
        assertEquals("Remux 2160p", releaseLabel("Film.2020.4K.REMUX.mkv"))
    }

    @Test
    fun `windows path uses the file name only`() {
        assertEquals("WEB-DL 1080p", releaseLabel("D:\\Remux\\Film.2020.1080p.WEB-DL.mkv"))
    }

    @Test
    fun `no release type`() {
        assertNull(releaseLabel(null))
        assertNull(releaseLabel("/movies/Film (2020).mkv"))
        assertNull(releaseLabel("/movies/Charlotte's Web (2006).mkv"))
        assertNull(releaseLabel("/movies/Film.2020.1080p.mkv"))
    }
}
