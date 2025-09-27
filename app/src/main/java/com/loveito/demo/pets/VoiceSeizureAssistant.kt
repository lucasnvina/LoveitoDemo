package com.loveito.demo.pets

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import com.loveito.demo.flow.FlowEngine
import com.loveito.demo.flow.FlowParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asistente de triage por voz basado en la definición seizure_assistant_v1_6_0.json ejecutada localmente con FlowEngine.
 * Recorre nodos info -> yes_no -> event -> branch final, con reconocimiento de voz castellano simple.
 */
class VoiceSeizureAssistant(
    private val activity: Activity,
    private val petId: String,
    private val onFinish: (Map<String, Any?>) -> Unit,
    private val onFallback: ((VoiceSeizureAssistant) -> Unit)? = null
) : TextToSpeech.OnInitListener {

    companion object { private const val TAG = "VoiceSeizureAssistant" }

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var engine: FlowEngine? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var tickJob: Job? = null

    private var startedAtMs: Long = 0L
    private val shuttingDown = AtomicBoolean(false)

    private val yesWords = listOf("si", "sí", "yes", "affirmative", "claro")
    private val noWords = listOf("no", "negativo")
    private val unknownWords = listOf("no se", "no sé", "dudo", "no estoy seguro", "no estoy segura")
    private val finishWords = listOf("termino", "terminó", "termino la crisis", "paro", "paró", "se detuvo", "fin", "finalizo", "finalizó")

    private var listening = false
    private var currentListeningForEvent = false

    private val maxErrorRetries = 3
    private var errorRetries = 0
    private var fatalError = false
    private fun createSpanishFallbackLocale(): Locale {
        // Preferimos es-AR pero evitamos constructor deprecated.
        return try {
            Locale.forLanguageTag("es-AR").takeIf { !it.language.isNullOrBlank() } ?: Locale.forLanguageTag("es")
        } catch (e: Exception) {
            Locale.getDefault()
        }
    }

    private var locale: Locale = run {
        val loc = Locale.getDefault()
        if (loc.language.startsWith("es")) loc else createSpanishFallbackLocale()
    }

    private var voiceDisabled = false

    // Exponer engine para fallback manual (sólo lectura externa)
    fun currentEngine(): FlowEngine? = engine
    fun currentNodeType(): String? = engine?.currentNode()?.type
    fun currentPrompt(): String? = when (val n = engine?.currentNode()) {
        is com.loveito.demo.flow.Node.YesNoNode -> n.prompt
        is com.loveito.demo.flow.Node.EventNode -> "Decí 'terminó' cuando la crisis cese"
        else -> null
    }
    fun manualAnswer(answer: String) {
        val eng = engine ?: return
        if (eng.isFinished()) return
        if (eng.currentNode() is com.loveito.demo.flow.Node.YesNoNode) {
            eng.answerYesNo(answer)
            if (eng.isFinished()) manualFinalize() else if (eng.currentNode() is com.loveito.demo.flow.Node.EventNode) { /* esperar evento manual */ }
        }
    }
    fun manualEventFinished() {
        val eng = engine ?: return
        if (eng.isFinished()) return
        if (eng.currentNode() is com.loveito.demo.flow.Node.EventNode) {
            eng.triggerEvent("user_confirms_seizure_stopped")
            if (eng.isFinished()) manualFinalize()
        }
    }
    private fun manualFinalize() {
        val eng = engine ?: return
        val triage = (eng.getVar("triage_level") as? String) ?: inferTriageByRules(eng)
        val duration = (eng.getVar("duration_sec") as? Number)?.toLong()
        val result = mutableMapOf<String, Any?>(
            "severity" to mapTriageToSeverity(triage),
            "title" to mapTriageToTitle(triage),
            "triage_level" to triage,
            "duration_sec" to duration,
            "fallback" to true
        )
        onFinish(result)
        shutdownPreserveEngine() // no null engine until finish delivered
        engine = null
    }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Toast.makeText(activity, "Reconocimiento de voz no disponible en este dispositivo.", Toast.LENGTH_LONG).show()
            // Finalizar devolviendo resultado neutro
            onFinish(
                mapOf(
                    "severity" to "amber",
                    "title" to "TRIAGE",
                    "triage_level" to null,
                    "duration_sec" to 0L,
                    "error" to "speech_recognizer_unavailable"
                )
            )
            return
        }
        tts = TextToSpeech(activity, this)
    }

    private fun loadFlowJson(): Pair<String, String?> {
        val assetMgr = activity.assets
        val assetsList = try { assetMgr.list("")?.joinToString(",") ?: "(sin listado)" } catch (e: Exception) { "(error listando assets: ${e.message})" }
        return try {
            val json = assetMgr.open("seizure_assistant_v1_6_0.json").use { ins ->
                BufferedReader(InputStreamReader(ins)).use { it.readText() }
            }
            json to null
        } catch (e: Exception) {
            val fallback = """{\n  \"version\": \"fallback\",\n  \"start_node\": \"only\",\n  \"nodes\": [ { \"id\": \"only\", \"type\": \"info\", \"prompt\": \"Flujo fallback.\", \"next\": null } ]\n}"""
                .replace("\\n", "\n") // convertir las secuencias literales en saltos reales
            fallback to "Error abriendo asset: ${e::class.simpleName}: ${e.message}. Assets: $assetsList"
        }
    }

    private fun preValidateJson(raw: String) {
        // Validación rápida: revisar tipos declarados
        val typeRegex = "\"type\"\\s*:\\s*\"(.*?)\"".toRegex()
        val allowed = setOf("info","yes_no","event","action","branch")
        val found = typeRegex.findAll(raw).map { it.groupValues[1] }.toList()
        val unknown = found.filter { it !in allowed }.distinct()
        if (unknown.isNotEmpty()) {
            android.util.Log.e(TAG, "Tipos de nodo desconocidos en JSON: $unknown")
        } else {
            android.util.Log.d(TAG, "Tipos de nodo detectados: $found")
        }
        // Comprobar start_node existe en listado de ids
        val idRegex = "\"id\"\\s*:\\s*\"(.*?)\"".toRegex()
        val ids = idRegex.findAll(raw).map { it.groupValues[1] }.toSet()
        val startMatch = "\"start_node\"\\s*:\\s*\"(.*?)\"".toRegex().find(raw)?.groupValues?.getOrNull(1)
        if (startMatch != null && startMatch !in ids) {
            android.util.Log.e(TAG, "start_node '$startMatch' no está en la lista de nodos: $ids")
        }
        android.util.Log.d(TAG, "Total nodos detectados: ${ids.size}")
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Toast.makeText(activity, "Error inicializando TTS", Toast.LENGTH_SHORT).show(); shutdown(); return
        }
        tts?.language = locale
        scope.launch(Dispatchers.IO) {
            val (json, openError) = loadFlowJson()
            preValidateJson(json)
            try {
                val def = FlowParser.parse(json)
                val eng = FlowEngine.fromDefinition(def)
                engine = eng
                startedAtMs = System.currentTimeMillis()
                // Avance inicial (safety info node) auto
                eng.proceedIfInfoOrAction()
            } catch (e: Exception) {
                val snippet = json.take(240).replace('\n', ' ').replace('\r', ' ')
                val stack = android.util.Log.getStackTraceString(e)
                val msg = buildString {
                    append("IllegalArgumentException? -> ")
                    append(e::class.simpleName).append(": ").append(e.message)
                    if (openError != null) append(" | Asset: ").append(openError)
                    append(" | Snippet: ").append(snippet)
                }
                android.util.Log.e(TAG, "Fallo cargando flujo: $msg\nStack:\n$stack")
                launch(Dispatchers.Main) {
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                    onFinish(
                        mapOf(
                            "severity" to "amber",
                            "title" to "TRIAGE", "triage_level" to null,
                            "duration_sec" to 0L,
                            "error" to "flow_load_failure",
                            "detail" to msg,
                            "stack" to stack.take(500)
                        )
                    )
                    shutdown()
                }
                return@launch
            }
            launch(Dispatchers.Main) { proceedVoice() }
        }
    }

    private fun proceedVoice() {
        val eng = engine ?: return
        if (eng.isFinished()) { finalizeAndSpeakResult(); return }
        val node = eng.currentNode()
        when (node.type) {
            "yes_no" -> speak(nodePrompt(node)) { startListeningYesNo() }
            "event" -> { currentListeningForEvent = true; speak(eventPrompt(node)) { startListeningEvent() } }
            "info", "action", "branch" -> {
                // Debería haberse resuelto automáticamente, pero por si acaso
                eng.proceedIfInfoOrAction(); proceedVoice()
            }
            else -> speak("Nodo no soportado") { shutdown() }
        }
        ensureTickLoop()
    }

    private fun nodePrompt(node: Any): String {
        return when (node) {
            is com.loveito.demo.flow.Node.YesNoNode -> node.prompt
            else -> "" }
    }

    private fun eventPrompt(node: Any): String {
        return "Avisame diciendo 'terminó' cuando la crisis cese o tocá el botón si estuviera en pantalla."
    }

    private fun normalize(raw: String): String {
        val base = raw.lowercase(locale).trim()
        return java.text.Normalizer.normalize(base, java.text.Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .replace("[^a-z0-9 áéíóúñ]".toRegex(), " ")
            .replace("\n|\r".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun classifyYesNo(norm: String): String {
        return when {
            yesWords.any { norm.contains(it) } -> "yes"
            noWords.any { norm.contains(it) } -> "no"
            unknownWords.any { norm.contains(it) } -> "unknown"
            else -> "other"
        }
    }

    private fun startListeningYesNo() {
        if (listening) return
        listening = true
        listen { text ->
            listening = false
            val eng = engine ?: return@listen
            val norm = normalize(text)
            val answer = classifyYesNo(norm)
            if (eng.isFinished()) { finalizeAndSpeakResult(); return@listen }
            if (eng.currentNode() is com.loveito.demo.flow.Node.YesNoNode) {
                eng.answerYesNo(answer)
            }
            if (!eng.isFinished() && answer == "other") {
                speak("No te entendí. Decime sí, no o no sé.") { startListeningYesNo() }
            } else {
                proceedVoice()
            }
        }
    }

    private fun startListeningEvent() {
        if (listening) return
        listening = true
        listen { text ->
            listening = false
            val eng = engine ?: return@listen
            val norm = normalize(text)
            if (finishWords.any { norm.contains(it) }) {
                eng.triggerEvent("user_confirms_seizure_stopped")
                proceedVoice()
            } else {
                speak("Aún no registré 'terminó'. Repetí cuando finalice la crisis.") { startListeningEvent() }
            }
        }
    }

    private fun listen(onResult: (String) -> Unit) {
        if (fatalError || shuttingDown.get()) return
        if (speechRecognizer == null) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity)
            } catch (e: Exception) {
                Toast.makeText(activity, "Error creando SpeechRecognizer: ${e.message}", Toast.LENGTH_LONG).show()
                fatalAbort("init_failure", e.message)
                return
            }
        }
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (shuttingDown.get()) return
                val fatalCodes = setOf(
                    SpeechRecognizer.ERROR_CLIENT,
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                    SpeechRecognizer.ERROR_SERVER,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                )
                if (error in fatalCodes) {
                    fatalAbort("speech_error_$error", "Fatal error de reconocimiento ($error)")
                    return
                }
                if (errorRetries >= maxErrorRetries) {
                    fatalAbort("speech_max_retries", "Demasiados intentos fallidos")
                    return
                }
                errorRetries++
                speak("No te entendí, intentemos otra vez.") { onResult("") }
            }
            override fun onResults(results: Bundle?) {
                if (shuttingDown.get()) return
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                errorRetries = 0
                onResult(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: SecurityException) {
            fatalAbort("security_exception", e.message)
        } catch (e: Exception) {
            fatalAbort("start_listening_failure", e.message)
        }
    }

    private fun fatalAbort(code: String, msg: String?) {
        fatalError = true
        // Si hay engine y no terminó, intentar fallback manual sin perder estado
        val eng = engine
        if (eng != null && !eng.isFinished() && onFallback != null) {
            voiceDisabled = true
            try { speechRecognizer?.destroy() } catch (_: Exception) {}
            try { tts?.shutdown() } catch (_: Exception) {}
            speechRecognizer = null; tts = null
            // Notificar fallback
            activity.runOnUiThread { onFallback.invoke(this) }
            return
        }
        speak("Error crítico en reconocimiento de voz. Finalizamos.") {
            onFinish(
                mapOf(
                    "severity" to "amber",
                    "title" to "TRIAGE",
                    "triage_level" to null,
                    "duration_sec" to ((System.currentTimeMillis() - startedAtMs) / 1000),
                    "error" to code,
                    "message" to msg
                )
            )
            shutdown()
        }
    }

    private fun shutdownPreserveEngine() {
        if (!shuttingDown.compareAndSet(false, true)) return
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tickJob?.cancel()
        // engine se mantiene para permitir lectura final si se necesitara (ya seteamos a null en manualFinalize)
    }
    private fun finalizeAndSpeakResult() {
        if (shuttingDown.get()) return
        val eng = engine ?: return
        val triage = (eng.getVar("triage_level") as? String) ?: inferTriageByRules(eng)
        val duration = (eng.getVar("duration_sec") as? Number)?.toLong()
        val severity = mapTriageToSeverity(triage)
        val title = mapTriageToTitle(triage)
        val phrase = when (triage) {
            "ROJO" -> "Situación crítica. Recomendación: asistencia veterinaria inmediata."
            "NARANJA" -> "Muy urgente. Vigila y acude a tu veterinario."
            "AMARILLO" -> "Urgente. Revisión recomendada pronto."
            "VERDE" -> "No urgente. Observa y registra cualquier cambio."
            else -> "Flujo finalizado."
        }
        speak("Triage final: $title. $phrase") {
            val result = mutableMapOf<String, Any?>(
                "severity" to severity,
                "title" to title,
                "triage_level" to triage,
                "duration_sec" to duration
            )
            onFinish(result)
            shutdown()
        }
    }

    private fun inferTriageByRules(eng: FlowEngine): String {
        val dur = (eng.getVar("duration_sec") as? Number)?.toLong() ?: 0L
        val breathing = eng.getVar("breathing_ok") as? Boolean?
        return when {
            dur >= 180 -> "ROJO"
            breathing == false -> "ROJO"
            eng.getVar("injury_present") == true -> "NARANJA"
            else -> "VERDE"
        }
    }

    private fun mapTriageToSeverity(level: String?): String = when (level) {
        "ROJO" -> "red"
        "NARANJA", "AMARILLO" -> "amber"
        "VERDE" -> "green"
        else -> "amber"
    }
    private fun mapTriageToTitle(level: String?): String = when (level) {
        "ROJO" -> "EMERGENCIA"
        "NARANJA" -> "MUY URGENTE"
        "AMARILLO" -> "URGENTE"
        "VERDE" -> "NO URGENTE"
        else -> "TRIAGE"
    }

    private fun speak(text: String, onDone: () -> Unit) {
        val t = tts ?: return onDone()
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "seizure_tts")
        t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { activity.runOnUiThread { if (!shuttingDown.get()) onDone() } }
            override fun onError(utteranceId: String?) { activity.runOnUiThread { if (!shuttingDown.get()) onDone() } }
        })
    }

    private fun ensureTickLoop() {
        if (tickJob != null) return
        tickJob = scope.launch(Dispatchers.Main) {
            while (!shuttingDown.get()) {
                val eng = engine ?: break
                if (eng.isFinished()) break
                val elapsedSec = ((System.currentTimeMillis() - startedAtMs) / 1000).coerceAtLeast(0)
                eng.tickTimer(elapsedSec)
                if (eng.isFinished()) {
                    finalizeAndSpeakResult()
                    break
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tickJob?.cancel()
        engine = null
    }
}
