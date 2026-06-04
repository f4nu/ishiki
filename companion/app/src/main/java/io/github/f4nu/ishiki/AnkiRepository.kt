package io.github.f4nu.ishiki

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * AnkiDroid ContentProvider contract (subset we use).
 * Verified against api/.../FlashCardsContract.kt on the AnkiDroid main branch.
 */
object Anki {
    const val AUTHORITY = "com.ichi2.anki.flashcards"
    const val PACKAGE = "com.ichi2.anki"
    const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    private val BASE: Uri = Uri.parse("content://$AUTHORITY")
    val DECKS_URI: Uri = Uri.withAppendedPath(BASE, "decks")
    val SCHEDULE_URI: Uri = Uri.withAppendedPath(BASE, "schedule")
    val NOTES_URI: Uri = Uri.withAppendedPath(BASE, "notes")

    // Deck columns
    const val DECK_ID = "deck_id"
    const val DECK_NAME = "deck_name"
    const val DECK_COUNTS = "deck_count"

    // ReviewInfo (schedule) columns
    const val NOTE_ID = "note_id"
    const val CARD_ORD = "ord"
    const val BUTTON_COUNT = "button_count"
    const val EASE = "answer_ease"
    const val TIME_TAKEN = "time_taken"

    // Card columns
    const val QUESTION_SIMPLE = "question_simple"
    const val ANSWER_SIMPLE = "answer_simple"
}

/** Reads decks/due-cards and submits reviews via AnkiDroid's ContentProvider. */
class AnkiRepository(context: Context) {

    private val cr: ContentResolver = context.applicationContext.contentResolver

    // cardId ("noteId:ord") -> button count, captured when serving /cards so we can
    // clamp the ease on /review (a 2/3-button card rejects ease=4, etc.).
    private val buttonCounts = ConcurrentHashMap<String, Int>()

    fun decks(): JSONArray {
        val arr = JSONArray()
        cr.query(Anki.DECKS_URI, null, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndex(Anki.DECK_ID)
            val nameIdx = c.getColumnIndex(Anki.DECK_NAME)
            val countIdx = c.getColumnIndex(Anki.DECK_COUNTS)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val name = c.getString(nameIdx) ?: continue
                val due = if (countIdx >= 0) sumCounts(c.getString(countIdx)) else 0
                arr.put(
                    JSONObject()
                        .put("id", id.toString())
                        .put("name", name)
                        .put("due", due)
                )
            }
        }
        return arr
    }

    fun cards(deckId: String, limit: Int = 50): JSONArray {
        val arr = JSONArray()
        // The schedule query parses "key=?" pairs; deckID temporarily selects the deck.
        val selection = "limit=?, deckID=?"
        val args = arrayOf(limit.toString(), deckId)
        cr.query(Anki.SCHEDULE_URI, null, selection, args, null)?.use { c ->
            val noteIdx = c.getColumnIndex(Anki.NOTE_ID)
            val ordIdx = c.getColumnIndex(Anki.CARD_ORD)
            val btnIdx = c.getColumnIndex(Anki.BUTTON_COUNT)
            while (c.moveToNext()) {
                val noteId = c.getLong(noteIdx)
                val ord = c.getInt(ordIdx)
                val buttons = if (btnIdx >= 0) c.getInt(btnIdx) else 4
                val cardId = "$noteId:$ord"
                buttonCounts[cardId] = buttons
                val (front, back) = cardText(noteId, ord)
                arr.put(
                    JSONObject()
                        .put("id", cardId)
                        .put("front", front)
                        .put("back", back)
                )
            }
        }
        return arr
    }

    /** ease: 1=Again .. up to the card's button count (clamped). timeTakenMs is answer duration. */
    fun review(cardId: String, ease: Int, timeTakenMs: Long) {
        val parts = cardId.split(":")
        require(parts.size == 2) { "bad cardId: $cardId" }
        val noteId = parts[0].toLong()
        val ord = parts[1].toInt()
        val buttons = buttonCounts[cardId] ?: 4
        val values = ContentValues().apply {
            put(Anki.NOTE_ID, noteId)
            put(Anki.CARD_ORD, ord)
            put(Anki.EASE, ease.coerceIn(1, buttons))
            put(Anki.TIME_TAKEN, timeTakenMs)
        }
        cr.update(Anki.SCHEDULE_URI, values, null, null)
    }

    private fun cardText(noteId: Long, ord: Int): Pair<String, String> {
        val uri = Uri.withAppendedPath(Anki.NOTES_URI, "$noteId/cards/$ord")
        cr.query(uri, arrayOf(Anki.QUESTION_SIMPLE, Anki.ANSWER_SIMPLE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                return toText(c.getString(0)) to toText(c.getString(1))
            }
        }
        return "" to ""
    }

    private fun sumCounts(json: String?): Int {
        if (json.isNullOrBlank()) return 0
        return try {
            val a = JSONArray(json)
            var s = 0
            for (i in 0 until a.length()) s += a.optInt(i)
            s
        } catch (e: Exception) {
            json.toIntOrNull() ?: 0
        }
    }

    /** Strip card HTML/markup down to plain text the watch can show. */
    private fun toText(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var s = raw
            .replace(Regex("(?is)<style.*?</style>"), "")
            .replace(Regex("(?is)<script.*?</script>"), "")
            .replace(Regex("\\[sound:[^]]+]"), "")
            .replace(Regex("\\[anki:play:[^]]*]"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|tr|h[1-6])>"), "\n")
        @Suppress("DEPRECATION")
        s = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()
        return s.replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
