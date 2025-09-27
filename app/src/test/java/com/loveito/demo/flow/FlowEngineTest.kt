package com.loveito.demo.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FlowEngineTest {

    private fun loadJson(): String {
        val file = File("src/main/assets/seizure_assistant_v1_6_0.json")
        require(file.exists()) { "Asset JSON no encontrado: ${file.absolutePath}" }
        return file.readText()
    }

    @Test
    fun flujoCompleto_caminoVerde() {
        val json = loadJson()
        val def = FlowParser.parse(json)
        val engine = FlowEngine.fromDefinition(def)

        // safety (info) avanza solo a q_has_rescue_med
        engine.proceedIfInfoOrAction()
        assertEquals("q_has_rescue_med", engine.getCurrentNodeId())

        // Responder NO (no tiene medicación) -> q_breathing
        engine.answerYesNo("no")
        assertEquals("q_breathing", engine.getCurrentNodeId())

        // Respira normal -> q_injury
        engine.answerYesNo("yes")
        assertEquals("q_injury", engine.getCurrentNodeId())

        // No hay lesión -> wait_end -> on_seizure_end (event)
        engine.answerYesNo("no")
        assertEquals("on_seizure_end", engine.getCurrentNodeId())

        // Simular duración de 100 segundos (<180)
        engine.tickTimer(100)

        // Usuario confirma fin
        engine.triggerEvent("user_confirms_seizure_stopped")
        // final_triage se evalúa automáticamente -> flujo terminado
        assertTrue(engine.isFinished())
        assertEquals("VERDE", engine.getVar("triage_level"))
    }

    @Test
    fun flujo_rojo_por_respiracion_anormal() {
        val json = loadJson()
        val def = FlowParser.parse(json)
        val engine = FlowEngine.fromDefinition(def)

        engine.proceedIfInfoOrAction() // safety -> q_has_rescue_med
        engine.answerYesNo("no") // q_breathing
        engine.answerYesNo("no") // respiración anormal -> q_injury
        engine.answerYesNo("no") // -> on_seizure_end
        engine.triggerEvent("user_confirms_seizure_stopped")

        assertTrue(engine.isFinished())
        assertEquals("ROJO", engine.getVar("triage_level"))
    }
}

