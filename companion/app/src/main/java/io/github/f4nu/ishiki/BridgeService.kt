package io.github.f4nu.ishiki

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import org.json.JSONObject

/**
 * Foreground service that runs a localhost HTTP server PebbleKit JS can call.
 *
 *   GET  /decks                 -> [{id, name, due}]
 *   GET  /cards?deckId=<id>      -> [{id:"noteId:ord", front, back}]
 *   POST /review {cardId, ease|rating, timestamp, timeTaken?} -> {ok:true}
 */
class BridgeService : Service() {

    private lateinit var repo: AnkiRepository
    private var server: Httpd? = null

    override fun onCreate() {
        super.onCreate()
        repo = AnkiRepository(this)
        startForeground(NOTIF_ID, buildNotification())
        server = Httpd(PORT, repo).also {
            try {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Logs.add("server listening on 127.0.0.1:$PORT")
            } catch (e: Exception) {
                it.stop()
                server = null
                Logs.add("ERR server failed to start: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        server?.stop()
        server = null
        Logs.add("server stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Ishiki bridge", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Ishiki")
            .setContentText("Serving AnkiDroid on 127.0.0.1:$PORT")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    /** NanoHTTPD bound to loopback only — PebbleKit JS runs on the same phone. */
    private class Httpd(port: Int, val repo: AnkiRepository) : NanoHTTPD("127.0.0.1", port) {
        override fun serve(session: IHTTPSession): Response {
            return try {
                when {
                    session.method == Method.GET && session.uri == "/decks" -> {
                        val decks = repo.decks()
                        Logs.add("GET /decks -> ${decks.length()} decks")
                        ok(decks)
                    }

                    session.method == Method.GET && session.uri == "/cards" -> {
                        val deckId = session.parameters["deckId"]?.firstOrNull()
                        if (deckId == null) {
                            Logs.add("GET /cards -> 400 (deckId required)")
                            return error(Response.Status.BAD_REQUEST, "deckId required")
                        }
                        val cards = repo.cards(deckId)
                        Logs.add("GET /cards deckId=$deckId -> ${cards.length()} cards")
                        ok(cards)
                    }

                    session.method == Method.POST && session.uri == "/review" -> {
                        val body = readBody(session)
                        val o = JSONObject(if (body.isBlank()) "{}" else body)
                        val cardId = o.optString("cardId")
                        if (cardId.isBlank()) {
                            Logs.add("POST /review -> 400 (cardId required)")
                            return error(Response.Status.BAD_REQUEST, "cardId required")
                        }
                        val ease = o.optInt("rating", o.optInt("ease", 0))
                        val timeTaken = o.optLong("timeTaken", 0L)
                        repo.review(cardId, ease, timeTaken)
                        Logs.add("POST /review $cardId ease=$ease")
                        ok(JSONObject().put("ok", true))
                    }

                    else -> {
                        Logs.add("${session.method} ${session.uri} -> 404")
                        error(Response.Status.NOT_FOUND, "not found")
                    }
                }
            } catch (e: SecurityException) {
                Logs.add("ERR ${session.uri}: AnkiDroid permission not granted")
                error(SERVICE_UNAVAILABLE, "AnkiDroid permission not granted")
            } catch (e: Exception) {
                Logs.add("ERR ${session.uri}: ${e.message}")
                error(Response.Status.INTERNAL_ERROR, e.message ?: "error")
            }
        }

        private fun readBody(session: IHTTPSession): String {
            val map = HashMap<String, String>()
            session.parseBody(map)
            return map["postData"] ?: ""
        }

        private fun ok(payload: Any): Response =
            newFixedLengthResponse(Response.Status.OK, "application/json", payload.toString())

        private fun error(status: Response.IStatus, msg: String): Response =
            newFixedLengthResponse(
                status, "application/json",
                JSONObject().put("error", msg).toString()
            )

        companion object {
            // NanoHTTPD has no 503 constant; provide one.
            private val SERVICE_UNAVAILABLE = object : Response.IStatus {
                override fun getRequestStatus() = 503
                override fun getDescription() = "503 Service Unavailable"
            }
        }
    }

    companion object {
        const val PORT = 8765
        private const val CHANNEL = "ishiki_bridge"
        private const val NOTIF_ID = 1
    }
}
