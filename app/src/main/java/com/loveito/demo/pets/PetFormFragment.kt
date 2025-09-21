package com.loveito.demo.pets

import android.app.DatePickerDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import com.google.android.material.datepicker.MaterialDatePicker
import com.loveito.demo.R
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale

class PetFormFragment : Fragment() {

    private val sexOptions = listOf("Macho", "Hembra")
    private val neuteredOptions by lazy { listOf(getString(R.string.yes), getString(R.string.no)) }

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun newEdit(id: String, name: String, notes: String, photoUrl: String) = PetFormFragment().apply {
            arguments = Bundle().apply { putString("id", id) }
        }
        fun newEdit(id: String) = PetFormFragment().apply {
            arguments = Bundle().apply { putString("id", id) }
        }
    }

    private val repo = PetsRepository()
    private var pickedUri: Uri? = null

    // Groups
    private lateinit var groupView: LinearLayout
    private lateinit var groupEdit: LinearLayout
    private lateinit var btnStartEdit: Button

    // Summary
    private lateinit var ivSummaryPhoto: ImageView
    private lateinit var tvSName: TextView
    private lateinit var tvSBreed: TextView
    private lateinit var tvSWeight: TextView
    private lateinit var tvSSex: TextView
    private lateinit var tvSBirth: TextView
    private lateinit var tvSAge: TextView
    private lateinit var tvSNeutered: TextView
    private lateinit var tvSHeight: TextView
    private lateinit var tvSLength: TextView
    private lateinit var tvLastCrisisDate: TextView
    private lateinit var sectionLastCrisis: View
    private lateinit var tvAvgCrisisTime: TextView
    private lateinit var tvMedicationSummary: TextView

    // Editor (nuevos widgets Material)
    private lateinit var etName: EditText
    private lateinit var etBreed: EditText
    private lateinit var etWeight: EditText
    private lateinit var actvSex: AutoCompleteTextView
    private lateinit var etBirthDate: EditText
    private lateinit var actvNeutered: AutoCompleteTextView
    private lateinit var etHeight: EditText
    private lateinit var etLength: EditText
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button
    private lateinit var btnDeletePetEdit: Button
    private var ivLogoLoveitoDog: ImageView? = null
    private lateinit var ivEditPhoto: ImageView
    private lateinit var editPhotoContainer: View
    private var ivCameraOverlay: ImageView? = null

    private var birthDateMillis: Long? = null

    private var editingId: String? = null

    private lateinit var sectionMedication: View

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pickedUri = uri
            val bmp = decodeBitmapWithExifFromUri(uri)
            if (bmp != null) {
                ivSummaryPhoto.setImageBitmap(bmp)
                ivEditPhoto.setImageBitmap(bmp)
            } else {
                ivSummaryPhoto.setImageResource(R.drawable.ic_user_placeholder)
                ivEditPhoto.setImageResource(R.drawable.ic_user_placeholder)
            }
        }
    }

    private var rootView: View? = null
    private lateinit var scrollView: ScrollView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_pet_form, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rootView = view

        groupView = view.findViewById(R.id.groupView)
        groupEdit = view.findViewById(R.id.groupEdit)
        scrollView = view.findViewById(R.id.scrollView)
        btnStartEdit = view.findViewById(R.id.btnStartEdit)
        btnDeletePetEdit = view.findViewById(R.id.btnDeletePetEdit)
        btnDeletePetEdit.setOnClickListener {
            val id = editingId ?: return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.delete_pet_title))
                .setMessage(getString(R.string.delete_pet_confirm))
                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                    repo.deletePet(id,
                        onSuccess = {
                            Toast.makeText(requireContext(), getString(R.string.pet_deleted), Toast.LENGTH_SHORT).show()
                            parentFragmentManager.popBackStack()
                        },
                        onError = { e ->
                            Toast.makeText(requireContext(), getString(R.string.error, e.localizedMessage), Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
        btnStartEdit.setOnClickListener {
            switchToEditMode()
            ivLogoLoveitoDog?.visibility = View.GONE
            if (::scrollView.isInitialized) {
                scrollView.post { scrollView.fullScroll(View.FOCUS_UP) }
            }
        }

        ivSummaryPhoto = view.findViewById(R.id.ivSummaryPhoto)
        ivEditPhoto = view.findViewById(R.id.ivEditPhoto)
        editPhotoContainer = view.findViewById(R.id.editPhotoContainer)
        ivCameraOverlay = view.findViewById(R.id.ivCameraOverlay)
        tvSName = view.findViewById(R.id.tvSName)
        tvSBreed = view.findViewById(R.id.tvSBreed)
        tvSWeight = view.findViewById(R.id.tvSWeight)
        tvSSex = view.findViewById(R.id.tvSSex)
        tvSBirth = view.findViewById(R.id.tvSBirth)
        tvSAge = view.findViewById(R.id.tvSAge)
        tvSNeutered = view.findViewById(R.id.tvSNeutered)
        tvSHeight = view.findViewById(R.id.tvSHeight)
        tvSLength = view.findViewById(R.id.tvSLength)
        tvLastCrisisDate = view.findViewById(R.id.tvLastCrisisDate)
        sectionLastCrisis = view.findViewById(R.id.sectionLastCrisis)
        tvAvgCrisisTime = view.findViewById(R.id.tvAvgCrisisTime)
        tvMedicationSummary = view.findViewById(R.id.tvMedicationSummary)

        etName = view.findViewById(R.id.etPetName)
        etBreed = view.findViewById(R.id.etPetBreed)
        etWeight = view.findViewById(R.id.etPetWeight)
        actvSex = view.findViewById(R.id.actvSex)
        etBirthDate = view.findViewById(R.id.etBirthDate)
        actvNeutered = view.findViewById(R.id.actvNeutered)
        etHeight = view.findViewById(R.id.etHeightCm)
        etLength = view.findViewById(R.id.etLengthCm)
        btnSave = view.findViewById(R.id.btnSavePet)
        btnCancel = view.findViewById(R.id.btnCancelEdit)
        btnDeletePetEdit = view.findViewById(R.id.btnDeletePetEdit)
        ivLogoLoveitoDog = view.findViewById(R.id.ivLogoLoveitoDog)
        // Crear crisis directamente para la mascota actual al tocar el logo
        ivLogoLoveitoDog?.setOnClickListener { logoView ->
            val petId = editingId
            if (petId == null) {
                Toast.makeText(requireContext(), "Guardá la mascota antes de registrar una crisis", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Anti multi-click
            logoView.isEnabled = false
            val triage = TriageEngine.randomResult(requireContext())
            repo.createTestCrisisWithTriage(petId, triage,
                onSuccess = {
                    Toast.makeText(requireContext(), "Crisis registrada", Toast.LENGTH_SHORT).show()
                    loadCrises(petId) // refrescar métricas
                    logoView.postDelayed({ logoView.isEnabled = true }, 600)
                },
                onError = { e ->
                    Toast.makeText(requireContext(), getString(R.string.error, e.localizedMessage), Toast.LENGTH_SHORT).show()
                    logoView.isEnabled = true
                }
            )
        }
        sectionMedication = view.findViewById(R.id.sectionMedication)

        // Adapters para dropdowns
        actvSex.setAdapter(ArrayAdapter(requireContext(), R.layout.item_dropdown_maroon, sexOptions).apply {
            setDropDownViewResource(R.layout.item_dropdown_maroon)
        })
        actvNeutered.setAdapter(ArrayAdapter(requireContext(), R.layout.item_dropdown_maroon, neuteredOptions).apply {
            setDropDownViewResource(R.layout.item_dropdown_maroon)
        })
        actvSex.setOnClickListener { actvSex.showDropDown() }
        actvNeutered.setOnClickListener { actvNeutered.showDropDown() }

        editingId = arguments?.getString("id")

        if (editingId != null) {
            switchToViewMode()
            repo.getPet(editingId!!,
                onSuccess = { p ->
                    // fill editor fields for when user taps "Editar"
                    etName.setText(p.name)
                    etBreed.setText(p.breed ?: "")
                    etWeight.setText(p.weightKg?.toString() ?: "")
                    actvSex.setText(p.sex ?: "", false)
                    actvNeutered.setText(if (p.neutered == true) getString(R.string.yes) else getString(R.string.no), false)
                    etHeight.setText(p.heightCm?.toString() ?: "")
                    etLength.setText(p.lengthCm?.toString() ?: "")
                    birthDateMillis = p.birthDate
                    if (birthDateMillis != null) etBirthDate.setText(formatDate(birthDateMillis!!))

                    // Set photo in summary
                    if (p.photoUrl != null && p.photoUrl.isNotEmpty()) {
                        loadBitmapWithExifFromUrl(p.photoUrl) { bmp ->
                            if (bmp != null) {
                                ivSummaryPhoto.setImageBitmap(bmp)
                                ivEditPhoto.setImageBitmap(bmp)
                            } else {
                                ivSummaryPhoto.setImageResource(R.drawable.ic_user_placeholder)
                                ivEditPhoto.setImageResource(R.drawable.ic_user_placeholder)
                            }
                        }
                    } else {
                        ivSummaryPhoto.setImageResource(R.drawable.ic_user_placeholder)
                        ivEditPhoto.setImageResource(R.drawable.ic_user_placeholder)
                    }

                    renderSummary(p)
                    loadCrises(p.id)
                },
                onError = { }
            )
        } else {
            // Creating new pet -> only edit mode
            btnDeletePetEdit.visibility = View.GONE
            switchToEditMode()
            // Nueva mascota: agregar 1 fila inicial vacía de medicación opcional
            // addMedicationRow(null)
        }

        // Date picker sobre el campo no editable
        etBirthDate.setOnClickListener { showDatePicker() }

        btnSave.setOnClickListener {
            val weight = etWeight.text.toString().replace(',', '.').toDoubleOrNull()
            val breed = etBreed.text.toString().trim().ifEmpty { null }
            val name = etName.text.toString().trim()
            val sex = actvSex.text?.toString()?.ifEmpty { null }
            val neutered = actvNeutered.text?.toString() == getString(R.string.yes)
            val height = etHeight.text.toString().replace(',', '.').toDoubleOrNull()
            val length = etLength.text.toString().replace(',', '.').toDoubleOrNull()
            save(name, breed, weight, sex, birthDateMillis, neutered, height, length)
        }

        btnCancel.setOnClickListener {
            pickedUri = null
            editingId?.let { id ->
                repo.getPet(id, onSuccess = { p ->
                    etName.setText(p.name)
                    etBreed.setText(p.breed ?: "")
                    etWeight.setText(p.weightKg?.toString() ?: "")
                    actvSex.setText(p.sex ?: "", false)
                    actvNeutered.setText(if (p.neutered == true) getString(R.string.yes) else getString(R.string.no), false)
                    etHeight.setText(p.heightCm?.toString() ?: "")
                    etLength.setText(p.lengthCm?.toString() ?: "")
                    birthDateMillis = p.birthDate
                    etBirthDate.setText(if (birthDateMillis != null) formatDate(birthDateMillis!!) else getString(R.string.pet_label_not_defined))
                    renderSummary(p)
                }, onError = {})
            }
            switchToViewMode()
            ivLogoLoveitoDog?.visibility = View.VISIBLE
        }

        sectionLastCrisis.setOnClickListener {
            // Navegar al fragmento de lista de crisis
            editingId?.let { petId ->
                val args = Bundle().apply { putString("petId", petId) }
                val fragment = PetCrisesFragment()
                fragment.arguments = args
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_host, fragment)
                    .addToBackStack(null)
                    .commit()
        }
        }

        // Set click listeners for picking image only in edit mode
        val photoClickListener = View.OnClickListener {
            if (groupEdit.visibility == View.VISIBLE) {
                pickImage.launch("image/*")
            }
        }
        editPhotoContainer.setOnClickListener(photoClickListener)
        ivEditPhoto.setOnClickListener(photoClickListener)
        ivCameraOverlay?.setOnClickListener(photoClickListener)

        // Optional: long click could also open picker
        editPhotoContainer.setOnLongClickListener {
            if (groupEdit.visibility == View.VISIBLE) {
                pickImage.launch("image/*"); true
            } else false
        }

        // btnAddMedication.setOnClickListener {
        //     addMedicationRow(null)
        // }

        // Escuchar resultados de actualización de medicaciones
        setFragmentResultListener("medicationsUpdated") { _, bundle ->
            val updatedPetId = bundle.getString("petId")
            if (updatedPetId != null && updatedPetId == editingId) {
                repo.getPet(updatedPetId, onSuccess = { p ->
                    renderSummary(p)
                }, onError = { })
            }
        }

        sectionMedication.setOnClickListener {
            editingId?.let { petId ->
                val f = PetMedicationsFragment()
                f.arguments = Bundle().apply { putString("petId", petId) }
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_host, f)
                    .addToBackStack(null)
                    .commit()
            }
        }
    }

    // Llamar después de obtener la mascota para llenar UI en modo edición (cuando el usuario entra a editar)
    // private fun populateMedicationsForEdit(medications: List<Medication>) {
    //     medicationsContainer.removeAllViews()
    //     if (medications.isEmpty()) {
    //         // Agregar una fila vacía para incentivar entrada
    //         addMedicationRow(null)
    //     } else {
    //         medications.forEach { addMedicationRow(it) }
    //     }
    // }

    // private fun addMedicationRow(med: Medication?) {
    //     val ctx = requireContext()
    //     val row = layoutInflater.inflate(R.layout.item_medication_edit_row, medicationsContainer, false)
    //     val etName = row.findViewById<EditText>(R.id.etMedName)
    //     val etDose = row.findViewById<EditText>(R.id.etMedDose)
    //     val chipGroup = row.findViewById<ChipGroup>(R.id.chipGroupTimes)
    //     val btnAddTime = row.findViewById<MaterialButton>(R.id.btnAddTime)
    //     val btnRemove = row.findViewById<ImageButton>(R.id.btnRemoveMedication)

    //     // Poblar datos iniciales
    //     med?.let {
    //         etName.setText(it.name)
    //         etDose.setText(it.dose)
    //         it.times.forEach { t -> addTimeChip(chipGroup, t) }
    //     }

    //     btnAddTime.setOnClickListener {
    //         showTimePicker { time ->
    //             // Evitar duplicados
    //             val exists = (0 until chipGroup.childCount).mapNotNull { idx -> (chipGroup.getChildAt(idx) as? Chip)?.text?.toString() }.contains(time)
    //             if (!exists) addTimeChip(chipGroup, time) else Toast.makeText(ctx, "Horario ya agregado", Toast.LENGTH_SHORT).show()
    //         }
    //     }

    //     btnRemove.setOnClickListener {
    //         AlertDialog.Builder(ctx)
    //             .setMessage(getString(R.string.medication_delete_confirm))
    //             .setPositiveButton(getString(R.string.delete)) { _, _ -> medicationsContainer.removeView(row) }
    //             .setNegativeButton(getString(R.string.cancel), null)
    //             .show()
    //     }

    //     medicationsContainer.addView(row)
    // }

    // private fun addTimeChip(group: ChipGroup, time: String) {
    //     val chip = Chip(requireContext())
    //     chip.text = time
    //     chip.isCloseIconVisible = true
    //     chip.setOnCloseIconClickListener { group.removeView(chip) }
    //     group.addView(chip)
    // }

    // private fun showTimePicker(onTime: (String) -> Unit) {
    //     val now = Calendar.getInstance()
    //     val h = now.get(Calendar.HOUR_OF_DAY)
    //     val m = now.get(Calendar.MINUTE)
    //     TimePickerDialog(requireContext(), { _, hourOfDay, minute ->
    //         val formatted = String.format(Locale.getDefault(), "%02d:%02d", hourOfDay, minute)
    //         onTime(formatted)
    //     }, h, m, true).show()
    // }

    // private fun collectMedications(): List<Medication> {
    //     val list = mutableListOf<Medication>()
    //     for (i in 0 until medicationsContainer.childCount) {
    //         val row = medicationsContainer.getChildAt(i)
    //         val etName = row.findViewById<EditText>(R.id.etMedName)
    //         val etDose = row.findViewById<EditText>(R.id.etMedDose)
    //         val chipGroup = row.findViewById<ChipGroup>(R.id.chipGroupTimes)
    //         val name = etName.text.toString().trim()
    //         val dose = etDose.text.toString().trim()
    //         // Si no hay nombre, ignorar la fila vacía
    //         if (name.isEmpty() && dose.isEmpty() && chipGroup.childCount == 0) continue
    //         val times = (0 until chipGroup.childCount).mapNotNull { idx -> (chipGroup.getChildAt(idx) as? Chip)?.text?.toString() }
    //         list.add(Medication(name = name, dose = dose, times = times))
    //     }
    //     return list
    // }

    private fun showDatePicker() {
        val builder = MaterialDatePicker.Builder.datePicker()
            .setTitleText(getString(R.string.pet_label_birth_date))
            .setTheme(R.style.ThemeOverlay_Loveito_DatePicker)
        // Pre-seleccionar si ya hay fecha
        birthDateMillis?.let { builder.setSelection(it) }
        val picker = builder.build()
        picker.addOnPositiveButtonClickListener { selection ->
            if (selection != null) {
                val cal = Calendar.getInstance()
                cal.timeInMillis = selection
                // Normalizar a medianoche local
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                birthDateMillis = cal.timeInMillis
                etBirthDate.setText(formatDate(birthDateMillis!!))
            }
        }
        picker.show(parentFragmentManager, "pet_birth_date_picker")
    }

    override fun onResume() {
        super.onResume()
        editingId?.let { loadCrises(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rootView = null
    }

    private fun switchToViewMode() {
        groupView.visibility = View.VISIBLE
        groupEdit.visibility = View.GONE
        ivLogoLoveitoDog?.visibility = View.VISIBLE
    }

    private fun switchToEditMode() {
        groupView.visibility = View.GONE
        groupEdit.visibility = View.VISIBLE
        ivLogoLoveitoDog?.visibility = View.GONE
        // Repoblar meds si ya estaban cargadas
        // if (loadedMedications.isNotEmpty()) populateMedicationsForEdit(loadedMedications)
    }

    private fun save(name: String, breed: String?, weightKg: Double?, sex: String?, birthDate: Long?, neutered: Boolean, heightCm: Double?, lengthCm: Double?) {
        if (name.isEmpty()) { Toast.makeText(requireContext(), getString(R.string.enter_name), Toast.LENGTH_SHORT).show(); return }
        val id = editingId
        if (id == null) {
            // Crear sin medicaciones (se editarán aparte)
            repo.createPet(name, breed, weightKg, pickedUri, sex, birthDate, neutered, heightCm, lengthCm, null,
                onSuccess = { Toast.makeText(requireContext(), getString(R.string.pet_created), Toast.LENGTH_SHORT).show(); parentFragmentManager.popBackStack() },
                onError = { e -> Toast.makeText(requireContext(), getString(R.string.error, e.localizedMessage), Toast.LENGTH_SHORT).show() }
            )
        } else {
            repo.updatePet(id, name, breed, weightKg, pickedUri, sex, birthDate, neutered, heightCm, lengthCm, null,
                onSuccess = {
                    Toast.makeText(requireContext(), getString(R.string.pet_updated), Toast.LENGTH_SHORT).show()
                    repo.getPet(id, onSuccess = { p ->
                        renderSummary(p)
                    }, onError = { })
                    pickedUri = null
                    switchToViewMode()
                },
                onError = { e -> Toast.makeText(requireContext(), getString(R.string.error, e.localizedMessage), Toast.LENGTH_SHORT).show() }
            )
        }
    }

    private fun renderSummary(p: Pet) {
        tvSName.text = p.name
        // Solo el dato, sin prefijo
        tvSBreed.text = p.breed ?: getString(R.string.dash)
        tvSWeight.text = p.weightKg?.let { "${it} ${getString(R.string.kg)}" } ?: getString(R.string.dash)
        tvSSex.text = p.sex ?: getString(R.string.dash)
        tvSBirth.text = p.birthDate?.let { formatDate(it) } ?: getString(R.string.dash)
        tvSAge.text = p.birthDate?.let { yearsFrom(it) }?.let { "$it ${getString(R.string.years)}" } ?: getString(R.string.dash)
        tvSNeutered.text = if (p.neutered == true) getString(R.string.yes) else getString(R.string.no)
        tvSHeight.text = p.heightCm?.let { "${it} ${getString(R.string.cm)}" } ?: getString(R.string.dash)
        tvSLength.text = p.lengthCm?.let { "${it} ${getString(R.string.cm)}" } ?: getString(R.string.dash)
        // Optionally, set image here as well if needed

        // Resumen de medicación
        val medsCount = p.medications.size
        tvMedicationSummary.text = when {
            medsCount == 0 -> getString(R.string.medication_no_info)
            medsCount == 1 -> getString(R.string.medication_summary_single)
            else -> getString(R.string.medication_summary_multi, medsCount)
        }
    }

    private fun yearsFrom(millis: Long): Int {
        val dob = Calendar.getInstance().apply { timeInMillis = millis }
        val now = Calendar.getInstance()
        var years = now.get(Calendar.YEAR) - dob.get(Calendar.YEAR)
        val mNow = now.get(Calendar.MONTH)
        val dNow = now.get(Calendar.DAY_OF_MONTH)
        if (mNow < dob.get(Calendar.MONTH) || (mNow == dob.get(Calendar.MONTH) && dNow < dob.get(Calendar.DAY_OF_MONTH))) {
            years -= 1
        }
        return years
    }

    private fun formatDate(millis: Long): String {
        return java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date(millis))
    }

    private fun decodeBitmapWithExifFromUri(uri: Uri): Bitmap? {
        return try {
            val cr = requireContext().contentResolver
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
            decodeBitmapWithExif(bytes)
        } catch (_: Exception) { null }
    }

    private fun loadBitmapWithExifFromUrl(urlStr: String, onReady: (Bitmap?) -> Unit) {
        Thread {
            var bmp: Bitmap? = null
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connect()
                val bytes = conn.inputStream.readBytes()
                conn.disconnect()
                bmp = decodeBitmapWithExif(bytes)
            } catch (_: Exception) {}
            Handler(Looper.getMainLooper()).post { onReady(bmp) }
        }.start()
    }

    private fun decodeBitmapWithExif(bytes: ByteArray): Bitmap? {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                else -> {}
            }
            if (!matrix.isIdentity) Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            else original
        } catch (_: Exception) { null }
    }

    private fun loadCrises(petId: String) {
        repo.getCrisesForPet(petId,
            onSuccess = { list ->
                val avgText = if (list.isNotEmpty()) {
                    val avgSec = list.map { it.durationSec }.average().toInt()
                    val min = avgSec / 60
                    val sec = avgSec % 60
                    getString(R.string.avg_crisis_time_format, min, sec)
                } else {
                    "-"
                }
                tvAvgCrisisTime.text = avgText

                if (list.isNotEmpty()) {
                    val lastCrisis = list.maxByOrNull { it.startedAt }
                    lastCrisis?.let {
                        val now = System.currentTimeMillis()
                        val days = ((now - it.startedAt) / (1000 * 60 * 60 * 24)).toInt()
                        val daysText = if (days == 0) getString(R.string.crisis_today) else getString(R.string.days_since_last_crisis, days)
                        tvLastCrisisDate.text = daysText
                    }
                } else {
                    tvLastCrisisDate.text = getString(R.string.no_crisis_registered)
                }
            },
            onError = {
                tvAvgCrisisTime.text = "-"
                tvLastCrisisDate.text = getString(R.string.error_loading_crisis)
            }
        )
    }
}
