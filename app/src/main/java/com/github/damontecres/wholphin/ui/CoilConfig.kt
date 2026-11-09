package com.github.damontecres.wholphin.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.CacheStrategy
import coil3.network.ConnectivityChecker
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import okhttp3.OkHttpClient
import timber.log.Timber
import kotlin.time.ExperimentalTime

private const val MAX_IMAGE_MEMORY_BYTES = 48L * 1024 * 1024

@Composable
fun CoilConfig(
    prefs: AppPreferences,
    okHttpClient: OkHttpClient,
    debugLogging: Boolean,
    enableCache: Boolean = true,
) = CoilConfig(
    diskCacheSizeBytes =
        prefs.advancedPreferences.imageDiskCacheSizeBytes.let {
            if (it < AppPreference.ImageDiskCacheSize.min * AppPreference.MEGA_BIT) {
                AppPreference.ImageDiskCacheSize.defaultValue * AppPreference.MEGA_BIT
            } else {
                it
            }
        },
    okHttpClient = okHttpClient,
    debugLogging = debugLogging,
    enableCache = enableCache,
)

/**
 * Configure Coil image loading
 */
@OptIn(ExperimentalTime::class, ExperimentalCoilApi::class)
@Composable
fun CoilConfig(
    diskCacheSizeBytes: Long,
    okHttpClient: OkHttpClient,
    debugLogging: Boolean,
    enableCache: Boolean = true,
) {
    val client =
        remember(okHttpClient, debugLogging) {
            if (debugLogging) {
                okHttpClient
                    .newBuilder()
                    .addInterceptor {
                        val start = System.currentTimeMillis()
                        val req = it.request()
                        val res = it.proceed(req)
                        val time = System.currentTimeMillis() - start
                        Timber.v("${time}ms - ${req.url}")
                        res
                    }.build()
            } else {
                okHttpClient
            }
        }
    setSingletonImageLoaderFactory { ctx ->
        Timber.i("Image diskCacheSizeBytes=$diskCacheSizeBytes")
        ImageLoader
            .Builder(ctx)
            .apply {
                if (enableCache) {
                    // Capped: a fast scroll through 200+ posters filled the default cache (a quarter of the heap limit) and
                    // Android killed the app on the 2GB Shield
                    memoryCache(MemoryCache.Builder().maxSizeBytes(MAX_IMAGE_MEMORY_BYTES).build())
                    diskCache(
                        DiskCache
                            .Builder()
                            .directory(ctx.cacheDir.resolve("coil3_image_cache"))
                            .maxSizeBytes(diskCacheSizeBytes)
                            .build(),
                    )
                } else {
                    memoryCache(null)
                    diskCache(null)
                }
            }.crossfade(false)
            .logger(if (debugLogging) DebugLogger() else null)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        cacheStrategy = { WholphinCacheStrategy(CacheControlCacheStrategy()) },
                        callFactory = { client },
                        // Disable connectivity checker which may be unreliable for VPNs and/or some versions of Android
                        connectivityChecker = { ConnectivityChecker { true } },
                    ),
                )
            }.build()
    }
}

/**
 * For a prefetch where only the file is wanted: it goes in the disk cache and almost nothing is decoded
 */
fun ImageRequest.Builder.diskCacheOnly(): ImageRequest.Builder = size(1, 1).memoryCachePolicy(CachePolicy.DISABLED)

/**
 * Decode no larger than the screen. A request without a target otherwise decodes at the original size
 */
fun ImageRequest.Builder.screenSized(context: Context): ImageRequest.Builder =
    context.resources.displayMetrics.let { size(it.widthPixels, it.heightPixels) }

/**
 * This [CacheStrategy] always prefers the cached response for Trickplay images,
 * otherwise the decision is delegated to the provided [CacheStrategy]
 *
 * The expectation is that Trickplay images will be prefetched so the cache will always be warm
 */
@OptIn(ExperimentalCoilApi::class)
private class WholphinCacheStrategy(
    private val delegate: CacheStrategy,
) : CacheStrategy {
    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): CacheStrategy.ReadResult =
        if (networkRequest.url.contains("/Trickplay/")) {
            CacheStrategy.ReadResult(cacheResponse)
        } else {
            delegate.read(cacheResponse, networkRequest, options)
        }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): CacheStrategy.WriteResult = delegate.write(cacheResponse, networkRequest, networkResponse, options)
}
