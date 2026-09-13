package com.system.location.service.ui.mock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RouteJsonTest {
    private val points = listOf(39.908822 to 116.397465, 39.907951 to 116.397500)
    private val route = HistoricalRoute("默认路线", points)

    @Test
    fun defaultRouteSurvivesPersistence() {
        assertEquals(listOf(route), RouteJson.decodeRoutes(RouteJson.encodeRoutes(listOf(route))))
    }

    @Test
    fun selectedRouteAndClearedSelectionRoundTrip() {
        assertEquals(route, RouteJson.decodeRoute(RouteJson.encodeRoute(route)))
        assertNull(RouteJson.decodeRoute(RouteJson.encodeRoute(null)))
    }

    @Test
    fun readsDocumentedPairCoordinatesWithoutReflection() {
        val json = """[{"name":"默认路线","route":[{"first":39.908822,"second":116.397465},{"first":39.907951,"second":116.3975}]}]"""
        assertEquals(listOf(route), RouteJson.decodeRoutes(json))
    }

    @Test
    fun editedCoordinatesAreTheCoordinatesSaved() {
        val editedPoints = RouteJson.decodePoints(
            """[{"first":31.2304,"second":121.4737},{"first":31.2305,"second":121.4738}]"""
        )
        val edited = HistoricalRoute("编辑后的路线", editedPoints)
        val stored = RouteJson.decodeRoutes(RouteJson.encodeRoutes(listOf(route) + edited))
        assertEquals(editedPoints, stored.last().route)
        assertEquals(31.2304, stored.last().route.first().first, 0.0)
    }

    @Test
    fun emptyHistoryAndDeletedLastRouteRemainEmpty() {
        assertEquals(emptyList<HistoricalRoute>(), RouteJson.decodeRoutes(""))
        assertEquals(emptyList<HistoricalRoute>(), RouteJson.decodeRoutes(RouteJson.encodeRoutes(emptyList())))
    }

    @Test
    fun unicodeNamesAndEscapesRoundTrip() {
        val named = HistoricalRoute("上海\"路线\"\\测试\n第二行", points)
        assertEquals(named, RouteJson.decodeRoute(RouteJson.encodeRoute(named)))
    }

    @Test
    fun rejectsMalformedOrInvalidCoordinates() {
        val invalidInputs = listOf(
            "not json", "null", "[]",
            """[{"first":39.9,"second":116.3}]""",
            """[{"first":91,"second":116.3},{"first":39.9,"second":116.3}]""",
            """[{"first":39.9,"second":181},{"first":39.9,"second":116.3}]""",
            """[{"first":39.9},{"first":39.9,"second":116.3}]""",
            """[{"first":null,"second":116.3},{"first":39.9,"second":116.3}]""",
            """[{"first":NaN,"second":116.3},{"first":39.9,"second":116.3}]"""
        )
        invalidInputs.forEach { json ->
            assertThrows(json, IllegalArgumentException::class.java) { RouteJson.decodePoints(json) }
        }
    }

    @Test
    fun rejectsInvalidStoredRoutesBeforePlayback() {
        listOf(
            """[{"name":"空路线","route":[]}]""",
            """[{"name":"单点","route":[{"first":39.9,"second":116.3}]}]""",
            """[{"name":" ","route":[{"first":39.9,"second":116.3},{"first":40.0,"second":116.4}]}]"""
        ).forEach { json ->
            assertThrows(json, IllegalArgumentException::class.java) { RouteJson.decodeRoutes(json) }
        }
    }

    @Test
    fun coordinateBoundariesRoundTrip() {
        val boundaries = listOf(-90.0 to -180.0, 90.0 to 180.0)
        assertEquals(boundaries, RouteJson.decodePoints(RouteJson.encodePoints(boundaries)))
    }
}
