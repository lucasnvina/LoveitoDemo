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
import com.loveito.demo.R
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.math.max

class PetFormFragment : Fragment() {

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
    private var ivPreview: ImageView? = null

    // Groups
    private lateinit var groupView: LinearLayout
    private lateinit var groupEdit: LinearLayout
    private lateinit var btnStartEdit: Button

    // Summary
    private lateinit var tvSName: TextView
    private lateinit var tvSBreed: TextView
    private lateinit var tvSWeight: TextView
    private lateinit var tvSSex: TextView
    private lateinit var tvSBirth: TextView
    private lateinit var tvSAge: TextView
    private lateinit var tvSNeutered: TextView
    private lateinit var tvSHeight: TextView
    private lateinit var tvSLength: TextView

    // Editor
    private lateinit var etName: EditText
    private lateinit var etBreed: EditText
    private lateinit var etWeight: EditText
    private lateinit var spSex: Spinner
    private lateinit var btnPickBirth: Button
    private lateinit var tvBirthDate: TextView
    private lateinit var cbNeutered: CheckBox
    private lateinit var etHeight: EditText
    private lateinit var etLength: EditText
    private lateinit var btnPick: Button
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button
    private lateinit var btnDeletePet: Button

    private var birthDateMillis: Long? = null

    // Crisis container
    private lateinit var crisesContainer: LinearLayout
    private var crisesItems: MutableList<Crisis> = mutableListOf()
    private var editingId: String? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pickedUri = uri
            val bmp = decodeBitmapWithExifFromUri(uri)
            ivPreview?.setImageBitmap(bmp)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_pet_form, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ivPreview = view.findViewById(R.id.ivPreview)
        groupView = view.findViewById(R.id.groupView)
        groupEdit = view.findViewById(R.id.groupEdit)
        btnStartEdit = view.findViewById(R.id.btnStartEdit)

        tvSName = view.findViewById(R.id.tvSName)
        tvSBreed = view.findViewById(R.id.tvSBreed)
        tvSWeight = view.findViewById(R.id.tvSWeight)
        tvSSex = view.findViewById(R.id.tvSSex)
        tvSBirth = view.findViewById(R.id.tvSBirth)
        tvSAge = view.findViewById(R.id.tvSAge)
        tvSNeutered = view.findViewById(R.id.tvSNeutered)
        tvSHeight = view.findViewById(R.id.tvSHeight)
        tvSLength = view.findViewById(R.id.tvSLength)

        etName = view.findViewById(R.id.etPetName)
        etBreed = view.findViewById(R.id.etPetBreed)
        etWeight = view.findViewById(R.id.etPetWeight)
        spSex = view.findViewById(R.id.spSex)
        btnPickBirth = view.findViewById(R.id.btnPickBirth)
        tvBirthDate = view.findViewById(R.id.tvBirthDate)
        cbNeutered = view.findViewById(R.id.cbNeutered)
        etHeight = view.findViewById(R.id.etHeightCm)
        etLength = view.findViewById(R.id.etLengthCm)
        btnPick = view.findViewById(R.id.btnPickImage)
        btnSave = view.findViewById(R.id.btnSavePet)
        btnCancel = view.findViewById(R.id.btnCancelEdit)
        btnDeletePet = view.findViewById(R.id.btnDeletePet)
        crisesContainer = view.findViewById(R.id.containerCrises)

        val sexAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf("","Macho","Hembra","Otro"))
        spSex.adapter = sexAdapter

        editingId = arguments?.getString("id")

        if (editingId != null) {
            switchToViewMode()
            repo.getPet(editingId!!,
                onSuccess = { p ->
                    // fill editor fields for when user taps "Editar"
                    etName.setText(p.name)
                    etBreed.setText(p.breed ?: "")
                    etWeight.setText(p.weightKg?.toString() ?: "")
                    val sexPos = listOf("","Macho","Hembra","Otro").indexOf(p.sex ?: "")
                    spSex.setSelection(max(sexPos, 0))
                    cbNeutered.isChecked = p.neutered == true
                    etHeight.setText(p.heightCm?.toString() ?: "")
                    etLength.setText(p.lengthCm?.toString() ?: "")
                    birthDateMillis = p.birthDate
                    if (birthDateMillis != null) tvBirthDate.text = formatDate(birthDateMillis!!)

                    p.photoUrl?.let { url -> loadBitmapWithExifFromUrl(url) { bmp -> ivPreview?.setImageBitmap(bmp) } }

                    renderSummary(p)
                    loadCrises(p.id)
                },
                onError = { }
            )
        } else {
            // Creating new pet -> only edit mode
            btnDeletePet.visibility = View.GONE
            switchToEditMode()
        }

        btnPick.setOnClickListener { pickImage.launch("image/*") }

        btnPickBirth.setOnClickListener {
            val cal = Calendar.getInstance()
            if (birthDateMillis != null) cal.timeInMillis = birthDateMillis!!
            val dlg = DatePickerDialog(requireContext(),
                { _, y, m, d ->
                    val c = Calendar.getInstance()
                    c.set(y, m, d, 0, 0, 0)
                    c.set(Calendar.MILLISECOND, 0)
                    birthDateMillis = c.timeInMillis
                    tvBirthDate.text = formatDate(birthDateMillis!!)
                },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
            )
            dlg.show()
        }

        btnSave.setOnClickListener {
            val weight = etWeight.text.toString().replace(',', '.').toDoubleOrNull()
            val breed = etBreed.text.toString().trim().ifEmpty { null }
            val name = etName.text.toString().trim()
            val sex = spSex.selectedItem?.toString()?.ifEmpty { null }
            val neutered = cbNeutered.isChecked
            val height = etHeight.text.toString().replace(',', '.').toDoubleOrNull()
            val length = etLength.text.toString().replace(',', '.').toDoubleOrNull()
            save(name, breed, weight, sex, birthDateMillis, neutered, height, length)
        }

        btnDeletePet.setOnClickListener {
            val id = editingId ?: return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("Eliminar Mascota")
                .setMessage("¿Querés eliminar esta mascota y sus datos asociados?")
                .setPositiveButton("Eliminar") { _, _ ->
                    repo.deletePet(id,
                        onSuccess = {
                            Toast.makeText(requireContext(), "Mascota eliminada", Toast.LENGTH_SHORT).show()
                            parentFragmentManager.popBackStack()
                        },
                        onError = { e ->
                            Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        btnStartEdit.setOnClickListener {
            switchToEditMode()
        }

        btnCancel.setOnClickListener {
            pickedUri = null
            // reload pet to refresh editor fields to last saved
            editingId?.let { id ->
                repo.getPet(id, onSuccess = { p ->
                    etName.setText(p.name)
                    etBreed.setText(p.breed ?: "")
                    etWeight.setText(p.weightKg?.toString() ?: "")
                    val sexPos = listOf("","Macho","Hembra","Otro").indexOf(p.sex ?: "")
                    spSex.setSelection(max(sexPos, 0))
                    cbNeutered.isChecked = p.neutered == true
                    etHeight.setText(p.heightCm?.toString() ?: "")
                    etLength.setText(p.lengthCm?.toString() ?: "")
                    birthDateMillis = p.birthDate
                    tvBirthDate.text = if (birthDateMillis != null) formatDate(birthDateMillis!!) else "No definida"
                    renderSummary(p)
                }, onError = {})
            }
            switchToViewMode()
        }
    }

    override fun onResume() {
        super.onResume()
        editingId?.let { loadCrises(it) }
    }

    override fun onDestroyView() { super.onDestroyView(); ivPreview = null }

    private fun switchToViewMode() {
        groupView.visibility = View.VISIBLE
        groupEdit.visibility = View.GONE
    }

    private fun switchToEditMode() {
        groupView.visibility = View.GONE
        groupEdit.visibility = View.VISIBLE
    }

    private fun save(name: String, breed: String?, weightKg: Double?, sex: String?, birthDate: Long?, neutered: Boolean, heightCm: Double?, lengthCm: Double?) {
        if (name.isEmpty()) { Toast.makeText(requireContext(), "Ingresá un nombre", Toast.LENGTH_SHORT).show(); return }
        val id = editingId
        if (id == null) {
            repo.createPet(name, breed, weightKg, pickedUri, sex, birthDate, neutered, heightCm, lengthCm,
                onSuccess = { Toast.makeText(requireContext(), "Mascota creada", Toast.LENGTH_SHORT).show(); parentFragmentManager.popBackStack() },
                onError = { e -> Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show() }
            )
        } else {
            repo.updatePet(id, name, breed, weightKg, pickedUri, sex, birthDate, neutered, heightCm, lengthCm,
                onSuccess = {
                    Toast.makeText(requireContext(), "Mascota actualizada", Toast.LENGTH_SHORT).show()
                    // After save, go back to view mode and refresh summary
                    repo.getPet(id, onSuccess = { p ->
                        renderSummary(p)
                    }, onError = { })
                    pickedUri = null
                    switchToViewMode()
                },
                onError = { e -> Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show() }
            )
        }
    }

    private fun loadCrises(petId: String) {
        repo.getCrisesForPet(petId,
            onSuccess = { list ->
                crisesItems.clear(); crisesItems.addAll(list)
                renderCrises()
            },
            onError = { e -> Toast.makeText(requireContext(), "Error al cargar crisis: ${e.localizedMessage}", Toast.LENGTH_SHORT).show() }
        )
    }

    private fun renderCrises() {
        crisesContainer.removeAllViews()
        if (crisesItems.isEmpty()) {
            val empty = TextView(requireContext())
            empty.text = "Sin crisis registradas"
            empty.setPadding(8, 8, 8, 8)
            crisesContainer.addView(empty)
            return
        }
        for (c in crisesItems) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 12, 16, 12)
            }
            val ts = java.text.SimpleDateFormat("dd/MM HH:mm").format(java.util.Date(c.startedAt))
            val mins = c.durationSec / 60
            val secs = c.durationSec % 60
            val sev = (c.triageSeverity ?: "").lowercase()
            val sevTag = when (sev) {
                "red" -> " [EMERGENCIA]"
                "amber" -> " [URGENCIA]"
                "green" -> " [OK]"
                else -> ""
            }
            val title = TextView(requireContext()).apply {
                text = "[$ts] ${c.note ?: "crisis"} — ${mins}m ${secs}s$sevTag"
                textSize = 16f
            }
            row.addView(title)
            row.setOnClickListener {
                val frag = CrisisDetailFragment.newInstance(c.petId, c.id)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_host, frag)
                    .addToBackStack(null)
                    .commit()
            }
            val divider = View(requireContext()).apply {
                setBackgroundColor(0x22FFFFFF)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(0, 8, 0, 8) }
            }
            crisesContainer.addView(row)
            crisesContainer.addView(divider)
        }
    }

    private fun renderSummary(p: Pet) {
        tvSName.text = "Nombre: ${p.name}"
        tvSBreed.text = "Raza: ${p.breed ?: "—"}"
        tvSWeight.text = "Peso: ${p.weightKg?.let { "${it} kg" } ?: "—"}"
        tvSSex.text = "Sexo: ${p.sex ?: "—"}"
        tvSBirth.text = "Nacimiento: ${p.birthDate?.let { formatDate(it) } ?: "—"}"
        tvSAge.text = "Edad: ${p.birthDate?.let { yearsFrom(it) }?.let { "${it} años" } ?: "—"}"
        tvSNeutered.text = "Castrado: ${if (p.neutered == true) "Sí" else "No" }"
        tvSHeight.text = "Alto: ${p.heightCm?.let { "${it} cm" } ?: "—"}"
        tvSLength.text = "Largo: ${p.lengthCm?.let { "${it} cm" } ?: "—"}"
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
        return java.text.SimpleDateFormat("dd/MM/yyyy").format(java.util.Date(millis))
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
}
