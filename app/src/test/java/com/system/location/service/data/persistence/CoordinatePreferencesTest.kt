package com.system.location.service.data.persistence

import com.system.location.service.ui.mock.HistoricalRoute
import com.system.location.service.ui.mock.RouteJson
import org.junit.Assert.*
import org.junit.Test

class CoordinatePreferencesTest {
    @Test fun doubleSurvivesSaveAndNewReaderExactly() {
        val storage = mutableMapOf<String, Any>()
        fun reader() = CoordinatePreferences({ storage.toMap() }) { storage.putAll(it) }
        val lat = 25.123456789012345
        val lon = 119.12345678901234
        reader().set(CoordinatePreferences.LATITUDE, lat)
        reader().set(CoordinatePreferences.LONGITUDE, lon)
        assertEquals(lat.toBits(), reader().get(CoordinatePreferences.LATITUDE).toBits())
        assertEquals(lon.toBits(), reader().get(CoordinatePreferences.LONGITUDE).toBits())
        assertEquals(1, storage[CoordinatePreferences.VERSION])
    }

    @Test fun migratesBothLegacyFloatsWithoutRequantizingNewWrites() {
        val storage = mutableMapOf<String, Any>("last_known_lat" to 25.12345f, "last_known_lng" to 119.12345f)
        val preferences = CoordinatePreferences({ storage.toMap() }) { storage.putAll(it) }
        assertEquals(25.12345f.toDouble(), preferences.get("last_known_lat"), 0.0)
        assertTrue(storage["last_known_lng"] is String)
        preferences.set("last_known_lat", 25.123456789012345)
        assertEquals(25.123456789012345, preferences.get("last_known_lat"), 0.0)
        assertThrows(IllegalArgumentException::class.java) { preferences.set("last_known_lat", Double.NaN) }
    }

    @Test fun routeVersionAndDoubleCoordinatesRoundTripAndLegacyMigration() {
        val route = HistoricalRoute("精度", listOf(25.123456789012345 to 119.12345678901234, 25.123456789012346 to 119.12345678901235))
        val json = RouteJson.encodeRoutes(listOf(route))
        assertTrue(json.contains("\"schemaVersion\":1"))
        assertEquals(route, RouteJson.decodeRoutes(json).single())
        assertEquals(emptyList<HistoricalRoute>(), RouteJson.decodeRoutes("[]"))
        assertThrows(IllegalArgumentException::class.java) { RouteJson.decodeRoutes("{\"schemaVersion\":2,\"routes\":[]}") }
    }
}
