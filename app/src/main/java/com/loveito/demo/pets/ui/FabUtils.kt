package com.loveito.demo.pets.ui

import android.widget.TextView
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Adapta dinámicamente un FloatingActionButton al alto del texto de un TextView.
 * @param iconScale proporción del diámetro para el ícono.
 * @param minDp tamaño mínimo (ignorado si disableMinTouchTarget = true y adaptToTextOnly = true).
 * @param maxDp tamaño máximo.
 * @param adaptToTextOnly usa font metrics en lugar del alto completo de la vista.
 * @param extraPaddingDp padding extra alrededor del texto.
 * @param disableMinTouchTarget si true intenta permitir FAB más pequeño que 48dp (puede afectar accesibilidad).
 */
fun adaptFabToLabel(
    fab: FloatingActionButton,
    label: TextView,
    iconScale: Float = 0.55f,
    minDp: Int = 40,
    maxDp: Int = 72,
    adaptToTextOnly: Boolean = false,
    extraPaddingDp: Int = 4,
    disableMinTouchTarget: Boolean = false
) {
    label.post {
        val dm = label.resources.displayMetrics
        val extraPx = (extraPaddingDp * dm.density).toInt()
        val fontHeight = label.paint.fontMetricsInt.let { it.descent - it.ascent }
        val rawHeight = if (adaptToTextOnly) fontHeight + extraPx else label.height
        if (rawHeight <= 0) return@post
        val minPx = (minDp * dm.density).toInt()
        val maxPx = (maxDp * dm.density).toInt()
        val target = if (disableMinTouchTarget && adaptToTextOnly) {
            // No forzar mínimo; solo limitar por max
            rawHeight.coerceAtMost(maxPx)
        } else {
            rawHeight.coerceIn(minPx, maxPx)
        }
        try { fab.setEnsureMinTouchTargetSize(!disableMinTouchTarget) } catch (_: Exception) {}
        fab.customSize = target
        try { fab.setMaxImageSize((target * iconScale).toInt()) } catch (_: Exception) {}
        fab.requestLayout()
    }
}
