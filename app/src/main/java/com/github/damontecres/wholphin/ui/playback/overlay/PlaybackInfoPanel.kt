package com.github.damontecres.wholphin.ui.playback.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.services.AudioOutputState
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.formatTime
import com.github.damontecres.wholphin.ui.playback.CurrentPlayback
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import java.time.LocalDateTime
import java.util.Locale
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
    val audioChips = currentAudioChips(currentPlayback)
    val output = currentAudioOutput(currentPlayback, audioChips)

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

/**
 * Name and channel layout of the audio track being played, from the media source
 */
@Composable
internal fun currentAudioChips(currentPlayback: CurrentPlayback?): List<String> {
    val streams = currentPlayback?.mediaSourceInfo?.mediaStreams.orEmpty()
    val audioIndex = currentPlayback?.audioIndex
    return remember(streams, audioIndex) {
        val audio = streams.filter { it.type == MediaStreamType.AUDIO }
        val selected =
            audio.firstOrNull { audioIndex != null && audioIndex >= 0 && it.index == audioIndex }
                ?: audio.firstOrNull { it.index == currentPlayback?.mediaSourceInfo?.defaultAudioStreamIndex }
                ?: audio.firstOrNull()
        audioChips(selected)
    }
}

/**
 * What the audio sink really does with that track (passthrough or PCM), null when unknown
 */
internal fun currentAudioOutput(
    currentPlayback: CurrentPlayback?,
    audioChips: List<String>,
): String? =
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

/**
 * Small message on the left of the screen naming the audio format when a playback starts
 */
@Composable
fun AudioFormatToast(
    currentPlayback: CurrentPlayback?,
    modifier: Modifier = Modifier,
) {
    // Shown again when the audio track changes
    val key = currentPlayback?.let { it.itemId to it.audioIndex }
    var visible by remember(key) { mutableStateOf(key != null) }
    LaunchedEffect(key) {
        if (key != null) {
            visible = true
            delay(12.seconds)
            visible = false
        }
    }
    val chips = currentAudioChips(currentPlayback)
    val output = currentAudioOutput(currentPlayback, chips)
    AnimatedVisibility(
        visible = visible && chips.isNotEmpty(),
        enter = fadeIn() + slideInHorizontally { -it / 4 },
        exit = fadeOut() + slideOutHorizontally { -it / 4 },
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(18.dp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .height(IntrinsicSize.Min)
                    .background(Color.Black.copy(alpha = 0.72f), shape)
                    .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
            AudioFormatBadge(chips.firstOrNull())
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = chips.joinToString(" "),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                output?.let {
                    Text(text = it, fontSize = 15.sp, color = Color.White.copy(alpha = 0.75f))
                }
            }
        }
    }
}

/**
 * Small mark of the audio format: a Dolby or DTS badge, or a speaker for the other formats
 */
@Composable
private fun AudioFormatBadge(name: String?) {
    val badge =
        when {
            name == null -> null
            "Atmos" in name -> Triple("ATMOS", Color.White, Color.Black)
            name.startsWith("Dolby") -> Triple("DOLBY", Color.White, Color.Black)
            name.startsWith("DTS") -> Triple("DTS", Color(0xFFE8710A), Color.White)
            else -> null
        }
    if (badge != null) {
        val (label, background, content) = badge
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            color = content,
            maxLines = 1,
            modifier =
                Modifier
                    .background(background, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    } else {
        Text(
            text = stringResource(R.string.fa_volume_high),
            fontFamily = FontAwesome,
            fontSize = 22.sp,
            color = Color.White,
        )
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
    return listOfNotNull(name, channels, shortLanguage(stream.language))
}

/**
 * Two letters code of an ISO 639-2 language ("fre" or "fra" gives "FR"), the code itself when unknown
 */
private fun shortLanguage(code: String?): String? {
    if (code.isNullOrBlank()) return null
    val lower = code.lowercase()
    if (lower == "und" || lower == "zxx") return null
    val bibliographic =
        mapOf("fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el", "per" to "fa")
    bibliographic[lower]?.let { return it.uppercase() }
    if (lower.length == 2) return lower.uppercase()
    val iso = Locale.getISOLanguages().firstOrNull { runCatching { Locale(it).isO3Language }.getOrNull() == lower }
    return (iso ?: lower).uppercase()
}
