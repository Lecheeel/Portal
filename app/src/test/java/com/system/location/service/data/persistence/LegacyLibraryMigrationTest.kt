package com.system.location.service.data.persistence

import com.system.location.service.data.repository.LegacyLibraryMigration
import org.junit.Assert.*
import org.junit.Test

class LegacyLibraryMigrationTest {
    @Test fun validRecordsMigrateWithStableIdsWhileBadRecordsAreReported() {
        val routes = """[{"name":"路线","route":[{"first":25.123456789123,"second":119.123456789123},{"first":25.2,"second":119.2}]},{"name":"broken","route":[]}]"""
        val raw = setOf("place,address,25.123456789123,119.123456789123", ",,,")
        val first = LegacyLibraryMigration.decode(routes, raw)
        assertEquals(first, LegacyLibraryMigration.decode(routes, raw))
        assertEquals(1, first.routes.size); assertEquals(1, first.locations.size); assertEquals(2, first.issues.size)
        assertEquals(25.123456789123.toBits(), first.locations.single().coordinate.latitude.toBits())
    }
    @Test fun unknownSchemaIsReportedWithoutInventingRecords() {
        val result = LegacyLibraryMigration.decode("""{"schemaVersion":7,"routes":[]}""", emptySet())
        assertTrue(result.routes.isEmpty()); assertEquals(1, result.issues.size)
    }
}
