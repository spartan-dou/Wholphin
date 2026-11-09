package com.github.damontecres.wholphin.ui.playback.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.services.AudioOutputState
import com.github.damontecres.wholphin.ui.formatTime
import com.github.damontecres.wholphin.ui.playback.CurrentPlayback
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.seconds

/**
 * Top right panel shown with the controls: when the playback ends and the technical format of the video and audio
 */
@Composable
fun PlaybackInfoPanel(
    player: Player,
    currentPlayback: CurrentPlayback?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val endsAt by produceState<LocalDateTime?>(null, player) {
        while (isActive) {
            val duration = player.duration
            value =
                if (duration > 0) {
                    val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
                    val remainingMs = ((duration - player.currentPosition) / speed).toLong()
                    LocalDateTime.now().plusSeconds(remainingMs / 1000)
                } else {
                    null
                }
            delay(5.seconds)
        }
    }
    val streams = currentPlayback?.mediaSourceInfo?.mediaStreams.orEmpty()
    val videoChips = remember(streams) { videoChips(streams.firstOrNull { it.type == MediaStreamType.VIDEO }) }
    val audioIndex = currentPlayback?.audioIndex
    val audioChips =
        remember(streams, audioIndex) {
            val audio = streams.filter { it.type == MediaStreamType.AUDIO }
            val selected =
                audio.firstOrNull { audioIndex != null && audioIndex >= 0 && it.index == audioIndex }
                    ?: audio.firstOrNull { it.index == currentPlayback?.mediaSourceInfo?.defaultAudioStreamIndex }
                    ?: audio.firstOrNull()
            audioChips(selected)
        }
    val output =
        if (currentPlayback?.backend == PlayerBackend.EXO_PLAYER) {
            when (AudioOutputState.passthrough) {
                true -> {
                    val sent = AudioOutputState.passthroughName(AudioOutputState.encoding)
                    if (sent != null) "Passthrough: $sent" else "Passthrough"
                }

                // Decoding to PCM drops the Atmos objects: say so rather than let the user wonder
                false -> {
                    if (audioChips.any { "Atmos" in it }) "PCM (no Atmos)" else "PCM"
                }

                null -> {
                    null
                }
            }
        } else {
            null
        }

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        endsAt?.let {
            Text(
                text = stringResource(R.string.ends_at, formatTime(context, it)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        val video = videoChips
        if (video.isNotEmpty()) {
            InfoChips(video)
        }
        val audio = audioChips + listOfNotNull(output)
        if (audio.isNotEmpty()) {
            InfoChips(audio)
        }
    }
}

@Composable
private fun InfoChips(
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        labels.forEach {
            Text(
                text = it,
                fontSize = 12.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            RoundedCornerShape(6.dp),
                        ).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

private fun videoChips(stream: MediaStream?): List<String> {
    stream ?: return emptyList()
    val width = stream.width ?: 0
    val height = stream.height ?: 0
    val resolution =
        when {
            width >= 3200 || height >= 2000 -> "4K"
            width >= 1800 || height >= 1000 -> "1080p"
            width >= 1200 || height >= 700 -> "720p"
            width > 0 -> "SD"
            else -> null
        }
    val range =
        stream.videoRangeType?.name?.let {
            when {
                it.startsWith("DOVI") -> "Dolby Vision"
                it.contains("PLUS") -> "HDR10+"
                it == "HDR10" -> "HDR10"
                it.startsWith("HLG") -> "HLG"
                else -> null
            }
        }
    val codec =
        when (val c = stream.codec?.lowercase()) {
            null -> null
            "hevc" -> "HEVC"
            "h264" -> "H.264"
            else -> c.uppercase()
        }
    return listOfNotNull(resolution, range, codec)
}

private fun audioChips(stream: MediaStream?): List<String> {
    stream ?: return emptyList()
    val codec = stream.codec?.lowercase()
    val text = listOfNotNull(stream.displayTitle, stream.title, stream.profile).joinToString(" ").lowercase()
    val name =
        when {
            "atmos" in text && codec == "truehd" -> {
                "Dolby TrueHD Atmos"
            }

            "atmos" in text -> {
                "Dolby Atmos"
            }

            codec == "eac3" -> {
                "Dolby Digital+"
            }

            codec == "ac3" -> {
                "Dolby Digital"
            }

            codec == "truehd" -> {
                "Dolby TrueHD"
            }

            codec == "dts" -> {
                when {
                    "dts:x" in text || "dts-x" in text -> "DTS:X"
                    "dts-hd ma" in text || "master" in text -> "DTS-HD MA"
                    "dts-hd" in text -> "DTS-HD HRA"
                    else -> "DTS"
                }
            }

            else -> {
                codec?.uppercase()
            }
        }
    val channels =
        when (val n = stream.channels) {
            null -> null
            1 -> "1.0"
            2 -> "2.0"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${n}ch"
        }
    return listOfNotNull(name, channels)
}
