package com.matty.cadence.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.matty.cadence.data.AudioTiming
import com.matty.cadence.data.Book
import com.matty.cadence.data.FlowItem
import com.matty.cadence.data.Library
import com.matty.cadence.data.Position
import com.matty.cadence.data.ReadingSegment
import com.matty.cadence.data.SyntheticTiming
import com.matty.cadence.data.flatten
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Drives the reading clock (same behaviour as the TV app):
 *  • text-only books: a synthetic clock paced by words-per-minute;
 *  • audiobooks: ExoPlayer's position matched against per-word timestamps.
 * Everything the screen draws comes from [current] (which sentence) and [revealed] (how many words of it).
 */
class ReaderModel(
    context: Context,
    val book: Book,
    startChapter: Int,
    startSegment: Int,
    private val library: Library,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    val hasAudio = book.audio != null

    var chapterIndex by mutableIntStateOf(startChapter.coerceIn(0, max(book.chapters.size - 1, 0)))
        private set
    var segments by mutableStateOf<List<ReadingSegment>>(emptyList())
        private set
    var flow by mutableStateOf<List<FlowItem>>(emptyList())
        private set
    var current by mutableIntStateOf(0)
        private set
    var revealed by mutableDoubleStateOf(0.0)
        private set
    var chapterElapsed by mutableDoubleStateOf(0.0)
        private set
    var chapterDuration by mutableDoubleStateOf(1.0)
        private set
    var isPlaying by mutableStateOf(true)
        private set
    var finished by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var sleepEndsAt by mutableStateOf<Long?>(null)

    val chapter get() = book.chapters[chapterIndex]
    val segment: ReadingSegment? get() = segments.getOrNull(current)

    // text clock
    private val hold = 0.9
    private var segElapsed = 0.0
    private val offsetsCache = HashMap<Int, DoubleArray>()
    private var segmentStart = DoubleArray(0)

    // audio clock
    private var player: ExoPlayer? = null
    private var lastGoodTime = 0.0
    private var urlFetchedAt = 0L
    private var recovering = false
    private var stalledSince = 0L

    init {
        if (hasAudio) {
            player = ExoPlayer.Builder(context).build().apply {
                playWhenReady = false
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { recoverAudio() }
                })
            }
        }
        load(chapterIndex, startSegment)
    }

    /** Called once the audio link is known. */
    fun attachAudio(url: String) {
        val p = player ?: return
        urlFetchedAt = System.currentTimeMillis()
        p.setMediaItem(mediaItem(url))
        p.prepare()
        p.setPlaybackSpeed(prefs.rate)
        seekAudio(segment?.let { if (current == 0) chapter.t0 ?: it.t0 else it.t0 } ?: 0.0)
        p.playWhenReady = isPlaying
    }

    private fun mediaItem(url: String): MediaItem {
        val ext = book.audio?.file?.substringAfterLast('.', "")?.lowercase()
        val mime = when (ext) {
            "m4a", "m4b", "mp4", "aac" -> MimeTypes.AUDIO_MP4
            "mp3" -> MimeTypes.AUDIO_MPEG
            else -> null
        }
        return MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }.build()
    }

    fun release() {
        savePosition()
        player?.release()
        player = null
    }

    // ---------------------------------------------------------------- clock

    /** Advance by dt seconds (called every frame while the reader is on screen). */
    fun tick(dt: Double) {
        sleepEndsAt?.let { if (System.currentTimeMillis() >= it) { sleepEndsAt = null; playing(false) } }
        if (hasAudio) audioTick() else textTick(dt)
    }

    private fun textTick(dt: Double) {
        if (!isPlaying) return
        val seg = segment ?: return
        val offs = offsets(seg)
        segElapsed += dt
        revealed = SyntheticTiming.revealed(segElapsed, offs)
        chapterElapsed = (segmentStart.getOrNull(current) ?: 0.0) + min(segElapsed, offs.last() + hold)
        if (segElapsed >= offs.last() + hold) advanceText()
    }

    private fun audioTick() {
        val p = player ?: return
        if (recovering) return
        buffering = p.playbackState == Player.STATE_BUFFERING
        if (isPlaying && buffering) {
            if (stalledSince == 0L) stalledSince = System.currentTimeMillis()
            if (System.currentTimeMillis() - stalledSince > 12_000) { recoverAudio(); return }
        } else stalledSince = 0L
        val t = p.currentPosition / 1000.0
        if (p.playbackState == Player.STATE_READY) lastGoodTime = t

        val end = chapter.t1
        if (end != null && t >= end) {
            if (chapterIndex + 1 < book.chapters.size) loadSegments(chapterIndex + 1, 0)
            else { playing(false); finished = true }
            return
        }
        if (segments.isNotEmpty()) {
            var i = current.coerceIn(0, segments.size - 1)
            if (segments[i].t0 > t) {
                while (i > 0 && segments[i].t0 > t) i--
            } else {
                while (i + 1 < segments.size && segments[i + 1].t0 <= t) i++
            }
            if (i != current) {
                current = i
                if (i % 5 == 0) savePosition()
            }
            revealed = AudioTiming.revealed(t, segments[current].wordTimes)
        }
        val c0 = chapter.t0 ?: 0.0
        chapterElapsed = max(0.0, t - c0)
        chapterDuration = max((chapter.t1 ?: (c0 + 1)) - c0, 1.0)
    }

    // ---------------------------------------------------------------- transport

    fun togglePlay() {
        if (finished) {
            finished = false
            load(0, 0)
            playing(true)
            return
        }
        playing(!isPlaying)
    }

    fun playing(on: Boolean) {
        isPlaying = on
        val p = player
        if (p != null) {
            if (on && System.currentTimeMillis() - urlFetchedAt > 45 * 60_000L) recoverAudio()
            else p.playWhenReady = on
        }
        if (!on) savePosition()
    }

    fun next() = jump(current + 1)

    fun previous() {
        // like a music player: well into this sentence → restart it; otherwise go back one
        if (revealed > 2 && isPlaying) jump(current) else jump(current - 1)
    }

    fun load(chapter: Int, segment: Int = 0) {
        loadSegments(chapter, segment)
        val seg = this.segment
        if (hasAudio && seg != null) seekAudio(if (segment == 0) this.chapter.t0 ?: seg.t0 else seg.t0)
    }

    fun seekFraction(f: Float) {
        if (hasAudio) {
            val c0 = chapter.t0 ?: 0.0
            seekAudio(c0 + f * chapterDuration)
        } else {
            val target = f * chapterDuration
            val i = segmentStart.indexOfLast { it <= target }.coerceAtLeast(0)
            moveTo(i)
        }
    }

    fun setRate(r: Float) {
        prefs.chooseRate(r)
        player?.setPlaybackSpeed(r)
    }

    fun setWpm(v: Double) {
        prefs.chooseWpm(v)
        rebuildTimeline(keepProgress = true)
    }

    private fun loadSegments(ch: Int, segIndex: Int) {
        if (ch !in book.chapters.indices) return
        chapterIndex = ch
        val (s, f) = book.chapters[ch].flatten()
        segments = s
        flow = f
        offsetsCache.clear()
        current = segIndex.coerceIn(0, max(s.size - 1, 0))
        segElapsed = 0.0
        revealed = 0.0
        finished = false
        rebuildTimeline(keepProgress = false)
        savePosition()
    }

    private fun advanceText() {
        when {
            current + 1 < segments.size -> moveTo(current + 1)
            chapterIndex + 1 < book.chapters.size -> load(chapterIndex + 1)
            else -> { playing(false); finished = true }
        }
    }

    private fun jump(index: Int) {
        if (index < 0) {
            if (chapterIndex > 0) {
                loadSegments(chapterIndex - 1, Int.MAX_VALUE)
                segment?.let { if (hasAudio) seekAudio(it.t0) }
            }
            return
        }
        if (index >= segments.size) {
            if (chapterIndex + 1 < book.chapters.size) load(chapterIndex + 1)
            return
        }
        moveTo(index)
        if (hasAudio) seekAudio(segments[index].t0)
    }

    private fun moveTo(i: Int) {
        current = i
        segElapsed = 0.0
        revealed = 0.0
        val seg = segment
        if (!isPlaying && seg != null) {          // paused + manual paging: show the whole sentence
            revealed = seg.words.size.toDouble()
            segElapsed = offsets(seg).last()
        }
        if (!hasAudio) chapterElapsed = segmentStart.getOrNull(i) ?: 0.0
        if (i % 5 == 0) savePosition()
    }

    private fun seekAudio(t: Double) {
        val p = player ?: return
        lastGoodTime = max(0.0, t - 0.05)
        p.seekTo((lastGoodTime * 1000).toLong())
    }

    /** Swap in a fresh streaming link and continue from the last good position. */
    private fun recoverAudio() {
        val p = player ?: return
        if (recovering) return
        recovering = true
        buffering = true
        val resumeAt = lastGoodTime
        scope.launch {
            try {
                val url = library.audioUrl(book) ?: return@launch
                urlFetchedAt = System.currentTimeMillis()
                p.setMediaItem(mediaItem(url))
                p.prepare()
                p.seekTo((resumeAt * 1000).toLong())
                p.playWhenReady = isPlaying
            } finally {
                recovering = false
                stalledSince = 0L
            }
        }
    }

    private fun offsets(seg: ReadingSegment): DoubleArray =
        offsetsCache.getOrPut(seg.id) { SyntheticTiming.offsets(seg.words, prefs.wpm) }

    private fun rebuildTimeline(keepProgress: Boolean) {
        if (hasAudio) {
            val c0 = chapter.t0 ?: 0.0
            chapterDuration = max((chapter.t1 ?: (c0 + 1)) - c0, 1.0)
            return
        }
        val fraction = segment?.let { s ->
            if (!keepProgress) 0.0 else { val end = offsets(s).last(); if (end > 0) min(segElapsed / end, 1.0) else 0.0 }
        } ?: 0.0
        offsetsCache.clear()
        var t = 0.0
        segmentStart = DoubleArray(segments.size)
        segments.forEachIndexed { i, s ->
            segmentStart[i] = t
            t += offsets(s).last() + hold
        }
        chapterDuration = max(t, 1.0)
        segment?.let { s -> segElapsed = fraction * offsets(s).last() }
        chapterElapsed = (segmentStart.getOrNull(current) ?: 0.0) + segElapsed
    }

    fun savePosition() {
        library.savePosition(book.id, Position(chapterIndex, current))
    }
}
