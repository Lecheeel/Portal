package com.system.location.service.core

import com.system.location.service.core.repository.*
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.scenario.*
import org.junit.Assert.*
import org.junit.Test

class RepositoryTest {
    private class Store : DocumentStore {
        var text: String? = null
        var fail = false
        override fun read() = text
        override fun writeAtomically(text: String) { if (fail) error("disk full"); this.text = text }
    }
    private val points = listOf(Wgs84(25.123456789012345, 119.12345678901234), Wgs84(25.2, 119.3))
    private val route = SavedRoute(Route("route1", "路线\"一\",二", points))

    @Test fun routeCrudCopyModesAndDoublePersistence() {
        val store = Store(); val repository = RouteRepository(store)
        repository.create(route)
        repository.rename("route1", "新名称")
        repository.favorite("route1", true)
        repository.mode("route1", RouteMode.PING_PONG)
        val restored = RouteRepository(store).get("route1")!!
        assertEquals(points.first().latitude.toBits(), restored.route.points.first().latitude.toBits())
        assertTrue(restored.favorite); assertEquals(RouteMode.PING_PONG, restored.mode)
        val copy = repository.copy("route1", "副本")
        assertNotEquals("route1", copy.route.id)
        assertTrue(repository.delete("route1")); assertNull(repository.get("route1"))
        assertEquals(1, repository.list().size)
    }
    @Test fun failedImportOrWritePreservesTheOriginalDocument() {
        val store = Store(); val repo = RouteRepository(store); repo.create(route)
        val original = store.text
        assertThrows(IllegalArgumentException::class.java) { repo.importJson(original!!.replace("\"schemaVersion\":1", "\"schemaVersion\":2")) }
        assertThrows(IllegalArgumentException::class.java) { repo.importJson(original!!.replace("25.2", "95.2")) }
        store.fail = true
        assertThrows(IllegalStateException::class.java) { repo.delete("route1") }
        assertEquals(original, store.text); assertEquals(route, repo.get("route1"))
    }
    @Test fun importsCopyConflictingIdsAndRejectWrongLibraryKind() {
        val repo = RouteRepository(Store()); repo.create(route)
        val imported = repo.importJson(repo.exportJson())
        assertNotEquals(route.route.id, imported.single().route.id)
        assertEquals(2, repo.list().size)
        assertThrows(IllegalArgumentException::class.java) { LocationRepository(Store()).importJson(repo.exportJson()) }
    }
    @Test fun sceneAndLocationLibrariesRoundTripAndSupportCrud() {
        val scenes = ScenarioRepository(Store())
        val scene = Scenario("s", "场景", point = points.first())
        scenes.create(scene); scenes.update(scene.copy(name = "重命名")); scenes.copy("s", "副本")
        val imported = ScenarioRepository(Store()); imported.importJson(scenes.exportJson())
        assertEquals(scenes.list(), imported.list()); assertTrue(scenes.delete("s"))
        val locations = LocationRepository(Store())
        val location = SavedLocation("l", "地址,\"含引号\"", points.first(), "第二行\n街道")
        locations.create(location); locations.update(location.copy(favorite = true)); locations.copy("l", "副本")
        val restored = LocationRepository(Store()); restored.importJson(locations.exportJson())
        assertEquals(locations.list(), restored.list()); assertTrue(locations.delete("l"))
    }
    @Test fun mutatingAnEditorAfterSaveCannotChangeStoredRoute() {
        val mutable = points.toMutableList()
        val repo = RouteRepository(Store()); repo.create(route.copy(route = route.route.copy(points = mutable)))
        mutable[0] = Wgs84(0.0, 0.0)
        assertEquals(points, repo.get("route1")!!.route.points)
    }
}
