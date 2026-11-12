package com.matty.cadence.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class LibraryStatus {
    data object Loading : LibraryStatus()
    data object Ready : LibraryStatus()
    data object NeedsSignIn : LibraryStatus()
    data class Failed(val message: String) : LibraryStatus()
}

data class Position(val chapter: Int, val segment: Int)

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
                401 -> status = LibraryStatus.NeedsSignIn
                else -> if (!(quiet && books.isNotEmpty())) status = LibraryStatus.Failed(e.message ?: "OneDrive error")
            }
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

    suspend fun coverUrl(b: BookSummary): String? {
        if (b.hasCover != true) return null
        val key = "${b.id}#${b.coverRev ?: 0}"
        coverLinks[key]?.let { return it }
        return runCatching { files.downloadUrl("books/${b.id}/cover.jpg") }.getOrNull()?.also { coverLinks[key] = it }
    }

    /** A fresh streaming link (OneDrive's expire after about an hour). */
    suspend fun audioUrl(book: Book): String? {
        val a = book.audio ?: return null
        return runCatching { files.downloadUrl("books/${book.id}/${a.file}") }.getOrNull()
    }

    fun position(id: String): Position? {
        val c = positions.getInt("$id.c", -1)
        return if (c < 0) null else Position(c, positions.getInt("$id.s", 0))
    }

    fun savePosition(id: String, p: Position) {
        positions.edit().putInt("$id.c", p.chapter).putInt("$id.s", p.segment).apply()
    }
}
