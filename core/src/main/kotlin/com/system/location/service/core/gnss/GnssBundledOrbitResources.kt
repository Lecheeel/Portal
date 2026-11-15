package com.system.location.service.core.gnss

/** Resource loading is separate from frame caches; immutable orbit adapters can be shared. */
object GnssBundledOrbitResources {
    /**
     * Injection hosts (LSPosed system_server) can load the module dex without exposing the
     * module APK's Java resources on the class loader. The xposed side installs a reader that
     * opens the bundled GNSS files from the module APK so the same resources remain available.
     */
    @Volatile private var externalReader: ((String) -> String?)? = null

    fun installExternalReader(reader: ((String) -> String?)?) {
        externalReader = reader
    }

    private fun read(name: String): String {
        javaClass.getResourceAsStream("/gnss/$name")?.bufferedReader(Charsets.UTF_8)?.use {
            return it.readText()
        }
        readFromCodeSource(name)?.let { return it }
        externalReader?.invoke(name)?.let { return it }
        BundledGnssFallback.read(name)?.let { return it }
        error("Missing bundled GNSS resource: $name")
    }

    /** Injected classes often have no resource path; their code source still points at the APK. */
    private fun readFromCodeSource(name: String): String? = runCatching {
        val location = javaClass.protectionDomain?.codeSource?.location ?: return@runCatching null
        val file = java.io.File(location.toURI())
        if (file.isDirectory) {
            return@runCatching java.io.File(file, "gnss/$name").takeIf { it.isFile }
                ?.readText(Charsets.UTF_8)
        }
        if (!file.isFile) return@runCatching null
        java.util.zip.ZipFile(file).use { zip ->
            val entry = zip.getEntry("gnss/$name") ?: zip.getEntry("assets/gnss/$name")
                ?: return@runCatching null
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }.getOrNull()

    private val virtual by lazy { VirtualWalkerResource.decode(read("virtual-walker.json")) }
    private val utc by lazy { runCatching { GnssUtcTimeScale.parseIers(read("leap-seconds.list")) } }
    private val gps by lazy { runCatching { GpsAlmanacResource.decode(read("gps-yuma.json")) } }
    private val beiDouCatalog by lazy { runCatching {
        BeiDouCatalogResource(BeiDouCatalogManifest.decode(read("igs-beidou-metadata-source.json")),
            read("igs-beidou-metadata.snx")).validate()
    } }

    /** Shared UTC/GPS conversion for synthetic raw observations; null when the bundle is invalid. */
    fun utcTimeScale(): GnssUtcTimeScale? = utc.getOrNull()

    /** Bundled GPS almanac records used as the production fallback for LNAV-shaped ephemerides. */
    fun gpsAlmanac(): List<GpsYumaAlmanac>? = gps.getOrNull()?.let { resource ->
        runCatching { GpsYumaParser.parse(resource.yuma, resource.resolvedReferenceWeek).toList() }.getOrNull()
    }

    /**
     * No network or local receiver access. The caller supplies its tested output envelope and
     * GPS-specific approximate-geometry age budget, rather than inheriting capture expiry.
     * Corrupt optional downloaded/bundled GPS data cannot disable the independent virtual model.
     */
    fun model(policy: GnssSimulationPolicy, outputLimits: GnssOutputLimits,
        gpsAlmanacAgeBudgetSeconds: Double, downloadedGps: GpsAlmanacResource? = null,
        navigation: RinexNavigationResource? = null,
        signalCalibration: GnssAutonomousSignalCalibration? = null): ReceiverAutonomousSkyModel {
        require(gpsAlmanacAgeBudgetSeconds.isFinite() && gpsAlmanacAgeBudgetSeconds >= 0)
        val providers = mutableListOf<GnssOrbitProvider>()
        val diagnostics = mutableListOf<String>()
        val timeScale = utc.getOrNull()
        if (timeScale == null) diagnostics += "离线 UTC 资源不可用，真实轨道来源已降级"
        if (timeScale != null && navigation != null) {
            runCatching { navigation.gpsProvider(timeScale) }
                .onSuccess { providers += it }
                .onFailure { diagnostics += "RINEX GPS 广播来源不可用，保留历书与虚拟兜底" }
            // Sky-only approximation, explicitly separate from capture's 24-hour eligibility.
            // This limits header extrapolation; it is not a claimed broadcast GGTO expiry.
            runCatching { navigation.galileoProvider(timeScale, RinexGalileoSkyPolicy(86400.0, true)) }
                .onSuccess { providers += it }
                .onFailure { diagnostics += "RINEX Galileo 广播来源不可用：${it.message}；保留其他来源与虚拟兜底" }
            // EPSG:7679 identifies PZ-90.11 as replacing PZ-90.02 from 2014-01-15.
            // Earlier/ambiguous-era archives are not silently treated as modern GLONASS.
            runCatching { navigation.glonassProvider(timeScale, RinexGlonassSkyPolicy(
                java.time.Instant.parse("2014-01-15T00:00:00Z").toEpochMilli(), true)) }
                .onSuccess { providers += it }
                .onFailure { diagnostics += "RINEX GLONASS 广播来源不可用：${it.message}；保留其他来源与虚拟兜底" }
            // These are separate sky-approximation budgets: one hour from toe, one week from
            // the BDUT header reference, and a dated catalog with a 30-day freshness policy.
            // None is an inferred broadcast fit duration or the capture expiry setting.
            runCatching {
                // Prefer accepted upstream corrections when creation labels tie with the bundle.
                val catalog = listOfNotNull(navigation.beiDouCatalog?.validate(), beiDouCatalog.getOrNull())
                    .maxByOrNull { it.creationLabel } ?: error("No valid BeiDou identity catalog")
                navigation.beiDouProvider(timeScale, catalog.catalog(timeScale, 30 * 86400L),
                    RinexBeiDouSkyPolicy(3600.0, 7 * 86400.0, true))
            }
                .onSuccess { providers += it }
                .onFailure { diagnostics += "RINEX 北斗广播来源不可用：${it.message}；保留其他来源与虚拟兜底" }
        }
        val candidates = listOfNotNull(downloadedGps, gps.getOrNull())
            .sortedByDescending { it.referenceEpochMillis }
        if (gps.isFailure) diagnostics += "内置 GPS 历书资源损坏，保留其他来源"
        if (timeScale != null) for (resource in candidates) {
            runCatching { resource.provider(timeScale, gpsAlmanacAgeBudgetSeconds) }
                .onSuccess { providers += it }
                .onFailure { diagnostics += "GPS 历书 ${resource.sha256.take(12)} 校验失败，保留其他来源" }
        }
        if (diagnostics.isNotEmpty()) providers += object : GnssOrbitProvider {
            override val kind = GnssOrbitSourceKind.OFFLINE_ORBIT
            override fun positions(request: GnssSkyRequest) = GnssOrbitBatch(emptyList(), diagnostics.toList())
        }
        // This enumerates protocol identity space, not the population of real orbiting satellites.
        val identities = Constellation.entries.flatMap { constellation -> (1..999).mapNotNull { svid ->
            GnssSatelliteIds.nmeaId(constellation, svid)?.let { constellation to svid }
        } }
        return ReceiverAutonomousSkyModel(providers, VirtualWalkerOrbitProvider(virtual, policy.virtualSeed), policy,
            quantitySupplement = GnssVirtualSkySupplement(identities, outputLimits, policy.virtualSeed),
            signalCalibration = signalCalibration)
    }
}
