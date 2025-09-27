package com.loveito.demo.flow

/** Evaluador muy simple para expresiones usadas en branch/conditions.
 * Soporta:
 *  - Comparaciones: ==, >=, <=, >, <
 *  - AND lógico: &&
 *  - Variables: $vars.nombre, $app.nombre, $timer.elapsed_sec
 */
object FlowEvaluator {
    fun eval(exprRaw: String, state: FlowRuntimeState): Boolean {
        val expr = exprRaw.trim().removeSurrounding("(", ")")
        if (expr == "else") return true
        // Split por && manteniendo simplicidad (no hay || en este flujo)
        val parts = expr.split("&&").map { it.trim() }
        for (p in parts) {
            if (!evalSimple(p, state)) return false
        }
        return true
    }

    private fun evalSimple(simple: String, state: FlowRuntimeState): Boolean {
        val ops = listOf(">=", "<=", "==", ">", "<")
        val op = ops.firstOrNull { simple.contains(it) } ?: return false
        val (leftRaw, rightRaw) = simple.split(op, limit = 2).map { it.trim() }
        val left = resolveValue(leftRaw, state)
        val right = resolveValue(rightRaw, state)
        return compare(left, right, op)
    }

    @Suppress("UNCHECKED_CAST")
    private fun compare(left: Any?, right: Any?, op: String): Boolean {
        return when (op) {
            "==" -> left == right
            ">=", ">", "<", "<=" -> {
                val l = (left as? Number)?.toDouble() ?: return false
                val r = (right as? Number)?.toDouble() ?: return false
                when (op) {
                    ">=" -> l >= r
                    ">" -> l > r
                    "<" -> l < r
                    "<=" -> l <= r
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun resolveValue(token: String, state: FlowRuntimeState): Any? {
        if (token.equals("true", true)) return true
        if (token.equals("false", true)) return false
        if (token == "null") return null
        token.toLongOrNull()?.let { return it }
        token.toDoubleOrNull()?.let { return it }
        if (token.startsWith("$")) {
            return when {
                token.startsWith("\$vars.") -> state.vars[token.removePrefix("\$vars.")]
                token.startsWith("\$app.") -> state.appContext[token.removePrefix("\$app.")]
                token == "\$timer.elapsed_sec" -> state.elapsedSec
                else -> null
            }
        }
        // String literal sin comillas - devolver texto
        return token.trim('"')
    }
}
