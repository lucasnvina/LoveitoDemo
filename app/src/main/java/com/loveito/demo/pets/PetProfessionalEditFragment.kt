package com.loveito.demo.pets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.loveito.demo.R
import android.widget.ArrayAdapter

class PetProfessionalEditFragment : Fragment() {
    private var petId: String? = null
    private var index: Int = -1

    private lateinit var etName: EditText
    private lateinit var etLastName: EditText
    private lateinit var actSpecialty: MaterialAutoCompleteTextView
    private lateinit var etPhone: EditText
    private lateinit var etEmail: EditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var tvTitle: TextView
    private lateinit var btnDelete: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { a ->
            petId = a.getString("petId")
            index = a.getInt("index", -1)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_pet_professional_edit, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        etName = view.findViewById(R.id.etProName)
        etLastName = view.findViewById(R.id.etProLastName)
        actSpecialty = view.findViewById(R.id.actProSpecialty)
        etPhone = view.findViewById(R.id.etProPhone)
        etEmail = view.findViewById(R.id.etProEmail)
        btnSave = view.findViewById(R.id.btnSavePro)
        btnCancel = view.findViewById(R.id.btnCancelPro)
        tvTitle = view.findViewById(R.id.tvTitlePro)
        btnDelete = view.findViewById(R.id.btnDeletePro)

        // Adapter de especialidades
        val specialties = resources.getStringArray(R.array.professional_specialties)
        val adapter = ArrayAdapter(requireContext(), R.layout.item_dropdown_maroon, specialties)
        adapter.setDropDownViewResource(R.layout.item_dropdown_maroon)
        actSpecialty.setAdapter(adapter)
        actSpecialty.setOnClickListener { actSpecialty.showDropDown() }

        arguments?.let { a ->
            etName.setText(a.getString("name", ""))
            etLastName.setText(a.getString("lastName", ""))
            actSpecialty.setText(a.getString("specialty", ""), false)
            etPhone.setText(a.getString("phone", ""))
            etEmail.setText(a.getString("email", ""))
            if (index >= 0) {
                tvTitle.text = getString(R.string.professional_edit_title)
                btnSave.text = getString(R.string.professionals_save_changes)
                btnDelete.visibility = View.VISIBLE
            } else {
                tvTitle.text = getString(R.string.professional_add_title)
                btnSave.text = getString(R.string.add_label)
                btnDelete.visibility = View.GONE
            }
        } ?: run {
            tvTitle.text = getString(R.string.professional_add_title)
            btnSave.text = getString(R.string.add_label)
            btnDelete.visibility = View.GONE
        }

        btnSave.setOnClickListener { saveAndReturn() }
        btnCancel.setOnClickListener { parentFragmentManager.popBackStack() }
        btnDelete.setOnClickListener { confirmDelete() }
    }

    private fun confirmDelete() {
        if (petId == null || index < 0) return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.professional_delete_title))
            .setMessage(getString(R.string.professional_delete_confirm))
            .setPositiveButton(getString(R.string.professional_delete_button)) { _, _ ->
                setFragmentResult("professionalDeleted", Bundle().apply {
                    putString("petId", petId)
                    putInt("index", index)
                })
                parentFragmentManager.popBackStack()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun saveAndReturn() {
        val name = etName.text.toString().trim()
        val lastName = etLastName.text.toString().trim()
        val specialty = actSpecialty.text.toString().trim()
        val phone = etPhone.text.toString().trim()
        val email = etEmail.text.toString().trim()
        if (name.isEmpty()) {
            etName.error = getString(R.string.enter_name)
            return
        }
        if (petId == null) {
            Toast.makeText(requireContext(), "PetId faltante", Toast.LENGTH_SHORT).show(); return
        }
        setFragmentResult("professionalEdited", Bundle().apply {
            putString("petId", petId)
            putInt("index", index)
            putString("name", name)
            putString("lastName", lastName)
            putString("specialty", specialty)
            putString("phone", phone)
            putString("email", email)
        })
        parentFragmentManager.popBackStack()
    }
}
