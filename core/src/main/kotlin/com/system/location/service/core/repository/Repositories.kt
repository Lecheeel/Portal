package com.system.location.service.core.repository

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.scenario.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.util.UUID

/** Implementations must replace the complete document atomically or leave the old one intact. */
interface DocumentStore {
    fun read(): String?
    fun writeAtomically(text: String)
}

interface Repository<T> {
    fun list(): List<T>
    fun get(id: String): T?
    fun create(item: T): T
    fun update(item: T): T
    fun delete(id: String): Boolean
    fun copy(id: String, name: String): T
    fun importJson(text: String): List<T>
    fun exportJson(): String
    fun replaceAll(items: List<T>)
}

/** No cached mutable collections: reads and failed writes never mutate the saved library. */
abstract class VersionedRepository<T>(private val store: DocumentStore, serializer: KSerializer<T>,
    private val kind: String) : Repository<T> {
    private val listSerializer = ListSerializer(serializer)
    protected abstract fun id(item: T): String
    protected abstract fun freeze(item: T): T
    protected abstract fun duplicate(item: T, id: String, name: String? = null): T
    @Synchronized override fun list(): List<T> = read().map(::freeze)
    @Synchronized override fun get(id: String): T? = read().find { id(it) == id }?.let(::freeze)
    @Synchronized override fun create(item: T): T {
        val frozen = freeze(item); val old = read()
        require(old.none { id(it) == id(frozen) }) { "Record already exists" }
        write(old + frozen)
        return freeze(frozen)
    }
    @Synchronized override fun update(item: T): T {
        val frozen = freeze(item); val old = read()
        require(old.any { id(it) == id(frozen) }) { "Record does not exist" }
        write(old.map { if (id(it) == id(frozen)) frozen else it })
        return freeze(frozen)
    }
    @Synchronized override fun delete(id: String): Boolean {
        val old = read(); val next = old.filterNot { id(it) == id }
        if (old.size == next.size) return false
        write(next)
        return true
    }
    @Synchronized override fun copy(id: String, name: String): T {
        require(name.isNotBlank())
        val source = get(id) ?: error("Record does not exist")
        return create(duplicate(source, UUID.randomUUID().toString(), name))
    }
    @Synchronized override fun importJson(text: String): List<T> {
        val incoming = decode(text) // Validate the entire import before writing anything.
        val old = read()
        val identifiers = old.map(::id).toMutableSet()
        val imported = incoming.map { item ->
            val next = if (id(item) in identifiers) duplicate(item, UUID.randomUUID().toString()) else item
            identifiers += id(next)
            freeze(next)
        }
        write(old + imported)
        return imported.map(::freeze)
    }
    @Synchronized override fun exportJson() = encode(read())
    @Synchronized override fun replaceAll(items: List<T>) { write(items.map(::freeze)) }
    private fun read(): List<T> = store.read()?.let(::decode) ?: emptyList()
    private fun decode(text: String): List<T> {
        require(text.length <= MAX_DOCUMENT_CHARS) { "Library exceeds size limit" }
        val root = Json.parseToJsonElement(text).jsonObject
        require(root["schemaVersion"]?.jsonPrimitive?.intOrNull == 1) { "Unsupported library schema" }
        require(root["kind"]?.jsonPrimitive?.content == kind) { "Wrong library type" }
        val items = Json.decodeFromJsonElement(listSerializer, root["items"] ?: error("Missing records"))
        validate(items)
        return items.map(::freeze)
    }
    private fun validate(items: List<T>) {
        require(items.size <= 10_000) { "Too many records" }
        require(items.all { id(it).isNotBlank() } && items.map(::id).distinct().size == items.size) { "Invalid or duplicate IDs" }
    }
    private fun encode(items: List<T>): String = buildJsonObject {
        put("schemaVersion", 1); put("kind", kind); put("items", Json.encodeToJsonElement(listSerializer, items))
    }.toString()
    private fun write(items: List<T>) {
        validate(items)
        val text = encode(items)
        require(text.length <= MAX_DOCUMENT_CHARS) { "Library exceeds size limit" }
        store.writeAtomically(text)
    }
    companion object { const val MAX_DOCUMENT_CHARS = 20_000_000 }
}

@Serializable data class SavedRoute(val route: Route, val mode: RouteMode = RouteMode.ONCE, val favorite: Boolean = false)
@Serializable data class SavedLocation(val id: String, val name: String, val coordinate: Wgs84,
    val address: String = "", val favorite: Boolean = false) {
    init { require(id.isNotBlank() && name.isNotBlank()) }
}

class RouteRepository(store: DocumentStore) : VersionedRepository<SavedRoute>(store, SavedRoute.serializer(), "routes") {
    override fun id(item: SavedRoute) = item.route.id
    override fun freeze(item: SavedRoute) = item.copy(route = item.route.frozen())
    override fun duplicate(item: SavedRoute, id: String, name: String?) = item.copy(route = item.route.copy(id = id, name = name ?: item.route.name))
    fun rename(id: String, name: String) = get(id)!!.let { update(it.copy(route = it.route.copy(name = name))) }
    fun favorite(id: String, value: Boolean) = get(id)!!.let { update(it.copy(favorite = value)) }
    fun mode(id: String, value: RouteMode) = get(id)!!.let { update(it.copy(mode = value)) }
}
class ScenarioRepository(store: DocumentStore) : VersionedRepository<Scenario>(store, Scenario.serializer(), "scenarios") {
    override fun id(item: Scenario) = item.id
    override fun freeze(item: Scenario) = item.frozen()
    override fun duplicate(item: Scenario, id: String, name: String?) = item.copy(id = id, name = name ?: item.name)
}
class LocationRepository(store: DocumentStore) : VersionedRepository<SavedLocation>(store, SavedLocation.serializer(), "locations") {
    override fun id(item: SavedLocation) = item.id
    override fun freeze(item: SavedLocation) = item.copy()
    override fun duplicate(item: SavedLocation, id: String, name: String?) = item.copy(id = id, name = name ?: item.name)
}
