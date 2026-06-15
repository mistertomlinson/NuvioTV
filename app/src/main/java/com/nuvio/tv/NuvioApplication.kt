package com.nuvio.tv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.nuvio.tv.core.homechannel.HomeScreenChannelWorker
import com.nuvio.tv.core.sync.StartupSyncService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

@HiltAndroidApp
class NuvioApplication : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject lateinit var startupSyncService: StartupSyncService
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        HomeScreenChannelWorker.schedule(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(coil.decode.SvgDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.45)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .decoderDispatcher(Dispatchers.IO.limitedParallelism(4))
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(6))
            .bitmapFactoryMaxParallelism(4)
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }
}
