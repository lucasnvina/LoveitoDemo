package com.loveito.demo.flow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class UiNode {
    data class Info(val id: String, val prompt: String): UiNode()
    data class YesNo(val id: String, val prompt: String): UiNode()
    data class Action(val id: String, val prompt: String?): UiNode()
    data class WaitingEvent(val id: String, val event: String): UiNode()
    object Finished: UiNode()
}

class FlowViewModel : ViewModel() {
    private var engine: FlowEngine? = null

    private val _uiNode = MutableStateFlow<UiNode>(UiNode.Finished)
    val uiNode: StateFlow<UiNode> = _uiNode

    private val _triageLevel = MutableStateFlow<String?>(null)
    val triageLevel: StateFlow<String?> = _triageLevel

    fun start(definition: SeizureAssistantDefinition) {
        engine = FlowEngine.fromDefinition(definition)
        advanceAutomatic()
    }

    private fun advanceAutomatic() {
        val e = engine ?: return
        e.proceedIfInfoOrAction()
        pushNode()
    }

    private fun pushNode() {
        val e = engine ?: return
        if (e.isFinished()) {
            _triageLevel.value = e.getVar("triage_level") as? String
            _uiNode.value = UiNode.Finished
            return
        }
        when (val n = e.currentNode()) {
            is Node.InfoNode -> _uiNode.value = UiNode.Info(n.id, n.prompt)
            is Node.YesNoNode -> _uiNode.value = UiNode.YesNo(n.id, n.prompt)
            is Node.ActionNode -> _uiNode.value = UiNode.Action(n.id, n.prompt)
            is Node.EventNode -> _uiNode.value = UiNode.WaitingEvent(n.id, n.event)
            is Node.BranchNode -> { /* se evaluará automáticamente */ e.proceedIfInfoOrAction(); pushNode(); return }
        }
    }

    fun answerYesNo(answer: String) {
        viewModelScope.launch {
            val e = engine ?: return@launch
            e.answerYesNo(answer)
            pushNode()
        }
    }

    fun triggerEvent(event: String) {
        viewModelScope.launch {
            val e = engine ?: return@launch
            e.triggerEvent(event)
            pushNode()
        }
    }
}

