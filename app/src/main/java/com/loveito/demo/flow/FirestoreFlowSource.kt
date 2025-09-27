package com.loveito.demo.flow

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Fuente híbrida: obtiene el JSON del flujo desde Firestore (flows/{flowId}/versions donde active=true)
 * y lo cachea localmente (SharedPreferences) para ejecución local de baja latencia.
 * Fallback: cache previo o (opcional) un asset embebido.
 */
class FirestoreFlowSource(
    private val context: Context,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val gson: Gson = Gson(),
    private val prefs: SharedPreferences = context.getSharedPreferences("flow_cache", Context.MODE_PRIVATE)
) {
    data class FetchResult(
        val definition: SeizureAssistantDefinition,
        val source: Source,
        val version: String,
        val hash: String?
    ) {
        enum class Source { REMOTE, CACHE, ASSET }
    }

    suspend fun getActiveDefinition(
        flowId: String,
        forceRefresh: Boolean = false,
        allowAssetFallback: Boolean = true,
        assetName: String? = null,
        maxCacheAgeMs: Long = 6 * 3600_000L // 6 horas por defecto
    ): FetchResult = withContext(Dispatchers.IO) {
        val cachedVersion = prefs.getString(cacheKeyVersion(flowId), null)
        val cachedJson = prefs.getString(cacheKeyJson(flowId), null)
        val cachedTs = prefs.getLong(cacheKeyTimestamp(flowId), 0L)
        val cacheFresh = isCacheFresh(cachedTs, maxCacheAgeMs)

        if (!forceRefresh && cacheFresh && cachedJson != null) {
            runCatching {
                val def = FlowParser.parse(cachedJson)
                return@withContext FetchResult(def, FetchResult.Source.CACHE, cachedVersion ?: def.version, prefs.getString(cacheKeyHash(flowId), null))
            }
        }

        var remoteError: Throwable? = null
        val remote = runCatching { fetchRemote(flowId) }
        remote.onSuccess { (version, hash, json) ->
            // Evitar escribir si es mismo hash para reducir I/O
            val prevHash = prefs.getString(cacheKeyHash(flowId), null)
            if (prevHash != hash) {
                cache(flowId, version, hash, json)
            } else {
                // Actualizar timestamp para extender vida si sigue válido
                prefs.edit().putLong(cacheKeyTimestamp(flowId), System.currentTimeMillis()).apply()
            }
            val def = FlowParser.parse(json)
            return@withContext FetchResult(def, FetchResult.Source.REMOTE, version, hash)
        }.onFailure { remoteError = it }

        // Fallback: cache aunque esté "stale" si existe
        if (cachedJson != null) {
            runCatching {
                val def = FlowParser.parse(cachedJson)
                return@withContext FetchResult(def, FetchResult.Source.CACHE, cachedVersion ?: def.version, prefs.getString(cacheKeyHash(flowId), null))
            }
        }

        // Fallback final: asset
        if (allowAssetFallback && assetName != null) {
            runCatching {
                val json = context.assets.open(assetName).bufferedReader().use { it.readText() }
                val def = FlowParser.parse(json)
                return@withContext FetchResult(def, FetchResult.Source.ASSET, def.version, sha256(json))
            }
        }

        throw remoteError ?: IllegalStateException("No se pudo obtener definición del flujo ni fallback válido")
    }

    /** Obtiene la cantidad de episodios en <24h para el usuario/pet y se pasa luego como contexto ($app) si se quisiera. */
    suspend fun getSeizuresCount24h(petId: String? = null): Int = withContext(Dispatchers.IO) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@withContext 0
        val since = System.currentTimeMillis() - 24L * 3600_000L
        var query = db.collection("seizures")
            .whereEqualTo("userId", uid)
            .whereGreaterThan("createdAt", com.google.firebase.Timestamp(since / 1000, 0))
        if (petId != null) query = query.whereEqualTo("petId", petId)
        val snap = runCatching { query.get().await() }.getOrNull() ?: return@withContext 0
        snap.size()
    }

    private suspend fun fetchRemote(flowId: String): Triple<String, String?, String> {
        val versSnap = db.collection("flows").document(flowId).collection("versions")
            .whereEqualTo("active", true)
            .limit(1)
            .get().await()
        if (versSnap.isEmpty) error("No hay versión activa para flowId=$flowId")
        val doc = versSnap.documents.first()
        val version = doc.getString("version") ?: doc.id
        val hash = doc.getString("hash") // opcional si la guardás en el doc
        val definitionMap = doc.get("definition") ?: error("Documento sin campo definition")
        val json = gson.toJson(definitionMap)
        return Triple(version, hash ?: sha256(json), json)
    }

    private fun cache(flowId: String, version: String, hash: String?, json: String) {
        prefs.edit()
            .putString(cacheKeyVersion(flowId), version)
            .putString(cacheKeyHash(flowId), hash)
            .putString(cacheKeyJson(flowId), json)
            .putLong(cacheKeyTimestamp(flowId), System.currentTimeMillis())
            .apply()
    }

    private fun cacheKeyVersion(flowId: String) = "${flowId}_version"
    private fun cacheKeyHash(flowId: String) = "${flowId}_hash"
    private fun cacheKeyJson(flowId: String) = "${flowId}_json"
    private fun cacheKeyTimestamp(flowId: String) = "${flowId}_ts"

    private fun sha256(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun invalidateCache(flowId: String) {
        prefs.edit()
            .remove(cacheKeyVersion(flowId))
            .remove(cacheKeyHash(flowId))
            .remove(cacheKeyJson(flowId))
            .remove(cacheKeyTimestamp(flowId))
            .apply()
    }

    private fun isCacheFresh(ts: Long, maxAgeMs: Long): Boolean = ts > 0 && (System.currentTimeMillis() - ts) < maxAgeMs
}
