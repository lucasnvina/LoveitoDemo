package com.loveito.demo.pets

import android.app.DatePickerDialog
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.loveito.demo.R
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Fragment dedicado a crear o editar una mascota.
 * - Si recibe argumento petId => modo edición
 * - Si no => modo creación
 */
class PetEditFragment : Fragment() {

    companion object {
        fun newCreate(): PetEditFragment = PetEditFragment()
        fun newEdit(petId: String): PetEditFragment = PetEditFragment().apply {
            arguments = Bundle().apply { putString("petId", petId) }
        }
    }

    private val repo = PetsRepository()
    private var petId: String? = null

    private lateinit var ivPhoto: ImageView
    private lateinit var ivCameraOverlay: ImageView
    private lateinit var tilName: TextInputLayout
    private lateinit var tilBreed: TextInputLayout
    private lateinit var tilWeight: TextInputLayout
    private lateinit var tilSex: TextInputLayout
    private lateinit var tilBirth: TextInputLayout
    private lateinit var tilNeutered: TextInputLayout
    private lateinit var tilHeight: TextInputLayout
    private lateinit var tilLength: TextInputLayout

    private lateinit var etName: TextInputEditText
    private lateinit var etBreed: TextInputEditText
    private lateinit var etWeight: TextInputEditText
    private lateinit var actvSex: AutoCompleteTextView
    private lateinit var etBirthDate: TextInputEditText
    private lateinit var actvNeutered: AutoCompleteTextView
    private lateinit var etHeight: TextInputEditText
    private lateinit var etLength: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnDelete: MaterialButton

    private var birthDateMillis: Long? = null
    private var pickedUri: Uri? = null

    private val sexOptions by lazy { listOf("Macho", "Hembra") }
    private val neuteredOptions by lazy { listOf(getString(R.string.yes), getString(R.string.no)) }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pickedUri = uri
            decodeBitmapWithExifFromUri(uri)?.let { ivPhoto.setImageBitmap(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        petId = arguments?.getString("petId")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_pet_edit, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ivPhoto = view.findViewById(R.id.ivEditPhoto)
        ivCameraOverlay = view.findViewById(R.id.ivCameraOverlay)
        tilName = view.findViewById(R.id.tilPetName)
        tilBreed = view.findViewById(R.id.tilPetBreed)
        tilWeight = view.findViewById(R.id.tilPetWeight)
        tilSex = view.findViewById(R.id.tilPetSex)
        tilBirth = view.findViewById(R.id.tilPetBirth)
        tilNeutered = view.findViewById(R.id.tilPetNeutered)
        tilHeight = view.findViewById(R.id.tilPetHeight)
        tilLength = view.findViewById(R.id.tilPetLength)
        etName = view.findViewById(R.id.etPetName)
        etBreed = view.findViewById(R.id.etPetBreed)
        etWeight = view.findViewById(R.id.etPetWeight)
        actvSex = view.findViewById(R.id.actvSex)
        etBirthDate = view.findViewById(R.id.etBirthDate)
        actvNeutered = view.findViewById(R.id.actvNeutered)
        etHeight = view.findViewById(R.id.etHeightCm)
        etLength = view.findViewById(R.id.etLengthCm)
        btnSave = view.findViewById(R.id.btnSave)
        btnCancel = view.findViewById(R.id.btnCancel)
        btnDelete = view.findViewById(R.id.btnDelete)

        // Dropdowns
        actvSex.setAdapter(android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, sexOptions))
        actvNeutered.setAdapter(android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, neuteredOptions))
        actvSex.setOnClickListener { actvSex.showDropDown() }
        actvNeutered.setOnClickListener { actvNeutered.showDropDown() }

        ivPhoto.setOnClickListener { pickImage.launch("image/*") }
        ivCameraOverlay.setOnClickListener { pickImage.launch("image/*") }

        etBirthDate.setOnClickListener { openDatePicker() }

        btnSave.setOnClickListener { save() }
        btnCancel.setOnClickListener { parentFragmentManager.popBackStack() }
        btnDelete.setOnClickListener { confirmDelete() }

        if (petId == null) {
            // Creación
            btnDelete.visibility = View.GONE
            ivPhoto.setImageResource(R.drawable.ic_user_placeholder)
        } else {
            loadPet()
        }
    }

    private fun loadPet() {
        repo.getPet(petId!!,
            onSuccess = { p ->
                etName.setText(p.name)
                etBreed.setText(p.breed ?: "")
                etWeight.setText(p.weightKg?.toString() ?: "")
                actvSex.setText(p.sex ?: "", false)
                actvNeutered.setText(if (p.neutered == true) getString(R.string.yes) else getString(R.string.no), false)
                etHeight.setText(p.heightCm?.toString() ?: "")
                etLength.setText(p.lengthCm?.toString() ?: "")
                birthDateMillis = p.birthDate
                etBirthDate.setText(p.birthDate?.let { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(java.util.Date(it)) }
                    ?: getString(R.string.pet_label_not_defined))
                if (!p.photoUrl.isNullOrBlank()) loadPhoto(p.photoUrl!!) else ivPhoto.setImageResource(R.drawable.ic_user_placeholder)
            },
            onError = { e -> Toast.makeText(requireContext(), getString(R.string.error, e.localizedMessage), Toast.LENGTH_SHORT).show() })
    }

    private fun openDatePicker() {
        val cal = Calendar.getInstance()
        birthDateMillis?.let { cal.timeInMillis = it }
        DatePickerDialog(requireContext(), { _, y, m, d ->
            val c = Calendar.getInstance(); c.set(y, m, d, 0, 0, 0); c.set(Calendar.MILLISECOND, 0)
            birthDateMillis = c.timeInMillis
            etBirthDate.setText(SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(c.time))
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun validate(): Boolean {
        if (etName.text.isNullOrBlank()) { Toast.makeText(requireContext(), getString(R.string.enter_name), Toast.LENGTH_SHORT).show(); return false }
        return true
    }

    private fun save() {
        if (!validate()) return
        val name = etName.text.toString().trim()
        val breed = etBreed.text.toString().trim().ifEmpty { null }
        val weight = etWeight.text.toString().replace(',', '.').toDoubleOrNull()
        val sex = actvSex.text?.toString()?.ifEmpty { null }
        val neutered = actvNeutered.text?.toString() == getString(R.string.yes)
        val height = etHeight.text.toString().replace(',', '.').toDoubleOrNull()
        val length = etLength.text.toString().replace(',', '.').toDoubleOrNull()

        val id = petId
        if (id == null) {
            // Crear
            repo.createPet(name, breed, weight, pickedUri, sex, birthDateMillis, neutered, height, length,
                medications = null, professionals = null,
                onSuccess = { newId ->
                    Toast.makeText(requireContext(), getString(R.string.pet_created), Toast.LENGTH_SHORT).show()
                    parentFragmentManager.setFragmentResult("petCreated", Bundle().apply { putString("petId", newId) })
                    // Navegar a detalles
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.fragment_host, PetDetailsFragment.newInstance(newId))
                        .commit()
                },
                onError = { e -> Toast.makeText(requireContext(), getString(R.string.error, e.localizedMessage), Toast.LENGTH_SHORT).show() }
            )
        } else {
            repo.updatePet(id, name, breed, weight, pickedUri, sex, birthDateMillis, neutered, height, length,
                medications = null, professionals = null,
                onSuccess = {
                    Toast.makeText(requireContext(), getString(R.string.pet_updated), Toast.LENGTH_SHORT).show()
                    parentFragmentManager.setFragmentResult("petUpdated", Bundle().apply { putString("petId", id) })
                    parentFragmentManager.popBackStack()
                },
                onError = { e -> Toast.makeText(requireContext(), getString(R.string.error, e.localizedMessage), Toast.LENGTH_SHORT).show() }
            )
        }
    }

    private fun confirmDelete() {
        val id = petId ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_pet_title))
            .setMessage(getString(R.string.delete_pet_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ -> delete(id) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun delete(id: String) {
        repo.deletePet(id,
            onSuccess = {
                Toast.makeText(requireContext(), getString(R.string.pet_deleted), Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            },
            onError = { e -> Toast.makeText(requireContext(), getString(R.string.error, e.localizedMessage), Toast.LENGTH_SHORT).show() }
        )
    }

    private fun loadPhoto(url: String) {
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connect(); val bytes = conn.inputStream.readBytes(); conn.disconnect()
                val bmp = decodeBitmapWithExif(bytes)
                Handler(Looper.getMainLooper()).post { ivPhoto.setImageBitmap(bmp) }
            } catch (_: Exception) {}
        }.start()
    }

    private fun decodeBitmapWithExifFromUri(uri: Uri): android.graphics.Bitmap? {
        return try {
            val bytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            decodeBitmapWithExif(bytes)
        } catch (_: Exception) { null }
    }

    private fun decodeBitmapWithExif(bytes: ByteArray): android.graphics.Bitmap? {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.setRotate(180f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
                else -> { /* ORIENTATION_NORMAL or undefined */ }
            }
            if (!matrix.isIdentity) android.graphics.Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true) else original
        } catch (_: Exception) { null }
    }
}
