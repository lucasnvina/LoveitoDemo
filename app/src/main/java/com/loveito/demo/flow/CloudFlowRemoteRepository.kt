package com.loveito.demo.flow

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/**
 * Repositorio que delega la lógica de avance del flujo a Cloud Functions.
 * Mantiene compatibilidad con el motor local como fallback offline si quisieras.
 */
class CloudFlowRemoteRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance()
) {
    data class StartSessionRequest(
        val flowId: String = "seizure_assistant",
        val version: String? = null,
        val petId: String? = null,
        val seizuresCount24h: Int = 0
    )

    data class NodePayload(val id: String, val type: String, val prompt: String? = null, val event: String? = null)

    data class StartSessionResponse(
        val sessionId: String,
        val version: String,
        val node: NodePayload?,
        val finished: Boolean,
        val triageLevel: String?
    )

    data class ProgressRequest(
        val sessionId: String,
        val answer: String? = null,
        val event: String? = null,
        val elapsedSec: Long? = null
    )

    data class ProgressResponse(
        val finished: Boolean,
        val triageLevel: String?,
        val node: NodePayload?
    )

    data class FinalizeResponse(
        val triageLevelFinal: String?,
        val updated: Boolean,
        val previous: String?,
        val matchedCond: String?
    )

    suspend fun startSession(req: StartSessionRequest): StartSessionResponse {
        val data = hashMapOf(
            "flowId" to req.flowId,
            "version" to req.version,
            "petId" to req.petId,
            "seizuresCount24h" to req.seizuresCount24h
        ).filterValues { it != null }
        val result = functions
            .getHttpsCallable("startSeizureSession")
            .call(data)
            .await()
        val map = result.data as Map<*, *>
        return StartSessionResponse(
            sessionId = map["sessionId"] as String,
            version = map["version"] as String,
            node = (map["node"] as? Map<*, *>)?.let { n ->
                NodePayload(
                    id = n["id"] as String,
                    type = n["type"] as String,
                    prompt = n["prompt"] as? String,
                    event = n["event"] as? String
                )
            },
            finished = map["finished"] as Boolean,
            triageLevel = map["triageLevel"] as? String
        )
    }

    suspend fun progress(req: ProgressRequest): ProgressResponse {
        val data = hashMapOf(
            "sessionId" to req.sessionId,
            "answer" to req.answer,
            "event" to req.event,
            "elapsedSec" to req.elapsedSec
        ).filterValues { it != null }
        val result = functions
            .getHttpsCallable("progressSeizureSession")
            .call(data)
            .await()
        val map = result.data as Map<*, *>
        return ProgressResponse(
            finished = map["finished"] as Boolean,
            triageLevel = map["triageLevel"] as? String,
            node = (map["node"] as? Map<*, *>)?.let { n ->
                NodePayload(
                    id = n["id"] as String,
                    type = n["type"] as String,
                    prompt = n["prompt"] as? String,
                    event = n["event"] as? String
                )
            }
        )
    }

    suspend fun finalizeSession(sessionId: String): FinalizeResponse {
        val result = functions
            .getHttpsCallable("finalizeSeizureSession")
            .call(mapOf("sessionId" to sessionId))
            .await()
        val map = result.data as Map<*, *>
        return FinalizeResponse(
            triageLevelFinal = map["triageLevelFinal"] as? String,
            updated = map["updated"] as? Boolean ?: false,
            previous = map["previous"] as? String,
            matchedCond = map["matchedCond"] as? String
        )
    }
}
