// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.data.media

enum class MediaKind { VIDEO, AUDIO }

/**
 * Every free video / audio extension DHVON indexes. Used by [MediaRepository] (MediaStore "Files" query + SAF scanner)
 * and by the player (to decide when a container is worth handing to the LibVLC fallback).
 */
object SupportedMediaFormats {
    val VIDEO_EXTENSIONS: Set<String> = setOf(
        "mkv", "mp4", "m4v", "3gp", "ts", "m2ts", "mts", "avi", "mov",
        "webm", "flv", "f4v", "wmv", "asf", "wtv", "vob", "mpg", "mpeg",
        "m2v", "mjpeg", "ogv", "mxf", "rm", "hevc",
    )

    val AUDIO_EXTENSIONS: Set<String> = setOf(
        "mp3", "mp2", "aac", "m4a", "m4r", "flac", "wav", "w64", "aiff",
        "caf", "ogg", "oga", "opus", "spx", "wv", "tta", "wma", "ac3",
        "dts", "ra", "au", "snd", "voc", "8svx", "amb", "ircam", "nist",
        "sph", "paf", "fap", "pvf", "avr", "sd2", "maud", "txw", "smp",
        "hcom", "htk", "fssd", "gsrt", "prc", "vox", "sln", "cvs", "cvsd",
        "cvu", "dvms", "sndr", "sndt", "cdda",
    )

    /** Project / image / animation files that must never show up in the library. */
    val EXCLUDED_EXTENSIONS: Set<String> = setOf("wve", "ima", "sou", "vms", "swf")

    /**
     * Containers where ExoPlayer's extractors or the device's MediaCodec regularly produce audio-only / black-screen playback.
     * A video item in one of these that exposes no video track is handed to the LibVLC fallback.
     */
    val FALLBACK_PRONE_VIDEO_EXTENSIONS: Set<String> = setOf(
        "ts", "m2ts", "mts", "avi", "vob", "mpg", "mpeg", "m2v", "mjpeg",
        "flv", "f4v", "wmv", "asf", "wtv", "rm", "mxf",
    )

    /** Extensions queried from MediaStore.Files (sorted → stable SQL). */
    val QUERYABLE_EXTENSIONS: List<String> = (VIDEO_EXTENSIONS + AUDIO_EXTENSIONS - EXCLUDED_EXTENSIONS).sorted()

    fun extensionOf(fileName: String?): String = fileName.orEmpty().substringAfterLast('.', "").lowercase()

    fun isExcluded(extension: String): Boolean = extension.lowercase().removePrefix(".") in EXCLUDED_EXTENSIONS

    fun isSupportedVideo(extension: String): Boolean {
        val ext = extension.lowercase().removePrefix(".")
        return ext !in EXCLUDED_EXTENSIONS && ext in VIDEO_EXTENSIONS
    }

    fun isSupportedAudio(extension: String): Boolean {
        val ext = extension.lowercase().removePrefix(".")
        return ext !in EXCLUDED_EXTENSIONS && ext in AUDIO_EXTENSIONS
    }

    /**
     * Decides what a file is. A clear audio/video MIME type wins (that is how the old SAF scanner worked); otherwise the
     * extension decides, which is what rescues `.ts`, `.vob`, `.ac3`, `.dts`… that providers report as octet-stream.
     * Returns null for excluded or non-media files.
     */
    fun classify(fileName: String, mimeType: String? = null): MediaKind? {
        val ext = extensionOf(fileName)
        if (ext in EXCLUDED_EXTENSIONS) return null
        val mime = mimeType.orEmpty().lowercase()
        return when {
            mime.startsWith("video/") -> MediaKind.VIDEO
            mime.startsWith("audio/") -> MediaKind.AUDIO
            ext in VIDEO_EXTENSIONS -> MediaKind.VIDEO
            ext in AUDIO_EXTENSIONS -> MediaKind.AUDIO
            else -> null
        }
    }
}
