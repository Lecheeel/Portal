package com.system.location.service.core.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticExportRedactorTest {
    @Test fun exportRedactsCredentialAndEnvironmentFieldsWithoutRemovingDiagnosticContext() {
        val report = DiagnosticExportRedactor.redact(
            "stage=HOOK_POINT result=INFO api_key=Abc12345 ssid=Office bssid=AA:BB:CC:DD:EE:FF cellId=12345 lat=31.2 lon=121.5",
        )

        assertTrue(report.contains("stage=HOOK_POINT result=INFO"))
        assertFalse(report.contains("Abc12345"))
        assertFalse(report.contains("Office"))
        assertFalse(report.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(report.contains("cellId=12345"))
        assertFalse(report.contains("31.2"))
        assertFalse(report.contains("121.5"))
    }

    @Test fun exportRedactsAuthorizationSchemesAndPreservesFollowingFields() {
        val report = DiagnosticExportRedactor.redact(
            "Authorization: Bearer bearer-secret; Authorization=Basic basic-secret stage=UPLOAD",
        )

        assertFalse(report.contains("bearer-secret"))
        assertFalse(report.contains("basic-secret"))
        assertTrue(report.contains("Authorization=<redacted>; Authorization=<redacted> stage=UPLOAD"))
    }

    @Test fun exportRedactsCommonSecretTokenFieldsAndUrlUserInfo() {
        val report = DiagnosticExportRedactor.redact(
            "client_secret=client-value access_token=access-value refresh-token=refresh-value secret:plain-value " +
                "request=https://user:password@example.test/private/path result=FAILED",
        )

        for (value in listOf("client-value", "access-value", "refresh-value", "plain-value", "user:password")) {
            assertFalse("Leaked $value in $report", report.contains(value))
        }
        assertTrue(report.contains("https://<redacted>@example.test/private/path"))
        assertTrue(report.contains("result=FAILED"))
    }

    @Test fun exportRedactsCommonSecretTokenFieldsInJson() {
        val report = DiagnosticExportRedactor.redact(
            "{\"client_secret\":\"client-value\",\"access_token\":\"access-value\",\"refresh-token\":\"refresh-value\",\"secret\":\"plain-value\"}",
        )

        for (value in listOf("client-value", "access-value", "refresh-value", "plain-value")) {
            assertFalse("Leaked $value in $report", report.contains(value))
        }
        assertTrue(report.contains("\"client_secret\":\"<redacted>\""))
        assertTrue(report.contains("\"access_token\":\"<redacted>\""))
        assertTrue(report.contains("\"refresh-token\":\"<redacted>\""))
        assertTrue(report.contains("\"secret\":\"<redacted>\""))
    }

    @Test fun exportLeavesOrdinaryDiagnosticTextReadable() {
        val text = "Android API 35; HOOK_POINT: installed=true; battery=80%"
        assertTrue(DiagnosticExportRedactor.redact(text).contains(text))
    }

    @Test fun exportRedactsLegacyPersistedScenarioNames() {
        val report = DiagnosticExportRedactor.redact("CONFIG_STORE INFO: 最近配置：Home address rev=42")
        assertFalse(report.contains("Home address"))
        assertTrue(report.contains("最近配置=<redacted> rev=42"))
    }

    @Test fun exportRedactsSsidContainingSpacesUntilItsFieldDelimiter() {
        val report = DiagnosticExportRedactor.redact("ssid=Office Guest Network; stage=HOOK_POINT")
        assertFalse(report.contains("Office Guest Network"))
        assertTrue(report.contains("stage=HOOK_POINT"))

        val adjacentFields = DiagnosticExportRedactor.redact("ssid=Office Guest Network stage=HOOK_POINT")
        assertFalse(adjacentFields.contains("Office Guest Network"))
        assertTrue(adjacentFields.contains("stage=HOOK_POINT"))
    }

    @Test fun exportRedactsCommonCellAndBeaconIdentifiers() {
        val report = DiagnosticExportRedactor.redact(
            "mcc=460 mnc=01 lac=1234 cid=5678 tac=9012 nci=3456 uuid=beacon-uuid major=12 minor=34 stage=SCAN",
        )
        for (value in listOf("460", "01", "1234", "5678", "9012", "3456", "beacon-uuid", "major=12", "minor=34")) {
            assertFalse("Leaked $value in $report", report.contains(value))
        }
        assertTrue(report.contains("stage=SCAN"))
    }

    @Test fun exportRedactsCoordinatesAndIdentifiersWithFullWidthSeparators() {
        val report = DiagnosticExportRedactor.redact(
            "WGS84：31.2304, 121.4737 latitude：31.2304 SSID：Office Guest BSSID：AA:BB:CC:DD:EE:FF token：secret",
        )
        for (value in listOf("31.2304", "121.4737", "Office Guest", "AA:BB:CC:DD:EE:FF", "secret")) {
            assertFalse("Leaked $value in $report", report.contains(value))
        }
        assertTrue(report.contains("WGS84=<redacted>"))
        assertTrue(report.contains("latitude=<redacted>"))
    }

    @Test fun exportRedactsCoordinatesFromAndroidLocationText() {
        val report = DiagnosticExportRedactor.redact(
            "received Location[fused 31.2304,121.4737 acc=5.0 et=+2s] result=INFO",
        )

        assertFalse(report.contains("31.2304"))
        assertFalse(report.contains("121.4737"))
        assertTrue(report.contains("Location[fused <redacted> acc=5.0 et=+2s]"))
        assertTrue(report.contains("result=INFO"))
    }

    @Test fun exportRedactsHyphenAndCiscoFormattedMacAddresses() {
        val report = DiagnosticExportRedactor.redact(
            "detected addresses AA-BB-CC-DD-EE-FF and AABB.CCDD.EEFF; result=INFO",
        )
        assertFalse(report.contains("AA-BB-CC-DD-EE-FF"))
        assertFalse(report.contains("AABB.CCDD.EEFF"))
        assertTrue(report.contains("result=INFO"))
    }

    @Test fun exportRedactsJsonEnvironmentIdentifiersCredentialsAndCoordinates() {
        val report = DiagnosticExportRedactor.redact(
            "{\"ssid\":\"Office Guest\",\"bssid\":\"AA:BB:CC:DD:EE:FF\",\"nci\":123456,\"lat\":31.2,\"lon\":121.5,\"api_key\":\"Secret\"}",
        )

        for (value in listOf("Office Guest", "AA:BB:CC:DD:EE:FF", "123456", "31.2", "121.5", "Secret")) {
            assertFalse("Leaked $value in $report", report.contains(value))
        }
        assertTrue(report.contains("\"ssid\":\"<redacted>\""))
        assertTrue(report.contains("\"api_key\":\"<redacted>\""))
        assertEquals(
            "{\"ssid\":\"<redacted>\",\"bssid\":\"<redacted>\",\"nci\":\"<redacted>\",\"lat\":\"<redacted>\",\"lon\":\"<redacted>\",\"api_key\":\"<redacted>\"}",
            report,
        )
    }

    @Test fun exportRedactsBleNamesAndRawScanRecordsFromModelText() {
        val report = DiagnosticExportRedactor.redact(
            "BleBeacon(address=AA:BB:CC:DD:EE:FF, name=North Entrance, rssi=-51, scanRecordHex=0201061AFF4C00021500112233445566778899AABBCCDDEEFF00010002, txPowerDbm=-8)",
        )

        for (value in listOf("AA:BB:CC:DD:EE:FF", "North Entrance", "0201061AFF4C00021500112233445566778899AABBCCDDEEFF00010002")) {
            assertFalse("Leaked $value in $report", report.contains(value))
        }
        assertTrue(report.contains("name=<redacted>"))
        assertTrue(report.contains("scanRecordHex=<redacted>"))
        assertTrue(report.contains("rssi=-51"))
        assertTrue(report.contains("txPowerDbm=-8"))
    }
}
