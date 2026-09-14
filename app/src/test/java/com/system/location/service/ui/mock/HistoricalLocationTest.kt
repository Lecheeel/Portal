package com.system.location.service.ui.mock

import org.junit.Assert.*
import org.junit.Test

class HistoricalLocationTest {
    @Test fun savedLocationPreservesDoubleCoordinatesAndArbitraryText() {
        val location = HistoricalLocation("地名,\"引号\"", "地址\n第二行", 25.123456789012345, 119.12345678901234)
        val read = HistoricalLocation.fromString(location.toString())
        assertEquals(location, read)
        assertEquals(location.lat.toBits(), read.lat.toBits())
        assertEquals(location.lon.toBits(), read.lon.toBits())
    }
    @Test fun readsLegacyQuotedCsvAndEmptyAddress() {
        assertEquals(HistoricalLocation("A,\"B\"", "", 25.1, 119.2),
            HistoricalLocation.fromString("\"A,\"\"B\"\"\",\"\",25.1,119.2"))
    }
    @Test fun rejectsOldEmptyPlaceholderAndFutureSchema() {
        assertThrows(IllegalArgumentException::class.java) { HistoricalLocation.fromString(",,,") }
        assertThrows(IllegalArgumentException::class.java) { HistoricalLocation.fromString(
            """{"schemaVersion":2,"location":{"name":"x","address":"","lat":25.0,"lon":119.0}}""") }
    }
}
