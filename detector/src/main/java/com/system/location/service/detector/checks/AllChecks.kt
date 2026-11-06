package com.system.location.service.detector.checks

import com.system.location.service.detector.model.Check

/** Single registry for detector v1. Order matches docs/roadmap/06-validation.md C01–C15. */
object AllChecks {
    fun list(): List<Check> = listOf(
        LocationMockCheck(),
        AppOpsCheck(),
        SettingsCheck(),
        ProviderListCheck(),
        LocationFieldsCheck(),
        GnssStatusCheck(),
        NmeaCheck(),
        WifiCheck(),
        CellCheck(),
        SensorCheck(),
        ActivityRecognitionCheck(),
        TrajectoryCheck(),
        PackageIdentityCheck(),
        RootTraceCheck(),
        NetworkConsistencyCheck(),
    )
}
