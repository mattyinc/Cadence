package com.matty.cadence.data

import android.content.Context
import android.net.Uri
import com.matty.cadence.Playback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

sealed class LibraryStatus {
    data object Loading : LibraryStatus()
    data object Ready : LibraryStatus()
    data object NeedsSignIn : LibraryStatus()
    data class Failed(val message: String) : LibraryStatus()
}

/**
 * Where you are in a book. Synced through OneDrive (positions/<book id>.json) so the tablet and the
 * Apple TV pick up from the same place; the newest `updated` wins.
 */
@Serializable
data class Position(
    val chapter: Int,
    val segment: Int,
    val time: Double = 0.0,        // audiobooks: seconds into the audio
    val updated: Long = 0,         // epoch ms
    val device: String = "android",
)

/** The shared library: OneDrive › Apps › Cadence/library.json + books/<id>/… */
class Library(context: Context) {
    val auth = OneDriveAuth(context)
    private val files = OneDriveFiles(auth)
    private val cacheDir = File(context.filesDir, "books").apply { mkdirs() }
    private val positions = context.getSharedPreferences("positions", Context.MODE_PRIVATE)
    private val coverLinks = HashMap<String, String>()

    var books by mutableStateOf<List<BookSummary>>(emptyList())
        private set
    var status by mutableStateOf<LibraryStatus>(LibraryStatus.Loading)
        private set

    init {
        // show the last known shelf instantly, refresh from OneDrive after
        runCatching {
            val f = File(cacheDir, "library.json")
            if (f.exists()) books = json.decodeFromString(LibraryIndex.serializer(), f.readText()).books.sortedBy { it.title.lowercase() }
        }
        if (!auth.signedIn) status = LibraryStatus.NeedsSignIn
    }

    suspend fun refresh(quiet: Boolean = false) {
        if (!auth.signedIn) {
            status = LibraryStatus.NeedsSignIn
            return
        }
        if (!quiet && books.isEmpty()) status = LibraryStatus.Loading
        try {
            val data = files.bytes("library.json")
            val idx = json.decodeFromString(LibraryIndex.serializer(), data.decodeToString())
            books = idx.books.sortedBy { it.title.lowercase() }
            withContext(Dispatchers.IO) { File(cacheDir, "library.json").writeBytes(data) }
            status = LibraryStatus.Ready
        } catch (e: GraphException) {
            when (e.code) {
                404 -> { books = emptyList(); status = LibraryStatus.Ready }
                401, 403 -> { auth.report(e.message ?: "OneDrive refused access."); status = LibraryStatus.NeedsSignIn }
                else -> if (!(quiet && books.isNotEmpty())) status = LibraryStatus.Failed(e.message ?: "OneDrive error")
            }
        } catch (e: java.net.UnknownHostException) {
            if (!(quiet && books.isNotEmpty())) status = LibraryStatus.Failed(
                "This tablet can't look up ${e.message?.substringAfter("host \"")?.substringBefore("\"") ?: "Microsoft's servers"}. " +
                "Check the Wi-Fi, then Settings › Connections › More connection settings › Private DNS (set it to Automatic) and turn off any ad-blocker or VPN app.")
        } catch (e: Exception) {
            if (!(quiet && books.isNotEmpty())) status = LibraryStatus.Failed("Couldn't load the library — ${e.message}")
        }
    }

    fun signOut() {
        auth.signOut()
        books = emptyList()
        coverLinks.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
        status = LibraryStatus.NeedsSignIn
    }

    /** Network first (so re-imports show up), cached copy when offline. */
    suspend fun book(id: String): Book {
        val cached = File(cacheDir, "$id.json")
        return try {
            val data = files.bytes("books/$id/book.json")
            withContext(Dispatchers.IO) { cached.writeBytes(data) }
            json.decodeFromString(Book.serializer(), data.decodeToString())
        } catch (e: Exception) {
            if (!cached.exists()) throw e
            withContext(Dispatchers.IO) { json.decodeFromString(Book.serializer(), cached.readText()) }
        }
    }

    private val coverDir = File(context.cacheDir, "covers").apply { mkdirs() }

    /** Cover as a local file (downloaded once per cover revision) — for the shelves and the lock screen. */
    suspend fun coverUrl(b: BookSummary): String? {
        if (b.hasCover != true) return null
        val f = File(coverDir, "${b.id}-${b.coverRev ?: 0}.jpg")
        if (!f.exists()) {
            val data = runCatching { files.bytes("books/${b.id}/cover.jpg") }.getOrNull() ?: return null
            withContext(Dispatchers.IO) { f.writeBytes(data) }
        }
        return Uri.fromFile(f).toString()
    }

    suspend fun coverUrl(bookId: String): String? {
        val b = books.firstOrNull { it.id == bookId } ?: return null
        return coverUrl(b)
    }

    /** A fresh streaming link (OneDrive's expire after about an hour). */
    suspend fun audioUrl(book: Book): String? {
        val a = book.audio ?: return null
        val token = runCatching { files.token() }.getOrNull() ?: return null
        Playback.authorize(token)          // streamed straight from Graph with the account's token
        return files.contentUrl("books/${book.id}/${a.file}")
    }

    // ---------------------------------------------------------------- reading positions

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val uploads = HashMap<String, Job>()

    /** This device's last known position (instant, offline). */
    fun position(id: String): Position? {
        positions.getString("$id.json", null)?.let { raw ->
            runCatching { return json.decodeFromString(Position.serializer(), raw) }
        }
        val c = positions.getInt("$id.c", -1)            // older builds stored two ints
        return if (c < 0) null else Position(c, positions.getInt("$id.s", 0))
    }

    /** Saves locally right away and uploads a few seconds later (or now, when `immediately`). */
    fun savePosition(id: String, p: Position, immediately: Boolean = false) {
        val stamped = p.copy(updated = System.currentTimeMillis(), device = "android")
        positions.edit().putString("$id.json", json.encodeToString(Position.serializer(), stamped)).apply()
        if (!auth.signedIn) return
        uploads[id]?.cancel()
        uploads[id] = syncScope.launch {
            if (!immediately) delay(4000)
            runCatching {
                files.put("positions/$id.json", json.encodeToString(Position.serializer(), stamped).encodeToByteArray())
            }
        }
    }

    /** Fetches the position saved on any device; adopts it when it's newer than this tablet's. */
    suspend fun syncPosition(id: String): Position? {
        val local = position(id)
        if (!auth.signedIn) return local
        val remote = runCatching {
            json.decodeFromString(Position.serializer(), files.bytes("positions/$id.json").decodeToString())
        }.getOrNull()
        if (remote != null && remote.updated > (local?.updated ?: 0)) {
            positions.edit().putString("$id.json", json.encodeToString(Position.serializer(), remote)).apply()
            return remote
        }
        return local
    }
}
