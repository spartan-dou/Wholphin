package com.github.damontecres.wholphin.ui.playback

import androidx.media3.common.PlaybackException

/**
 * What a [PlaybackException] says about the way to recover from it
 */
enum class PlaybackErrorKind {
    /** The connection dropped for a moment: playing again from the same position is enough */
    TRANSIENT_NETWORK,

    /** The device cannot decode or output this stream: another player or a transcode may */
    UNSUPPORTED_FORMAT,

    OTHER,
}

object PlaybackErrorPolicy {
    /** How many times in a row a transient network error is retried before giving up */
    const val MAX_NETWORK_RETRIES = 3

    fun classify(errorCode: Int): PlaybackErrorKind =
        when (errorCode) {
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
            -> PlaybackErrorKind.TRANSIENT_NETWORK

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            -> PlaybackErrorKind.UNSUPPORTED_FORMAT

            else -> PlaybackErrorKind.OTHER
        }

    /** Wait before the nth retry, 1s then 2s then 3s */
    fun retryDelayMs(attempt: Int): Long = attempt * 1000L
}
