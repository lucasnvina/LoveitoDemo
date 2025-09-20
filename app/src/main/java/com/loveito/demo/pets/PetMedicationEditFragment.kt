package com.loveito.demo.pets

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.loveito.demo.R
import java.util.Calendar
import java.util.Locale

class PetMedicationEditFragment : Fragment() {
    private var petId: String? = null
    private var index: Int = -1

    private lateinit var etName: EditText
    private lateinit var etDose: EditText
    private lateinit var chipGroup: ChipGroup
    private lateinit var btnAddTime: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var tvTitle: TextView

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
        chipGroup = view.findViewById(R.id.chipGroupTimes)
        btnAddTime = view.findViewById(R.id.btnAddTime)
        btnSave = view.findViewById(R.id.btnSave)
        btnCancel = view.findViewById(R.id.btnCancel)
        tvTitle = view.findViewById(R.id.tvTitle)

        // Prefill si venimos a editar
        arguments?.let { a ->
            val existingName = a.getString("name", "")
            val existingDose = a.getString("dose", "")
            val times = a.getStringArrayList("times") ?: arrayListOf()
            etName.setText(existingName)
            etDose.setText(existingDose)
            times.forEach { addTimeChip(it) }
            if (index >= 0) {
                tvTitle.text = getString(R.string.medication_edit_title)
            }
        }

        btnAddTime.setOnClickListener { showTimePicker { time ->
            val exists = (0 until chipGroup.childCount).any { i -> (chipGroup.getChildAt(i) as? Chip)?.text?.toString() == time }
            if (!exists) addTimeChip(time) else Toast.makeText(requireContext(), "Horario ya agregado", Toast.LENGTH_SHORT).show()
        } }

        btnSave.setOnClickListener { saveAndReturn() }
        btnCancel.setOnClickListener { parentFragmentManager.popBackStack() }
    }

    private fun addTimeChip(time: String) {
        val chip = Chip(requireContext())
        chip.text = time
        chip.isCloseIconVisible = true
        chip.setOnCloseIconClickListener { chipGroup.removeView(chip) }
        chipGroup.addView(chip)
    }

    private fun showTimePicker(onTime: (String) -> Unit) {
        val now = Calendar.getInstance()
        TimePickerDialog(requireContext(), { _, h, m ->
            val formatted = String.format(Locale.getDefault(), "%02d:%02d", h, m)
            onTime(formatted)
        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
    }

    private fun saveAndReturn() {
        val name = etName.text.toString().trim()
        val dose = etDose.text.toString().trim()
        if (name.isEmpty()) {
            etName.error = getString(R.string.enter_name)
            return
        }
        val times = (0 until chipGroup.childCount).mapNotNull { i -> (chipGroup.getChildAt(i) as? Chip)?.text?.toString() }.sorted()
        if (petId == null) {
            Toast.makeText(requireContext(), "PetId faltante", Toast.LENGTH_SHORT).show()
            return
        }
        setFragmentResult("medicationEdited", Bundle().apply {
            putString("petId", petId)
            putInt("index", index)
            putString("name", name)
            putString("dose", dose)
            putStringArrayList("times", ArrayList(times))
        })
        parentFragmentManager.popBackStack()
    }
}

