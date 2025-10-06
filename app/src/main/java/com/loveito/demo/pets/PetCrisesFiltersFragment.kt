package com.loveito.demo.pets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.loveito.demo.R
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import android.widget.TextView

class PetCrisesFiltersFragment : Fragment() {

    private var petId: String? = null

    // Reemplaza selSeverity por selección múltiple
    private val selSeverities: MutableSet<String> = mutableSetOf() // códigos: red, amber, green
    private var selFrom: Long? = null
    private var selTo: Long? = null
    private var selMinDur: Int? = null
    private var selMaxDur: Int? = null

    private lateinit var actvSeverity: MaterialAutoCompleteTextView
    private lateinit var actvMinDuration: MaterialAutoCompleteTextView
    private lateinit var actvMaxDuration: MaterialAutoCompleteTextView
    private lateinit var etDateRange: TextInputEditText
    private lateinit var btnApply: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var btnCancel: MaterialButton

    private val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }

    // Duraciones disponibles (segundos -> etiqueta)
    private val durationOptions = listOf(
        30 to "30s",
        60 to "1m",
        90 to "1m 30s",
        120 to "2m",
        150 to "2m 30s",
        180 to "3m"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            petId = args.getString("petId")
            // Recuperar severidades múltiples previas
            val prevSev = args.getStringArrayList("severities")
            prevSev?.forEach { code -> selSeverities += code }
            selFrom = args.getLong("from", -1L).takeIf { it > 0 }
            selTo = args.getLong("to", -1L).takeIf { it > 0 }
            selMinDur = args.getInt("minDuration", -1).takeIf { it >= 0 }
            selMaxDur = args.getInt("maxDuration", -1).takeIf { it >= 0 }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_pet_crises_filters, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        actvSeverity = view.findViewById(R.id.actvSeverity)
        actvMinDuration = view.findViewById(R.id.actvMinDuration)
        actvMaxDuration = view.findViewById(R.id.actvMaxDuration)
        etDateRange = view.findViewById(R.id.etDateRange)
        btnApply = view.findViewById(R.id.btnApply)
        btnClear = view.findViewById(R.id.btnClear)
        btnCancel = view.findViewById(R.id.btnCancel)

        setupSeverityDropdownMulti()
        setupDurationDropdowns()
        preloadValues()
        setupRangeField()
        setupButtons()
    }

    // Data class para severidades en el dropdown
    private data class SeverityItem(val code: String, val label: String)
    private lateinit var severityAdapter: ArrayAdapter<SeverityItem>

    private fun setupSeverityDropdownMulti() {
        val items = listOf(
            SeverityItem("red", getString(R.string.severity_emergency)),
            SeverityItem("amber", getString(R.string.severity_urgency)),
            SeverityItem("green", getString(R.string.severity_observation))
        )
        severityAdapter = object : ArrayAdapter<SeverityItem>(requireContext(), R.layout.item_filter_spinner_dropdown, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                val item = getItem(position)!!
                styleSeverityRow(v, item)
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                val item = getItem(position)!!
                styleSeverityRow(v, item)
                return v
            }
            fun styleSeverityRow(tv: TextView, item: SeverityItem) {
                tv.text = item.label
                val selected = selSeverities.contains(item.code)
                val check = if (selected) R.drawable.ic_check_mark else 0
                tv.setCompoundDrawablesWithIntrinsicBounds(0, 0, check, 0)
                tv.compoundDrawablePadding = 12
            }
        }
        actvSeverity.setAdapter(severityAdapter)
        actvSeverity.setOnClickListener { actvSeverity.showDropDown() }
        actvSeverity.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val item = severityAdapter.getItem(position) ?: return@OnItemClickListener
            // Toggle selección
            if (selSeverities.contains(item.code)) selSeverities.remove(item.code) else selSeverities.add(item.code)
            // Si las 3 están seleccionadas, interpretamos como "todas" -> limpiar para no filtrar
            if (selSeverities.size == 3) selSeverities.clear()
            updateSeverityLabel()
            // Refrescar dropdown
            severityAdapter.notifyDataSetChanged()
            // Mantener dropdown abierto para seleccionar múltiples (re-abrimos)
            actvSeverity.post { actvSeverity.showDropDown() }
        }
        updateSeverityLabel()
    }

    // Añadida nuevamente (se había eliminado accidentalmente)
    private fun updateSeverityLabel() {
        val label = when {
            selSeverities.isEmpty() -> getString(R.string.severity_all)
            selSeverities.size == 3 -> { selSeverities.clear(); getString(R.string.severity_all) }
            else -> selSeverities.map { code ->
                when (code) {
                    "red" -> getString(R.string.severity_emergency)
                    "amber" -> getString(R.string.severity_urgency)
                    "green" -> getString(R.string.severity_observation)
                    else -> code
                }
            }.sorted().joinToString(", ")
        }
        actvSeverity.setText(label, false)
    }

    private fun setupDurationDropdowns() {
        val minLabels = listOf(getString(R.string.filter_duration_no_min)) + durationOptions.map { it.second }
        val maxLabels = listOf(getString(R.string.filter_duration_no_max)) + durationOptions.map { it.second }
        val adapterMin = ArrayAdapter(requireContext(), R.layout.item_filter_spinner_dropdown, minLabels)
        val adapterMax = ArrayAdapter(requireContext(), R.layout.item_filter_spinner_dropdown, maxLabels)
        actvMinDuration.setAdapter(adapterMin)
        actvMaxDuration.setAdapter(adapterMax)
        actvMinDuration.setOnClickListener { actvMinDuration.showDropDown() }
        actvMaxDuration.setOnClickListener { actvMaxDuration.showDropDown() }
    }

    private fun preloadValues() {
        if (selMinDur == null) actvMinDuration.setText(getString(R.string.filter_duration_no_min), false)
        else durationOptions.firstOrNull { it.first == selMinDur }?.let { actvMinDuration.setText(it.second, false) }
        if (selMaxDur == null) actvMaxDuration.setText(getString(R.string.filter_duration_no_max), false)
        else durationOptions.firstOrNull { it.first == selMaxDur }?.let { actvMaxDuration.setText(it.second, false) }
        updateRangeFieldText()
        updateSeverityLabel()
        severityAdapter.notifyDataSetChanged()
    }

    private fun updateRangeFieldText() {
        if (selFrom == null && selTo == null) {
            etDateRange.setText("")
        } else {
            etDateRange.setText(buildLabel(selFrom, selTo))
        }
    }

    private fun setupRangeField() {
        val openPicker: (View) -> Unit = {
            val builder = MaterialDatePicker.Builder.dateRangePicker()
            builder.setTheme(R.style.ThemeOverlay_Loveito_DatePicker)
            val sel = if (selFrom != null && selTo != null) androidx.core.util.Pair(selFrom, selTo) else null
            builder.setSelection(sel)
            val constraints = CalendarConstraints.Builder()
                .setValidator(DateValidatorPointBackward.now())
                .build()
            builder.setCalendarConstraints(constraints)
            builder.setTitleText(getString(R.string.filter_date_range))
            val picker = builder.build()
            picker.addOnPositiveButtonClickListener { pair ->
                selFrom = pair.first
                selTo = pair.second
                updateRangeFieldText()
            }
            picker.show(parentFragmentManager, "crisis_filters_range")
        }
        etDateRange.setOnClickListener(openPicker)
        etDateRange.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) openPicker(v) }
    }

    private fun parseDurationLabel(label: String?, isMin: Boolean): Int? {
        if (label.isNullOrBlank()) return null
        if (isMin && label == getString(R.string.filter_duration_no_min)) return null
        if (!isMin && label == getString(R.string.filter_duration_no_max)) return null
        return durationOptions.firstOrNull { it.second == label }?.first
    }

    private fun setupButtons() {
        btnApply.setOnClickListener {
            selMinDur = parseDurationLabel(actvMinDuration.text?.toString(), true)
            selMaxDur = parseDurationLabel(actvMaxDuration.text?.toString(), false)
            if (selMinDur != null && selMaxDur != null && selMinDur!! > selMaxDur!!) {
                Toast.makeText(requireContext(), getString(R.string.filter_duration_invalid_range), Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            if (selFrom != null && selTo != null && selFrom!! > selTo!!) {
                Toast.makeText(requireContext(), getString(R.string.filter_duration_invalid_range), Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            val severitiesArray = ArrayList(
                if (selSeverities.size in 1..2) selSeverities else emptySet() // si 0 o 3 -> sin filtro
            )
            parentFragmentManager.setFragmentResult("crisesFiltersApplied", bundleOf(
                "petId" to petId,
                "severities" to severitiesArray,
                "from" to (selFrom ?: -1L),
                "to" to (selTo ?: -1L),
                "minDuration" to (selMinDur ?: -1),
                "maxDuration" to (selMaxDur ?: -1)
            ))
            parentFragmentManager.popBackStack()
        }
        btnClear.setOnClickListener {
            selSeverities.clear(); selFrom = null; selTo = null; selMinDur = null; selMaxDur = null
            actvMinDuration.setText(getString(R.string.filter_duration_no_min), false)
            actvMaxDuration.setText(getString(R.string.filter_duration_no_max), false)
            updateRangeFieldText(); updateSeverityLabel(); severityAdapter.notifyDataSetChanged()
        }
        btnCancel.setOnClickListener { parentFragmentManager.popBackStack() }
    }

    private fun buildLabel(start: Long?, end: Long?): String {
        if (start == null && end == null) return "—"
        return listOf(start?.let { dateFmt.format(it) } ?: "?", end?.let { dateFmt.format(it) } ?: "?").joinToString(" → ")
    }
}
