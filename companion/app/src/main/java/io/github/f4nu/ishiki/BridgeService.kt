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
            } catch (e: Exception) {
                it.stop()
                server = null
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        server?.stop()
        server = null
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
                    session.method == Method.GET && session.uri == "/decks" ->
                        ok(repo.decks())

                    session.method == Method.GET && session.uri == "/cards" -> {
                        val deckId = session.parameters["deckId"]?.firstOrNull()
                            ?: return error(Response.Status.BAD_REQUEST, "deckId required")
                        ok(repo.cards(deckId))
                    }

                    session.method == Method.POST && session.uri == "/review" -> {
                        val body = readBody(session)
                        val o = JSONObject(if (body.isBlank()) "{}" else body)
                        val cardId = o.optString("cardId")
                        if (cardId.isBlank()) return error(Response.Status.BAD_REQUEST, "cardId required")
                        val ease = o.optInt("rating", o.optInt("ease", 0))
                        val timeTaken = o.optLong("timeTaken", 0L)
                        repo.review(cardId, ease, timeTaken)
                        ok(JSONObject().put("ok", true))
                    }

                    else -> error(Response.Status.NOT_FOUND, "not found")
                }
            } catch (e: SecurityException) {
                error(SERVICE_UNAVAILABLE, "AnkiDroid permission not granted")
            } catch (e: Exception) {
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
