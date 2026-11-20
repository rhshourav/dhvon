// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.data.media

import java.nio.charset.Charset

data class SubCue(val startMs: Long, val endMs: Long, val text: String)

/** Parses SRT / WebVTT / ASS / SSA into a flat, start-sorted cue list. Offsets are applied at lookup time. */
object SubtitleParser {
    private val timing = Regex("""((?:\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val htmlTag = Regex("<[^>]+>")
    private val assTag = Regex("""\{[^}]*\}""")

    fun parse(fileName: String, bytes: ByteArray): List<SubCue> {
        val text = decode(bytes)
        val n = fileName.lowercase()
        val cues = if (n.endsWith(".ass") || n.endsWith(".ssa")) parseAss(text) else parseSrtOrVtt(text)
        return cues.sortedBy { it.startMs }
    }

    private fun decode(bytes: ByteArray): String {
        val s = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        return if ('\uFFFD' in s) String(bytes, Charset.forName("windows-1252")) else s
    }

    private fun parseSrtOrVtt(text: String): List<SubCue> {
        val out = ArrayList<SubCue>()
        text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n{2,}")).forEach { block ->
            val lines = block.lines()
            val ti = lines.indexOfFirst { timing.containsMatchIn(it) }
            if (ti < 0) return@forEach
            val m = timing.find(lines[ti])!!
            val body = lines.drop(ti + 1).joinToString("\n") { it.trim() }
                .replace(htmlTag, "").replace(assTag, "").trim()
            if (body.isNotEmpty()) out += SubCue(ts(m.groupValues[1]), ts(m.groupValues[2]), body)
        }
        return out
    }

    private fun parseAss(text: String): List<SubCue> {
        val out = ArrayList<SubCue>()
        var inEvents = false
        var startIdx = 1; var endIdx = 2; var textIdx = 9
        text.lines().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("[")) inEvents = line.equals("[Events]", ignoreCase = true)
            else if (inEvents && line.startsWith("Format:", true)) {
                val cols = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                startIdx = cols.indexOf("start").takeIf { it >= 0 } ?: 1
                endIdx = cols.indexOf("end").takeIf { it >= 0 } ?: 2
                textIdx = cols.indexOf("text").takeIf { it >= 0 } ?: (cols.size - 1)
            } else if (inEvents && line.startsWith("Dialogue:", true)) {
                val parts = line.substringAfter(':').split(',', limit = textIdx + 1)
                if (parts.size > textIdx) {
                    val body = parts[textIdx].replace(assTag, "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
                    if (body.isNotEmpty()) out += SubCue(ts(parts[startIdx].trim()), ts(parts[endIdx].trim()), body)
                }
            }
        }
        return out
    }

    /** Accepts hh:mm:ss,mmm · mm:ss.mmm · h:mm:ss.cc (ASS centiseconds). */
    private fun ts(raw: String): Long {
        val t = raw.trim().replace(',', '.')
        val dot = t.lastIndexOf('.')
        val main = if (dot >= 0) t.substring(0, dot) else t
        val frac = if (dot >= 0) t.substring(dot + 1) else "0"
        val p = main.split(':').map { it.toLongOrNull() ?: 0L }
        val sec = when (p.size) { 3 -> p[0] * 3600 + p[1] * 60 + p[2]; 2 -> p[0] * 60 + p[1]; else -> p.lastOrNull() ?: 0L }
        val ms = when (frac.length) { 1 -> (frac.toLongOrNull() ?: 0L) * 100; 2 -> (frac.toLongOrNull() ?: 0L) * 10; else -> frac.take(3).toLongOrNull() ?: 0L }
        return sec * 1000 + ms
    }
}

class SubtitleTrackEngine(val name: String, private val cues: List<SubCue>) {
    /** Cues visible at [positionMs] (already shifted by the user's sync offset). */
    fun activeAt(positionMs: Long): List<SubCue> {
        if (cues.isEmpty()) return emptyList()
        var lo = 0; var hi = cues.lastIndex; var idx = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (cues[mid].startMs <= positionMs) { idx = mid; lo = mid + 1 } else hi = mid - 1 }
        if (idx < 0) return emptyList()
        val out = ArrayList<SubCue>(2)
        for (i in idx downTo maxOf(0, idx - 4)) if (cues[i].endMs > positionMs) out += cues[i]
        return out.reversed()
    }
}

data class LrcLine(val timeMs: Long, val text: String)

/** Synced-lyrics (.lrc) parser: supports multiple timestamps per line and the [offset:] tag. */
object LrcParser {
    private val tag = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    fun parse(text: String): List<LrcLine> {
        val offset = Regex("""\[offset:\s*(-?\d+)]""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = ArrayList<LrcLine>()
        text.lines().forEach { line ->
            val stamps = tag.findAll(line).toList()
            if (stamps.isEmpty()) return@forEach
            val body = line.substring(stamps.last().range.last + 1).trim()
            stamps.forEach { m ->
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                out += LrcLine(m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + ms - offset, body)
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
