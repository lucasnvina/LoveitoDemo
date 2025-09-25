package com.loveito.demo.pets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.loveito.demo.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PetDetailsFragment : Fragment() {

    companion object {
        fun newInstance(petId: String): PetDetailsFragment = PetDetailsFragment().apply {
            arguments = Bundle().apply { putString("petId", petId) }
        }
    }

    private val repo = PetsRepository()
    private var petId: String? = null
    private var pet: Pet? = null

    // UI
    private lateinit var tvName: TextView
    private lateinit var ivPhoto: ImageView
    private lateinit var tvBreed: TextView
    private lateinit var tvWeight: TextView
    private lateinit var tvSex: TextView
    private lateinit var tvBirth: TextView
    private lateinit var tvAge: TextView
    private lateinit var tvNeutered: TextView
    private lateinit var tvHeight: TextView
    private lateinit var tvLength: TextView
    private lateinit var tvLastCrisis: TextView
    private lateinit var tvAvgTime: TextView
    private lateinit var tvMedicationSummary: TextView
    private lateinit var tvProfessionalSummary: TextView
    private lateinit var btnEdit: MaterialButton

    private lateinit var sectionMedication: LinearLayout
    private lateinit var sectionProfessional: LinearLayout
    private lateinit var sectionShare: LinearLayout
    private lateinit var sectionCare: LinearLayout
    private lateinit var sectionLastCrisis: LinearLayout
    private lateinit var sectionAvgTime: LinearLayout

    private var logo: ImageView? = null

    private val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    private lateinit var viewModel: PetDetailsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        petId = arguments?.getString("petId")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_pet_details, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvName = view.findViewById(R.id.tvName)
        ivPhoto = view.findViewById(R.id.ivPhoto)
        tvBreed = view.findViewById(R.id.tvBreed)
        tvWeight = view.findViewById(R.id.tvWeight)
        tvSex = view.findViewById(R.id.tvSex)
        tvBirth = view.findViewById(R.id.tvBirth)
        tvAge = view.findViewById(R.id.tvAge)
        tvNeutered = view.findViewById(R.id.tvNeutered)
        tvHeight = view.findViewById(R.id.tvHeight)
        tvLength = view.findViewById(R.id.tvLength)
        tvLastCrisis = view.findViewById(R.id.tvLastCrisis)
        tvAvgTime = view.findViewById(R.id.tvAvgTime)
        tvMedicationSummary = view.findViewById(R.id.tvMedicationSummary)
        tvProfessionalSummary = view.findViewById(R.id.tvProfessionalSummary)
        btnEdit = view.findViewById(R.id.btnEdit)
        sectionMedication = view.findViewById(R.id.sectionMedication)
        sectionProfessional = view.findViewById(R.id.sectionProfessional)
        sectionShare = view.findViewById(R.id.sectionShare)
        sectionCare = view.findViewById(R.id.sectionCare)
        sectionLastCrisis = view.findViewById(R.id.sectionLastCrisis)
        sectionAvgTime = view.findViewById(R.id.sectionAvgTime)
        logo = view.findViewById(R.id.ivLogoLoveitoDog)

        if (petId == null) {
            Toast.makeText(requireContext(), "Falta petId", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack(); return
        }

        viewModel = ViewModelProvider(
            this,
            PetDetailsViewModel.Factory(petId!!, repo)
        )[PetDetailsViewModel::class.java]

        observeViewModel()

        btnEdit.setOnClickListener { openEdit() }

        sectionMedication.setOnClickListener { petId?.let { openMedications(it) } }
        sectionProfessional.setOnClickListener { petId?.let { openProfessionals(it) } }
        sectionShare.setOnClickListener { pet?.let { showShareDialog(it) } }
        sectionLastCrisis.setOnClickListener { petId?.let { openCrises(it) } }
        sectionAvgTime.setOnClickListener { petId?.let { openCrises(it) } }
        logo?.setOnClickListener { quickRegisterCrisis() }

        parentFragmentManager.setFragmentResultListener("petUpdated", this) { _, bundle ->
            if (bundle.getString("petId") == petId) {
                // Recargar sólo datos de mascota (mantiene métricas ya cargadas)
                viewModel.loadPet(loadCrisesIfNeeded = false)
            }
        }
        parentFragmentManager.setFragmentResultListener("petCreated", this) { _, bundle ->
            if (bundle.getString("petId") == petId) viewModel.loadPet(loadCrisesIfNeeded = true)
        }

        // iniciar carga (pet + métricas si aún no)
        viewModel.loadPet(loadCrisesIfNeeded = true)
    }

    private fun observeViewModel() {
        viewModel.pet.observe(viewLifecycleOwner) { p -> p?.let { bind(it) } }
        viewModel.metrics.observe(viewLifecycleOwner) { m ->
            val lastText = if (!m.loaded) {
                tvLastCrisis.text // mantener
            } else if (!m.hasCrises) {
                getString(R.string.no_crisis_registered)
            } else {
                val delta = System.currentTimeMillis() - (m.lastStartedAt ?: 0L)
                val days = (delta / (1000 * 60 * 60 * 24)).toInt()
                if (days == 0) getString(R.string.crisis_today) else getString(R.string.days_since_last_crisis, days)
            }
            val avgText = if (!m.loaded) {
                tvAvgTime.text
            } else if (!m.hasCrises) {
                getString(R.string.dash)
            } else {
                val sec = m.avgDurationSec ?: 0
                val min = sec / 60; val s = sec % 60
                getString(R.string.avg_crisis_time_format, min, s)
            }
            tvLastCrisis.text = lastText
            tvAvgTime.text = avgText
        }
    }

    private fun bind(p: Pet) {
        tvName.text = p.name
        tvBreed.text = p.breed ?: getString(R.string.dash)
        tvWeight.text = p.weightKg?.let { "${it} ${getString(R.string.kg)}" } ?: getString(R.string.dash)
        tvSex.text = p.sex ?: getString(R.string.dash)
        tvBirth.text = p.birthDate?.let { dateFmt.format(Date(it)) } ?: getString(R.string.dash)
        tvAge.text = p.birthDate?.let { yearsFrom(it).toString() } ?: getString(R.string.dash)
        tvNeutered.text = if (p.neutered == true) getString(R.string.yes) else getString(R.string.no)
        tvHeight.text = p.heightCm?.let { "${it} ${getString(R.string.cm)}" } ?: getString(R.string.dash)
        tvLength.text = p.lengthCm?.let { "${it} ${getString(R.string.cm)}" } ?: getString(R.string.dash)
        val medsCount = p.medications.size
        tvMedicationSummary.text = when (medsCount) {
            0 -> getString(R.string.medication_no_info)
            1 -> getString(R.string.medication_summary_single)
            else -> getString(R.string.medication_summary_multi, medsCount)
        }
        val fav = p.professionals.firstOrNull { it.isFavorite }
        tvProfessionalSummary.text = fav?.let { listOf(it.name, it.lastName).filter { s -> s.isNotBlank() }.joinToString(" ") }
            ?: getString(R.string.professional_no_info)
        // photo
        if (!p.photoUrl.isNullOrBlank()) loadPhoto(p.photoUrl!!) else ivPhoto.setImageResource(R.drawable.ic_user_placeholder)
    }

    private fun loadPhoto(url: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connect(); val bytes = conn.inputStream.readBytes(); conn.disconnect()
                val bmp = decodeBitmapWithExif(bytes)
                withContext(Dispatchers.Main) { ivPhoto.setImageBitmap(bmp) }
            } catch (_: Exception) {}
        }
    }

    private fun decodeBitmapWithExif(bytes: ByteArray): android.graphics.Bitmap? {
        return try {
            val exif = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
            val orientation = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
            val original = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val matrix = android.graphics.Matrix()
            when (orientation) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.setRotate(180f); matrix.postScale(-1f, 1f) }
                androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
                else -> { /* ORIENTATION_NORMAL */ }
            }
            if (!matrix.isIdentity) android.graphics.Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true) else original
        } catch (_: Exception) { null }
    }

    private fun openEdit() {
        val f = com.loveito.demo.pets.PetEditFragment.newEdit(petId!!)
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_host, f)
            .addToBackStack(null)
            .commit()
    }

    private fun openMedications(id: String) {
        val f = PetMedicationsFragment(); f.arguments = Bundle().apply { putString("petId", id) }
        parentFragmentManager.beginTransaction().replace(R.id.fragment_host, f).addToBackStack(null).commit()
    }

    private fun openProfessionals(id: String) {
        val f = PetProfessionalsFragment(); f.arguments = Bundle().apply { putString("petId", id) }
        parentFragmentManager.beginTransaction().replace(R.id.fragment_host, f).addToBackStack(null).commit()
    }

    private fun openCrises(id: String) {
        val f = PetCrisesFragment(); f.arguments = Bundle().apply { putString("petId", id) }
        parentFragmentManager.beginTransaction().replace(R.id.fragment_host, f).addToBackStack(null).commit()
    }

    private fun yearsFrom(millis: Long): Int {
        val dob = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val now = java.util.Calendar.getInstance()
        var years = now.get(java.util.Calendar.YEAR) - dob.get(java.util.Calendar.YEAR)
        val mNow = now.get(java.util.Calendar.MONTH)
        val dNow = now.get(java.util.Calendar.DAY_OF_MONTH)
        if (mNow < dob.get(java.util.Calendar.MONTH) || (mNow == dob.get(java.util.Calendar.MONTH) && dNow < dob.get(java.util.Calendar.DAY_OF_MONTH))) years -= 1
        return years
    }

    private fun quickRegisterCrisis() {
        val id = petId ?: return
        val logoView = logo ?: return
        logoView.isEnabled = false
        val triage = TriageEngine.randomResult(requireContext())
        repo.createTestCrisisWithTriage(id, triage,
            onSuccess = {
                Toast.makeText(requireContext(), "Crisis registrada", Toast.LENGTH_SHORT).show()
                viewModel.invalidateCrises()
                viewModel.loadCrises(force = true)
                logoView.postDelayed({ logoView.isEnabled = true }, 600)
            },
            onError = { e -> Toast.makeText(requireContext(), getString(R.string.error, e.localizedMessage), Toast.LENGTH_SHORT).show(); logoView.isEnabled = true }
        )
    }

    // --- Compartir PDF (adaptado del fragment original) ---
    private fun showShareDialog(p: Pet) {
        val ctx = requireContext()
        val container = android.widget.ScrollView(ctx)
        val content = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48,32,48,16) }
        container.addView(content)
        val cbCrises = android.widget.CheckBox(ctx).apply { text = getString(R.string.share_section_crises); isChecked = true }
        val cbMeds = android.widget.CheckBox(ctx).apply { text = getString(R.string.share_section_medications); isChecked = p.medications.isNotEmpty() }
        val cbPros = android.widget.CheckBox(ctx).apply { text = getString(R.string.share_section_professionals); isChecked = p.professionals.isNotEmpty() }
        val cbCare = android.widget.CheckBox(ctx).apply { text = getString(R.string.share_section_care); isChecked = false }
        content.addView(cbCrises); content.addView(cbMeds); content.addView(cbPros); content.addView(cbCare)
        val authEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email
        val cbMe = android.widget.CheckBox(ctx).apply { text = getString(R.string.share_include_me); isChecked = false; isEnabled = authEmail != null }
        content.addView(android.widget.TextView(ctx).apply { text = getString(R.string.share_recipients_title); textSize = 16f; setPadding(0,32,0,8) })
        content.addView(cbMe)
        val profChecks = mutableListOf<Pair<Professional, android.widget.CheckBox>>()
        p.professionals.filter { it.email.isNotBlank() }.forEach { prof ->
            val cb = android.widget.CheckBox(ctx).apply { text = listOf(prof.name, prof.lastName).filter { it.isNotBlank() }.joinToString(" ") + " (" + prof.email + ")"; isChecked = prof.isFavorite }
            profChecks += prof to cb; content.addView(cb)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setView(container)
            .setPositiveButton(R.string.share_label, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val sectionsOk = listOf(cbCrises, cbMeds, cbPros, cbCare).any { it.isChecked }
                if (!sectionsOk) { Toast.makeText(ctx, getString(R.string.share_no_section_selected), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                val recipients = mutableSetOf<String>()
                if (cbMe.isChecked && authEmail != null) recipients += authEmail
                profChecks.forEach { (pr, cb) -> if (cb.isChecked) recipients += pr.email }
                if (recipients.isEmpty()) { Toast.makeText(ctx, getString(R.string.share_no_recipient_selected), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                dialog.dismiss()
                generateAndSharePdf(p, cbCrises.isChecked, cbMeds.isChecked, cbPros.isChecked, cbCare.isChecked, recipients.toList())
            }
        }
        dialog.show()
    }

    private fun generateAndSharePdf(pet: Pet, c: Boolean, m: Boolean, pr: Boolean, care: Boolean, recipients: List<String>) {
        Toast.makeText(requireContext(), getString(R.string.share_generate_pdf), Toast.LENGTH_SHORT).show()
        if (c) repo.getCrisesForPet(pet.id,
            onSuccess = { crises -> buildShare(pet, crises, c, m, pr, care, recipients) },
            onError = { buildShare(pet, emptyList(), c, m, pr, care, recipients) })
        else buildShare(pet, emptyList(), c, m, pr, care, recipients)
    }

    private fun buildShare(p: Pet, crises: List<Crisis>, c: Boolean, m: Boolean, pr: Boolean, care: Boolean, recipients: List<String>) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val file = try { PetReportPdfBuilder(requireContext(), c, m, pr, care).build(p, crises) } catch (e: Exception) { null }
            withContext(Dispatchers.Main) {
                if (file == null || !file.exists()) {
                    Toast.makeText(requireContext(), getString(R.string.share_pdf_error), Toast.LENGTH_SHORT).show()
                } else {
                    shareFile(file, p, c, m, pr, care, recipients)
                    view?.postDelayed({ file.delete() }, 5 * 60 * 1000)
                }
            }
        }
    }

    private fun shareFile(file: java.io.File, p: Pet, c: Boolean, m: Boolean, pr: Boolean, care: Boolean, recipients: List<String>) {
        val ctx = requireContext()
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val sections = mutableListOf<String>()
        if (c) sections += getString(R.string.share_section_crises)
        if (m) sections += getString(R.string.share_section_medications)
        if (pr) sections += getString(R.string.share_section_professionals)
        if (care) sections += getString(R.string.share_section_care)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(android.content.Intent.EXTRA_EMAIL, recipients.toTypedArray())
            putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.share_email_subject, p.name))
            putExtra(android.content.Intent.EXTRA_TEXT, getString(R.string.share_email_body_intro, p.name, sections.joinToString(", ")))
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(android.content.Intent.createChooser(intent, getString(R.string.share_chooser_title))) }
        catch (_: Exception) { Toast.makeText(ctx, getString(R.string.share_no_app), Toast.LENGTH_SHORT).show() }
    }
}
