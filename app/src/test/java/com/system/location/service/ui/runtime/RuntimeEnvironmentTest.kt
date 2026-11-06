package com.system.location.service.ui.runtime

import com.system.location.service.R
import com.system.location.service.core.backend.BackendType
import org.junit.Assert.*
import org.junit.Test

class RuntimeEnvironmentTest {
    @Test fun standardModeDoesNotReportOptionalRootOrXposedAsProblems() {
        val environment = RuntimeEnvironment(false, false, true, true, true)
        assertTrue(environment.issues(BackendType.MOCK_PROVIDER).isEmpty())
    }

    @Test fun hookModeDoesNotRequireMockAppSelection() {
        val environment = RuntimeEnvironment(false, true, false, true, true)
        assertTrue(environment.issues(BackendType.XPOSED).isEmpty())
        assertEquals(listOf(R.string.runtime_issue_root), environment.issues(BackendType.NATIVE))
    }

    @Test fun missingRequirementsAreSpecificToTheSelectedMode() {
        val environment = RuntimeEnvironment(false, false, false, false, false)
        assertEquals(listOf(R.string.runtime_issue_permission, R.string.runtime_issue_location,
            R.string.runtime_issue_mock), environment.issues(BackendType.MOCK_PROVIDER))
        assertEquals(listOf(R.string.runtime_issue_permission, R.string.runtime_issue_location,
            R.string.runtime_issue_xposed), environment.issues(BackendType.XPOSED))
    }
}
