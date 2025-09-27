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

    fun tickTimer(seconds: Long) { state.elapsedSec = seconds; processEventHooks("timer_tick") }

    fun answerYesNo(answerRaw: String) {
        if (state.finished) return
        val node = currentNode()
        require(node is Node.YesNoNode) { "Nodo actual no es yes_no" }
        val answer = normalizeAnswer(answerRaw)
        val route = node.onAnswer.firstOrNull { it.predicate == answer }
            ?: node.onAnswer.firstOrNull { it.predicate == "other" }
            ?: error("Ruta 'other' faltante en nodo ${node.id}")
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
                    // Si redirect cambió el nodo y es info/action/branch evaluarlo
                    val node = currentNode()
                    when (node) {
                        is Node.InfoNode, is Node.ActionNode, is Node.BranchNode -> proceedIfInfoOrAction()
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
                    proceedIfInfoOrAction()
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
        when (node) {
            is Node.InfoNode, is Node.ActionNode, is Node.BranchNode -> proceedIfInfoOrAction()
            else -> {}
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
                "say" -> { /* UI/TTS */ }
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
        "no" -> "no"
        "ns", "nose", "no se", "no sé", "unknown" -> "unknown"
        else -> "other"
    }

    companion object {
        fun fromDefinition(def: SeizureAssistantDefinition): FlowEngine {
            val vars = (def.globals?.vars ?: mutableMapOf()).toMutableMap()
            val nodeMap = def.nodeMap()
            val state = FlowRuntimeState(def, nodeMap, vars)
            return FlowEngine(state)
        }
    }
}
