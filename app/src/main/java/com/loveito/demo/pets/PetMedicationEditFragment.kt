package com.loveito.demo.pets

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.loveito.demo.R
import android.util.TypedValue
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import java.util.Calendar
import java.util.Locale

class PetMedicationEditFragment : Fragment() {
    private var petId: String? = null
    private var index: Int = -1

    private lateinit var etName: EditText
    private lateinit var etDose: EditText
    private lateinit var actUnit: MaterialAutoCompleteTextView
    private lateinit var chipGroup: ChipGroup
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var tvTitle: TextView
    private lateinit var btnAddTime: ImageButton
    private lateinit var btnDelete: MaterialButton

    private val timesList = mutableListOf<String>()
    private val MAX_TIMES = 12 // límite de horarios

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            petId = args.getString("petId")
            index = args.getInt("index", -1)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_pet_medication_edit, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        etName = view.findViewById(R.id.etName)
        etDose = view.findViewById(R.id.etDose)
        actUnit = view.findViewById(R.id.actUnit)
        chipGroup = view.findViewById(R.id.chipGroupTimes)
        btnSave = view.findViewById(R.id.btnSave)
        btnCancel = view.findViewById(R.id.btnCancel)
        tvTitle = view.findViewById(R.id.tvTitle)
        btnAddTime = view.findViewById(R.id.btnAddTime)
        btnDelete = view.findViewById(R.id.btnDelete)

        arguments?.let { a ->
            val existingName = a.getString("name", "")
            val existingDose = a.getString("dose", "")
            val existingUnit = a.getString("unit", "")
            val times = a.getStringArrayList("times") ?: arrayListOf()
            etName.setText(existingName)
            etDose.setText(existingDose)
            actUnit.setText(existingUnit, false)
            timesList.clear(); timesList.addAll(times.distinct().sorted())
            if (index >= 0) {
                tvTitle.text = getString(R.string.medication_edit_title)
                btnSave.text = getString(R.string.medications_save_changes)
                btnDelete.visibility = View.VISIBLE
            } else {
                tvTitle.text = getString(R.string.medication_add_title)
                btnSave.text = getString(R.string.add_label)
                btnDelete.visibility = View.GONE
            }
        } ?: run {
            tvTitle.text = getString(R.string.medication_add_title)
            btnSave.text = getString(R.string.add_label)
            btnDelete.visibility = View.GONE
        }
        rebuildChips()

        val units = resources.getStringArray(R.array.medication_units)
        val unitAdapter = ArrayAdapter(requireContext(), R.layout.item_dropdown_unit, units)
        unitAdapter.setDropDownViewResource(R.layout.item_dropdown_unit)
        actUnit.setAdapter(unitAdapter)

        btnAddTime.setOnClickListener { promptAddTime() }
        btnSave.setOnClickListener { saveAndReturn() }
        btnCancel.setOnClickListener { parentFragmentManager.popBackStack() }
        btnDelete.setOnClickListener { confirmDelete() }
    }

    private fun promptAddTime() {
        if (timesList.size >= MAX_TIMES) {
            view?.performHapticFeedback(HapticFeedbackConstants.REJECT)
            Toast.makeText(requireContext(), getString(R.string.medication_time_limit, MAX_TIMES), Toast.LENGTH_SHORT).show()
            return
        }
        showTimePicker { time ->
            if (timesList.contains(time)) {
                view?.performHapticFeedback(HapticFeedbackConstants.REJECT)
                Toast.makeText(requireContext(), getString(R.string.medication_time_duplicate), Toast.LENGTH_SHORT).show()
            } else {
                timesList.add(time)
                timesList.sort()
                rebuildChips()
                view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
        }
    }

    private fun rebuildChips() {
        val parent = chipGroup.parent as? ViewGroup
        if (parent != null) {
            TransitionManager.beginDelayedTransition(parent, AutoTransition().apply { duration = 150 })
        }
        chipGroup.removeAllViews()
        timesList.forEach { addStyledTimeChip(it) }
    }

    private fun addStyledTimeChip(time: String) {
        val chip = Chip(requireContext()).apply {
            text = time
            isCloseIconVisible = true
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#b96c30"))
            chipStrokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#230000"))
            chipStrokeWidth = resources.displayMetrics.density * 1f
            val typeface = ResourcesCompat.getFont(requireContext(), R.font.nunito_semibold)
            if (typeface != null) this.typeface = typeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val primaryColor = ContextCompat.getColor(requireContext(), R.color.text_primary)
            setTextColor(primaryColor)
            closeIconTint = android.content.res.ColorStateList.valueOf(primaryColor)
            val radiusPx = 16f * resources.displayMetrics.density
            shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(radiusPx).build()
            setOnCloseIconClickListener {
                timesList.remove(time)
                rebuildChips()
                view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
        chipGroup.addView(chip)
    }

    private fun showTimePicker(onTime: (String) -> Unit) {
        val now = Calendar.getInstance()
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(now.get(Calendar.HOUR_OF_DAY))
            .setMinute(now.get(Calendar.MINUTE))
            .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK) // forzar que abra la rueda primero
            .setTheme(R.style.ThemeOverlay_Loveito_TimePicker)
            .setTitleText(getString(R.string.medication_times_dialog_title))
            .build()
        picker.addOnPositiveButtonClickListener {
            val h = picker.hour
            val m = picker.minute
            val formatted = String.format(Locale.getDefault(), "%02d:%02d", h, m)
            onTime(formatted)
        }
        picker.show(parentFragmentManager, "med_time_picker")
    }

    private fun saveAndReturn() {
        val name = etName.text.toString().trim()
        val dose = etDose.text.toString().trim()
        val unit = actUnit.text.toString().trim()
        if (name.isEmpty()) {
            etName.error = getString(R.string.enter_name)
            return
        }
        val times = timesList.sorted()
        if (petId == null) {
            Toast.makeText(requireContext(), "PetId faltante", Toast.LENGTH_SHORT).show(); return
        }
        setFragmentResult("medicationEdited", Bundle().apply {
            putString("petId", petId)
            putInt("index", index)
            putString("name", name)
            putString("dose", dose)
            putString("unit", unit)
            putStringArrayList("times", ArrayList(times))
        })
        parentFragmentManager.popBackStack()
    }

    private fun confirmDelete() {
        if (petId == null || index < 0) return
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.medication_delete))
            .setMessage(getString(R.string.medication_delete_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                setFragmentResult("medicationDeleted", Bundle().apply {
                    putString("petId", petId)
                    putInt("index", index)
                })
                parentFragmentManager.popBackStack()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
