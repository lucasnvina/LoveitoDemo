package com.loveito.demo.pets

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import com.loveito.demo.R
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Genera el PDF del reporte de la mascota con formato mejorado:
 * - Encabezado por página (foto + nombre + fecha)
 * - Footer con número de página
 * - Sección de resumen (crisis totales, promedio, tiempo desde última)
 * - Tablas simples para medicaciones y profesionales con encabezado sombreado y filas cebra
 * - Paginación adecuada evitando cortar títulos solos
 */
class PetReportPdfBuilder(
    private val context: Context,
    private val includeCrises: Boolean,
    private val includeMedications: Boolean,
    private val includeProfessionals: Boolean,
    private val includeCare: Boolean,
    private val compactMode: Boolean = false,
    private val careRecs: List<CareRecommendation> = emptyList(),
) {
    private val dateFmtFull = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    private val dateFmtShort = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private val fileDateFmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f
        color = Color.BLACK
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.rgb(60,21,32)
    }
    private val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.rgb(90,40,50)
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f
        color = Color.DKGRAY
    }
    private val linePaint = Paint().apply {
        strokeWidth = 1f
        color = Color.LTGRAY
    }
    private val tableHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.BLACK
    }
    private val tableHeaderBgPaint = Paint().apply {
        color = Color.argb(40, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val zebraBgPaint = Paint().apply {
        color = Color.argb(18, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // Brand colors
    init {
        val border = ContextCompat.getColor(context, R.color.border_default)
        val textPrimary = ContextCompat.getColor(context, R.color.text_primary)
        val titleColor = ContextCompat.getColor(context, R.color.maroon_900)
        val sectionColor = ContextCompat.getColor(context, R.color.brown_800)
        bodyPaint.color = textPrimary
        titlePaint.color = titleColor
        sectionPaint.color = sectionColor
        linePaint.color = border
        // backgrounds with light alpha from brand palette
        tableHeaderBgPaint.color = ColorUtils.setAlphaComponent(textPrimary, 28) // ~11%
        zebraBgPaint.color = ColorUtils.setAlphaComponent(border, 20) // very light
    }

    private val pageWidth = 595
    private val pageHeight = 842
    private val marginLeft = 40f
    private val marginTop = 40f
    private val marginRight = 40f
    private val marginBottom = 50f
    private val usableWidth = pageWidth - marginLeft - marginRight

    private lateinit var pdf: PdfDocument
    private var pageNumber = 0
    private lateinit var page: PdfDocument.Page
    private lateinit var canvas: Canvas
    private var y = 0f

    private var petPhoto: Bitmap? = null
    private var appLogo: Bitmap? = null
    private var totalPages: Int = 0
    private var headerPhotoHeight: Float = 0f
    private var headerTitle: String = ""

    private var compactEnabled: Boolean = compactMode

    @WorkerThread
    fun build(pet: Pet, crises: List<Crisis>): File {
        loadPetPhotoSync(pet.photoUrl)
        loadAppLogo()
        headerTitle = context.getString(R.string.share_email_subject, pet.name)
        if (compactEnabled) applyCompactSizing()
        // First simulation
        totalPages = simulateLayout(pet, crises)
        // Auto-compact if too many pages
        if (!compactEnabled && totalPages >= 6) {
            compactEnabled = true
            applyCompactSizing()
            totalPages = simulateLayout(pet, crises)
        }
        pdf = PdfDocument()
        pageNumber = 0
        newPage()
        drawHeader(pet, firstPage = true)
        y += 8f
        drawGeneratedTimestamp()
        y += 12f
        drawBasicPetInfo(pet)
        y += 16f
        drawSummarySection(pet, crises)
        y += if (compactEnabled) 10f else 14f
        if (includeCrises) drawCrisesSection(crises)
        if (includeMedications) drawMedicationsSection(pet)
        if (includeProfessionals) drawProfessionalsSection(pet)
        if (includeCare) drawCareSection(pet)
        finishPage()
        // Nombre de archivo: "%nombredelamascota% - %fecha(YYYYMMDD)%"
        val safeName = sanitizeFileName(pet.name.ifBlank { "Mascota" })
        val datePart = fileDateFmt.format(Date())
        val file = File(context.cacheDir, "$safeName - $datePart.pdf")
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    private fun applyCompactSizing() {
        // Reduce text sizes slightly and tighten layout spacing
        bodyPaint.textSize = 10f
        tableHeaderPaint.textSize = 10f
        smallPaint.textSize = 8f
        sectionPaint.textSize = 13f
        // Keep title readable
        // titlePaint.textSize remains
    }

    private fun rowPadV(): Float = if (compactEnabled) 4f else 6f

    private fun sanitizeFileName(input: String): String {
        val trimmed = input.trim().ifBlank { "Mascota" }
        // Remover caracteres no permitidos en archivos, incluyendo espacio -> guion
        val replaced = trimmed
            .replace("[\\:*?\"<>|/]".toRegex(), "-")
            .replace("[\\n\\r]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .replace("-+".toRegex(), "-")
        return replaced.trim().take(100)
    }

    private fun loadPetPhotoSync(url: String?) {
        if (url.isNullOrBlank()) return
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connect()
            val bytes = conn.inputStream.readBytes()
            conn.disconnect()
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            val maxHeaderWidth = 280f
            val maxHeaderHeight = 400f
            val scaleFit = minOf(1f, maxHeaderWidth / original.width.toFloat(), maxHeaderHeight / original.height.toFloat())
            val base = if (scaleFit < 0.999f) Bitmap.createScaledBitmap(original, (original.width * scaleFit).toInt(), (original.height * scaleFit).toInt(), true) else original
            // Reducción adicional solicitada: 50% respecto al tamaño actual mostrado
            val halfW = (base.width * 0.5f).toInt().coerceAtLeast(1)
            val halfH = (base.height * 0.5f).toInt().coerceAtLeast(1)
            petPhoto = Bitmap.createScaledBitmap(base, halfW, halfH, true)
            headerPhotoHeight = petPhoto?.height?.toFloat() ?: 0f
        } catch (_: Exception) {}
    }

    private fun loadAppLogo() {
        try {
            val d = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.mipmap.ic_launcher)
            val raw = d?.toBitmap()
            raw?.let {
                val size = 48
                // Usar createScaledBitmap está bien aquí (dependencia mínima), mantener por compatibilidad
                appLogo = Bitmap.createScaledBitmap(it, size, size, true)
            }
        } catch (_: Exception) { }
    }

    // --- Simulación de layout para contar páginas (misma lógica de saltos sin dibujar) ---
    private fun simulateLayout(pet: Pet, crises: List<Crisis>): Int {
        var simPage = 1
        var simY = marginTop
        val lineHeight = bodyPaint.textSize + 4f
        val photoH = if (petPhoto != null) headerPhotoHeight.coerceAtLeast(48f) else 48f
        fun header(first: Boolean) {
            simY += if (first) photoH + 8f + 12f + 12f else sectionPaint.textSize + 4f + 12f
        }
        fun ensure(h: Float) {
            if (simY + h > pageHeight - marginBottom) {
                simPage += 1
                simY = marginTop
                header(false)
            }
        }
        header(true)
        // timestamp + basic info + summary
        ensure(8f + smallPaint.textSize + 12f)
        simY += 8f + smallPaint.textSize + 12f
        val basicLines = listOfNotNull(
            pet.name,
            pet.breed?.takeIf { it.isNotBlank() },
            pet.weightKg?.toString(),
            pet.sex,
            pet.birthDate?.toString(),
            pet.heightCm?.toString(),
            pet.lengthCm?.toString()
        ).size
        ensure(basicLines * lineHeight + 16f)
        simY += basicLines * lineHeight + 16f
        // summary (estimado 5 líneas)
        ensure(5 * lineHeight + 32f)
        simY += 5 * lineHeight + 32f + 14f // extra separación
        if (includeCrises) {
            val crisisLines = crises.size.coerceAtLeast(1)
            ensure(lineHeight * (crisisLines + 3) + 24f)
            simY += lineHeight * (crisisLines + 3) + 24f
        }
        if (includeMedications) {
            val meds = pet.medications.size.coerceAtLeast(1)
            ensure(lineHeight * (meds + 4) + 24f)
            simY += lineHeight * (meds + 4) + 24f
        }
        if (includeProfessionals) {
            val pros = pet.professionals.size.coerceAtLeast(1)
            ensure(lineHeight * (pros + 4) + 24f)
            simY += lineHeight * (pros + 4) + 24f
        }
        if (includeCare) {
            ensure(lineHeight * 6 + 24f)
            simY += lineHeight * 6 + 24f
        }
        return simPage
    }

    private fun newPage() {
        pageNumber += 1
        val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        page = pdf.startPage(info)
        canvas = page.canvas
        y = marginTop
    }

    private fun finishPage() {
        drawFooter()
        pdf.finishPage(page)
    }

    private fun ensureSpace(required: Float) {
        if (y + required > pageHeight - marginBottom) {
            finishPage()
            newPage()
            drawHeaderCached()
            y += 12f
        }
    }

    private fun drawHeader(pet: Pet, firstPage: Boolean) {
        val title = pet.name
        val photo = petPhoto
        val startY = y
        if (firstPage && photo != null) {
            val rect = RectF(marginLeft, y, marginLeft + photo.width, y + photo.height)
            canvas.drawBitmap(photo, null, rect, null)
        }
        val textX = if (firstPage && photo != null) marginLeft + (photo.width) + 16f else marginLeft
        canvas.drawText(title, textX, startY + 24f, titlePaint)
        if (firstPage) canvas.drawText(pet.breed?.takeIf { it.isNotBlank() } ?: "", textX, startY + 24f + 18f, bodyPaint)
        // Logo (arriba derecha)
        appLogo?.let { logo ->
            val lx = pageWidth - marginRight - logo.width
            val ly = startY
            canvas.drawBitmap(logo, lx, ly, null)
        }
        y = startY + if (firstPage && photo != null) photo.height.toFloat().coerceAtLeast(48f) else 48f
        y += 8f
        canvas.drawLine(marginLeft, y, pageWidth - marginRight, y, linePaint)
        y += 12f
    }

    private fun drawHeaderCached() {
        val text = headerTitle.ifBlank { context.getString(R.string.app_name) }
        canvas.drawText(text, marginLeft, y, sectionPaint)
        appLogo?.let { logo ->
            val lx = pageWidth - marginRight - logo.width
            val ly = y - sectionPaint.textSize
            canvas.drawBitmap(logo, lx, ly, null)
        }
        y += sectionPaint.textSize + 4f
        canvas.drawLine(marginLeft, y, pageWidth - marginRight, y, linePaint)
        y += 12f
    }

    private fun drawFooter() {
        val footerY = pageHeight - 18f
        canvas.drawLine(marginLeft, footerY - 10f, pageWidth - marginRight, footerY - 10f, linePaint)
        val pageText = if (totalPages > 0) context.getString(R.string.report_page_of, pageNumber, totalPages) else context.getString(R.string.report_page_label, pageNumber)
        canvas.drawText(pageText, marginLeft, footerY, smallPaint)
    }

    private fun drawGeneratedTimestamp() {
        val text = context.getString(R.string.report_generated_label, dateFmtFull.format(Date()))
        drawWrapped(text, smallPaint)
    }

    private fun drawBasicPetInfo(pet: Pet) {
        val lines = listOfNotNull(
            "${context.getString(R.string.pet_label_name)}: ${pet.name}",
            pet.breed?.takeIf { it.isNotBlank() }?.let { "${context.getString(R.string.pet_label_breed)}: $it" },
            pet.weightKg?.let { "${context.getString(R.string.pet_label_weight)}: ${it} kg" },
            pet.sex?.let { "${context.getString(R.string.pet_label_sex)}: $it" },
            pet.birthDate?.let { "${context.getString(R.string.pet_label_birth)}: ${dateFmtShort.format(Date(it))}" },
            pet.heightCm?.let { "${context.getString(R.string.pet_label_height)}: ${it} cm" },
            pet.lengthCm?.let { "${context.getString(R.string.pet_label_length)}: ${it} cm" }
        )
        lines.forEach { drawWrapped(it) }
    }

    private fun drawSummarySection(pet: Pet, crises: List<Crisis>) {
        ensureSpace(140f)
        drawSectionTitle(context.getString(R.string.report_summary_title))
        val total = crises.size
        drawWrapped(context.getString(R.string.report_summary_crisis_count, total))
        if (total > 0) {
            val avgSec = crises.map { it.durationSec }.average().toInt()
            val avgMin = avgSec / 60
            val avgRema = avgSec % 60
            drawWrapped(context.getString(R.string.report_summary_avg_duration, avgMin, avgRema))
            val last = crises.maxByOrNull { it.startedAt }!!
            val delta = System.currentTimeMillis() - last.startedAt
            val hours = (delta / (1000 * 60 * 60)).toInt()
            val minutes = (delta / (1000 * 60) % 60).toInt()
            val since = if (hours >= 1) context.getString(R.string.report_hours_minutes, hours, minutes) else context.getString(R.string.report_minutes_seconds, minutes, (delta/1000 %60).toInt())
            drawWrapped(context.getString(R.string.report_summary_last_crisis, since))
        } else {
            drawWrapped(context.getString(R.string.report_no_last_crisis))
        }
        divider(10f, 14f)
    }

    private fun drawCrisesSection(crises: List<Crisis>) {
        drawSectionTitle(context.getString(R.string.share_section_crises))
        if (crises.isEmpty()) {
            drawWrapped(context.getString(R.string.no_crisis_registered))
            divider(10f, 16f)
            return
        }
        crises.sortedBy { it.startedAt }.forEach { c ->
            ensureSpace(bodyPaint.textSize + 12f)
            val date = dateFmtFull.format(Date(c.startedAt))
            val durMin = c.durationSec / 60
            val durSec = c.durationSec % 60
            val triage = listOfNotNull(c.triageTitle, c.triageSeverity).joinToString(" – ")
            val line = "• $date (${durMin}m ${durSec}s) ${if (triage.isNotBlank()) triage else ""}".trim()
            drawWrapped(line)
        }
        divider(10f, 16f)
    }

    private fun drawMedicationsSection(pet: Pet) {
        drawSectionTitle(context.getString(R.string.share_section_medications))
        if (pet.medications.isEmpty()) {
            drawWrapped(context.getString(R.string.medication_no_info))
            divider(if (compactEnabled) 8f else 10f, if (compactEnabled) 10f else 16f)
            return
        }
        // Column widths
        val colNameW = usableWidth * 0.35f
        val colDoseW = usableWidth * 0.25f
        val colTimesW = usableWidth * 0.40f
        val padV = rowPadV()
        val x1 = marginLeft
        val x2 = marginLeft + colNameW
        val x3 = x2 + colDoseW
        val x4 = pageWidth - marginRight
        // Header background band
        ensureSpace(tableHeaderPaint.textSize + 2*padV + 6f)
        val headerTop = y
        val headerBottom = headerTop + tableHeaderPaint.textSize + 2*padV
        canvas.drawRect(x1, headerTop, x4, headerBottom, tableHeaderBgPaint)
        // Header texts
        val baseline = headerTop + padV + tableHeaderPaint.textSize
        canvas.drawText(context.getString(R.string.medication_header_name), x1, baseline, tableHeaderPaint)
        canvas.drawText(context.getString(R.string.medication_header_dose), x2, baseline, tableHeaderPaint)
        canvas.drawText(context.getString(R.string.medication_header_frequency), x3, baseline, tableHeaderPaint)
        // header vertical borders
        canvas.drawLine(x1, headerTop, x1, headerBottom, linePaint)
        canvas.drawLine(x2, headerTop, x2, headerBottom, linePaint)
        canvas.drawLine(x3, headerTop, x3, headerBottom, linePaint)
        canvas.drawLine(x4, headerTop, x4, headerBottom, linePaint)

        y = headerBottom
        canvas.drawLine(x1, y, x4, y, linePaint)
        y += if (compactEnabled) 4f else 6f
        var zebra = false
        pet.medications.forEach { m ->
            val doseStr = listOf(m.dose.takeIf { it.isNotBlank() }, m.unit.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" ")
            val times = if (m.times.isEmpty()) context.getString(R.string.medication_no_times) else m.times.joinToString(", ")
            val timesLines = wrapText(times, bodyPaint, colTimesW)
            val linesCount = max(1, timesLines.size)
            val rowHeight = linesCount * (bodyPaint.textSize + 4f) + 2*padV
            ensureSpace(rowHeight + (if (compactEnabled) 4f else 6f))
            val top = y
            val bottom = top + rowHeight
            if (zebra) canvas.drawRect(x1, top, x4, bottom, zebraBgPaint)
            // name (left)
            val textBase = top + padV + bodyPaint.textSize
            canvas.drawText(m.name, x1, textBase, bodyPaint)
            // dose (right-aligned within its column)
            val doseW = bodyPaint.measureText(doseStr)
            val doseX = (x3 - 4f - doseW).coerceAtLeast(x2)
            canvas.drawText(doseStr, doseX, textBase, bodyPaint)
            // times (wrapped)
            timesLines.forEachIndexed { idx, line ->
                val ly = top + padV + bodyPaint.textSize + idx * (bodyPaint.textSize + 4f)
                canvas.drawText(line, x3, ly, bodyPaint)
            }
            // row borders
            canvas.drawLine(x1, top, x1, bottom, linePaint)
            canvas.drawLine(x2, top, x2, bottom, linePaint)
            canvas.drawLine(x3, top, x3, bottom, linePaint)
            canvas.drawLine(x4, top, x4, bottom, linePaint)

            y = bottom
            canvas.drawLine(x1, y, x4, y, linePaint)
            y += if (compactEnabled) 4f else 6f
            zebra = !zebra
        }
        y += if (compactEnabled) 6f else 8f
        divider(4f, if (compactEnabled) 10f else 12f)
    }

    private fun drawProfessionalsSection(pet: Pet) {
        drawSectionTitle(context.getString(R.string.share_section_professionals))
        if (pet.professionals.isEmpty()) {
            drawWrapped(context.getString(R.string.professional_empty_list))
            divider(if (compactEnabled) 8f else 10f, if (compactEnabled) 10f else 16f); return
        }
        val colNameW = usableWidth * 0.40f
        val colSpecW = usableWidth * 0.25f
        val colContactW = usableWidth * 0.35f
        val padV = rowPadV()
        val x1 = marginLeft
        val x2 = marginLeft + colNameW
        val x3 = x2 + colSpecW
        val x4 = pageWidth - marginRight
        // Header background band
        ensureSpace(tableHeaderPaint.textSize + 2*padV + 6f)
        val headerTop = y
        val headerBottom = headerTop + tableHeaderPaint.textSize + 2*padV
        canvas.drawRect(x1, headerTop, x4, headerBottom, tableHeaderBgPaint)
        val baseline = headerTop + padV + tableHeaderPaint.textSize
        canvas.drawText(context.getString(R.string.professional_header_name), x1, baseline, tableHeaderPaint)
        canvas.drawText(context.getString(R.string.professional_header_specialty), x2, baseline, tableHeaderPaint)
        canvas.drawText(context.getString(R.string.professional_header_email), x3, baseline, tableHeaderPaint)
        // header vertical borders
        canvas.drawLine(x1, headerTop, x1, headerBottom, linePaint)
        canvas.drawLine(x2, headerTop, x2, headerBottom, linePaint)
        canvas.drawLine(x3, headerTop, x3, headerBottom, linePaint)
        canvas.drawLine(x4, headerTop, x4, headerBottom, linePaint)

        y = headerBottom
        canvas.drawLine(x1, y, x4, y, linePaint)
        y += if (compactEnabled) 4f else 6f
        var zebra = false
        pet.professionals.forEach { pr ->
            val name = listOf(pr.name, pr.lastName).filter { it.isNotBlank() }.joinToString(" ") + if (pr.isFavorite) " ★" else ""
            val spec = pr.specialty
            val contact = listOfNotNull(pr.email.takeIf { it.isNotBlank() }, pr.phone.takeIf { it.isNotBlank() }).joinToString(" / ")
            val contactLines = wrapText(contact, bodyPaint, colContactW)
            val linesCount = max(1, contactLines.size)
            val rowHeight = linesCount * (bodyPaint.textSize + 4f) + 2*padV
            ensureSpace(rowHeight + (if (compactEnabled) 4f else 6f))
            val top = y
            val bottom = top + rowHeight
            if (zebra) canvas.drawRect(x1, top, x4, bottom, zebraBgPaint)
            canvas.drawText(name, x1, top + padV + bodyPaint.textSize, bodyPaint)
            canvas.drawText(spec, x2, top + padV + bodyPaint.textSize, bodyPaint)
            contactLines.forEachIndexed { idx, line ->
                val ly = top + padV + bodyPaint.textSize + idx * (bodyPaint.textSize + 4f)
                canvas.drawText(line, x3, ly, bodyPaint)
            }
            // row borders
            canvas.drawLine(x1, top, x1, bottom, linePaint)
            canvas.drawLine(x2, top, x2, bottom, linePaint)
            canvas.drawLine(x3, top, x3, bottom, linePaint)
            canvas.drawLine(x4, top, x4, bottom, linePaint)

            y = bottom
            canvas.drawLine(x1, y, x4, y, linePaint)
            y += if (compactEnabled) 4f else 6f
            zebra = !zebra
        }
        y += if (compactEnabled) 6f else 8f
        divider(4f, if (compactEnabled) 10f else 12f)
    }

    private fun drawCareSection(pet: Pet) {
        ensureSpace(40f)
        drawSectionTitle(context.getString(R.string.share_section_care))
        if (careRecs.isNotEmpty()) {
            careRecs.forEach { rec ->
                ensureSpace(bodyPaint.textSize + 8f)
                canvas.drawText("• ${rec.title}", marginLeft, y, bodyPaint)
                y += bodyPaint.textSize + 2f
                if (rec.body.isNotBlank()) drawWrapped(rec.body)
                rec.evidence?.takeIf { it.isNotBlank() }?.let { ev ->
                    val italic = Paint(bodyPaint).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC); color = Color.DKGRAY }
                    drawWrapped(ev, italic)
                }
                y += 4f
            }
        } else {
            val careText = pet.notes?.takeIf { it.isNotBlank() } ?: context.getString(R.string.share_care_no_info)
            drawWrapped(careText)
        }
        divider(10f, 12f)
    }

    private fun drawSectionTitle(text: String) {
        ensureSpace(sectionPaint.textSize + 20f)
        canvas.drawText(text, marginLeft, y, sectionPaint)
        y += sectionPaint.textSize + 6f
    }

    private fun drawWrapped(text: String, paint: Paint = bodyPaint) {
        val lines = wrapText(text, paint, usableWidth)
        lines.forEach { line ->
            ensureSpace(paint.textSize + 8f)
            canvas.drawText(line, marginLeft, y, paint)
            y += paint.textSize + 4f
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
        val words = text.trim().split("\\s+".toRegex())
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (word in words) {
            val tentative = if (current.isEmpty()) word else current.toString() + " " + word
            if (paint.measureText(tentative) <= maxWidth) {
                if (current.isEmpty()) current.append(word) else current.append(' ').append(word)
            } else {
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current.clear()
                }
                // Si una palabra sola excede el ancho, dividir por caracteres
                if (paint.measureText(word) > maxWidth) {
                    var chunk = StringBuilder()
                    for (ch in word) {
                        val test = chunk.toString() + ch
                        if (paint.measureText(test) > maxWidth) {
                            if (chunk.isNotEmpty()) {
                                lines += chunk.toString()
                                chunk = StringBuilder()
                            }
                        }
                        chunk.append(ch)
                    }
                    if (chunk.isNotEmpty()) {
                        current.append(chunk.toString())
                    }
                } else {
                    current.append(word)
                }
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    // Helper unificado para dibujar separadores con espaciado consistente
    private fun divider(extraTop: Float = 8f, extraBottom: Float = 12f) {
        // Reservar espacio: línea + márgenes solicitados
        ensureSpace(extraTop + extraBottom + 2f)
        y += extraTop
        canvas.drawLine(marginLeft, y, pageWidth - marginRight, y, linePaint)
        y += extraBottom
    }
}
