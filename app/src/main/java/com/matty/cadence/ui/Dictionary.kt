package com.matty.cadence.ui

import com.matty.cadence.data.http
import com.matty.cadence.data.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Request
import java.net.URLEncoder

/** English definitions from the free Wiktionary-based dictionaryapi.dev (same source as the TV app). */
data class Definition(val text: String, val example: String?)
data class Sense(val partOfSpeech: String, val definitions: List<Definition>)
data class DictionaryEntry(val word: String, val phonetic: String?, val senses: List<Sense>)

object WordLookup {
    @Serializable private data class ApiDef(val definition: String, val example: String? = null)
    @Serializable private data class ApiMeaning(val partOfSpeech: String = "", val definitions: List<ApiDef> = emptyList())
    @Serializable private data class ApiPhonetic(val text: String? = null)
    @Serializable private data class ApiEntry(
        val word: String,
        val phonetic: String? = null,
        val phonetics: List<ApiPhonetic> = emptyList(),
        val meanings: List<ApiMeaning> = emptyList(),
    )

    private val cache = HashMap<String, DictionaryEntry?>()

    /** null = the dictionary doesn't know the word. Throws when offline. */
    suspend fun define(word: String): DictionaryEntry? {
        val key = word.lowercase()
        if (cache.containsKey(key)) return cache[key]
        val entry = withContext(Dispatchers.IO) {
            val url = "https://api.dictionaryapi.dev/api/v2/entries/en/" + URLEncoder.encode(key, "UTF-8").replace("+", "%20")
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (resp.code == 404) return@withContext null
                if (!resp.isSuccessful) throw IllegalStateException("dictionary error ${resp.code}")
                val list = json.decodeFromString(ListSerializer(ApiEntry.serializer()), resp.body!!.string())
                val first = list.firstOrNull() ?: return@withContext null
                val senses = ArrayList<Sense>()
                for (e in list) for (m in e.meanings) {
                    if (senses.size >= 3) break
                    val defs = m.definitions.take(2).map { Definition(it.definition, it.example) }
                    if (defs.isNotEmpty()) senses.add(Sense(m.partOfSpeech, defs))
                }
                DictionaryEntry(first.word, first.phonetic ?: first.phonetics.firstNotNullOfOrNull { it.text }, senses)
            }
        }
        cache[key] = entry
        return entry
    }

    /** Distinct words of a sentence, cleaned of punctuation, in reading order. */
    fun words(text: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (raw in text) {
            val w = raw.trim { !it.isLetterOrDigit() && it != '\'' && it != '’' && it != '-' }.replace('’', '\'')
            if (w.length > 1 && w.any { it.isLetter() } && seen.add(w.lowercase())) out.add(w)
        }
        return out
    }
}
