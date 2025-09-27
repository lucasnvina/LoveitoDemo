package com.loveito.demo.pets

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.widget.Toast
import com.loveito.demo.flow.FlowEngine
import com.loveito.demo.flow.FlowParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Limpio: implementación única sin duplicados. Incluye:
 * - Temporizador y finalización incompleta por falta de respuesta
 * - Acciones 'say' con buffer
 * - Beep auditivo antes de escuchar
 */
class VoiceSeizureAssistant(
    private val activity: Activity,
    private val onFinish: (Map<String, Any?>) -> Unit,
    private val onFallback: ((VoiceSeizureAssistant) -> Unit)? = null,
    private val userName: String? = null,
    private val userSex: String? = null,
    private val petSex: String? = null
) : TextToSpeech.OnInitListener {

    companion object { private const val TAG = "VoiceSeizureAssistant" }

    // --- Nuevo: retardo mínimo entre prompts para que no se "peguen" ---
    private val MIN_INTER_NODE_DELAY_MS = 550L
    // Delay entre fin de TTS y comienzo de escucha (anti-eco, margen de cancelación)
    private val TTS_LISTEN_DELAY_MS = 600L

    // Flag interno para saber si TTS está hablando y evitar iniciar escucha prematuramente
    private var ttsActive: Boolean = false

    // Defaults cargados desde el JSON para reutilizar mensajes (silence/timeout/other)
    private val yesNoDefaults = mutableMapOf<String, String>()
    private val eventDefaults = mutableMapOf<String, String>()

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
    // Eliminado unknownWords: ahora todo lo que no es sí/no pasa a 'other'
    // private val unknownWords = listOf("no se", "no sé", "dudo", "no estoy seguro", "no estoy segura")
    private val finishWords = listOf("termino", "terminó", "termino la crisis", "paro", "paró", "se detuvo", "fin", "finalizo", "finalizó")

    // Auto-commit single word yes/no handling
    private val AUTO_COMMIT_DELAY_MS = 450L
    private var autoCommitRunnable: Runnable? = null
    private var autoCommitValue: String? = null
    private var lastSingleWordPartial: String? = null
    private var lastSingleWordCount: Int = 0
    // Added: tracking generation of recognition sessions and one-shot fallback suppression flag
    private var recognitionGeneration: Long = 0
    private var suppressFallbackMessagesOnce: Boolean = false
    private fun cancelAutoCommit() { autoCommitRunnable?.let { handler.removeCallbacks(it) }; autoCommitRunnable = null; autoCommitValue = null }
    private fun scheduleAutoCommit(value: String) {
        if (!listening) return
        autoCommitValue = value
        cancelAutoCommit()
        autoCommitRunnable = Runnable {
            if (!listening || ttsActive) return@Runnable
            val commitVal = autoCommitValue ?: return@Runnable
            commitSingleWordAnswer(commitVal)
        }
        handler.postDelayed(autoCommitRunnable!!, AUTO_COMMIT_DELAY_MS)
    }
    private fun commitSingleWordAnswer(value: String) {
        val eng = engine ?: return
        cancelAutoCommit(); cancelNoAnswerTimeout(); listening = false
        try { lastManualCancelMs = System.currentTimeMillis(); speechRecognizer?.cancel() } catch (_: Exception) {}
        val ans = if (value.startsWith("n")) "no" else "yes"
        val prevNode = eng.getCurrentNodeId()
        log("ASR", "AUTO_COMMIT_SINGLE_WORD value='$value' answer='$ans'")
        eng.answerYesNo(ans)
        if (eng.getCurrentNodeId() != prevNode) suppressFallbackMessagesOnce = true
        if (eng.isFinished()) { finalizeAndSpeakResult(); return }
        handler.postDelayed({ proceedVoice() }, MIN_INTER_NODE_DELAY_MS)
    }

    private var listening = false
    private val maxErrorRetries = 3
    private var errorRetries = 0
    private var fatalError = false

    private fun createSpanishFallbackLocale(): Locale = try {
        Locale.forLanguageTag("es-AR").takeIf { it.language.isNotBlank() } ?: Locale.forLanguageTag("es")
    } catch (_: Exception) { Locale.getDefault() }

    private var locale: Locale = Locale.getDefault().let { if (it.language.startsWith("es")) it else createSpanishFallbackLocale() }

    // --- No respuesta timeout ---
    private val NO_ANSWER_TIMEOUT_MS = 3000L
    private var noAnswerRunnable: Runnable? = null
    private var noAnswerCycles = 0
    private val MAX_NO_ANSWER_CYCLES = 8
    private val INCOMPLETE_REASON_TIMEOUT = "no_user_response_timeout"

    private fun cancelNoAnswerTimeout() { noAnswerRunnable?.let { handler.removeCallbacks(it) }; noAnswerRunnable = null }

    private fun scheduleNoAnswerTimeout(kind: String, originalPrompt: String) {
        cancelNoAnswerTimeout()
        if (shuttingDown.get() || noAnswerCycles >= MAX_NO_ANSWER_CYCLES) return
        noAnswerRunnable = Runnable {
            if (shuttingDown.get() || !listening) return@Runnable
            noAnswerCycles++
            if (noAnswerCycles > MAX_NO_ANSWER_CYCLES) {
                listening = false
                try { lastManualCancelMs = System.currentTimeMillis(); speechRecognizer?.cancel() } catch (_: Exception) {}
                val elapsedSec = ((System.currentTimeMillis() - startedAtMs) / 1000).coerceAtLeast(0)
                speak("No recibí respuesta. Finalizamos la sesión y guardamos como registro incompleto.") {
                    onFinish(
                        mapOf(
                            "severity" to "amber",
                            "title" to "INCOMPLETA",
                            "triage_level" to null,
                            "duration_sec" to elapsedSec,
                            "incomplete" to true,
                            "reason" to INCOMPLETE_REASON_TIMEOUT
                        )
                    ); shutdown()
                }
                return@Runnable
            }
            listening = false
            try { lastManualCancelMs = System.currentTimeMillis(); speechRecognizer?.cancel() } catch (_: Exception) {}

            if (kind == "yes_no") {
                val msg = yesNoDefaults["timeout"] ?: yesNoDefaults["silence"] ?: "No te entendí."
                // Primer mensaje default, luego repetir pregunta original limpia
                speak(formatPrompt(msg)) {
                    val question = originalPrompt.trim()
                    if (question.isNotEmpty()) speak(question) {
                        handler.postDelayed({ playBeep { startListeningYesNo() } }, 250)
                    } else {
                        handler.postDelayed({ playBeep { startListeningYesNo() } }, 250)
                    }
                }
            } else { // event
                val msg = eventDefaults["timeout"] ?: "No te entendí."
                speak(formatPrompt(msg)) {
                    val repeat = originalPrompt.trim().ifEmpty { "Avisame diciendo 'terminó' cuando finalice la crisis." }
                    speak(repeat) {
                        handler.postDelayed({ playBeep { startListeningEvent() } }, 250)
                    }
                }
            }
        }
        handler.postDelayed(noAnswerRunnable!!, NO_ANSWER_TIMEOUT_MS)
    }

    // --- Beep ---
    private var toneGen: ToneGenerator? = null
    private fun ensureToneGenerator() { if (toneGen == null) try { toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80) } catch (_: Exception) {} }
    private fun playBeep(onDone: () -> Unit) {
        if (shuttingDown.get()) { onDone(); return }
        ensureToneGenerator(); val tg = toneGen ?: return onDone()
        try { tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120); handler.postDelayed({ onDone() }, 140) } catch (_: Exception) { onDone() }
    }

    // --- API manual fallback ---
    @Suppress("unused")
    fun manualAnswer(answer: String) { val eng = engine ?: return; if (eng.isFinished()) return; if (eng.currentNode() is com.loveito.demo.flow.Node.YesNoNode) { eng.answerYesNo(answer); if (eng.isFinished()) manualFinalize() } }
    @Suppress("unused")
    fun manualEventFinished() { val eng = engine ?: return; if (eng.isFinished()) return; if (eng.currentNode() is com.loveito.demo.flow.Node.EventNode) { eng.triggerEvent("user_confirms_seizure_stopped"); if (eng.isFinished()) manualFinalize() } }
    private fun manualFinalize() { val eng = engine ?: return; val triage = (eng.getVar("triage_level") as? String) ?: inferTriageByRules(eng); val duration = (eng.getVar("duration_sec") as? Number)?.toLong(); onFinish(mapOf("severity" to mapTriageToSeverity(triage), "title" to mapTriageToTitle(triage), "triage_level" to triage, "duration_sec" to duration, "fallback" to true)); shutdownPreserveEngine(); engine = null }

    fun start() {
        try { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Toast.makeText(activity, "Reconocimiento de voz no disponible", Toast.LENGTH_LONG).show()
            try { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}
            onFinish(mapOf("severity" to "amber", "title" to "TRIAGE", "triage_level" to null, "duration_sec" to 0L, "error" to "speech_recognizer_unavailable"))
            return
        }
        tts = TextToSpeech(activity, this)
    }

    private fun loadFlowJson(): Pair<String,String?> = try {
        val json = activity.assets.open("seizure_assistant_v1_6_0.json").use { ins -> BufferedReader(InputStreamReader(ins)).use { it.readText() } }
        // Extraer defaults antes de parsear
        extractDefaults(json)
        json to null
    } catch (e: Exception) {
        val fb = """{\n  \"version\": \"fallback\",\n  \"start_node\": \"only\",\n  \"nodes\": [{\"id\":\"only\",\"type\":\"info\",\"prompt\":\"Flujo fallback.\",\"next\":null}]\n}""".trimIndent(); fb to e.message
    }

    private fun extractDefaults(raw: String) {
        try {
            val root = JSONObject(raw)
            val defs = root.optJSONObject("defaults") ?: return
            defs.optJSONObject("yes_no")?.let { obj ->
                listOf("other","timeout","silence").forEach { k ->
                    obj.optString(k)?.takeIf { it.isNotBlank() }?.let { yesNoDefaults[k] = it }
                }
            }
            defs.optJSONObject("event")?.let { obj ->
                listOf("timeout").forEach { k ->
                    obj.optString(k)?.takeIf { it.isNotBlank() }?.let { eventDefaults[k] = it }
                }
            }
        } catch (_: Exception) { /* ignorar parse de defaults */ }
    }

    private fun preValidateJson(raw: String) {
        // Validación ligera: comprobar que start_node exista entre ids
        try {
            val idPattern = Regex("\"id\"\\s*:\\s*\"(.*?)\"")
            val startPattern = Regex("\"start_node\"\\s*:\\s*\"(.*?)\"")
            val ids = idPattern.findAll(raw).map { it.groupValues[1] }.toSet()
            val start = startPattern.find(raw)?.groupValues?.getOrNull(1)
            if (start != null && start !in ids) {
                android.util.Log.w(TAG, "start_node '$start' no está listado en ids (${ids.size})")
            }
        } catch (_: Exception) { /* ignore */ }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { Toast.makeText(activity, "Error inicializando TTS", Toast.LENGTH_SHORT).show(); shutdown(); return }
        tts?.let { t ->
            val preferred = try { Locale.forLanguageTag("es-AR") } catch (_: Exception) { null }
            var applied = false
            if (preferred != null) when (t.isLanguageAvailable(preferred)) {
                TextToSpeech.LANG_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE, TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> { t.language = preferred; applied = true }
            }
            if (!applied) t.language = locale
        }
        // Siempre cargar versión local desde assets
        scope.launch(Dispatchers.IO) {
            val (json, openErr) = loadFlowJson(); preValidateJson(json)
            try {
                val def = FlowParser.parse(json)
                initEngineAndStart(def)
            } catch (e: Exception) {
                val msg = "Error flujo local: ${e.message} assetErr=$openErr"
                launch(Dispatchers.Main) {
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                    onFinish(mapOf(
                        "severity" to "amber",
                        "title" to "TRIAGE",
                        "triage_level" to null,
                        "duration_sec" to 0L,
                        "error" to "flow_load_failure_local",
                        "detail" to msg
                    ))
                    shutdown()
                }
            }
        }
    }

    private fun initEngineAndStart(definition: com.loveito.demo.flow.SeizureAssistantDefinition) {
        try {
            val eng = FlowEngine.fromDefinition(definition)
            engine = eng; startedAtMs = System.currentTimeMillis()
            val first = eng.currentNode()
            scope.launch(Dispatchers.Main) {
                if (first is com.loveito.demo.flow.Node.InfoNode) {
                    val prompt = formatPrompt(first.prompt)
                    speak(prompt) { eng.proceedIfInfoOrAction(); proceedVoice() }
                } else { eng.proceedIfInfoOrAction(); proceedVoice() }
            }
        } catch (e: Exception) {
            scope.launch(Dispatchers.Main) {
                val msg = "Error iniciando engine: ${e.message}"
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                onFinish(mapOf(
                    "severity" to "amber",
                    "title" to "TRIAGE",
                    "triage_level" to null,
                    "duration_sec" to 0L,
                    "error" to "flow_engine_init_failed",
                    "detail" to msg
                ))
                shutdown()
            }
        }
    }

    private var reassuranceScheduled = false // se mantiene por compatibilidad pero no se usa
    private var lastManualCancelMs: Long = 0L
    private val MANUAL_CANCEL_SUPPRESS_WINDOW_MS = 800L

    // Control para evitar repetir prompt completo tras fallback 'other'
    private var awaitingReanswer = false
    private var awaitingReanswerNodeId: String? = null

    private fun proceedVoice() {
        val eng = engine ?: return
        if (eng.hasPendingSay()) { drainSayQueue { proceedVoice() }; return }
        if (eng.isFinished()) { finalizeAndSpeakResult(); return }
        val current = eng.currentNode()
        if (awaitingReanswer && awaitingReanswerNodeId != null && awaitingReanswerNodeId != current.id) {
            awaitingReanswer = false; awaitingReanswerNodeId = null
        }
        when (current.type) {
            "yes_no" -> {
                if (awaitingReanswer && awaitingReanswerNodeId == current.id) {
                    handler.postDelayed({ playBeep { startListeningYesNo() } }, TTS_LISTEN_DELAY_MS)
                } else {
                    handler.postDelayed({ speak(nodePrompt(current)) {
                        handler.postDelayed({ playBeep { startListeningYesNo() } }, TTS_LISTEN_DELAY_MS)
                    } }, MIN_INTER_NODE_DELAY_MS)
                }
            }
            "event" -> {
                // Ya se dio la instrucción en el nodo info previo (p.ej. wait_end). No repetir texto extra.
                handler.postDelayed({ playBeep { startListeningEvent() } }, MIN_INTER_NODE_DELAY_MS)
            }
            "info" -> {
                val prompt = (current as? com.loveito.demo.flow.Node.InfoNode)?.prompt
                if (!prompt.isNullOrBlank()) {
                    handler.postDelayed({ speak(formatPrompt(prompt)) {
                        eng.proceedIfInfoOrAction(); handler.postDelayed({ proceedVoice() }, MIN_INTER_NODE_DELAY_MS)
                    } }, if (MIN_INTER_NODE_DELAY_MS > 300) 300 else MIN_INTER_NODE_DELAY_MS / 2)
                } else { eng.proceedIfInfoOrAction(); proceedVoice() }
            }
            "action", "branch" -> { eng.proceedIfInfoOrAction(); handler.postDelayed({ proceedVoice() }, MIN_INTER_NODE_DELAY_MS) }
            else -> speak("Nodo no soportado") { shutdown() }
        }
        ensureTickLoop()
    }

    private fun drainSayQueue(onComplete: () -> Unit) {
        val eng = engine ?: return onComplete()
        fun next() {
            val msg = eng.pollSayMessage() ?: run {
                handler.postDelayed(onComplete, MIN_INTER_NODE_DELAY_MS); return
            }
            val fallbackSet = mutableSetOf<String>().apply { yesNoDefaults.values.forEach { add(formatPrompt(it)) } }
            if (suppressFallbackMessagesOnce && fallbackSet.contains(msg.trim())) {
                log("CLS", "Suppressed fallback message='$msg' after decisive answer")
                suppressFallbackMessagesOnce = false
                next(); return
            }
            suppressFallbackMessagesOnce = false
            speak(msg) { drainSayQueue(onComplete) }
        }
        next()
    }

    private fun formatPrompt(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = raw
        val safeUser = (userName ?: "usuario").trim().ifEmpty { "usuario" }
        text = text.replace("%nombre_de_usuario%", safeUser, ignoreCase = true)
        fun norm(s: String?): String? = s?.lowercase()?.let {
            when {
                it.startsWith("m") || it.startsWith("h") || it == "male" -> "m"
                it.startsWith("f") || it == "hembra" || it == "female" -> "f"
                else -> null
            }
        }
        val u = norm(userSex); val p = norm(petSex)
        val regex = Regex("\\{\\{([^{}]+)\\}\\}")
        return regex.replace(text) { m ->
            val body = m.groupValues[1]
            val parts = body.split('|').map { it.trim() }
            val map = mutableMapOf<String,String>()
            parts.forEach { seg ->
                val idx = seg.indexOf(':'); if (idx>0 && idx < seg.length-1) map[seg.substring(0,idx).trim()] = seg.substring(idx+1).trim()
            }
            val petCand = when (p) { "m" -> map["mascota_m"] ?: map["pm"]; "f" -> map["mascota_f"] ?: map["pf"]; else -> null }
            val userCand = when (u) { "m" -> map["m"] ?: map["user_m"]; "f" -> map["f"] ?: map["user_f"]; else -> null }
            petCand ?: userCand ?: map.values.firstOrNull() ?: parts.first()
        }
    }

    private fun nodePrompt(node: Any): String = (node as? com.loveito.demo.flow.Node.YesNoNode)?.prompt?.let { formatPrompt(it) } ?: ""
    private fun normalize(raw: String): String = java.text.Normalizer.normalize(raw.lowercase(locale).trim(), java.text.Normalizer.Form.NFD)
        .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        .replace("[^a-z0-9 áéíóúñ]".toRegex(), " ")
        .replace("[\\n\\r]".toRegex(), " ") // reemplazo regex simplificado (antes "\n|\r")
        .replace("\\s+".toRegex(), " ").trim()
    private fun classifyYesNo(norm: String): String = when {
        yesWords.any { norm.contains(it) } -> "yes"
        noWords.any { norm.contains(it) } -> "no"
        else -> "other"
    }
    // Reemplazo del clasificador token-based para distinguir claramente 'no' vs 'no se / no sé'
    private fun classifyYesNoTokens(norm: String): String {
        val trimmed = norm.trim()
        if (trimmed.isEmpty()) return "other"
        val tokens = trimmed.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return "other"
        val first = tokens.first()
        val second = tokens.getOrNull(1)
        val third = tokens.getOrNull(2)
        val joined = tokens.joinToString(" ")

        val uncertaintyKeywords = setOf("dudo", "dudoso", "dudosa", "inseguro", "insegura", "inseguridad")
        val yesTokens = setOf("si", "sí", "yes", "affirmative", "claro")
        val noTokens = setOf("no", "negativo")

        // Patrones explícitos de desconocimiento ahora se consideran 'other'
        val isFormerUnknown = (
            (first == "no" && (second == "se" || second == "sé")) ||
            (first == "nose") ||
            (first == "no" && second == "estoy" && (third == "seguro" || third == "segura")) ||
            joined.startsWith("no se ") ||
            joined == "no se" || joined == "no sé"
        )
        if (isFormerUnknown) {
            log("CLS", "Pattern interpreted as NO (previously unknown): tokens=$tokens")
            return "no"
        }

        // Primera palabra decisiva
        if (first in noTokens) { log("CLS", "Detected NO by first token='$first'"); return "no" }
        if (first in yesTokens) { log("CLS", "Detected YES by first token='$first'"); return "yes" }

        // Tokens internos
        if (tokens.any { it in noTokens }) { log("CLS", "Detected NO inside tokens=$tokens"); return "no" }
        if (tokens.any { it in yesTokens }) { log("CLS", "Detected YES inside tokens=$tokens"); return "yes" }

        // Keywords de incertidumbre -> other
        if (tokens.any { it in uncertaintyKeywords }) { log("CLS", "Treating uncertainty as OTHER tokens=$tokens"); return "other" }

        // Heurística substring
        when {
            yesWords.any { trimmed.contains(it) } -> { log("CLS", "Heuristic YES by substring match"); return "yes" }
            noWords.any { trimmed.contains(it) } -> { log("CLS", "Heuristic NO by substring match"); return "no" }
        }
        log("CLS", "Classified OTHER tokens=$tokens")
        return "other"
    }

    // --- Helper para reintentar escucha sin re-procesar motor ---
    private fun restartListeningSameNode() {
        val type = engine?.currentNode()?.type
        handler.postDelayed({
            when (type) {
                "yes_no" -> { playBeep { startListeningYesNo() } }
                "event" -> { playBeep { startListeningEvent() } }
                else -> proceedVoice()
            }
        }, 250)
    }

    private val echoPhrases = listOf(
        "no te entendi", "no te entendí", "tenes medi", "tenes medic", "medicacion de rescate", "medicación de rescate"
    )
    private var lastYesNoNodeIdForAttempts: String? = null
    private var consecutiveOtherAttempts: Int = 0

    private fun log(tag: String, msg: String) { android.util.Log.d("VSA-$tag", msg) }

    // Guarda último parcial corto (para detectar "no" que luego se expande erróneamente a "no se")
    private var lastPartialYesNo: String? = null

    private fun pickBestYesNoHypothesis(list: List<String>?): String {
        if (list.isNullOrEmpty()) return ""
        // Normalizamos y limpiamos puntuación simple
        val cleaned = list.map { it.trim().lowercase(locale).replace("[.,;:!?]$".toRegex(), "") }
        // 1. Exactos priorizados (en orden de fuerza)
        val exactPriority = listOf("no", "si", "sí", "yes")
        exactPriority.firstOrNull { cand -> cleaned.any { it == cand } }?.let { pr ->
            // Devolver la forma exacta encontrada original más corta (por si hay varias)
            val idx = cleaned.indexOfFirst { it == pr }
            return list[idx]
        }
        // 2. Tokens que empiezan con no/si + algo muy corto (<=2 chars extra) => quizá ruido de ASR, preferir forma corta si parcial coincidía
        val shortExpansion = cleaned.filter { it.startsWith("no ") || it.startsWith("si ") || it.startsWith("sí ") }
        if (shortExpansion.isNotEmpty()) {
            // Si hubo parcial exacto 'no' o 'si', confiamos en el parcial
            lastPartialYesNo?.let { lp ->
                val lpNorm = lp.lowercase(locale)
                if (lpNorm == "no" || lpNorm == "si" || lpNorm == "sí") return lp
            }
        }
        // 3. Preferir hipótesis con menor número de palabras si contiene no/si al inicio
        val scored = cleaned.sortedWith(compareBy({ it.split(' ').size }, { it.length }))
        scored.firstOrNull { it == "no" || it == "si" || it == "sí" || it == "yes" }?.let { simple ->
            val idx = cleaned.indexOf(simple); return list[idx]
        }
        // 4. Fallback: si hay 'no se'/'no sé' y no había parcial puro, mantenerlo; si había parcial puro 'no', usar parcial
        val unknownPatterns = setOf("no se","no sé","nose")
        if (cleaned.any { it in unknownPatterns }) {
            if (lastPartialYesNo != null) {
                val lp = lastPartialYesNo!!.lowercase(locale)
                if (lp == "no" || lp == "si" || lp == "sí") return lastPartialYesNo!!
            }
            val idx = cleaned.indexOfFirst { it in unknownPatterns }
            // Si solo tenemos la forma extendida, devolvemos tal cual para que la clasificación la trate como 'no'
            return list[idx]
        }
        // 5. Último recurso: la más corta (evitamos expansión a frases erróneas)
        val shortest = cleaned.minByOrNull { it.length } ?: cleaned.first()
        return list[cleaned.indexOf(shortest)]
    }

    private fun startListeningYesNo() {
        if (listening || ttsActive) return
        val currentGen = ++recognitionGeneration
        val promptCopy = (engine?.currentNode() as? com.loveito.demo.flow.Node.YesNoNode)?.prompt?.let { formatPrompt(it) } ?: ""
        listening = true
        lastPartialYesNo = null
        scheduleNoAnswerTimeout("yes_no", promptCopy)
        listen(kind = "yes_no", gen = currentGen) { rawText ->
            if (currentGen != recognitionGeneration) { log("ASR","IGNORED_STALE_RESULT gen=$currentGen active=$recognitionGeneration"); return@listen }
            cancelNoAnswerTimeout(); listening = false
            val eng = engine ?: return@listen
            val normText = normalize(rawText)
            log("ASR", "YES_NO raw='$rawText' norm='$normText' node=${eng.currentNode().id}")
            if (ttsActive) {
                handler.postDelayed({ if (currentGen == recognitionGeneration) playBeep { startListeningYesNo() } }, 400)
                return@listen
            }
            if (normText.isBlank() && (rawText.equals("no", true) || rawText.equals("si", true) || rawText.equals("sí", true))) {
                val forced = rawText.lowercase(Locale.getDefault())
                val answerForced = if (forced.startsWith("n")) "no" else "yes"
                val prevNode = eng.getCurrentNodeId()
                log("ASR", "FORCE_RECOVER answer=$answerForced from raw='$rawText'")
                eng.answerYesNo(answerForced)
                if (eng.getCurrentNodeId() != prevNode) suppressFallbackMessagesOnce = true
                if (eng.isFinished()) { finalizeAndSpeakResult(); return@listen }
                handler.postDelayed({ proceedVoice() }, MIN_INTER_NODE_DELAY_MS)
                return@listen
            }
            if (normText.isBlank()) {
                val msg = yesNoDefaults["silence"] ?: yesNoDefaults["timeout"] ?: "No te entendí."
                speak(formatPrompt(msg)) {
                    val question = promptCopy.trim()
                    if (question.isNotEmpty()) speak(question) {
                        handler.postDelayed({ if (currentGen == recognitionGeneration) playBeep { startListeningYesNo() } }, 450)
                    } else handler.postDelayed({ if (currentGen == recognitionGeneration) playBeep { startListeningYesNo() } }, 450)
                }
                return@listen
            }
            val answer = classifyYesNoTokens(normText)
            val prevNode = eng.getCurrentNodeId()
            log("ASR", "CLASSIFIED_TOKENS answer=$answer tokens='${normText.split(' ')}'")
            eng.answerYesNo(answer)
            if (eng.getCurrentNodeId() != prevNode && (answer == "yes" || answer == "no")) suppressFallbackMessagesOnce = true
            if (eng.isFinished()) { finalizeAndSpeakResult(); return@listen }
            handler.postDelayed({ proceedVoice() }, MIN_INTER_NODE_DELAY_MS)
        }
    }

    private fun startListeningEvent() {
        if (listening) return
        // Ya se indicó en el nodo info anterior cómo finalizar. Simplemente escuchar.
        val basePrompt = "" // no hablar nada aquí
        listening = true
        scheduleNoAnswerTimeout("event", basePrompt)
        listen { text ->
            cancelNoAnswerTimeout(); listening = false
            val eng = engine ?: return@listen
            val norm = normalize(text)
            if (finishWords.any { norm.contains(it) }) {
                eng.triggerEvent("user_confirms_seizure_stopped"); noAnswerCycles = 0; handler.postDelayed({ proceedVoice() }, MIN_INTER_NODE_DELAY_MS)
            } else {
                val msg = eventDefaults["timeout"] ?: "No te entendí."
                speak(formatPrompt(msg)) {
                    handler.postDelayed({ playBeep { startListeningEvent() } }, 300)
                }
            }
        }
    }

    private fun listen(kind: String = "generic", gen: Long? = null, onResult: (String) -> Unit) {
        if (fatalError || shuttingDown.get()) return
        if (speechRecognizer == null) try { speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity) } catch (e: Exception) { fatalAbort("init_failure", e.message); return }
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        val sessionGen = recognitionGeneration
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (sessionGen != recognitionGeneration) return
                // ...existing code...
            }
            override fun onResults(results: Bundle?) {
                if (sessionGen != recognitionGeneration) { log("ASR","IGNORE_RESULTS_STALE gen=$sessionGen active=$recognitionGeneration"); return }
                cancelAutoCommit()
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = if (kind == "yes_no") pickBestYesNoHypothesis(list) else list?.maxByOrNull { it.length }?.trim().orEmpty()
                log("ASR", "FINAL kind=$kind list=$list chosen='$best'")
                errorRetries = 0; onResult(best)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (sessionGen != recognitionGeneration) return
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    if (kind == "yes_no") {
                        lastPartialYesNo = partial.trim()
                        val norm = lastPartialYesNo!!.lowercase(locale).trim()
                        val single = !norm.contains(' ') && (norm == "no" || norm == "si" || norm == "sí")
                        if (single) {
                            if (lastSingleWordPartial == norm) {
                                lastSingleWordCount++
                            } else {
                                lastSingleWordPartial = norm
                                lastSingleWordCount = 1
                            }
                            // Commit inmediato tras 2 repeticiones para evitar degradación a timeout
                            if (lastSingleWordCount >= 2 && listening && !ttsActive) {
                                log("ASR", "INSTANT_COMMIT_SINGLE_WORD word='$norm' count=$lastSingleWordCount")
                                commitSingleWordAnswer(norm)
                                return
                            } else {
                                scheduleAutoCommit(norm) // programar (reinicia el timer cada parcial)
                            }
                        } else {
                            // Aparece más texto -> cancelar auto-commit previo y reset contador
                            cancelAutoCommit()
                            lastSingleWordPartial = null
                            lastSingleWordCount = 0
                        }
                    }
                    log("ASR", "PARTIAL kind=$kind partial='${partial.trim()}'")
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try { speechRecognizer?.startListening(intent) } catch (e: SecurityException) { fatalAbort("security_exception", e.message) } catch (e: Exception) { fatalAbort("start_listening_failure", e.message) }
    }

    private fun fatalAbort(code: String, msg: String?) {
        fatalError = true
        val eng = engine
        if (eng != null && !eng.isFinished() && onFallback != null) {
            try { speechRecognizer?.destroy() } catch (_: Exception) {}
            try { tts?.shutdown() } catch (_: Exception) {}
            speechRecognizer = null; tts = null
            activity.runOnUiThread { onFallback.invoke(this) }
            return
        }
        speak("Error crítico en reconocimiento de voz. Finalizamos.") {
            onFinish(mapOf("severity" to "amber", "title" to "TRIAGE", "triage_level" to null, "duration_sec" to ((System.currentTimeMillis()-startedAtMs)/1000), "error" to code, "message" to msg))
            shutdown()
        }
    }

    private fun finalizeAndSpeakResult() {
        if (shuttingDown.get()) return
        val eng = engine ?: return
        // Si el flujo (JSON) ya seteó triage_level lo usamos; si no, aplicamos reglas fallback simplificadas
        var triage = (eng.getVar("triage_level") as? String)
        val duration = (eng.getVar("duration_sec") as? Number)?.toLong()
        if (triage == null) {
            triage = inferTriageByRules(eng)
            android.util.Log.i(TAG, "[triage] Fallback inference applied (flow did not set triage_level). Inferred=$triage duration=${duration} breathing=${eng.getVar("breathing_ok")} injury=${eng.getVar("injury_present")}")
        }
        val phrase = when (triage) {
            "ROJO" -> "Situación crítica. Recomendación: asistencia veterinaria inmediata."
            "NARANJA" -> "Muy urgente. Vigila y acude a tu veterinario."
            "AMARILLO" -> "Urgente. Revisión recomendada pronto."
            "VERDE" -> "No urgente. Observa y registra cualquier cambio."
            else -> "Flujo finalizado."
        }
        speak("Triage final: ${mapTriageToTitle(triage)}. $phrase") {
            onFinish(mapOf(
                "severity" to mapTriageToSeverity(triage),
                "title" to mapTriageToTitle(triage),
                "triage_level" to triage,
                "duration_sec" to duration
            ))
            shutdown()
        }
    }

    // Reglas fallback minimalistas: sirven sólo si el flujo no seteó triage_level (por finalización anticipada / emergencia / interrupción).
    // NOTA: El JSON tiene lógica más rica (branch final) que contempla más condiciones (seizures_count_24h, falta de administración, etc.).
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
    private fun mapTriageToSeverity(level: String?): String = when (level) { "ROJO" -> "red"; "NARANJA", "AMARILLO" -> "amber"; "VERDE" -> "green"; else -> "amber" }
    private fun mapTriageToTitle(level: String?): String = when (level) { "ROJO" -> "EMERGENCIA"; "NARANJA" -> "MUY URGENTE"; "AMARILLO" -> "URGENTE"; "VERDE" -> "NO URGENTE"; else -> "TRIAGE" }

    private fun speak(text: String, onDone: () -> Unit) {
        val t = tts ?: return onDone()
        val say = formatPrompt(text)
        ttsActive = true
        t.speak(say, TextToSpeech.QUEUE_FLUSH, null, "seizure_tts_${System.currentTimeMillis()}")
        t.setOnUtteranceProgressListener(object: android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { activity.runOnUiThread { ttsActive = false; if (!shuttingDown.get()) onDone() } }
            override fun onError(utteranceId: String?) { activity.runOnUiThread { ttsActive = false; if (!shuttingDown.get()) onDone() } }
        })
    }

    private fun ensureTickLoop() {
        if (tickJob != null) return
        tickJob = scope.launch(Dispatchers.Main) {
            while (!shuttingDown.get()) {
                val eng = engine ?: break
                if (eng.isFinished()) break
                val elapsedSec = ((System.currentTimeMillis()-startedAtMs)/1000).coerceAtLeast(0)
                eng.tickTimer(elapsedSec)
                if (eng.isFinished()) { finalizeAndSpeakResult(); break }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun shutdownPreserveEngine() {
        if (!shuttingDown.compareAndSet(false, true)) return
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tickJob?.cancel(); cancelNoAnswerTimeout()
        try { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}
        try { toneGen?.release() } catch (_: Exception) {}
        toneGen = null
        // engine se conserva (lo limpia manualFinalize)
    }

    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tickJob?.cancel(); cancelNoAnswerTimeout()
        try { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}
        try { toneGen?.release() } catch (_: Exception) {}
        toneGen = null
        engine = null
    }
}
