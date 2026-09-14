package com.system.location.service.data.repository

import android.content.Context
import com.system.location.service.LocationServiceApp
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.repository.*
import com.system.location.service.core.scenario.Route
import com.system.location.service.data.persistence.AtomicDocumentStore
import com.system.location.service.ui.mock.HistoricalLocation
import com.system.location.service.ui.mock.HistoricalRoute
import com.system.location.service.ui.mock.RouteJson
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

fun stableRecordId(kind: String, value: String): String = UUID.nameUUIDFromBytes("$kind:$value".toByteArray(Charsets.UTF_8)).toString()
fun HistoricalRoute.toSaved() = SavedRoute(Route(id, name, route.map { Wgs84(it.first, it.second) }), mode, favorite)
fun SavedRoute.toHistorical() = HistoricalRoute(route.name, route.points.map { it.latitude to it.longitude }, route.id, mode, favorite)
fun HistoricalLocation.toSaved() = SavedLocation(id, name, Wgs84(lat, lon), address, favorite)
fun SavedLocation.toHistorical() = HistoricalLocation(name, address, coordinate.latitude, coordinate.longitude, id, favorite)

data class LegacyMigration(val routes: List<SavedRoute>, val locations: List<SavedLocation>, val issues: List<String>)
object LegacyLibraryMigration {
    fun decode(routeJson: String?, rawLocations: Set<String>): LegacyMigration {
        val routes = mutableListOf<SavedRoute>(); val locations = mutableListOf<SavedLocation>(); val issues = mutableListOf<String>()
        if (!routeJson.isNullOrBlank()) runCatching {
            val root = Json.parseToJsonElement(routeJson)
            val entries = if (root is JsonObject) {
                require(root["schemaVersion"]?.jsonPrimitive?.intOrNull == 1) { "未知旧路线版本" }
                root["routes"]!!.jsonArray
            } else root.jsonArray
            entries.forEachIndexed { index, entry ->
                runCatching { RouteJson.decodeRoute(entry.toString())!!.toSaved() }
                    .onSuccess { saved ->
                        routes += if (routes.any { it.route.id == saved.route.id })
                            saved.copy(route = saved.route.copy(id = stableRecordId("duplicate-route", "$index:$entry"))) else saved
                    }.onFailure { issues += "旧路线 ${index + 1}: ${it.message}" }
            }
        }.onFailure { issues += "旧路线库: ${it.message}" }
        rawLocations.sorted().forEachIndexed { index, text ->
            runCatching { HistoricalLocation.fromString(text).toSaved() }
                .onSuccess { if (locations.none { old -> old.id == it.id }) locations += it }
                .onFailure { issues += "旧位置 ${index + 1}: ${it.message}" }
        }
        return LegacyMigration(routes, locations, issues)
    }
}

/** Singleton repository instances serialize access to each atomic document. Legacy preferences are retained. */
object LibraryRepositories {
    private val context get() = LocationServiceApp.appContext
    private fun store(name: String) = AtomicDocumentStore(File(context.filesDir, "library/$name.json"))
    val routes by lazy { RouteRepository(store("routes")) }
    val scenarios by lazy { ScenarioRepository(store("scenarios")) }
    val locations by lazy { LocationRepository(store("locations")) }
    @Synchronized fun ensureMigrated() {
        val preferences = context.getSharedPreferences("fused_ext", Context.MODE_PRIVATE)
        if (preferences.getInt("library_migration_version", 0) == 1) return
        val migration = LegacyLibraryMigration.decode(preferences.getString("routes", null), preferences.getStringSet("locations", emptySet())!!.toSet())
        migration.routes.forEach { if (routes.get(it.route.id) == null) routes.create(it) }
        migration.locations.forEach { if (locations.get(it.id) == null) locations.create(it) }
        check(preferences.edit().putStringSet("library_migration_issues", migration.issues.toSet())
            .putInt("library_migration_version", 1).commit()) { "无法保存迁移记录" }
    }
    fun migrationIssues(): Set<String> = context.getSharedPreferences("fused_ext", Context.MODE_PRIVATE)
        .getStringSet("library_migration_issues", emptySet())!!.toSet()
}
