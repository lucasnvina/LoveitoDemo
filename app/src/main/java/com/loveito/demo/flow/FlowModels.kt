package com.loveito.demo.flow

import com.google.gson.annotations.SerializedName

// Raíz del JSON
data class SeizureAssistantDefinition(
    val version: String,
    val locale: String?,
    @SerializedName("start_node") val startNode: String,
    val globals: GlobalsBlock?,
    @SerializedName("actions_catalog") val actionsCatalog: Map<String, ActionCatalogEntry>?,
    @SerializedName("event_hooks") val eventHooks: List<EventHook>?,
    // NUEVO: bloque de defaults opcional
    val defaults: DefaultsConfig? = null,
    val nodes: List<Node>
) {
    fun nodeMap(): Map<String, Node> = nodes.associateBy { it.id }
}

// --- Defaults ---
// Estructura: {
//   "defaults": { "yes_no": { "other": "...", "timeout": "...", "silence": "..." }, "event": { "timeout": "..." } }
// }

data class DefaultsConfig(
    @SerializedName("yes_no") val yesNo: YesNoDefaults? = null,
    val event: EventDefaults? = null
)

data class YesNoDefaults(
    val other: String? = null,
    val timeout: String? = null,
    val silence: String? = null
)

data class EventDefaults(
    val timeout: String? = null
)

data class GlobalsBlock(
    val vars: MutableMap<String, Any?> = mutableMapOf()
)

data class ActionCatalogEntry(
    val type: String,
    val params: Map<String, Any?>?
)

data class EventHook(
    val id: String,
    val event: String,
    val interrupt: Boolean = false,
    val conditions: List<String> = emptyList(),
    val actions: List<FlowAction> = emptyList()
)

// Acción ejecutable
data class FlowAction(
    @SerializedName("do") val doAction: String,
    val params: Map<String, Any?>? = null
)

// Tipos de nodos
sealed class Node { // removemos backing fields duplicados
    abstract val id: String
    abstract val type: String

    data class InfoNode(
        override val id: String,
        override val type: String = "info",
        val prompt: String,
        val actions: List<FlowAction> = emptyList(),
        val next: String?
    ) : Node()

    data class YesNoNode(
        override val id: String,
        override val type: String = "yes_no",
        val prompt: String,
        @SerializedName("on_answer") val onAnswer: List<AnswerRoute>
    ) : Node()

    data class EventNode(
        override val id: String,
        override val type: String = "event",
        val event: String,
        val actions: List<FlowAction> = emptyList(),
        val next: String?
    ) : Node()

    data class ActionNode(
        override val id: String,
        override val type: String = "action",
        val prompt: String?,
        val actions: List<FlowAction> = emptyList(),
        val next: String?
    ) : Node()

    data class BranchNode(
        override val id: String,
        override val type: String = "branch",
        val conditions: List<BranchCondition>
    ) : Node()
}

// Ramas para yes/no
data class AnswerRoute(
    val when_: String?, // Gson mapea 'when'
    @SerializedName("when") private val _when: String? = null,
    val actions: List<FlowAction> = emptyList(),
    val next: String?
) {
    val predicate: String
        get() = _when ?: when_ ?: "other"
}

// Condiciones en BranchNode
data class BranchCondition(
    val when_: String?,
    @SerializedName("when") private val _when: String? = null,
    val actions: List<FlowAction> = emptyList(),
    val next: String?
) {
    val predicate: String get() = _when ?: when_ ?: "else"
}

// Estado runtime
class FlowRuntimeState(
    val definition: SeizureAssistantDefinition,
    val nodeMap: Map<String, Node>,
    val vars: MutableMap<String, Any?>,
    val appContext: MutableMap<String, Any?> = mutableMapOf(
        // Variables dinámicas externas (pueden setearse desde la app)
        "seizures_count_24h" to 0
    )
) {
    var currentNodeId: String = definition.startNode
    var elapsedSec: Long = 0L
    var finished: Boolean = false
    // Buffer de mensajes de acciones 'say' pendientes de pronunciar
    val sayBuffer: MutableList<String> = mutableListOf()
}
