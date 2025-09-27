package com.loveito.demo.flow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await

/**
 * ViewModel simplificada: sólo asset, sin FirestoreFlowSource ni FlowValidator.
 */
class HybridFlowViewModel(app: Application) : AndroidViewModel(app) {

    sealed class UiState {
        object Loading: UiState()
        data class NodeYesNo(val id: String, val prompt: String): UiState()
        data class NodeInfo(val id: String, val prompt: String): UiState()
        data class NodeEvent(val id: String, val event: String): UiState()
        data class NodeAction(val id: String, val prompt: String?): UiState()
        data class Finished(
            val triageLevel: String?,
            val durationSec: Long?,
            val vars: Map<String, Any?>,
            val verified: Boolean,
            val triageLevelFinal: String?,
            val decisionCond: String?,
            val updatedByFinalize: Boolean
        ): UiState()
        data class Error(val message: String, val recoverable: Boolean = true): UiState()
    }

    private val firestore = FirebaseFirestore.getInstance()
    private val remoteRepo = CloudFlowRemoteRepository()

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState

    private val _sourceInfo = MutableStateFlow("")
    val sourceInfo: StateFlow<String> = _sourceInfo

    private var engine: FlowEngine? = null
    private var timerJob: Job? = null

    private var sessionId: String? = null
    private var flowId: String = "seizure_assistant"
    private var activeVersion: String? = null

    private var finalizeRequested = false
    private var finalizeCompleted = false
    private var finalizeDecisionCond: String? = null
    private var finalizeTriageFinal: String? = null
    private var finalizeUpdated = false

    /** Inicia el flujo híbrido. */
    fun start(
        flowId: String = "seizure_assistant",
        forceRefresh: Boolean = false, // ignorado (sin remoto)
        petId: String? = null
    ) {
        this.flowId = flowId
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                // Cargar definición desde assets directamente (sin remoto / sin caché)
                val json = withContext(Dispatchers.IO) {
                    val assetName = "seizure_assistant_v1_6_0.json"
                    getApplication<Application>().assets.open(assetName).bufferedReader().use { it.readText() }
                }
                val def = FlowParser.parse(json)
                activeVersion = def.version
                _sourceInfo.value = "def:ASSET@${def.version}"

                // Contexto externo (seizures últimas 24h) replicando lógica previa
                val c24 = getSeizuresCount24h(petId)
                val eng = FlowEngine.fromDefinition(def).also { it.setAppContext("seizures_count_24h", c24) }
                engine = eng

                eng.proceedIfInfoOrAction()
                pushCurrentNode()

                // Mantener creación de sesión en Firestore (auditoría) si se desea
                createLocalSessionDocument(petId, c24)

                startTimerLoopIfNeeded()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Error iniciando flujo", true)
            }
        }
    }

    /** Lógica para nodos automáticos y final. */
    private fun pushCurrentNode() {
        val e = engine ?: return
        if (e.isFinished()) {
            _uiState.value = UiState.Finished(
                triageLevel = e.getVar("triage_level") as? String,
                durationSec = (e.getVar("duration_sec") as? Number)?.toLong(),
                vars = snapshotVars(),
                verified = finalizeCompleted,
                triageLevelFinal = finalizeTriageFinal ?: (e.getVar("triage_level") as? String),
                decisionCond = finalizeDecisionCond,
                updatedByFinalize = finalizeUpdated
            ); return
        }
        when (val n = e.currentNode()) {
            is Node.YesNoNode -> _uiState.value = UiState.NodeYesNo(n.id, n.prompt)
            is Node.InfoNode -> _uiState.value = UiState.NodeInfo(n.id, n.prompt)
            is Node.EventNode -> _uiState.value = UiState.NodeEvent(n.id, n.event)
            is Node.ActionNode -> _uiState.value = UiState.NodeAction(n.id, n.prompt)
            is Node.BranchNode -> { e.proceedIfInfoOrAction(); pushCurrentNode(); return }
        }
    }

    /** Respuesta para nodo yes/no. */
    fun answerYesNo(answer: String) {
        val e = engine ?: return
        viewModelScope.launch {
            try {
                if (e.isFinished() || e.currentNode() !is Node.YesNoNode) return@launch
                e.answerYesNo(answer)
                pushCurrentNode()
                if (e.isFinished()) {
                    persistFinalIfNeeded()
                } else {
                    startTimerLoopIfNeeded()
                }
            } catch (e2: Exception) { _uiState.value = UiState.Error(e2.message ?: "Error procesando respuesta", true) }
        }
    }

    /** Trigger del usuario para eventos (ej: fin de convulsión). */
    fun triggerEvent(event: String) {
        val e = engine ?: return
        viewModelScope.launch {
            try {
                if (e.isFinished() || e.currentNode() !is Node.EventNode) return@launch
                e.triggerEvent(event)
                pushCurrentNode()
                if (e.isFinished()) {
                    persistFinalIfNeeded()
                } else {
                    startTimerLoopIfNeeded()
                }
            } catch (e2: Exception) { _uiState.value = UiState.Error(e2.message ?: "Error procesando evento", true) }
        }
    }

    /** Loop que monitorea tiempo transcurrido para hooks (>=180s). */
    private fun startTimerLoopIfNeeded() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                val e = engine ?: break
                if (e.isFinished()) break
                val started = e.getVar("started_at_epoch") as? Number
                if (started != null) {
                    val nowEpoch = System.currentTimeMillis() / 1000
                    val elapsed = nowEpoch - started.toLong()
                    e.tickTimer(elapsed)
                    if (e.isFinished()) { // hook pudo cerrar
                        pushCurrentNode()
                        persistFinalIfNeeded()
                        break
                    }
                }
                delay(1000)
            }
        }
    }

    /** Crea documento inicial de sesión (auditoría). */
    private fun createLocalSessionDocument(petId: String?, seizuresCount24h: Int) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val data = hashMapOf(
            "userId" to uid,
            "flowId" to flowId,
            "version" to activeVersion,
            "petId" to petId,
            "startedAt" to Timestamp.now(),
            "status" to "active",
            "execution" to "local_hybrid",
            "seizures_count_24h" to seizuresCount24h
        )
        firestore.collection("flowSessions")
            .add(data)
            .addOnSuccessListener { ref ->
                sessionId = ref.id
                // Si ya había finalizado y estaba esperando finalize => intentar ahora
                if (finalizeRequested && !finalizeCompleted) attemptFinalize()
            }
    }

    /** Snapshot de variables relevantes para UI / persistencia. */
    private fun snapshotVars(): Map<String, Any?> {
        val e = engine ?: return emptyMap()
        val keys = listOf(
            "started_at_epoch","duration_sec","seizures_count_24h",
            "has_rescue_med_prescribed","rescue_med_admin","breathing_ok","injury_present","triage_level"
        )
        return keys.associateWith { e.getVar(it) }
    }

    /** Persiste estado final y dispara verificación backend. */
    private fun persistFinalIfNeeded() {
        val e = engine ?: return
        if (!e.isFinished()) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val triage = e.getVar("triage_level") as? String
        val duration = (e.getVar("duration_sec") as? Number)?.toLong()
        val vars = snapshotVars().toMutableMap()
        val finish = hashMapOf(
            "status" to "finished",
            "triageLevel" to triage,
            "durationSec" to duration,
            "finishedAt" to Timestamp.now(),
            "vars" to vars
        )
        sessionId?.let { sid ->
            firestore.collection("flowSessions").document(sid)
                .set(finish, SetOptions.merge())
                .addOnSuccessListener { attemptFinalize() }
                .addOnFailureListener { attemptFinalize() }
        } ?: run {
            // Si la sesión aún no se creó (carrera), marcamos finalize solicitado
            attemptFinalize()
        }
        // Snapshot síntesis separada
        val seizureDoc = hashMapOf(
            "userId" to uid,
            "sessionId" to sessionId,
            "triageLevel" to triage,
            "durationSec" to duration,
            "vars" to vars,
            "createdAt" to Timestamp.now(),
            "flowVersion" to activeVersion,
            "flowId" to flowId
        )
        firestore.collection("seizures").add(seizureDoc)
    }

    /** Marca intención de verificación y gestiona reintentos si falta sessionId. */
    private fun attemptFinalize() {
        finalizeRequested = true
        if (finalizeCompleted) return
        if (sessionId == null) {
            // Reintentos escalonados hasta obtener sessionId
            viewModelScope.launch {
                repeat(3) { idx ->
                    delay(500L * (idx + 1))
                    if (sessionId != null && !finalizeCompleted) {
                        finalizeSessionRemote(); return@launch
                    }
                }
            }
        } else {
            finalizeSessionRemote()
        }
    }

    /** Permite reintentar verificación desde UI. */
    fun retryFinalize() { if (!finalizeCompleted) attemptFinalize() }

    /** Llamada a Cloud Function finalizeSeizureSession. */
    private fun finalizeSessionRemote() {
        val sid = sessionId ?: return
        val e = engine ?: return
        if (!e.isFinished()) return
        viewModelScope.launch {
            runCatching { remoteRepo.finalizeSession(sid) }
                .onSuccess { resp ->
                    finalizeCompleted = true
                    finalizeDecisionCond = resp.matchedCond
                    finalizeTriageFinal = resp.triageLevelFinal
                    finalizeUpdated = resp.updated
                    pushCurrentNode()
                }
                .onFailure { /* mantener estado no verificado */ }
        }
    }

    /** Contexto externo (seizures últimas 24h) replicando lógica previa */
    private suspend fun getSeizuresCount24h(petId: String?): Int = withContext(Dispatchers.IO) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@withContext 0
        val since = System.currentTimeMillis() - 24L * 3600_000L
        var query = firestore.collection("seizures")
            .whereEqualTo("userId", uid)
            .whereGreaterThan("createdAt", com.google.firebase.Timestamp(since / 1000, 0))
        if (petId != null) query = query.whereEqualTo("petId", petId)
        val snap = runCatching { query.get().await() }.getOrNull() ?: return@withContext 0
        snap.size()
    }

    override fun onCleared() { timerJob?.cancel(); super.onCleared() }
}
