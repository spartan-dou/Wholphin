package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.playback.overlay.audioFormatLogo
import org.junit.Assert
import org.junit.Test

class TestAudioFormatLogo {
    @Test
    fun `Each format has the right logo`() {
        val expected =
            mapOf(
                "Dolby TrueHD Atmos" to R.drawable.audio_logo_dolby_atmos,
                "Dolby Atmos" to R.drawable.audio_logo_dolby_atmos,
                "Dolby Digital+" to R.drawable.audio_logo_dolby_digital_plus,
                "Dolby Digital" to R.drawable.audio_logo_dolby_digital,
                "Dolby TrueHD" to R.drawable.audio_logo_dolby_truehd,
                "DTS-HD MA" to R.drawable.audio_logo_dts_hd_ma,
                "DTS-HD HRA" to R.drawable.audio_logo_dts,
                "DTS:X" to R.drawable.audio_logo_dts,
                "DTS" to R.drawable.audio_logo_dts,
            )
        expected.forEach { (name, logo) -> Assert.assertEquals(name, logo, audioFormatLogo(name)) }
    }

    @Test
    fun `Other formats have no logo`() {
        Assert.assertNull(audioFormatLogo("AAC"))
        Assert.assertNull(audioFormatLogo(null))
    }
}
