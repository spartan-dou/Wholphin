package com.github.damontecres.wholphin.ui

import androidx.media3.common.PlaybackException
import com.github.damontecres.wholphin.ui.playback.PlaybackErrorKind
import com.github.damontecres.wholphin.ui.playback.PlaybackErrorPolicy
import org.junit.Assert
import org.junit.Test

class TestPlaybackErrorPolicy {
    @Test
    fun droppedConnectionIsRetried() {
        listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        ).forEach {
            Assert.assertEquals(PlaybackErrorKind.TRANSIENT_NETWORK, PlaybackErrorPolicy.classify(it))
        }
    }

    @Test
    fun decoderFailuresTryAnotherPlayer() {
        listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        ).forEach {
            Assert.assertEquals(PlaybackErrorKind.UNSUPPORTED_FORMAT, PlaybackErrorPolicy.classify(it))
        }
    }

    @Test
    fun badHttpStatusIsNotRetried() {
        Assert.assertEquals(
            PlaybackErrorKind.OTHER,
            PlaybackErrorPolicy.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
    }

    @Test
    fun retryDelayGrows() {
        Assert.assertEquals(1000L, PlaybackErrorPolicy.retryDelayMs(1))
        Assert.assertEquals(3000L, PlaybackErrorPolicy.retryDelayMs(3))
    }
}
