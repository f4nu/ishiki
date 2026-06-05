package io.github.f4nu.ishiki

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny in-memory log buffer surfaced in MainActivity — HTTP calls, errors, lifecycle.
 * Lives as long as the process (the foreground service keeps it alive).
 */
object Logs {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Set by MainActivity while visible so the textarea refreshes on each append. */
    @Volatile
    var onChange: (() -> Unit)? = null

    @Synchronized
    fun add(msg: String) {
        lines.addLast("${fmt.format(Date())}  $msg")
        while (lines.size > MAX) lines.removeFirst()
        onChange?.invoke()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}
