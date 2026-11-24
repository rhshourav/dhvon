// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.data.media

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Finds the English soundtrack among several audio tracks, the way a person would read the track list:
 *  - the language tag: `en`, `eng`, `en-US`, `en_GB`, `english`;
 *  - the track name: "English", "ENG", "Eng 5.1", "[EN] Dolby", "DubEnglish", plus other languages' word for it
 *    ("Inglés", "Anglais", "Englisch", "英语", …);
 *  - typos: "Englsih", "Enlgish", "Englis" (one edit, or one swapped pair of letters, away);
 *  - never a commentary / audio-description track, and never a lookalike such as "Bengali".
 * Pure Kotlin on purpose: it is shared by the ExoPlayer path and the LibVLC fallback.
 */
object AudioTrackPicker {

    data class Candidate(
        /** Whatever lets the caller find the track again (an index, a VLC track id…). */
        val key: Int,
        val language: String? = null,
        val label: String? = null,
        val channelCount: Int = 0,
        val isDefault: Boolean = false,
        /** The container itself marks it as commentary / audio description (ExoPlayer role flags). */
        val flaggedAsCommentary: Boolean = false,
    )

    /** Score at or above which a track counts as English. */
    const val MIN_SCORE = 60

    private val SHORT_CODES = setOf("en", "eng", "engl")
    private val ENGLISH_WORDS = setOf(
        "english", "ingles", "anglais", "englisch", "inglese", "engels", "engelska", "engelsk",
        "angielski", "inggris", "ingilizce", "английский", "英语", "英語", "영어",
    )
    private val COMMENTARY_WORDS = setOf(
        "commentary", "commentaries", "comment", "director", "directors", "descriptive", "description",
        "described", "narration", "narrator", "karaoke", "isolated",
    )
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /** Lower-case, accents removed ("Inglés" → "ingles"). */
    private fun fold(text: String?): String =
        Normalizer.normalize(text.orEmpty().lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun tokens(text: String?): List<String> = fold(text).split(SPLIT).filter { it.isNotEmpty() }

    private fun languageScore(language: String?): Int {
        val tag = language.orEmpty().trim().lowercase(Locale.ROOT)
        if (tag.isEmpty() || tag == "und") return 0
        val primary = tag.split('-', '_').first()
        return if (primary == "english" || primary in SHORT_CODES) 100 else 0
    }

    private fun labelScore(label: String?): Int {
        val text = fold(label)
        if (text.isEmpty()) return 0
        val words = text.split(SPLIT).filter { it.isNotEmpty() }
        if (words.any { it in ENGLISH_WORDS }) return 90
        if (words.any { it in SHORT_CODES }) return 80                       // "ENG", "EN", "[eng] 5.1"
        // "DubEnglish", "English5.1": long names (or non-Latin ones) may sit inside a longer word
        if (ENGLISH_WORDS.any { w -> (w.length >= 5 || w.any { it.code > 127 }) && text.contains(w) }) return 70
        // typos — only for words close to "english" in length, so "Bengali" / "Finnish" / "Spanish" never match
        if (words.any { it.length in 6..8 && withinOneEdit(it, "english") }) return 60
        return 0
    }

    /** One substitution, insertion, deletion or swap of two neighbouring letters. */
    internal fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        if (abs(a.length - b.length) > 1) return false
        if (a.length == b.length) {
            val diff = a.indices.filter { a[it] != b[it] }
            return diff.size == 1 ||
                (diff.size == 2 && diff[1] == diff[0] + 1 && a[diff[0]] == b[diff[1]] && a[diff[1]] == b[diff[0]])
        }
        val (shorter, longer) = if (a.length < b.length) a to b else b to a
        var i = 0
        while (i < shorter.length && shorter[i] == longer[i]) i++
        return shorter.substring(i) == longer.substring(i + 1)
    }

    fun englishScore(c: Candidate): Int = maxOf(languageScore(c.language), labelScore(c.label))

    fun looksLikeCommentary(c: Candidate): Boolean =
        c.flaggedAsCommentary || tokens(c.label).any { it in COMMENTARY_WORDS }

    /** A real English soundtrack: English by tag or name, and not commentary / description. */
    fun isEnglish(c: Candidate): Boolean = !looksLikeCommentary(c) && englishScore(c) >= MIN_SCORE

    /**
     * The best English track, or null when none of them looks English (the caller then leaves the player's own choice alone).
     * Ties go to the container's default track, then more channels, then the earlier track.
     */
    fun pickEnglish(candidates: List<Candidate>): Candidate? =
        candidates.filter { isEnglish(it) }.maxWithOrNull(
            compareBy<Candidate>(
                { englishScore(it) },
                { if (it.isDefault) 1 else 0 },
                { it.channelCount.coerceIn(0, 8) },
                { -it.key },
            )
        )
}
