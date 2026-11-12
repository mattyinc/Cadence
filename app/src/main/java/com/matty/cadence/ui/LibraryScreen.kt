package com.matty.cadence.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.matty.cadence.data.Book
import com.matty.cadence.data.BookSummary
import com.matty.cadence.data.LibraryStatus
import kotlinx.coroutines.launch

enum class Shelf(val title: String) { BOOKS("Books"), AUDIOBOOKS("Audiobooks") }

@Composable
fun ShelfScreen(shelf: Shelf, onOpen: (BookSummary) -> Unit) {
    val pal = LocalPalette.current
    val library = LocalLibrary.current
    val scope = rememberCoroutineScope()
    val books = library.books.filter { it.isAudiobook == (shelf == Shelf.AUDIOBOOKS) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(170.dp),
        contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(shelf.title, color = pal.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    IconButton(onClick = { scope.launch { library.refresh() } }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = pal.secondary)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (books.size == 1) "1 title" else "${books.size} titles", color = pal.secondary, fontSize = 16.sp)
                }
                Spacer(Modifier.height(12.dp))
                StatusLine(shelf, books.isEmpty())
            }
        }
        items(books, key = { it.id }) { b -> BookCard(b) { onOpen(b) } }
    }
}

@Composable
private fun StatusLine(shelf: Shelf, empty: Boolean) {
    val pal = LocalPalette.current
    val library = LocalLibrary.current
    val scope = rememberCoroutineScope()
    when (val s = library.status) {
        LibraryStatus.Loading -> if (empty) CircularProgressIndicator(color = pal.text)
        LibraryStatus.NeedsSignIn -> SignInCard()
        is LibraryStatus.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.message, color = pal.secondary, fontSize = 16.sp)
            Pill("Try again") { scope.launch { library.refresh() } }
        }
        LibraryStatus.Ready -> if (empty) Text(
            if (shelf == Shelf.AUDIOBOOKS) "No audiobooks yet. Add one in Cadence Studio on your Mac — with its EPUB or transcript for real chapters."
            else "No books yet. Add EPUB, PDF, MOBI or TXT files in Cadence Studio on your Mac.",
            color = pal.secondary, fontSize = 16.sp,
        )
    }
}

@Composable
fun BookCard(b: BookSummary, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val library = LocalLibrary.current
    val cover by produceState<String?>(null, b.id, b.coverRev, b.hasCover) { value = library.coverUrl(b) }
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .shadow(8.dp, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.linearGradient(listOf(pal.selected, pal.bgGlow)))
        ) {
            Text(b.title, color = pal.text, fontFamily = FontFamily.Serif, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(16.dp))
            if (cover != null) AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
            if (b.isAudiobook) Box(
                Modifier.align(Alignment.BottomStart).padding(8.dp).size(28.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Headphones, contentDescription = "Audiobook", tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
        Spacer(Modifier.height(10.dp))
        Text(b.title, color = pal.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            if (b.transcribing == true) "Transcribing… ${b.chapters} ch ready" else (b.author ?: b.format.uppercase()),
            color = pal.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Device-code sign-in: tap to open Microsoft's page, paste the code, approve. */
@Composable
fun SignInCard() {
    val pal = LocalPalette.current
    val library = LocalLibrary.current
    val auth = library.auth
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect OneDrive", color = pal.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            val p = auth.pending
            if (p != null) {
                Text("Copy the code, open the Microsoft page and paste it, then approve.", color = pal.secondary, fontSize = 16.sp)
                Text(p.userCode, color = pal.text, fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 6.sp)
                FlowRow2 {
                    Pill("Copy code & open Microsoft", selected = true) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Microsoft code", p.userCode))
                        uri.openUri(p.url)
                    }
                    Pill("Cancel") { auth.cancelSignIn() }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = pal.text, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (auth.checking) "Checking with Microsoft…" else "Waiting for you to approve…", color = pal.secondary, fontSize = 15.sp)
                }
                Pill("I've approved it") { auth.checkNow() }
            } else {
                Text("Cadence reads your books from OneDrive › Apps › Cadence — the same library as your Apple TV. It can't see any of your other files.",
                    color = pal.secondary, fontSize = 16.sp)
                auth.error?.let { Text(it, color = pal.secondary, fontSize = 15.sp) }
                Pill("Sign in with Microsoft", selected = true) {
                    auth.signIn { library.refresh() }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRow2(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

// ---------------------------------------------------------------- one book: chapters

@Composable
fun BookScreen(summary: BookSummary, onBack: () -> Unit, onRead: (Book, Int, Int, Double) -> Unit) {
    val pal = LocalPalette.current
    val library = LocalLibrary.current
    var error by remember { mutableStateOf<String?>(null) }
    val book by produceState<Book?>(null, summary.id) {
        value = try { library.book(summary.id) } catch (e: Exception) { error = "Couldn't open this book: ${e.message}"; null }
    }
    // the newest position from any device (tablet or Apple TV), checked each time this page opens
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(summary.id) {
        library.syncPosition(summary.id)
        tick++
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = pal.text) }
        }
        val b = book
        when {
            b != null -> ChapterGrid(b, tick, onRead)
            error != null -> Text(error!!, color = pal.secondary, modifier = Modifier.padding(32.dp))
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = pal.text) }
        }
    }
}

@Composable
private fun ChapterGrid(book: Book, @Suppress("UNUSED_PARAMETER") tick: Int, onRead: (Book, Int, Int, Double) -> Unit) {
    val pal = LocalPalette.current
    val library = LocalLibrary.current
    val pos = library.position(book.id)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(220.dp),
        contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                Text(book.title, color = pal.text, fontSize = 38.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    fontFamily = if (book.isAmharic) amharicFamily else FontFamily.Default)
                book.author?.let { Text(it, color = pal.secondary, fontSize = 18.sp) }
                Text(
                    "${book.chapters.size} chapters" + if (book.audio != null) " · narrated, ${formatHours(book.audio.duration)}" else "",
                    color = pal.secondary, fontSize = 15.sp,
                )
                if (pos != null && pos.chapter in book.chapters.indices) {
                    Spacer(Modifier.height(4.dp))
                    Pill("Continue · ${book.chapters[pos.chapter].title}" + if (pos.device == "tv") " (from Apple TV)" else "",
                        selected = true, icon = Icons.Outlined.PlayArrow) {
                        onRead(book, pos.chapter, pos.segment, pos.time)
                    }
                }
            }
        }
        items(book.chapters, key = { it.index }) { ch ->
            val current = pos?.chapter == ch.index
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (current) pal.selected else pal.glass)
                    .clickable { onRead(book, ch.index, 0, 0.0) }
                    .padding(16.dp)
            ) {
                Text("${ch.index + 1}", color = pal.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(ch.title, color = pal.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, fontFamily = if (book.isAmharic) amharicFamily else FontFamily.Default)
                val mins = if (ch.t0 != null && ch.t1 != null) ((ch.t1 - ch.t0) / 60).toInt() else ((ch.words ?: 0) / 200)
                Text("${mins.coerceAtLeast(1)} min", color = pal.secondary, fontSize = 13.sp)
            }
        }
    }
}

private fun formatHours(s: Double): String {
    val m = (s / 60).toInt()
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}
