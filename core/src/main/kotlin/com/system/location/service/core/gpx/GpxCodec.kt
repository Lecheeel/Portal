package com.system.location.service.core.gpx

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.scenario.Route
import java.io.StringReader
import java.util.UUID
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/** GPX is WGS84. Separate tracks/segments remain separate routes, never joined across gaps. */
object GpxCodec {
    const val MAX_CHARS = 20_000_000
    fun decode(text: String): List<Route> {
        require(text.length <= MAX_CHARS) { "GPX 文件过大" }
        require(!text.contains("<!DOCTYPE", ignoreCase = true) && !text.contains("<!ENTITY", ignoreCase = true)) { "GPX 不允许 DTD 或实体声明" }
        val routes = mutableListOf<Route>()
        val stack = mutableListOf<String>()
        var points: MutableList<Wgs84>? = null
        var trackName = ""
        var routeName = ""
        var nameText: StringBuilder? = null
        var totalPoints = 0
        var rootSeen = false
        val handler = object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("External entities disabled")
            override fun startElement(uri: String?, localName: String, qName: String?, attrs: Attributes) {
                require(stack.size < 64) { "GPX 嵌套过深" }
                if (stack.isEmpty()) { require(localName == "gpx") { "不是 GPX 文件" }; rootSeen = true }
                val tag = if (uri.isNullOrEmpty() || uri == "http://www.topografix.com/GPX/1/1" || uri == "http://www.topografix.com/GPX/1/0") localName else "#extension"
                val parent = stack.lastOrNull()
                when {
                    tag == "trk" && stack == listOf("gpx") -> trackName = ""
                    tag == "rte" && stack == listOf("gpx") -> { points = mutableListOf(); routeName = "" }
                    tag == "trkseg" && stack == listOf("gpx", "trk") -> { points = mutableListOf(); routeName = trackName }
                    tag == "name" && stack.size == 2 && parent in listOf("trk", "rte") -> nameText = StringBuilder()
                    (tag == "trkpt" && stack == listOf("gpx", "trk", "trkseg") || tag == "rtept" && stack == listOf("gpx", "rte")) -> {
                        require(++totalPoints <= 100_000) { "GPX 最多支持 100000 个点" }
                        val lat = attrs.getValue("lat")?.toDoubleOrNull() ?: error("GPX 缺少有效纬度")
                        val lon = attrs.getValue("lon")?.toDoubleOrNull() ?: error("GPX 缺少有效经度")
                        points!!.add(Wgs84(lat, lon))
                    }
                }
                stack += tag
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { nameText?.append(ch, start, length) }
            override fun endElement(uri: String?, localName: String, qName: String?) {
                if (stack.lastOrNull() == "name" && stack.size == 3 && nameText != null) {
                    val name = nameText.toString().trim().take(200)
                    if (stack.getOrNull(stack.size - 2) == "trk") trackName = name else routeName = name
                    nameText = null
                }
                if (stack == listOf("gpx", "trk", "trkseg") || stack == listOf("gpx", "rte")) {
                    val valid = points.orEmpty().filterIndexed { i, p -> i == 0 || p != points!![i - 1] }
                    if (valid.size >= 2) {
                        require(routes.size < 10_000) { "GPX 路线过多" }
                        routes += Route(UUID.randomUUID().toString(), routeName.ifBlank { "GPX 路线 ${routes.size + 1}" }, valid)
                    }
                    points = null
                }
                stack.removeAt(stack.lastIndex)
            }
            override fun error(e: org.xml.sax.SAXParseException) { throw e }
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
        }
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        // Android/JVM parsers differ in supported features; the declaration guard and resolver
        // also reject external content on platforms without these optional switches.
        listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities").forEach {
            runCatching { factory.setFeature(it, false) }
        }
        factory.newSAXParser().parse(InputSource(StringReader(text)), handler)
        require(rootSeen && routes.isNotEmpty()) { "GPX 没有含至少两个不同坐标的轨迹段或路线" }
        return routes
    }
    fun encode(routes: List<Route>): String {
        require(routes.isNotEmpty()) { "没有路线可导出" }
        fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"LocationService\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            routes.forEach { route ->
                append("<trk><name>${escape(route.name)}</name><trkseg>\n")
                route.points.forEach { append("<trkpt lat=\"${it.latitude}\" lon=\"${it.longitude}\"/>\n") }
                append("</trkseg></trk>\n")
            }
            append("</gpx>\n")
            require(length <= MAX_CHARS) { "GPX 导出文件过大" }
        }
    }
}
