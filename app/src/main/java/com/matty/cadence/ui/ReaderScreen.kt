package com.matty.cadence.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.matty.cadence.data.Book
import com.matty.cadence.data.FlowItem
import com.matty.cadence.data.ReadingSegment
import com.matty.cadence.data.SyntheticTiming
import com.matty.cadence.data.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class Sheet { CHAPTERS, STYLE, SPEED, SLEEP, LOOKUP }

/**
 * Tap anywhere: show/hide the controls. Swipe left/right: next/previous sentence.
 * Swipe up: switch between one sentence at a time and the whole chapter. Back: hide controls, then leave.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(book: Book, startChapter: Int, startSegment: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val library = LocalLibrary.current
    val prefs = LocalPrefs.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    val model = remember(book.id) { ReaderModel(context, book, startChapter, startSegment, library, prefs, scope) }

    var chrome by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var resumeAfterLookup by remember { mutableStateOf(false) }

    // lifecycle: audio link, frame clock, screen awake, full screen
    DisposableEffect(model) { onDispose { model.release() } }
    LaunchedEffect(model) { if (model.hasAudio) library.audioUrl(book)?.let { model.attachAudio(it) } }
    LaunchedEffect(model) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                model.tick(min((now - last) / 1e9, 0.1))
                last = now
            }
        }
    }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        val window = (context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = false
            ctl?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // auto-hide the controls 5 s after the last touch while playing (never while a panel is open)
    LaunchedEffect(chrome, lastTouch, model.isPlaying, sheet) {
        if (chrome && sheet == null && model.isPlaying) {
            delay(5000)
            chrome = false
        }
    }

    fun touch() { lastTouch = System.currentTimeMillis() }
    fun openSheet(s: Sheet) {
        if (s == Sheet.LOOKUP && model.isPlaying) { resumeAfterLookup = true; model.playing(false) }
        sheet = s
        touch()
    }
    fun closeSheet() {
        if (sheet == Sheet.LOOKUP && resumeAfterLookup) { resumeAfterLookup = false; model.playing(true) }
        sheet = null
    }

    BackHandler {
        when {
            sheet != null -> closeSheet()
            chrome -> chrome = false
            prefs.mode == ReaderMode.CARD -> prefs.chooseMode(ReaderMode.SINGLE)
            else -> onClose()
        }
    }

    val swipe = with(LocalDensity.current) { 70.dp.toPx() }
    val inkFamily = if (book.isAmharic) amharicFamily else prefs.font.family

    Box(
        Modifier
            .fillMaxSize()
            .background(pal.pageBase)
            .background(Brush.radialGradient(listOf(pal.pageLight, pal.pageLight.copy(alpha = 0f)), center = Offset(380f, 220f), radius = 1600f))
            .background(Brush.radialGradient(listOf(Color.Transparent, pal.pageEdge.copy(alpha = 0.10f), pal.pageEdge.copy(alpha = 0.38f)), radius = 1900f))
            .pointerInput(Unit) { detectTapGestures(onTap = { chrome = !chrome; touch() }) }
            .pointerInput(Unit) {
                var total = Offset.Zero
                detectDragGestures(
                    onDragStart = { total = Offset.Zero },
                    onDrag = { change, amount -> change.consume(); total += amount },
                    onDragEnd = {
                        touch()
                        if (abs(total.x) > abs(total.y) && abs(total.x) > swipe) {
                            if (total.x < 0) model.next() else model.previous()
                        } else if (total.y < -swipe) {
                            prefs.chooseMode(if (prefs.mode == ReaderMode.FLOWING) ReaderMode.SINGLE else ReaderMode.FLOWING)
                        } else if (total.y > swipe) {
                            chrome = true
                        }
                    },
                )
            }
    ) {
        when (prefs.mode) {
            ReaderMode.SINGLE -> SingleView(model, inkFamily)
            ReaderMode.FLOWING -> FlowingView(model, inkFamily)
            ReaderMode.CARD -> CardView(model, inkFamily)
        }
        if (model.buffering) CircularProgressIndicator(color = pal.ink, modifier = Modifier.align(Alignment.Center))

        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn() + slideInVertically { it / 8 },
            exit = fadeOut() + slideOutVertically { it / 8 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Controls(model, onClose = onClose, onSheet = { openSheet(it) }, onTouch = { touch() }, onCard = {
                chrome = false
                prefs.chooseMode(ReaderMode.CARD)
            })
        }
        AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding().padding(12.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Close book", tint = pal.ink)
            }
        }
    }

    if (sheet != null) {
        ModalBottomSheet(
            onDismissRequest = { closeSheet() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = pal.bg,
            contentColor = pal.text,
        ) {
            Box(Modifier.padding(horizontal = 28.dp).padding(bottom = 28.dp).navigationBarsPadding()) {
                when (sheet) {
                    Sheet.CHAPTERS -> ChaptersSheet(model) { closeSheet() }
                    Sheet.STYLE -> ReadingStyleControls(compact = true, amharic = book.isAmharic)
                    Sheet.SPEED -> SpeedSheet(model) { closeSheet() }
                    Sheet.SLEEP -> SleepSheet(model) { closeSheet() }
                    Sheet.LOOKUP -> LookupSheet(model.segment?.words ?: emptyList())
                    null -> {}
                }
            }
        }
    }
}

// ---------------------------------------------------------------- the text

private fun reveal(seg: ReadingSegment, revealed: Double, ink: Color, numberSize: Float): AnnotatedString = buildAnnotatedString {
    val numberAlpha = (min(1.0, 0.35 + revealed * 2) * 0.75).toFloat()
    withStyle(SpanStyle(color = ink.copy(alpha = numberAlpha), fontSize = numberSize.em, baselineShift = BaselineShift.Superscript,
        fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Default)) { append("${seg.id} ") }
    seg.words.forEachIndexed { i, w ->
        val a = ((revealed - i + 0.25) / 1.3).coerceIn(0.0, 1.0).toFloat()
        withStyle(SpanStyle(color = ink.copy(alpha = a))) { append("$w ") }
    }
}

@Composable
private fun SingleView(model: ReaderModel, family: FontFamily) {
    val pal = LocalPalette.current
    val prefs = LocalPrefs.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val inset = maxWidth * prefs.margins.inset
        val h = maxHeight
        Column(Modifier.fillMaxSize().padding(horizontal = inset)) {
            Spacer(Modifier.height(h * 0.07f))
            Text(model.chapter.title.uppercase(), color = pal.inkSoft, fontSize = 14.sp, letterSpacing = 2.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(h * 0.16f))
            AnimatedContent(
                targetState = model.current to model.chapterIndex,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "sentence",
            ) { (idx, _) ->
                val seg = model.segments.getOrNull(idx)
                if (seg != null) {
                    val base = when { seg.words.size < 26 -> 34f; seg.words.size < 36 -> 30f; else -> 26f } * prefs.scale
                    val shown = if (idx == model.current) model.revealed else seg.words.size.toDouble()
                    Text(
                        reveal(seg, shown, pal.ink, 0.45f),
                        style = TextStyle(fontFamily = family, fontSize = base.sp, lineHeight = (base * prefs.spacing.factor).sp,
                            fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun FlowingView(model: ReaderModel, family: FontFamily) {
    val pal = LocalPalette.current
    val prefs = LocalPrefs.current
    val list = rememberLazyListState()
    val size = 22f * prefs.scale
    val currentItem = model.segment?.flowItem ?: 0
    LaunchedEffect(currentItem, model.chapterIndex) {
        val idx = model.flow.indexOfFirst { it.id == currentItem }.coerceAtLeast(0)
        list.animateScrollToItem(idx)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val inset = maxWidth * (prefs.margins.inset + 0.03f)
        LazyColumn(
            state = list,
            userScrollEnabled = false,
            contentPadding = PaddingValues(start = inset, end = inset, top = maxHeight * 0.22f, bottom = maxHeight * 0.7f),
            verticalArrangement = Arrangement.spacedBy((18 * prefs.scale).dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(model.flow, key = { it.id }) { item ->
                when (item) {
                    is FlowItem.Heading -> Text(item.title, color = pal.inkSoft, fontFamily = family, fontSize = (size * 1.1f).sp,
                        fontWeight = FontWeight.SemiBold)
                    is FlowItem.Paragraph -> Text(
                        buildAnnotatedString {
                            for (i in item.range) {
                                val seg = model.segments[i]
                                val cur = i == model.current
                                withStyle(SpanStyle(color = pal.ink.copy(alpha = if (cur) 0.85f else 0.4f), fontSize = 0.47.em,
                                    baselineShift = BaselineShift.Superscript, fontFamily = FontFamily.Default,
                                    fontWeight = FontWeight.SemiBold)) { append("${seg.id} ") }
                                withStyle(SpanStyle(color = pal.ink.copy(alpha = if (cur) 1f else 0.5f))) { append(seg.text + " ") }
                            }
                        },
                        style = TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = (size * prefs.spacing.factor * 1.1f).sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CardView(model: ReaderModel, family: FontFamily) {
    val pal = LocalPalette.current
    val prefs = LocalPrefs.current
    BoxWithConstraints(Modifier.fillMaxSize().background(pal.pageEdge.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        val seg = model.segment ?: return@BoxWithConstraints
        val size = (if (seg.words.size > 30) 34f else 44f) * prefs.scale
        Column(
            Modifier.padding(horizontal = maxWidth * (prefs.margins.inset + 0.04f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(seg.text, color = pal.ink, fontFamily = family, fontSize = size.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, lineHeight = (size * prefs.spacing.factor * 0.95f).sp)
            Text("${model.chapter.title} · ${seg.id}".uppercase(), color = pal.inkSoft, fontSize = 14.sp, letterSpacing = 2.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- controls

@Composable
private fun Controls(model: ReaderModel, onClose: () -> Unit, onSheet: (Sheet) -> Unit, onTouch: () -> Unit, onCard: () -> Unit) {
    val pal = LocalPalette.current
    val prefs = LocalPrefs.current
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, pal.bg.copy(alpha = 0.92f), pal.bg)))
            .navigationBarsPadding()
            .padding(start = 32.dp, end = 32.dp, top = 48.dp, bottom = 20.dp)
            .pointerInput(Unit) { detectTapGestures { onTouch() } },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(listOfNotNull(model.book.title, model.book.author).joinToString(" · "), color = pal.secondary, fontSize = 14.sp, maxLines = 1)
        Text(model.chapter.title, color = pal.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            fontFamily = if (model.book.isAmharic) amharicFamily else FontFamily.Default)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(if (prefs.mode == ReaderMode.FLOWING) Icons.Outlined.ViewAgenda else Icons.AutoMirrored.Outlined.Subject,
                if (prefs.mode == ReaderMode.FLOWING) "One sentence at a time" else "Whole chapter") {
                onTouch()
                prefs.chooseMode(if (prefs.mode == ReaderMode.FLOWING) ReaderMode.SINGLE else ReaderMode.FLOWING)
            }
            RoundButton(Icons.Outlined.OpenInFull, "Full-screen card") { onCard() }
            RoundButton(Icons.Outlined.FormatSize, "Reading style") { onSheet(Sheet.STYLE) }
            if (!model.book.isAmharic) RoundButton(Icons.Outlined.Book, "Look up a word") { onSheet(Sheet.LOOKUP) }
            RoundButton(Icons.Outlined.Bedtime, "Sleep timer", selected = model.sleepEndsAt != null) { onSheet(Sheet.SLEEP) }
            RoundButton(Icons.Outlined.Speed, "Speed") { onSheet(Sheet.SPEED) }
            Spacer(Modifier.weight(1f))
            RoundButton(Icons.Outlined.SkipPrevious, "Previous sentence") { onTouch(); model.previous() }
            RoundButton(if (model.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (model.isPlaying) "Pause" else "Play",
                size = 60.dp, selected = true) { onTouch(); model.togglePlay() }
            RoundButton(Icons.Outlined.SkipNext, "Next sentence") { onTouch(); model.next() }
        }
        val progress = (model.chapterElapsed / model.chapterDuration).coerceIn(0.0, 1.0).toFloat()
        Slider(
            value = dragging ?: progress,
            onValueChange = { dragging = it; onTouch() },
            onValueChangeFinished = { dragging?.let { model.seekFraction(it) }; dragging = null },
            colors = SliderDefaults.colors(thumbColor = pal.text, activeTrackColor = pal.text, inactiveTrackColor = pal.text.copy(alpha = 0.25f)),
        )
        Row {
            Text(formatTime(model.chapterElapsed), color = pal.secondary, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("-" + formatTime(model.chapterDuration - model.chapterElapsed), color = pal.secondary, fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill("Chapters", icon = Icons.Outlined.FormatListBulleted) { onSheet(Sheet.CHAPTERS) }
            if (model.sleepEndsAt != null) {
                val left = max(0L, (model.sleepEndsAt!! - System.currentTimeMillis()) / 60000)
                Pill("Sleep in ${left + 1} min", icon = Icons.Outlined.Bedtime) { onSheet(Sheet.SLEEP) }
            }
        }
    }
}

@Composable
private fun ChaptersSheet(model: ReaderModel, done: () -> Unit) {
    val pal = LocalPalette.current
    Column {
        SectionTitle("Chapters")
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.height(480.dp)) {
            items(model.book.chapters, key = { it.index }) { ch ->
                val cur = ch.index == model.chapterIndex
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (cur) pal.selected else Color.Transparent)
                        .clickable { model.load(ch.index); done() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${ch.index + 1}", color = pal.secondary, fontSize = 15.sp, modifier = Modifier.width(44.dp))
                    Text(ch.title, color = pal.text, fontSize = 17.sp, maxLines = 1,
                        fontFamily = if (model.book.isAmharic) amharicFamily else FontFamily.Default)
                }
            }
        }
    }
}

@Composable
private fun SpeedSheet(model: ReaderModel, done: () -> Unit) {
    val prefs = LocalPrefs.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (model.hasAudio) {
            SectionTitle("Playback speed")
            FlowRow2 {
                for (r in listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) {
                    Pill("${r}×", selected = abs(prefs.rate - r) < 0.01f) { model.setRate(r); done() }
                }
            }
        } else {
            SectionTitle("Reading speed · words per minute")
            FlowRow2 {
                for (v in SyntheticTiming.speeds) {
                    Pill("${v.toInt()}", selected = prefs.wpm.toInt() == v.toInt()) { model.setWpm(v); done() }
                }
            }
        }
    }
}

@Composable
private fun SleepSheet(model: ReaderModel, done: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Sleep timer")
        FlowRow2 {
            Pill("Off", selected = model.sleepEndsAt == null) { model.sleepEndsAt = null; done() }
            for (m in listOf(15, 30, 45, 60)) {
                Pill("$m minutes") { model.sleepEndsAt = System.currentTimeMillis() + m * 60_000L; done() }
            }
        }
    }
}

@Composable
private fun LookupSheet(words: List<String>) {
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    val choices = remember(words) { WordLookup.words(words) }
    var selected by remember { mutableStateOf<String?>(null) }
    var entry by remember { mutableStateOf<DictionaryEntry?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Look up a word")
        FlowRow2 {
            for (w in choices) {
                Pill(w, selected = selected == w) {
                    selected = w; entry = null; message = null; loading = true
                    scope.launch {
                        try {
                            entry = WordLookup.define(w)
                            if (entry == null) message = "No definition found for “$w”. Names and rare spellings often aren't in the dictionary."
                        } catch (e: Exception) {
                            message = "Couldn't reach the dictionary. Check the internet connection."
                        } finally { loading = false }
                    }
                }
            }
        }
        val e = entry
        when {
            loading -> CircularProgressIndicator(color = pal.text, modifier = Modifier.size(24.dp))
            e != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(e.word, color = pal.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                    e.phonetic?.let { Spacer(Modifier.width(12.dp)); Text(it, color = pal.secondary, fontSize = 18.sp) }
                }
                for (s in e.senses) {
                    Text(s.partOfSpeech, color = pal.secondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    s.definitions.forEachIndexed { i, d ->
                        Text("${i + 1}. ${d.text}", color = pal.text, fontSize = 17.sp)
                        d.example?.let { Text("“$it”", color = pal.secondary, fontSize = 15.sp, modifier = Modifier.padding(start = 20.dp)) }
                    }
                }
            }
            message != null -> Text(message!!, color = pal.secondary, fontSize = 16.sp)
            else -> Text("Tap a word to see what it means.", color = pal.secondary, fontSize = 16.sp)
        }
    }
}
