package com.matty.cadence.data

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

// Package format written by Cadence Studio (importer/reader_import.py) — the same files the Apple TV reads.

@Serializable
data class LibraryIndex(val books: List<BookSummary> = emptyList())

@Serializable
data class BookSummary(
    val id: String,
    val title: String,
    val author: String? = null,
    val format: String = "",
    val chapters: Int = 0,
    val words: Int = 0,
    val hasCover: Boolean? = null,
    val audio: Boolean? = null,
    val transcribing: Boolean? = null,
    val coverRev: Int? = null,
    val language: String? = null,
) {
    val isAudiobook: Boolean get() = audio == true
}

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String? = null,
    val format: String = "",
    val chapters: List<Chapter> = emptyList(),
    val audio: AudioInfo? = null,
    val transcribing: Boolean? = null,
    val language: String? = null,
) {
    val isAmharic: Boolean get() = language == "am"
}

@Serializable
data class AudioInfo(val file: String, val duration: Double = 0.0)

@Serializable
data class Chapter(
    val index: Int,
    val title: String,
    val words: Int? = null,
    val t0: Double? = null,
    val t1: Double? = null,
    val blocks: List<Block> = emptyList(),
)

@Serializable
data class Block(
    val type: String,
    val text: String? = null,
    val segments: List<Segment>? = null,
)

@Serializable
data class Segment(
    val n: Int,
    val text: String,
    val t0: Double? = null,
    val t1: Double? = null,
    val w: List<List<Double>>? = null,
)

// ---------------------------------------------------------------- reader-side flattened model

/** One screen of text in single-sentence mode. */
data class ReadingSegment(
    val id: Int,
    val text: String,
    val words: List<String>,
    val flowItem: Int,
    val t0: Double = 0.0,
    val t1: Double = 0.0,
    val wordTimes: List<List<Double>> = emptyList(),
)

/** A row of the flowing-chapter view. */
sealed class FlowItem(val id: Int) {
    class Heading(id: Int, val title: String) : FlowItem(id)
    class Paragraph(id: Int, val range: IntRange) : FlowItem(id)
}

fun Chapter.flatten(): Pair<List<ReadingSegment>, List<FlowItem>> {
    val segs = ArrayList<ReadingSegment>()
    val flow = ArrayList<FlowItem>()
    for (block in blocks) {
        val itemId = flow.size
        val s = block.segments
        if (block.type == "heading" && block.text != null) {
            flow.add(FlowItem.Heading(itemId, block.text))
        } else if (!s.isNullOrEmpty()) {
            val start = segs.size
            for (seg in s) {
                val words = seg.text.split(' ', '\n').filter { it.isNotEmpty() }
                segs.add(
                    ReadingSegment(
                        id = seg.n, text = seg.text, words = words, flowItem = itemId,
                        t0 = seg.t0 ?: 0.0, t1 = seg.t1 ?: 0.0, wordTimes = seg.w ?: emptyList(),
                    )
                )
            }
            flow.add(FlowItem.Paragraph(itemId, start until segs.size))
        }
    }
    return segs to flow
}

// ---------------------------------------------------------------- timing (same maths as the TV app)

object SyntheticTiming {
    val speeds = listOf(150.0, 200.0, 250.0, 300.0, 350.0, 400.0, 500.0, 600.0, 750.0, 900.0)

    /** Word start offsets; size == words.size + 1, the last entry is the end. */
    fun offsets(words: List<String>, wpm: Double): DoubleArray {
        val base = 60.0 / max(wpm, 60.0)
        val out = DoubleArray(words.size + 1)
        var t = 0.0
        words.forEachIndexed { i, w ->
            out[i] = t
            val letters = w.count { it.isLetter() }
            var d = base * (0.72 + 0.056 * min(letters, 12))
            val last = w.trimEnd('"', '”', '’', ')', ']').lastOrNull()
            if (last != null) {
                if (last in ".!?…።፧") d += base * 1.1
                else if (last in ",;:—–፣፤") d += base * 0.45
            }
            t += d
        }
        out[words.size] = t
        return out
    }

    fun revealed(time: Double, offsets: DoubleArray): Double {
        val n = offsets.size - 1
        if (n <= 0 || time <= 0) return 0.0
        if (time >= offsets[n]) return n.toDouble()
        var i = 0
        while (i < n - 1 && offsets[i + 1] <= time) i++
        val span = max(offsets[i + 1] - offsets[i], 0.001)
        return i + min(1.0, (time - offsets[i]) / span)
    }
}

object AudioTiming {
    fun revealed(t: Double, words: List<List<Double>>): Double {
        if (words.isEmpty() || t <= words[0][0]) return 0.0
        var lo = 0
        var hi = words.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (words[mid][0] <= t) lo = mid else hi = mid - 1
        }
        val w = words[lo]
        val span = max(w[1] - w[0], 0.08)
        return lo + min(1.0, (t - w[0]) / span)
    }
}

fun formatTime(s: Double): String {
    val t = max(0, Math.round(s).toInt())
    val h = t / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (t % 3600) / 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
}
