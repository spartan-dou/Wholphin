package com.github.damontecres.wholphin.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.surfaceColorAtElevation
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.AppColors
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import com.github.damontecres.wholphin.ui.dot
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.formatTime
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.Clock
import com.github.damontecres.wholphin.ui.util.LocalClock
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.ImageType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Layout for showing the next up episode during playback
 */
@Composable
fun NextUpEpisode(
    title: String?,
    description: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    timeLeft: Duration?,
    modifier: Modifier = Modifier,
    aspectRatio: Float = AspectRatios.WIDE,
    runtime: Duration? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
    Box(
        modifier = modifier,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.next_up) + "...",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(8.dp),
            ) {
                NextUpCard(
                    imageUrl = imageUrl,
                    onClick = onClick,
                    timeLeft = timeLeft,
                    interactionSource = interactionSource,
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(aspectRatio.coerceAtLeast(AspectRatios.MIN))
//                            .fillMaxSize()
//                            .weight(1f)
                            .focusRequester(focusRequester),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .weight(1f),
                ) {
                    Text(
                        text = title ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier,
                    )
                    Text(
                        text = description ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier,
                    )
                    runtime?.let {
                        val context = LocalContext.current
                        val resources = LocalResources.current
                        val now by LocalClock.current.now
                        val text =
                            remember(now, runtime) {
                                buildAnnotatedString {
                                    append(resources.formatDuration(runtime))
                                    dot()
                                    val endsAt = now.plus(runtime.toJavaDuration())
                                    val endTimeStr = formatTime(context, endsAt)
                                    append(resources.getString(R.string.ends_at, endTimeStr))
                                }
                            }
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .padding(top = 4.dp)
                                    .align(Alignment.Start),
                        )
                    }
                }
            }
        }
    }
}

/**
 * An image of [episode]'s series, never one of the episode itself, so the next up does not spoil it.
 * A wide image (thumb, then backdrop) is preferred, then the series' poster.
 *
 * @param series the episode's series once it is loaded, for when the episode has no inherited series images
 * @return the image URL and its aspect ratio, or null if the series has no image
 */
fun seriesImageForEpisode(
    imageUrlService: ImageUrlService,
    episode: BaseItem,
    series: BaseItem?,
): Pair<String, Float>? {
    fun image(
        itemId: UUID,
        imageType: ImageType,
        tag: String?,
        aspectRatio: Float,
    ): Pair<String, Float>? =
        imageUrlService
            .getItemImageUrl(itemId = itemId, imageType = imageType, tag = tag)
            ?.let { url -> url to aspectRatio }

    val data = episode.data
    data.parentThumbItemId?.let { id ->
        image(id, ImageType.THUMB, data.parentThumbImageTag, AspectRatios.WIDE)?.let { return it }
    }
    data.parentBackdropItemId?.let { id ->
        val tag = data.parentBackdropImageTags?.firstOrNull()
        image(id, ImageType.BACKDROP, tag, AspectRatios.WIDE)?.let { return it }
    }
    if (series != null) {
        val tags = series.data.imageTags.orEmpty()
        tags[ImageType.THUMB]?.let { tag ->
            image(series.id, ImageType.THUMB, tag, AspectRatios.WIDE)?.let { return it }
        }
        series.data.backdropImageTags?.firstOrNull()?.let { tag ->
            image(series.id, ImageType.BACKDROP, tag, AspectRatios.WIDE)?.let { return it }
        }
        tags[ImageType.PRIMARY]?.let { tag ->
            image(series.id, ImageType.PRIMARY, tag, series.aspectRatio ?: AspectRatios.TALL)?.let { return it }
        }
    }
    val seriesId = data.seriesId
    val seriesPrimaryTag = data.seriesPrimaryImageTag
    if (seriesId != null && seriesPrimaryTag != null) {
        return image(seriesId, ImageType.PRIMARY, seriesPrimaryTag, AspectRatios.TALL)
    }
    return null
}

@Composable
fun NextUpCard(
    imageUrl: String?,
    onClick: () -> Unit,
    timeLeft: Duration?,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        onClick = onClick,
        interactionSource = interactionSource,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .background(
                            AppColors.TransparentBlack50,
                            shape = CircleShape,
                        ),
            ) {
                if (timeLeft != null && timeLeft > Duration.ZERO) {
                    Text(
                        text = timeLeft.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier =
                            Modifier
                                .size(60.dp)
                                .align(Alignment.Center),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalCoilApi::class)
@PreviewTvSpec
@Composable
private fun NextUpEpisodePreview() {
    WholphinTheme(true) {
        val theme = MaterialTheme.colorScheme
        val previewHandler =
            AsyncImagePreviewHandler {
                ColorImage(theme.surfaceVariant.toArgb())
            }
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
        ) {
            CompositionLocalProvider(
                LocalAsyncImagePreviewHandler provides previewHandler,
                LocalClock provides Clock(),
            ) {
                NextUpEpisode(
                    title = "Episode Title",
                    description =
                        "This is the description of the episode. It might be long. " +
                            "It might be very, very long and overflow the available space. " +
                            "But it just keeps going and going.",
                    imageUrl = "",
                    onClick = {},
                    aspectRatio = AspectRatios.WIDE,
                    timeLeft = 30.seconds,
                    runtime = 32.minutes + 20.seconds,
                    modifier =
                        Modifier
                            .padding(8.dp)
                            .fillMaxHeight(.4f)
                            .fillMaxWidth(.66f)
                            .align(Alignment.BottomCenter)
                            .background(
                                MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                                shape = RoundedCornerShape(8.dp),
                            ),
                )
            }
        }
    }
}
