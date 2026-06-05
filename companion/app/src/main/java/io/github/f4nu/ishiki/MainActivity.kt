package io.github.f4nu.ishiki

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.TextView

/**
 * Minimal UI: requests the AnkiDroid permission, starts the bridge service, shows status,
 * and a live log of the HTTP calls served. Everything else happens in [BridgeService].
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logview: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        logview = findViewById(R.id.logview)
        logview.movementMethod = ScrollingMovementMethod()
        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        renderLog()
        Logs.onChange = { runOnUiThread { renderLog() } }
    }

    override fun onPause() {
        Logs.onChange = null
        super.onPause()
    }

    private fun renderLog() {
        logview.text = Logs.text()
        // auto-scroll to the newest line
        logview.post {
            val layout = logview.layout ?: return@post
            val y = layout.getLineBottom(logview.lineCount - 1) - logview.height
            logview.scrollTo(0, if (y > 0) y else 0)
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Anki.PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else maybeStart()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        maybeStart()
        refresh()
    }

    private fun ankiInstalled(): Boolean = try {
        packageManager.getPackageInfo(Anki.PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun hasAnkiPermission(): Boolean =
        checkSelfPermission(Anki.PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun maybeStart() {
        if (!ankiInstalled() || !hasAnkiPermission()) return
        val i = Intent(this, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    private fun refresh() {
        status.text = buildString {
            append("Ishiki companion\n\n")
            append("AnkiDroid installed: ").append(if (ankiInstalled()) "yes" else "NO — install AnkiDroid").append('\n')
            append("Permission: ").append(if (hasAnkiPermission()) "granted" else "NOT granted").append('\n')
            append("Server: http://127.0.0.1:").append(BridgeService.PORT).append("\n\n")
            append("In the Pebble app, set the Anki watchapp's\nBackend URL to:\nhttp://127.0.0.1:").append(BridgeService.PORT)
        }
    }
}
