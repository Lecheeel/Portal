package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class BeiDouSatelliteMetadataTest {
    private fun text(extra: String = "", block: String = "BDS-3I") = """
%=SNX 2.02 DLR 26:295:28054 DLR 78:053:00000 00:000:00000 P 00000 0
+SATELLITE/IDENTIFIER
${" C005 2010-036A  36828 " + "BDS-2I".padEnd(15) + " old"}
${" C220 2019-023A  44204 " + block.padEnd(15) + " new"}
-SATELLITE/IDENTIFIER
+SATELLITE/PRN
 C005 2010:212:00000 2026:100:43200 C06
 C220 2026:100:57600 0000:000:00000 C06
$extra
-SATELLITE/PRN
%ENDSNX
""".trimIndent()
    private val old = LocalDateTime.of(2026, 4, 10, 11, 59, 59)
    private val start = LocalDateTime.of(2026, 4, 10, 16, 0, 0)

    @Test fun assignmentUsesSvnAndHalfOpenIntervalsRatherThanPrnRange() {
        val metadata = BeiDouSatelliteMetadata.parse(text())
        assertEquals("26:295:28054", metadata.creationLabel)
        assertEquals("C005", metadata.identityAt(6, old)?.svn)
        assertNull(metadata.identityAt(6, old.plusSeconds(1)))
        assertNull(metadata.identityAt(6, start.minusSeconds(1)))
        assertEquals("C220", metadata.identityAt(6, start)?.svn)
        assertEquals(BeiDouOrbitType.IGSO, metadata.identityAt(6, start)?.orbitType)
        assertNull(metadata.identityAt(5, start))
        assertNull(metadata.identityAt(6, start.minusSeconds(1), start.plusSeconds(1)))
        assertEquals("C220", metadata.identityAt(6, start.plusSeconds(1), start.plusSeconds(60))?.svn)
    }

    @Test fun ambiguousAssignmentsAndUnknownBlocksDoNotGuessAnOrbitClass() {
        assertNull(BeiDouSatelliteMetadata.parse(text(" C005 2026:100:57600 0000:000:00000 C06"))
            .identityAt(6, start))
        assertNull(BeiDouSatelliteMetadata.parse(text(block = "BDS-NEW" )).identityAt(6, start))
        assertNull(BeiDouSatelliteMetadata.parse(text(" C220 2026:100:57600 0000:000:00000 C07"))
            .identityAt(6, start))
        assertThrows(IllegalArgumentException::class.java) {
            BeiDouSatelliteMetadata.parse(text()).identityAt(6, start, old)
        }
    }

    @Test fun truncationMissingIdentityAndMalformedEpochsFailTheWholeCatalog() {
        for (invalid in listOf(text().replace("%ENDSNX", ""),
            text().replace(" C220 2026:", " C999 2026:"), text().replace("2026:100:57600", "2026:366:57600"),
            text().replace("2026:100:57600", "2026:100:86400"), text().replace("2.02 DLR", "9.99 DLR"))) {
            assertThrows(IllegalArgumentException::class.java) { BeiDouSatelliteMetadata.parse(invalid) }
        }
    }

    @Test fun bundledRealMetadataPreservesThe2026ReassignmentsAndOriginalReleaseLabel() {
        val metadata = BeiDouSatelliteMetadata.parse(requireNotNull(javaClass.getResource("/gnss/igs-beidou-metadata.snx")).readText())
        assertEquals("26:295:28054", metadata.creationLabel)
        assertEquals(LocalDateTime.of(2026, 10, 22, 7, 47, 34), metadata.creationAt(2026))
        assertThrows(IllegalArgumentException::class.java) { metadata.creationAt(2025) }
        val before = LocalDateTime.of(2026, 1, 1, 0, 0)
        val after = LocalDateTime.of(2026, 11, 11, 3, 0)
        assertEquals("C006", metadata.identityAt(4, before)?.svn)
        assertEquals("C217", metadata.identityAt(4, after)?.svn)
        assertEquals(BeiDouOrbitType.GEO, metadata.identityAt(4, after)?.orbitType)
        assertEquals("C005", metadata.identityAt(6, before)?.svn)
        assertEquals("C220", metadata.identityAt(6, after)?.svn)
        assertEquals(BeiDouOrbitType.IGSO, metadata.identityAt(6, after)?.orbitType)
        assertNull(metadata.identityAt(59, after))
    }
}
