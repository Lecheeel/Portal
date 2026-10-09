package com.system.location.service.core

import com.system.location.service.core.gpx.GpxCodec
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.scenario.Route
import org.junit.Assert.*
import org.junit.Test

class GpxCodecTest {
    @Test fun roundTripPreservesCoordinatesNamesAndSeparateTracks() {
        val routes = listOf(Route("a", "A & <路线>", listOf(Wgs84(25.1234567890123, 119.1), Wgs84(25.2, 119.2))),
            Route("b", "B", listOf(Wgs84(-30.0, 150.0), Wgs84(-30.1, 150.1))))
        val parsed = GpxCodec.decode(GpxCodec.encode(routes))
        assertEquals(routes.map { it.points }, parsed.map { it.points })
        assertEquals(routes.map { it.name }, parsed.map { it.name })
    }
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
