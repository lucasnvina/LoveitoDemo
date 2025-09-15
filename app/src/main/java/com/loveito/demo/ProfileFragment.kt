package com.loveito.demo

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

class ProfileFragment : Fragment() {
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private var selectedPhotoUri: Uri? = null
    private lateinit var ivProfilePhoto: ImageView
    private lateinit var etFirstName: EditText
    private lateinit var etLastName: EditText
    private lateinit var tvEmail: TextView
    private lateinit var btnSaveProfile: MaterialButton
    private lateinit var btnChangePhoto: MaterialButton

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
        btnChangePhoto = view.findViewById(R.id.btnChangePhoto)
        etFirstName = view.findViewById(R.id.etFirstName)
        etLastName = view.findViewById(R.id.etLastName)
        tvEmail = view.findViewById(R.id.tvEmail)
        btnSaveProfile = view.findViewById(R.id.btnSaveProfile)

        val user = FirebaseAuth.getInstance().currentUser
        val uid = user?.uid ?: ""
        tvEmail.text = user?.email ?: ""

        // Cargar datos del usuario desde Firestore
        db.collection("users").document(uid).get().addOnSuccessListener { doc ->
            if (doc != null && doc.exists()) {
                etFirstName.setText(doc.getString("firstName") ?: "")
                etLastName.setText(doc.getString("lastName") ?: "")
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

        btnChangePhoto.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        btnSaveProfile.setOnClickListener {
            val firstName = etFirstName.text.toString().trim()
            val lastName = etLastName.text.toString().trim()
            val updates = hashMapOf<String, Any>(
                "firstName" to firstName,
                "lastName" to lastName,
                "email" to (user?.email ?: "")
            )
            if (selectedPhotoUri != null) {
                // Subir foto a Storage y guardar URL
                val ref = storage.reference.child("profile_photos/$uid.jpg")
                ref.putFile(selectedPhotoUri!!).continueWithTask { task ->
                    if (!task.isSuccessful) throw task.exception!!
                    ref.downloadUrl
                }.addOnSuccessListener { uri ->
                    updates["photoUrl"] = uri.toString()
                    db.collection("users").document(uid).set(updates)
                }
            } else {
                db.collection("users").document(uid).set(updates)
            }
        }

        return view
    }
}
