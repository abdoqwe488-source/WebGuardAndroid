package com.fastdownload.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private var lightMode = false
    private lateinit var urlInput: EditText
    private lateinit var info: TextView
    private lateinit var items: LinearLayout
    private lateinit var sizeText: TextView
    private lateinit var status: TextView
    private var currentDownloadId = -1L
    private val handler = Handler(Looper.getMainLooper())
    private val downloads by lazy { getSystemService(DOWNLOAD_SERVICE) as DownloadManager }
    private val poller = object : Runnable {
        override fun run() { updateProgress(); handler.postDelayed(this, 600L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lightMode = getPreferences(Context.MODE_PRIVATE).getBoolean("light_mode", false)
        buildUi()
        handler.post(poller)
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        super.onDestroy()
    }

    private fun buildUi() {
        val bg = if (lightMode) 0xFFF4F7FB.toInt() else 0xFF0B1220.toInt()
        val panel = if (lightMode) 0xFFFFFFFF.toInt() else 0xFF172033.toInt()
        val fg = if (lightMode) 0xFF172033.toInt() else 0xFFF3F4F6.toInt()
        val muted = if (lightMode) 0xFF64748B.toInt() else 0xFF9CA3AF.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "↓"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(-1)
            setBackgroundColor(0xFF2563EB.toInt())
            setPadding(dp(13), dp(7), dp(13), dp(7))
        })
        header.addView(TextView(this).apply {
            text = "  Fast Download\n  FAST • SIMPLE • POWERFUL"
            textSize = 18f
            setTextColor(fg)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply {
            text = if (lightMode) "Dark" else "Light"
            setOnClickListener {
                lightMode = !lightMode
                getPreferences(Context.MODE_PRIVATE).edit().putBoolean("light_mode", lightMode).apply()
                buildUi()
            }
        })
        content.addView(header)
        space(content, 22)

        content.addView(label("Download", fg, 25f))
        content.addView(label("Paste a media URL or a YouTube link.", muted, 14f))
        space(content, 12)

        urlInput = EditText(this).apply {
            hint = "https://..."
            textSize = 14f
            minLines = 2
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(fg)
            setHintTextColor(muted)
            setBackgroundColor(panel)
        }
        content.addView(urlInput, matchWrap())
        space(content, 8)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "Paste"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) urlInput.setText(clip.getItemAt(0).coerceToText(this@MainActivity))
                else toast("Clipboard is empty")
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(Button(this).apply {
            text = "Analyze link"
            setOnClickListener { analyzeLink() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(actions)
        space(content, 10)

        info = label("Link information will appear here.", fg, 15f)
        info.setPadding(dp(12), dp(12), dp(12), dp(12))
        info.setBackgroundColor(panel)
        content.addView(info, matchWrap())
        sizeText = label("File size: not checked", muted, 13f)
        content.addView(sizeText)
        space(content, 14)

        content.addView(label("Playlist / Mix", fg, 19f))
        content.addView(label("Use All or type item numbers like 1,2,8,30. Listing YouTube playlist items requires an API key.", muted, 13f))
        space(content, 6)
        content.addView(EditText(this).apply {
            hint = "All or 1,2,8,30"
            setTextColor(fg)
            setHintTextColor(muted)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setBackgroundColor(panel)
        }, matchWrap())
        space(content, 8)
        items = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(panel)
        }
        content.addView(items, matchWrap())
        space(content, 14)

        content.addView(label("Download", fg, 19f))
        content.addView(label("This version downloads direct media-file URLs. It does not extract YouTube streams. For YouTube, use the official app or a download option provided by the content owner.", muted, 13f))
        space(content, 8)
        content.addView(Button(this).apply {
            text = "Download direct file"
            setOnClickListener { startDownload() }
        }, matchWrap())
        status = label("Ready", muted, 13f)
        content.addView(status)
        content.addView(label("↓  Download                         ⚙  Settings", muted, 14f))
        setContentView(root)
    }

    private fun analyzeLink() {
        val text = urlInput.text.toString().trim()
        val uri = runCatching { Uri.parse(text) }.getOrNull()
        if (uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
            toast("Enter a valid http or https URL")
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlInput.windowToken, 0)
        val host = uri.host!!.lowercase()
        if (host.contains("youtube.com") || host == "youtu.be" || host.endsWith(".youtube.com")) {
            val playlistId = uri.getQueryParameter("list")
            val videoId = uri.getQueryParameter("v") ?: if (host == "youtu.be") uri.lastPathSegment else null
            items.removeAllViews()
            if (!playlistId.isNullOrBlank()) {
                info.text = "YouTube playlist detected\nPlaylist ID: " + playlistId
                addItem("Open playlist in YouTube", "Open") {
                    openUrl("https://www.youtube.com/playlist?list=" + Uri.encode(playlistId))
                }
                addItem("Item listing is not available in this build", "Open") {
                    openUrl("https://www.youtube.com/playlist?list=" + Uri.encode(playlistId))
                }
            } else if (!videoId.isNullOrBlank()) {
                info.text = "YouTube video detected\nVideo ID: " + videoId
                addItem("Open video in YouTube", "Open") {
                    openUrl("https://www.youtube.com/watch?v=" + Uri.encode(videoId))
                }
            } else {
                info.text = "YouTube URL detected. A video or playlist ID was not found."
            }
            sizeText.text = "Size: unavailable for YouTube in this build"
            status.text = "YouTube link recognized. Direct stream downloading is not enabled."
        } else {
            info.text = "Direct media URL detected\nHost: " + host + "\nThe URL must point to an actual downloadable file."
            items.removeAllViews()
            addItem("Download this exact file", "Select") { startDownload() }
            checkSize(uri)
        }
    }

    private fun checkSize(uri: Uri) {
        sizeText.text = "Checking file size..."
        thread {
            var result = "File size: unavailable (server may not report it)"
            try {
                val conn = URL(uri.toString()).openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = true
                conn.connect()
                val length = conn.getHeaderFieldLong("Content-Length", -1L)
                if (length >= 0L) result = "File size: " + bytes(length)
                conn.disconnect()
            } catch (_: Exception) { }
            runOnUiThread { sizeText.text = result }
        }
    }

    private fun startDownload() {
        val text = urlInput.text.toString().trim()
        val uri = runCatching { Uri.parse(text) }.getOrNull()
        if (uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
            toast("Paste a valid direct file URL first")
            return
        }
        val host = uri.host!!.lowercase()
        if (host.contains("youtube.com") || host == "youtu.be" || host.endsWith(".youtube.com")) {
            toast("YouTube stream download is not available in this version")
            return
        }
        val raw = uri.lastPathSegment?.takeIf { it.contains(".") && it.length <= 160 }
            ?: ("fast_download_" + System.currentTimeMillis() + ".bin")
        val safeName = raw.replace(Regex("""[\\/:*?"<>|]"""), "_")
        if (hasDuplicate(safeName)) {
            AlertDialog.Builder(this)
                .setTitle("Duplicate file")
                .setMessage("A completed download with this name exists: " + safeName + "\nDownload again using a new name?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Download again") { _, _ -> enqueue(uri, uniqueName(safeName)) }
                .show()
        } else enqueue(uri, safeName)
    }

    private fun hasDuplicate(name: String): Boolean {
        return try {
            val c = downloads.query(DownloadManager.Query())
            c.use {
                val title = it.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val state = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                while (it.moveToNext()) {
                    if (title >= 0 && state >= 0 && it.getString(title) == name &&
                        it.getInt(state) == DownloadManager.STATUS_SUCCESSFUL) return true
                }
            }
            false
        } catch (_: Exception) { false }
    }

    private fun uniqueName(name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        return base + "_" + System.currentTimeMillis() + ext
    }

    private fun enqueue(uri: Uri, name: String) {
        try {
            val request = DownloadManager.Request(uri)
                .setTitle(name)
                .setDescription("Fast Download")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            currentDownloadId = downloads.enqueue(request)
            status.text = "Queued: " + name
            toast("Download added")
        } catch (e: Exception) {
            status.text = "Download error: " + (e.message ?: "unknown error")
        }
    }

    private fun updateProgress() {
        if (currentDownloadId < 0L) return
        try {
            val c = downloads.query(DownloadManager.Query().setFilterById(currentDownloadId))
            c.use {
                if (!it.moveToFirst()) return
                val stateCol = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val nowCol = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalCol = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val state = if (stateCol >= 0) it.getInt(stateCol) else -1
                val now = if (nowCol >= 0) it.getLong(nowCol) else 0L
                val total = if (totalCol >= 0) it.getLong(totalCol) else -1L
                status.text = when (state) {
                    DownloadManager.STATUS_SUCCESSFUL -> "Download complete • " + bytes(now)
                    DownloadManager.STATUS_FAILED -> "Download failed. Check the link and network."
                    DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                        if (total > 0L) "Downloading: " + ((now * 100L) / total).coerceIn(0L, 100L) + "% • " + bytes(now) + " / " + bytes(total)
                        else "Downloading: " + bytes(now)
                    }
                    DownloadManager.STATUS_PAUSED -> "Download paused by system"
                    else -> status.text
                }
            }
        } catch (_: Exception) { }
    }

    private fun addItem(text: String, button: String, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(label(text, if (lightMode) 0xFF172033.toInt() else 0xFFE5E7EB.toInt(), 13f),
            LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { this.text = button; setOnClickListener { action() } })
        items.addView(row)
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { toast("No app available to open the link") }
    }

    private fun label(text: String, color: Int, size: Float) = TextView(this).apply {
        this.text = text; setTextColor(color); textSize = size
    }
    private fun space(parent: LinearLayout, h: Int) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h))) }
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun bytes(v: Long): String {
        if (v < 1024L) return v.toString() + " B"
        val kb = v / 1024.0
        if (kb < 1024.0) return DecimalFormat("#,##0.0").format(kb) + " KB"
        val mb = kb / 1024.0
        if (mb < 1024.0) return DecimalFormat("#,##0.0").format(mb) + " MB"
        return DecimalFormat("#,##0.0").format(mb / 1024.0) + " GB"
    }
}
