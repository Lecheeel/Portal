package com.system.location.service.core

import com.system.location.service.core.gpx.GpxCodec
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.scenario.Route
import org.junit.Assert.*
import org.junit.Test

class GpxCodecTest {
    @Test fun exportRejectsXml10IllegalCharactersAndUnpairedSurrogates() {
        val illegal = (0..0x1f).filter { it !in listOf(0x9, 0xa, 0xd) }.map { it.toChar().toString() } +
            listOf("\uFFFE", "\uFFFF", "\uD800", "\uDC00", "\uD800x", "\uDC00\uD800")
        illegal.forEach { value ->
            val name = "路线${value}名称"
            val route = largeRoute("a", 2).copy(name = name)
            assertThrows("XML 1.0 must reject ${value.toCharArray().map { it.code }}",
                IllegalArgumentException::class.java) { GpxCodec.encode(listOf(route)) }
        }
    }

    @Test fun exportPreservesLegalUnicodeIncludingSupplementaryCodePointsAndXmlWhitespace() {
        val name = "路线\t\n\r & < > \" ' \u007F\u0085\uD7FF\uE000\uFFFD" +
            String(Character.toChars(0x10000)) + "🚶" + String(Character.toChars(0x10ffff)) + "终"
        val route = largeRoute("a", 2).copy(name = name)
        val xml = GpxCodec.encode(listOf(route))
        assertTrue(xml.contains("🚶"))
        assertEquals(name, GpxCodec.decode(xml).single().name)
    }

    @Test fun roundTripPreservesCoordinatesNamesAndSeparateTracks() {
        val routes = listOf(Route("a", "A & <路线>", listOf(Wgs84(25.1234567890123, 119.1), Wgs84(25.2, 119.2))),
            Route("b", "B", listOf(Wgs84(-30.0, 150.0), Wgs84(-30.1, 150.1))))
        val parsed = GpxCodec.decode(GpxCodec.encode(routes))
        assertEquals(routes.map { it.points }, parsed.map { it.points })
        assertEquals(routes.map { it.name }, parsed.map { it.name })
    }
    @Test fun twoSixtyThousandPointRoutesRoundTripTogether() {
        val routes = listOf(largeRoute("a", 60_000), largeRoute("b", 60_000))
        val decoded = GpxCodec.decode(GpxCodec.encode(routes))
        assertEquals(routes.map { it.name }, decoded.map { it.name })
        assertEquals(routes.map { it.points }, decoded.map { it.points })
    }

    @Test fun totalPointLimitIsTheSameForImportAndExport() {
        val atLimit = listOf(largeRoute("a", 100_000), largeRoute("b", 100_000))
        assertEquals(atLimit.map { it.points }, GpxCodec.decode(GpxCodec.encode(atLimit)).map { it.points })
        val overLimit = atLimit + largeRoute("c", 2)
        assertThrows(IllegalArgumentException::class.java) { GpxCodec.encode(overLimit) }
        val xml = GpxCodec.encode(atLimit).replace("</gpx>",
            "<rte><rtept lat=\"1\" lon=\"3\"/><rtept lat=\"2\" lon=\"3\"/></rte></gpx>")
        assertThrows(IllegalArgumentException::class.java) { GpxCodec.decode(xml) }
    }

    @Test fun routeCountLimitIsTheSameForImportAndExport() {
        val route = largeRoute("a", 2)
        val atLimit = List(10_000) { route }
        assertEquals(10_000, GpxCodec.decode(GpxCodec.encode(atLimit)).size)
        assertThrows(IllegalArgumentException::class.java) { GpxCodec.encode(atLimit + route) }
        val xml = GpxCodec.encode(atLimit).replace("</gpx>",
            "<rte><rtept lat=\"1\" lon=\"3\"/><rtept lat=\"2\" lon=\"3\"/></rte></gpx>")
        assertThrows(IllegalArgumentException::class.java) { GpxCodec.decode(xml) }
    }

    @Test fun eachRouteIsLimitedBeforeDuplicateCoordinatesAreRemoved() {
        val xml = "<gpx><rte>" + "<rtept lat=\"1\" lon=\"3\"/>".repeat(100_000) +
            "<rtept lat=\"2\" lon=\"3\"/></rte></gpx>"
        assertThrows(IllegalArgumentException::class.java) { GpxCodec.decode(xml) }
        val mutablePoints = largeRoute("a", 2).points.toMutableList()
        val route = Route("a", "a", mutablePoints)
        repeat(99_999) { mutablePoints += Wgs84(1.0, 3.0) }
        assertThrows(IllegalArgumentException::class.java) { GpxCodec.encode(listOf(route)) }
    }

    @Test fun exportRejectsRoutesThatWouldDisappearDuringImport() {
        val point = Wgs84(1.0, 3.0)
        val collapsed = Route("a", "a", listOf(point, point))
        assertThrows(IllegalArgumentException::class.java) { GpxCodec.encode(listOf(collapsed)) }
        assertThrows(IllegalArgumentException::class.java) { GpxCodec.encode(listOf(largeRoute("b", 2), collapsed)) }
    }

    private fun largeRoute(id: String, count: Int) = Route(id, id,
        List(count) { Wgs84(if (it % 2 == 0) 1.0 else 2.0, 3.0) })

    @Test fun extensionsCannotCloseOrInjectTrackSegments() {
        val parsed = GpxCodec.decode("""<gpx xmlns="http://www.topografix.com/GPX/1/1" xmlns:e="urn:extension"><trk><name>T</name><trkseg><trkpt lat="1" lon="2"><extensions><e:trkseg><e:trkpt lat="8" lon="9"/></e:trkseg></extensions></trkpt><trkpt lat="1.1" lon="2"/></trkseg></trk></gpx>""")
        assertEquals(listOf(Wgs84(1.0, 2.0), Wgs84(1.1, 2.0)), parsed.single().points)
    }
    @Test fun segmentsAreNotConnectedAndWaypointsAreNotInventedAsARoute() {
        val parsed = GpxCodec.decode("""<gpx><wpt lat="0" lon="0"/><trk><name>T</name><trkseg><trkpt lat="1" lon="2"/><trkpt lat="1.1" lon="2"/></trkseg><trkseg><trkpt lat="8" lon="9"/><trkpt lat="8.1" lon="9"/></trkseg></trk><rte><name>R</name><rtept lat="10" lon="20"/><rtept lat="10" lon="21"/></rte></gpx>""")
        assertEquals(3, parsed.size)
        assertEquals(Wgs84(8.0, 9.0), parsed[1].points.first())
    }
    @Test fun invalidCoordinatesMalformedXmlAndEntitiesFailTheWholeImport() {
        listOf("<gpx><rte><rtept lat=\"NaN\" lon=\"1\"/></rte></gpx>",
            "<gpx><rte><rtept lat=\"91\" lon=\"1\"/></rte></gpx>", "<gpx>",
            "<!DOCTYPE gpx [<!ENTITY x SYSTEM 'file:///secret'>]><gpx>&x;</gpx>",
            "<gpx><wpt lat=\"1\" lon=\"2\"/></gpx>").forEach { xml ->
            assertThrows(Exception::class.java) { GpxCodec.decode(xml) }
        }
    }
}
