package com.system.location.service.debug

import android.content.Context
import android.net.LocalServerSocket
import android.util.Log
import com.system.location.service.BuildConfig
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.scenario.MovementProfile
import com.system.location.service.core.scenario.Scenario
import com.system.location.service.ext.accuracy
import com.system.location.service.ext.altitude
import com.system.location.service.ext.environmentApCount
import com.system.location.service.ext.environmentSourceMode
import com.system.location.service.ext.gnssOrbital
import com.system.location.service.ext.physicalNoise
import com.system.location.service.ext.reportDuration
import com.system.location.service.ext.simReady
import com.system.location.service.ext.speed
import com.system.location.service.ext.wifiFingerprintEnabled
import com.system.location.service.runtime.ScenarioRuntime
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * P8 debug-only HTTP control surface. It is compiled into all variants for now, but only starts
 * when [BuildConfig.DEBUG] is true; the release entry point never calls [start].
 *
 * No auth: intended for a trusted lab LAN / adb-forwarded port only.
 */
object DebugHttpServer {
    const val PORT = 8765
    private const val TAG = "PortalDebug"
    private val json = Json { prettyPrint = true }
    @Volatile private var server: ServerSocket? = null
    @Volatile private var localServer: LocalServerSocket? = null
    @Volatile private var context: Context? = null

    fun start(appContext: Context) {
        if (!BuildConfig.DEBUG) return
        if (server != null || localServer != null) return
        context = appContext.applicationContext
        Thread({
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", PORT))
                server = socket
                Log.i(TAG, "debug server http://${localIp()}:$PORT (adb forward tcp:18765 localabstract:portal-debug)")
                while (!socket.isClosed) {
                    val client = socket.accept()
                    Log.d(TAG, "accepted ${client.remoteSocketAddress}")
                    Thread({ handle(client) }, "portal-debug-client").apply { isDaemon = true }.start()
                }
            } catch (error: Throwable) {
                Log.e(TAG, "debug server stopped: ${error.message}", error)
            } finally {
                server = null
            }
        }, "portal-debug").apply { isDaemon = true }.start()
        Thread({
            try {
                val local = LocalServerSocket("portal-debug")
                localServer = local
                while (true) {
                    val client = local.accept()
                    Thread({ client.use { handleStreams(it.inputStream, it.outputStream) } }, "portal-debug-local-client")
                        .apply { isDaemon = true }.start()
                }
            } catch (error: Throwable) {
                Log.e(TAG, "local debug server stopped: ${error.message}", error)
            } finally {
                localServer = null
            }
        }, "portal-debug-local").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { server?.close() }
        runCatching { localServer?.close() }
        server = null
        localServer = null
    }

    private fun handle(socket: Socket) {
        socket.use { client -> handleStreams(client.getInputStream(), client.getOutputStream()) }
    }

    private fun handleStreams(input: InputStream, output: OutputStream) {
        runCatching {
            val request = readRequest(input) ?: return
            Log.d(TAG, "${request.method} ${request.path}")
            val response = route(request)
            writeResponse(output, response.first, response.second)
        }.onFailure {
            runCatching {
                writeResponse(output, 500, """{"ok":false,"error":"${escape(it.message ?: "error")}"}""")
            }
        }
    }

    private fun route(request: Request): Pair<Int, String> = when {
        request.method == "GET" && request.path == "/" -> 200 to help()
        request.method == "GET" && request.path == "/status" -> 200 to status()
        request.method == "GET" && request.path == "/logs" -> 200 to logs(request)
        request.method == "GET" && request.path == "/targets" -> 200 to targets()
        request.method == "POST" && request.path == "/location" -> location(request.body)
        request.method == "POST" && request.path == "/scenario/start" -> location(request.body)
        request.method == "POST" && request.path == "/scenario/stop" -> stopScenario()
        request.method == "POST" && request.path == "/config" -> config(request.body)
        request.method == "POST" && request.path == "/hot_reload" -> hotReload()
        request.method == "POST" && request.path == "/shell" -> shell(request.body)
        request.method == "OPTIONS" -> 204 to ""
        else -> 404 to """{"ok":false,"error":"not found"}"""
    }

    private fun help(): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("ok", true)
        put("endpoints", buildJsonArray {
            listOf(
                "GET /status", "GET /logs?since=0&limit=200",
                "POST /location {lat,lon,speed?,altitude?,accuracy?}",
                "POST /scenario/start {lat,lon}", "POST /scenario/stop",
                "POST /config {key:value}", "POST /shell {cmd}",
            ).forEach { add(JsonPrimitive(it)) }
        })
    })

    private fun status(): String {
        val state = ScenarioRuntime.state.value
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("ok", true)
            put("app", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("revision", BuildConfig.GIT_REVISION)
            put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            put("android", android.os.Build.VERSION.RELEASE)
            put("sdk", android.os.Build.VERSION.SDK_INT)
            put("phase", state.phase.name)
            put("backend", state.backend.name)
            put("scenarioId", state.scenarioId)
            put("scenarioName", state.scenarioName)
            put("speedMps", state.configuredSpeedMps)
            put("diagnostics", ScenarioRuntime.diagnostics.value.size)
            state.sample?.let { sample ->
                put("sample", buildJsonObject {
                    put("lat", sample.latitude)
                    put("lon", sample.longitude)
                    put("altitude", sample.altitude)
                    put("accuracy", sample.accuracy)
                    put("speed", sample.speed)
                    put("bearing", sample.bearing)
                    put("timeMillis", sample.timeMillis)
                })
            }
        })
    }

    private fun logs(request: Request): String {
        val since = request.query["since"]?.toLongOrNull() ?: 0L
        val limit = (request.query["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 1000)
        val events = ScenarioRuntime.diagnostics.value.filter { it.timestamp > since }.takeLast(limit)
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("ok", true)
            put("events", buildJsonArray {
                events.forEach { event ->
                    add(buildJsonObject {
                        put("timestamp", event.timestamp)
                        put("backend", event.backend.name)
                        put("stage", event.stage)
                        put("result", event.result)
                        put("reason", event.reason)
                        put("suggestion", event.suggestion)
                    })
                }
            })
        })
    }

    private fun location(body: String): Pair<Int, String> {
        val obj = parseObject(body)
        val lat = obj["lat"]?.jsonPrimitive?.doubleOrNull
            ?: return 400 to """{"ok":false,"error":"lat required"}"""
        val lon = obj["lon"]?.jsonPrimitive?.doubleOrNull
            ?: return 400 to """{"ok":false,"error":"lon required"}"""
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return 400 to """{"ok":false,"error":"coordinate out of range"}"""
        }
        val app = context ?: return 500 to """{"ok":false,"error":"server not ready"}"""
        val speed = obj["speed"]?.jsonPrimitive?.doubleOrNull ?: app.speed
        val altitude = obj["altitude"]?.jsonPrimitive?.doubleOrNull ?: app.altitude
        val accuracy = obj["accuracy"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: app.accuracy
        app.speed = speed
        app.altitude = altitude
        app.accuracy = accuracy
        val scenario = Scenario(
            id = "debug-${System.currentTimeMillis()}",
            name = "Debug API",
            point = Wgs84(lat, lon),
            profile = MovementProfile(
                speedMps = speed,
                altitudeMeters = altitude,
                accuracyMeters = accuracy,
                intervalMs = app.reportDuration.toLong(),
            ),
        )
        val started = runBlocking {
            if (ScenarioRuntime.state.value.isActive) ScenarioRuntime.stop().await()
            ScenarioRuntime.start(scenario).await()
        }
        return if (started) 200 to status() else 500 to """{"ok":false,"error":"start failed"}"""
    }

    private fun stopScenario(): Pair<Int, String> = 200 to buildJsonObject {
        val stopped = runBlocking { ScenarioRuntime.stop().await() }
        put("ok", stopped)
        put("phase", ScenarioRuntime.state.value.phase.name)
    }.toString()

    private fun config(body: String): Pair<Int, String> {
        val obj = parseObject(body)
        val app = context ?: return 500 to """{"ok":false,"error":"server not ready"}"""
        if (obj.isEmpty()) return 400 to """{"ok":false,"error":"empty config"}"""
        obj["backend"]?.jsonPrimitive?.content?.let { name ->
            val backend = runCatching { BackendType.valueOf(name.uppercase()) }.getOrNull()
                ?: return 400 to """{"ok":false,"error":"unknown backend"}"""
            runBlocking { ScenarioRuntime.selectBackend(backend).await() }
        }
        obj["speed"]?.jsonPrimitive?.doubleOrNull?.let { app.speed = it.coerceIn(0.0, 1000.0) }
        obj["altitude"]?.jsonPrimitive?.doubleOrNull?.let { app.altitude = it.coerceIn(0.0, 20_000.0) }
        obj["accuracy"]?.jsonPrimitive?.doubleOrNull?.let { app.accuracy = it.toFloat().coerceIn(0.1f, 10_000f) }
        obj["report_interval"]?.jsonPrimitive?.jsonPrimitive?.content?.toIntOrNull()
            ?.let { app.reportDuration = it.coerceIn(50, 1000) }
        obj["environment_ap_count"]?.jsonPrimitive?.jsonPrimitive?.content?.toIntOrNull()
            ?.let { app.environmentApCount = it.coerceIn(1, 8) }
        obj["physical_noise"]?.jsonPrimitive?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            ?.let { app.physicalNoise = it }
        obj["wifi_fingerprint_enabled"]?.jsonPrimitive?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            ?.let { app.wifiFingerprintEnabled = it }
        obj["sim_ready"]?.jsonPrimitive?.jsonPrimitive?.content?.toBooleanStrictOrNull()?.let { app.simReady = it }
        obj["gnss_orbital"]?.jsonPrimitive?.jsonPrimitive?.content?.toBooleanStrictOrNull()?.let { app.gnssOrbital = it }
        obj["environment_source"]?.jsonPrimitive?.content?.let { app.environmentSourceMode = it }
        val applied = ScenarioRuntime.applyConfigNow()
        return 200 to buildJsonObject {
            put("ok", true)
            put("applied", applied)
            put("config", buildJsonObject {
                put("backend", ScenarioRuntime.state.value.backend.name)
                put("speed", app.speed)
                put("altitude", app.altitude)
                put("accuracy", app.accuracy)
                put("report_interval", app.reportDuration)
                put("environment_ap_count", app.environmentApCount)
                put("physical_noise", app.physicalNoise)
                put("wifi_fingerprint_enabled", app.wifiFingerprintEnabled)
                put("sim_ready", app.simReady)
                put("gnss_orbital", app.gnssOrbital)
                put("environment_source", app.environmentSourceMode)
            })
        }.toString()
    }

    private fun targets(): String {
        val targets = com.system.location.service.runtime.XposedServiceBridge.runningTargets()
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("ok", true)
            put("serviceBound", com.system.location.service.runtime.XposedServiceBridge.bound())
            put("targets", buildJsonArray {
                targets.forEach { target ->
                    add(buildJsonObject {
                        put("process", target.processName)
                        put("pid", target.pid)
                        put("uid", target.uid)
                        put("state", target.state.name)
                        put("versionCode", target.loadedVersionCode)
                    })
                }
            })
        })
    }

    private fun hotReload(): Pair<Int, String> {
        val result = com.system.location.service.runtime.XposedServiceBridge.hotReloadSystemServer()
        return 200 to buildJsonObject {
            put("ok", !result.startsWith("hot reload FAILED") && !result.startsWith("hot reload timed out"))
            put("result", result)
            put("serviceBound", com.system.location.service.runtime.XposedServiceBridge.bound())
        }.toString()
    }

    private fun shell(body: String): Pair<Int, String> {        val command = parseObject(body)["cmd"]?.jsonPrimitive?.content
            ?: return 400 to """{"ok":false,"error":"cmd required"}"""
        if (command.isBlank()) return 400 to """{"ok":false,"error":"empty cmd"}"""
        val process = runCatching {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        }.getOrElse { return 500 to """{"ok":false,"error":"${escape(it.message ?: "spawn failed")}"}""" }
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return 504 to """{"ok":false,"error":"timeout"}"""
        }
        val output = process.inputStream.bufferedReader().readText().take(65_536)
        return 200 to buildJsonObject {
            put("ok", process.exitValue() == 0)
            put("exit", process.exitValue())
            put("output", output)
        }.toString()
    }

    private fun parseObject(body: String): Map<String, kotlinx.serialization.json.JsonElement> =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { return emptyMap() }

    private fun localIp(): String {
        val candidates = Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { Collections.list(it.inetAddresses) }
            .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
        return candidates.firstOrNull()?.hostAddress ?: "127.0.0.1"
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
        val query = target.substringAfter('?', "").split('&').mapNotNull {
            if (it.isBlank()) null else it.substringBefore('=') to it.substringAfter('=', "")
        }.toMap()
        return Request(first[0], path, query, String(body, 0, read, Charsets.UTF_8))
    }

    private fun writeResponse(output: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 404 -> "Not Found"
            500 -> "Internal Server Error"; 504 -> "Gateway Timeout"; else -> "OK"
        }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Headers: content-type\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val body: String,
    )
}
