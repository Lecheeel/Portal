package com.system.location.service.detector

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Debug-only detector HTTP bridge (port 8766): GET /status, GET /report, POST /run.
 * No auth; intended for the lab LAN or adb forward only.
 */
object DetectorDebugServer {
    private const val TAG = "DetectorDebug"
    private const val PORT = 8766
    @Volatile private var server: ServerSocket? = null
    @Volatile private var appContext: android.content.Context? = null

    fun start(context: android.content.Context) {
        if (server != null) return
        appContext = context.applicationContext
        Thread({
            runCatching {
                val socket = ServerSocket(PORT)
                server = socket
                Log.i(TAG, "detector debug server http://0.0.0.0:$PORT")
                while (!socket.isClosed) {
                    val client = socket.accept()
                    Thread({ handle(client) }, "detector-debug-client").apply { isDaemon = true }.start()
                }
            }.onFailure { Log.e(TAG, "detector debug server stopped", it) }
        }, "detector-debug").apply { isDaemon = true }.start()
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            runCatching {
                val request = readRequest(socket.getInputStream()) ?: return
                val response = route(request)
                writeResponse(socket.getOutputStream(), response.first, response.second)
            }.onFailure {
                runCatching {
                    writeResponse(socket.getOutputStream(), 500, """{"ok":false,"error":"${escape(it.message ?: "error")}"}""")
                }
            }
        }
    }

    private fun route(request: Request): Pair<Int, String> = when {
        request.method == "GET" && request.path == "/" -> 200 to """{"ok":true,"endpoints":["GET /status","GET /report","POST /run {sampleSeconds?}"]}"""
        request.method == "GET" && request.path == "/status" -> 200 to status()
        request.method == "GET" && request.path == "/report" -> {
            val json = DetectorRunner.lastReportJson
            if (json == null) 404 to """{"ok":false,"error":"no report yet"}""" else 200 to json
        }
        request.method == "POST" && request.path == "/run" -> run(request.body)
        else -> 404 to """{"ok":false,"error":"not found"}"""
    }

    private fun status(): String {
        val report = DetectorRunner.lastReportJson
        return JSONObject().apply {
            put("ok", true)
            put("running", DetectorRunner.isRunning)
            put("hasReport", report != null)
            put("generatedAtMillis", DetectorRunner.lastGeneratedAtMillis)
        }.toString()
    }

    private fun run(body: String): Pair<Int, String> {
        val context = appContext ?: return 500 to """{"ok":false,"error":"server not ready"}"""
        val settings = DetectorSettings.get(context)
        val requested = runCatching {
            if (body.isBlank()) null else JSONObject(body).optInt("sampleSeconds", 0).takeIf { it > 0 }
        }.getOrNull()
        val previous = settings.sampleSeconds
        if (requested != null) settings.sampleSeconds = requested
        return try {
            val report = DetectorRunner.run(context, settings)
                ?: return 409 to """{"ok":false,"error":"detector already running"}"""
            val json = DetectorRunner.lastReportJson ?: report.toJson()
            200 to json
        } finally {
            if (requested != null) settings.sampleSeconds = previous
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val header = ByteArrayOutputStream()
        var state = 0
        while (state < 4) {
            val value = input.read()
            if (value == -1) return null
            header.write(value)
            state = when {
                state == 0 && value == '\r'.code -> 1
                state == 1 && value == '\n'.code -> 2
                state == 2 && value == '\r'.code -> 3
                state == 3 && value == '\n'.code -> 4
                else -> 0
            }
            if (header.size() > 64 * 1024) error("headers too large")
        }
        val lines = header.toString(Charsets.UTF_8.name()).split("\r\n")
        val first = lines.firstOrNull()?.split(" ") ?: return null
        if (first.size < 2) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > 1024 * 1024) error("body too large")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count <= 0) break
            read += count
        }
        val target = first[1]
        val path = target.substringBefore('?')
        return Request(first[0], path, String(body, 0, read, Charsets.UTF_8))
    }

    private fun writeResponse(output: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 409 -> "Conflict"; 500 -> "Internal Server Error"; else -> "OK"
        }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private data class Request(val method: String, val path: String, val body: String)
}
