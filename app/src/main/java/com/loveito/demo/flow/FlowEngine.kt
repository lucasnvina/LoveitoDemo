package com.loveito.demo.flow

import java.time.Instant

class FlowEngine(
    private val state: FlowRuntimeState,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private var timerStartEpochMs: Long? = null
    private val eventHooks = state.definition.eventHooks ?: emptyList()

    fun currentNode(): Node = state.nodeMap[state.currentNodeId]
        ?: error("Nodo no encontrado: ${state.currentNodeId}")

    fun isFinished(): Boolean = state.finished

    // Accesores públicos para pruebas / UI
    fun getVar(key: String): Any? = state.vars[key]
    fun setAppContext(key: String, value: Any?) { state.appContext[key] = value }
    fun getCurrentNodeId(): String = state.currentNodeId
    fun getDefaults(): DefaultsConfig? = state.definition.defaults

    fun tickTimer(seconds: Long) { state.elapsedSec = seconds; processEventHooks("timer_tick") }

    fun enqueueSay(text: String) { if (text.isNotBlank()) state.sayBuffer += text }

    fun answerYesNo(answerRaw: String) {
        if (state.finished) return
        val node = currentNode()
        require(node is Node.YesNoNode) { "Nodo actual no es yes_no" }
        val answer = normalizeAnswer(answerRaw)
        val defaults = state.definition.defaults
        if (answer == "other") {
            // Intentar ruta explícita 'other'
            val explicit = node.onAnswer.firstOrNull { it.predicate == "other" }
            if (explicit != null) {
                executeActions(explicit.actions)
                goTo(explicit.next)
                return
            }
            // Usar defaults si existen
            val defMsg = defaults?.yesNo?.other
            if (defMsg != null) {
                enqueueSay(defMsg)
                // Quedarse en mismo nodo para re-preguntar
                return
            }
            // Si no hay ruta ni default -> error explícito (debería haberlo prevenido el validator)
            error("Ruta 'other' faltante en nodo ${node.id} y no hay defaults.yes_no.other")
        }
        val route = node.onAnswer.firstOrNull { it.predicate == answer }
            ?: node.onAnswer.firstOrNull { it.predicate == "other" } // fallback si la clasificación cayó a valor inesperado
            ?: run {
                val defMsg = defaults?.yesNo?.other
                if (defMsg != null) { enqueueSay(defMsg); return } else error("Ruta 'other' faltante en nodo ${node.id}")
            }
        executeActions(route.actions)
        goTo(route.next)
    }

    fun triggerEvent(eventName: String) {
        if (state.finished) return
        val node = currentNode()
        require(node is Node.EventNode) { "Nodo actual no es event" }
        if (node.event != eventName) return
        executeActions(node.actions)
        goTo(node.next)
    }

    fun proceedIfInfoOrAction() {
        if (state.finished) return
        when (val node = currentNode()) {
            is Node.InfoNode -> { executeActions(node.actions); goTo(node.next) }
            is Node.ActionNode -> { executeActions(node.actions); goTo(node.next) }
            is Node.BranchNode -> { evaluateBranch(node) }
            else -> {}
        }
    }

    private fun processEventHooks(eventName: String) {
        if (state.finished) return
        val hooks = eventHooks.filter { it.event == eventName }
        for (hook in hooks) {
            val allTrue = hook.conditions.all { cond -> FlowEvaluator.eval(cond, state) }
            if (allTrue) {
                executeActions(hook.actions)
                if (hook.interrupt) {
                    val node = currentNode()
                    when (node) {
                        is Node.ActionNode, is Node.BranchNode -> proceedIfInfoOrAction()
                        else -> {}
                    }
                    break
                }
            }
        }
    }

    private fun evaluateBranch(branch: Node.BranchNode) {
        for (cond in branch.conditions) {
            val predicate = cond.predicate
            val ok = if (predicate == "else") true else FlowEvaluator.eval(predicate, state)
            if (ok) {
                executeActions(cond.actions)
                if (cond.next == null) {
                    state.finished = true
                } else {
                    state.currentNodeId = cond.next
                    val node = currentNode()
                    // Igual que en goTo: no auto-skip de InfoNode
                    when (node) {
                        is Node.ActionNode, is Node.BranchNode -> proceedIfInfoOrAction()
                        else -> {}
                    }
                }
                return
            }
        }
        state.finished = true
    }

    private fun goTo(next: String?) {
        if (next == null) { state.finished = true; return }
        state.currentNodeId = next
        val node = currentNode()
        // Ya no auto-avanzamos InfoNode: dejamos que la capa de voz lo lea y luego llame a proceedIfInfoOrAction()
        when (node) {
            is Node.ActionNode, is Node.BranchNode -> proceedIfInfoOrAction()
            else -> { /* mantener en InfoNode / YesNo / Event hasta que motor de voz actúe */ }
        }
    }

    private fun executeActions(actions: List<FlowAction>) {
        actions.forEach { act ->
            when (act.doAction) {
                "start_timer" -> startTimer()
                "stop_timer" -> stopTimer()
                "record_note" -> recordNote(act)
                "mark_emergency" -> markFlag("ROJO")
                "mark_warning" -> markFlag("AMARILLO")
                "say" -> {
                    val text = (act.params?.get("text") as? String)?.takeIf { it.isNotBlank() }
                    if (text != null) state.sayBuffer += text
                }
                "redirect" -> {
                    val nodeId = act.params?.get("node_id") as? String
                    if (!nodeId.isNullOrBlank()) state.currentNodeId = nodeId
                }
            }
        }
    }

    private fun startTimer() { timerStartEpochMs = timeProvider() }

    private fun stopTimer() {
        val start = timerStartEpochMs ?: return
        val elapsed = (timeProvider() - start) / 1000
        state.elapsedSec = elapsed
    }

    private fun recordNote(action: FlowAction) {
        val field = action.params?.get("field") as? String ?: return
        val valueRaw = action.params["value"]
        val value = when (valueRaw) { is String -> resolveDynamicValue(valueRaw) else -> valueRaw }
        state.vars[field] = value
    }

    private fun resolveDynamicValue(token: String): Any? = when (token) {
        "\$timer.elapsed_sec" -> state.elapsedSec
        "\$now.epoch" -> Instant.now().epochSecond
        else -> if (token.startsWith("$")) null else token
    }

    private fun markFlag(level: String) { state.vars["triage_level"] = level }

    private fun normalizeAnswer(a: String): String = when (a.lowercase()) {
        "si", "sí", "yes" -> "yes"
        // Tratar variantes de 'no' y expansiones frecuentes de ASR como 'nose' / 'no se' / 'no sé' como NO
        "no", "nose", "no se", "no sé", "negativo" -> "no"
        // 'ns' (no sé abreviado) lo dejamos como 'other' para forzar aclaración
        // Cualquier otra cosa cae en 'other'
        else -> "other"
    }

    fun pollSayMessage(): String? = if (state.sayBuffer.isNotEmpty()) state.sayBuffer.removeAt(0) else null
    fun hasPendingSay(): Boolean = state.sayBuffer.isNotEmpty()

    companion object {
        fun fromDefinition(def: SeizureAssistantDefinition): FlowEngine {
            val vars = (def.globals?.vars ?: mutableMapOf()).toMutableMap()
            val nodeMap = def.nodeMap()
            val state = FlowRuntimeState(def, nodeMap, vars)
            return FlowEngine(state)
        }
    }
}
