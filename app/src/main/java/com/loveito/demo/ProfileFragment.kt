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

class ProfileFragment : Fragment() {
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private var selectedPhotoUri: Uri? = null
    private lateinit var ivProfilePhoto: ImageView
    private lateinit var tvFullName: TextView
    private lateinit var tvEmail: TextView
    private lateinit var btnSaveProfile: MaterialButton
    private lateinit var btnEditProfile: MaterialButton
    private lateinit var etFirstName: android.widget.EditText
    private lateinit var etLastName: android.widget.EditText
    private var isEditingName = false

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedPhotoUri = uri
            ivProfilePhoto.setImageURI(uri)
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
        etFirstName = view.findViewById(R.id.etFirstName)
        etLastName = view.findViewById(R.id.etLastName)

        val user = FirebaseAuth.getInstance().currentUser
        val uid = user?.uid ?: ""
        tvEmail.text = user?.email ?: ""

        // Cargar datos del usuario desde Firestore
        db.collection("users").document(uid).get().addOnSuccessListener { doc ->
            if (doc != null && doc.exists()) {
                val firstName = doc.getString("firstName") ?: ""
                val lastName = doc.getString("lastName") ?: ""
                tvFullName.text = "$firstName $lastName"
                etFirstName.setText(firstName)
                etLastName.setText(lastName)
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

        // Make photo tappable to change
        ivProfilePhoto.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        btnEditProfile.setOnClickListener {
            if (!isEditingName) {
                // Switch to edit mode
                tvFullName.visibility = View.GONE
                etFirstName.visibility = View.VISIBLE
                etLastName.visibility = View.VISIBLE
                isEditingName = true
            } else {
                // Cancel edit mode
                tvFullName.visibility = View.VISIBLE
                etFirstName.visibility = View.GONE
                etLastName.visibility = View.GONE
                isEditingName = false
            }
        }

        btnSaveProfile.setOnClickListener {
            if (isEditingName) {
                val firstName = etFirstName.text.toString().trim()
                val lastName = etLastName.text.toString().trim()
                if (firstName.isEmpty() || lastName.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), "Nombre y apellido son obligatorios", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val updates = hashMapOf<String, Any>(
                    "firstName" to firstName,
                    "lastName" to lastName
                )
                db.collection("users").document(uid).update(updates)
                    .addOnSuccessListener {
                        tvFullName.text = "$firstName $lastName"
                        tvFullName.visibility = View.VISIBLE
                        etFirstName.visibility = View.GONE
                        etLastName.visibility = View.GONE
                        isEditingName = false
                        android.widget.Toast.makeText(requireContext(), "Nombre actualizado", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener {
                        android.widget.Toast.makeText(requireContext(), "Error al guardar nombre", android.widget.Toast.LENGTH_SHORT).show()
                    }
                return@setOnClickListener
            }
            // Only save photo if changed
            if (selectedPhotoUri != null) {
                val updates = hashMapOf<String, Any>(
                    "email" to (user?.email ?: "")
                )
                val ref = storage.reference.child("users/$uid/profilepic")
                ref.putFile(selectedPhotoUri!!).continueWithTask { task ->
                    if (!task.isSuccessful) throw task.exception!!
                    ref.downloadUrl
                }.addOnSuccessListener { uri ->
                    updates["photoUrl"] = uri.toString()
                    db.collection("users").document(uid).update(updates)
                        .addOnSuccessListener {
                            android.widget.Toast.makeText(requireContext(), "Perfil guardado", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .addOnFailureListener {
                            android.widget.Toast.makeText(requireContext(), "Error al guardar perfil", android.widget.Toast.LENGTH_SHORT).show()
                        }
                }.addOnFailureListener { exception ->
                    android.widget.Toast.makeText(requireContext(), "Error al subir foto: ${exception.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            } else {
                android.widget.Toast.makeText(requireContext(), "No hay cambios para guardar", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        return view
    }
}
