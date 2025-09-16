package com.loveito.demo

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {
    fun updateTopBarVisibility() {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_host)
        val topBar = findViewById<android.view.View>(R.id.topBar)
        topBar?.visibility = if (fragment != null && fragment::class.java.simpleName == "AuthFragment") android.view.View.GONE else android.view.View.VISIBLE
    }

    fun loadUserPhoto() {
        val ivUserPhoto = findViewById<android.widget.ImageView>(R.id.ivUserPhoto)
        val tvUserName = findViewById<android.widget.TextView>(R.id.tvUserName)
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        db.collection("users").document(uid).get().addOnSuccessListener { doc ->
            val photoUrl = doc.getString("photoUrl")
            val firstName = doc.getString("firstName") ?: ""
            // Solo mostrar el primer nombre (primer palabra)
            val firstWord = firstName.split(" ").firstOrNull()?.trim() ?: "Usuario"
            tvUserName?.text = if (firstWord.isNotBlank()) firstWord else "Usuario"
            if (!photoUrl.isNullOrEmpty()) {
                try {
                    com.bumptech.glide.Glide.with(this)
                        .load(photoUrl)
                        .circleCrop()
                        .placeholder(R.drawable.ic_user_placeholder)
                        .error(R.drawable.ic_user_placeholder)
                        .into(ivUserPhoto)
                } catch (_: Exception) {}
            } else {
                ivUserPhoto?.setImageResource(R.drawable.ic_user_placeholder)
            }
        }.addOnFailureListener {
            tvUserName?.text = "Usuario"
            ivUserPhoto?.setImageResource(R.drawable.ic_user_placeholder)
        }
    }

    override fun onResume() {
        super.onResume()
        updateTopBarVisibility()
        loadUserPhoto()
    }

    override fun onStart() {
        super.onStart()
        updateTopBarVisibility()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Ajuste dinámico de padding superior para la barra
        val topBar = findViewById<android.view.View>(R.id.topBar)
        topBar?.setOnApplyWindowInsetsListener { view, insets ->
            val statusBarHeight = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
            view.setPadding(
                view.paddingLeft,
                statusBarHeight,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }

        val ivUserPhoto = findViewById<android.widget.ImageView>(R.id.ivUserPhoto)
        ivUserPhoto?.setOnClickListener {
            val popup = android.widget.PopupMenu(android.view.ContextThemeWrapper(this, R.style.CustomPopupMenu), ivUserPhoto)
            popup.menu.add("Ver Perfil")
            popup.menu.add("Cerrar Sesión")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Ver Perfil" -> {
                        navigateToFragment(ProfileFragment(), true)
                        true
                    }
                    "Cerrar Sesión" -> {
                        com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
                        navigateToFragment(AuthFragment())
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        supportFragmentManager.addOnBackStackChangedListener {
            updateTopBarVisibility()
        }

        if (savedInstanceState == null) {
            val start = if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) {
                AuthFragment()
            } else {
                HomeFragment()
            }
            navigateToFragment(start)
        }

        loadUserPhoto()
    }

    fun navigateToFragment(fragment: androidx.fragment.app.Fragment, addToBackStack: Boolean = false) {
        val transaction = supportFragmentManager.beginTransaction().replace(R.id.fragment_host, fragment)
        if (addToBackStack) transaction.addToBackStack(null)
        transaction.commit()
        updateTopBarVisibility()
    }
}
