// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp

/**
 * DHVON — Every Sound. Every Frame.
 * An offline-first, ad-free video + audio player by rhshourav — https://github.com/rhshourav/DHVON
 */
@HiltAndroidApp
class DhvonApp : Application(), SingletonImageLoader.Factory {
    /** Coil 3 loader with the video-frame decoder, used for local video thumbnails. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
