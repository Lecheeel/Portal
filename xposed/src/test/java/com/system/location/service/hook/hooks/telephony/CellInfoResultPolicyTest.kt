package com.system.location.service.hook.hooks.telephony

import com.system.location.service.core.environment.CellTower
import com.system.location.service.core.environment.CellType
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentSourceMode
import com.system.location.service.core.environment.EnvironmentSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CellInfoResultPolicyTest {
    @Test
    fun protectedCallsNeverReturnFrameworkRecordsWhenSynthesisIsUnavailable() {
        val frameworkRecords = listOf("real-cell")

        val result = selectCellInfoResult<String>(
            protectedCall = true,
            frameworkResult = frameworkRecords,
            synthetic = emptyList(),
        )

        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun unprotectedCallsKeepTheFrameworkResult() {
        val frameworkResult = listOf("framework-cell")

        val result = selectCellInfoResult<String>(
            protectedCall = false,
            frameworkResult = frameworkResult,
            synthetic = listOf("synthetic-cell"),
        )

        assertSame(frameworkResult, result)
    }

    @Test
    fun protectedCallsUseOnlySyntheticRecords() {
        val synthetic = listOf("synthetic-cell")

        val result = selectCellInfoResult(
            protectedCall = true,
            frameworkResult = listOf("real-cell"),
            synthetic = synthetic,
        )

        assertSame(synthetic, result)
    }

    @Test
    fun synthesisFailsAsAWholeWhenOneRecordCannotBeBuilt() {
        val built = buildAllOrNothing(listOf(1, 2, 3)) { value ->
            value.takeIf { it != 2 }
        }

        assertEquals(emptyList<Int>(), built)
    }

    @Test
    fun synthesisKeepsEveryRecordWhenAllBuildsSucceed() {
        val built = buildAllOrNothing(listOf(1, 2, 3)) { value -> value * 10 }

        assertEquals(listOf(10, 20, 30), built)
    }

    @Test
    fun proceduralModeDoesNotDeriveTargetCellsFromDeviceSimIdentity() {
        val profile = profileWithCell()

        assertNull(selectExplicitCellRecords(EnvironmentSourceMode.PROCEDURAL, profile))
        assertNull(selectExplicitCellRecords(EnvironmentSourceMode.AUTO, null))
    }

    @Test
    fun replayModeUsesOnlyExplicitCellRecords() {
        val profile = profileWithCell()

        assertEquals(
            ExplicitCellRecords(profile.cells, profile.cdmaCells),
            selectExplicitCellRecords(EnvironmentSourceMode.REPLAY, profile),
        )
    }

    private fun profileWithCell() = EnvironmentProfile(
        source = EnvironmentSourceType.REPLAY,
        generatedAtMillis = 1L,
        seed = 1L,
        cells = listOf(
            CellTower(
                type = CellType.LTE,
                mcc = 310,
                mnc = 260,
                areaCode = 10,
                cellId = 20,
                rsrp = -90,
            ),
        ),
    )

    @Test
    fun asyncCellInfoReportsUnsupportedInsteadOfReturningAnEmptyOrRealList() {
        assertEquals(CellInfoCallbackDecision.Unsupported, cellInfoCallbackDecision(true, emptyList<String>()))
    }

    @Test
    fun asyncCellInfoPreservesFrameworkResultForExplicitlyDeniedRoute() {
        assertEquals(CellInfoCallbackDecision.PassThrough, cellInfoCallbackDecision(false, listOf("real-cell")))
    }

    @Test
    fun asyncCellInfoDeliversOnlyTheSyntheticList() {
        val synthetic = listOf("synthetic-cell")
        var delivered: List<String>? = null
        var reportedUnavailable = false
        var passedThrough = false

        val result = dispatchCellInfoCallback(
            protectedCall = true,
            synthetic = synthetic,
            passThrough = { passedThrough = true; "framework" },
            deliver = { delivered = it; "delivered" },
            unavailable = { reportedUnavailable = true; "unsupported" },
        )

        assertEquals("delivered", result)
        assertSame(synthetic, delivered)
        assertEquals(false, reportedUnavailable)
        assertEquals(false, passedThrough)
    }

    @Test
    fun asyncCellInfoUsesErrorPathWhenSynthesisIsUnavailable() {
        var delivered = false
        var reportedUnavailable = false
        var passedThrough = false

        val result = dispatchCellInfoCallback(
            protectedCall = true,
            synthetic = emptyList<String>(),
            passThrough = { passedThrough = true; "framework" },
            deliver = { delivered = true; "delivered" },
            unavailable = { reportedUnavailable = true; "unsupported" },
        )

        assertEquals("unsupported", result)
        assertEquals(false, delivered)
        assertEquals(true, reportedUnavailable)
        assertEquals(false, passedThrough)
    }

    @Test
    fun asyncCellInfoDoesNotRewriteFrameworkResultForUnprotectedRoute() {
        var delivered = false
        var reportedUnavailable = false
        var passedThrough = false

        val result = dispatchCellInfoCallback(
            protectedCall = false,
            synthetic = emptyList<String>(),
            passThrough = { passedThrough = true; "framework" },
            deliver = { delivered = true; "delivered" },
            unavailable = { reportedUnavailable = true; "unsupported" },
        )

        assertEquals("framework", result)
        assertEquals(true, passedThrough)
        assertEquals(false, delivered)
        assertEquals(false, reportedUnavailable)
    }
}
