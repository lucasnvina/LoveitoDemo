package com.loveito.demo

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.EditText

class ProfileFragment : Fragment() {
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private var selectedPhotoUri: Uri? = null
    private lateinit var ivProfilePhoto: ImageView
    private lateinit var tvFullName: TextView
    private lateinit var tvEmail: TextView
    private lateinit var btnSaveProfile: MaterialButton
    private lateinit var btnEditProfile: MaterialButton
    private lateinit var btnCancelProfile: MaterialButton
    private lateinit var etFirstName: EditText
    private lateinit var etLastName: EditText
    private var isEditingName = false
    private var originalFirstName: String = ""
    private var originalLastName: String = ""
    private var currentUid: String? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedPhotoUri = uri
            ivProfilePhoto.setImageURI(uri)
            currentUid?.let { uid ->
                uploadPhoto(uid, uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_profile, container, false)

        ivProfilePhoto = view.findViewById(R.id.ivProfilePhoto)
        tvFullName = view.findViewById(R.id.tvFullName)
        tvEmail = view.findViewById(R.id.tvEmail)
        btnSaveProfile = view.findViewById(R.id.btnSaveProfile)
        btnEditProfile = view.findViewById(R.id.btnEditProfile)
        btnCancelProfile = view.findViewById(R.id.btnCancelProfile)
        etFirstName = view.findViewById(R.id.etFirstName)
        etLastName = view.findViewById(R.id.etLastName)

        val user = FirebaseAuth.getInstance().currentUser
        val uid = user?.uid ?: ""
        currentUid = uid
        tvEmail.text = user?.email ?: ""

        // Cargar datos del usuario desde Firestore
        db.collection("users").document(uid).get().addOnSuccessListener { doc ->
            if (doc != null && doc.exists()) {
                originalFirstName = doc.getString("firstName") ?: ""
                originalLastName = doc.getString("lastName") ?: ""
                tvFullName.text = listOf(originalFirstName, originalLastName).filter { it.isNotBlank() }.joinToString(" ")
                etFirstName.setText(originalFirstName)
                etLastName.setText(originalLastName)
                val photoUrl = doc.getString("photoUrl")
                if (!photoUrl.isNullOrEmpty()) {
                    // Cargar imagen con Glide si está disponible
                    try {
                        com.bumptech.glide.Glide.with(this)
                            .load(photoUrl)
                            .circleCrop()
                            .into(ivProfilePhoto)
                    } catch (_: Exception) {}
                }
            }
        }

        // Hacer la foto clickeable para cambiarla (se sube automáticamente)
        ivProfilePhoto.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // Entrar en modo edición
        btnEditProfile.setOnClickListener {
            enterEditMode()
        }

        // Guardar cambios de nombre
        btnSaveProfile.setOnClickListener {
            if (!isEditingName) return@setOnClickListener
            val newFirst = etFirstName.text.toString().trim()
            val newLast = etLastName.text.toString().trim()
            if (newFirst.isEmpty() || newLast.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), "Nombre y apellido son obligatorios", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val updates = hashMapOf<String, Any>(
                "firstName" to newFirst,
                "lastName" to newLast
            )
            db.collection("users").document(uid).update(updates)
                .addOnSuccessListener {
                    originalFirstName = newFirst
                    originalLastName = newLast
                    tvFullName.text = "$newFirst $newLast"
                    exitEditMode(cancel = false)
                    android.widget.Toast.makeText(requireContext(), "Nombre actualizado", android.widget.Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    android.widget.Toast.makeText(requireContext(), "Error al guardar nombre", android.widget.Toast.LENGTH_SHORT).show()
                }
        }

        // Cancelar edición
        btnCancelProfile.setOnClickListener {
            if (isEditingName) {
                etFirstName.setText(originalFirstName)
                etLastName.setText(originalLastName)
                exitEditMode(cancel = true)
            }
        }

        // Asegurar estado inicial (vista, no edición)
        exitEditMode(cancel = true)

        return view
    }

    private fun enterEditMode() {
        if (isEditingName) return
        isEditingName = true
        tvFullName.visibility = View.GONE
        etFirstName.visibility = View.VISIBLE
        etLastName.visibility = View.VISIBLE
        btnEditProfile.visibility = View.GONE
        btnSaveProfile.visibility = View.VISIBLE
        btnCancelProfile.visibility = View.VISIBLE
        etFirstName.requestFocus()
    }

    private fun exitEditMode(cancel: Boolean) {
        isEditingName = false
        tvFullName.visibility = View.VISIBLE
        etFirstName.visibility = View.GONE
        etLastName.visibility = View.GONE
        btnEditProfile.visibility = View.VISIBLE
        btnSaveProfile.visibility = View.GONE
        btnCancelProfile.visibility = View.GONE
    }

    private fun uploadPhoto(uid: String, uri: Uri) {
        val ref = storage.reference.child("users/$uid/profilepic")
        ref.putFile(uri).continueWithTask { task ->
            if (!task.isSuccessful) throw task.exception ?: Exception("Upload failed")
            ref.downloadUrl
        }.addOnSuccessListener { download ->
            val updates = hashMapOf<String, Any>("photoUrl" to download.toString())
            db.collection("users").document(uid).update(updates)
                .addOnSuccessListener {
                    android.widget.Toast.makeText(requireContext(), "Foto actualizada", android.widget.Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    android.widget.Toast.makeText(requireContext(), "Error al guardar foto", android.widget.Toast.LENGTH_SHORT).show()
                }
        }.addOnFailureListener { ex ->
            android.widget.Toast.makeText(requireContext(), "Error al subir foto: ${ex.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
