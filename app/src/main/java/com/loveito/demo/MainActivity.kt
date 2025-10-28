package com.loveito.demo

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.loveito.demo.flow.TriageEngineStore
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    fun updateTopBarVisibility() {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_host)
        val topBar = findViewById<android.view.View>(R.id.topBar)
        topBar?.visibility = if (fragment != null && fragment::class.java.simpleName == "AuthFragment") android.view.View.GONE else android.view.View.VISIBLE
    }

    fun loadUserPhoto() {
        val ivUserPhoto = findViewById<android.widget.ImageView>(R.id.ivUserPhoto)
        val tvUserName = findViewById<android.widget.TextView>(R.id.tvUserName)
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val defaultName: String = try {
            val id = resources.getIdentifier("user_default_name", "string", packageName)
            if (id != 0) getString(id) else "Usuario"
        } catch (_: Exception) { "Usuario" }
        db.collection("users").document(uid).get().addOnSuccessListener { doc ->
            val photoUrl = doc.getString("photoUrl")
            val firstName = doc.getString("firstName") ?: ""
            val firstWord = firstName.split(" ").firstOrNull()?.trim() ?: defaultName
            tvUserName?.text = if (firstWord.isNotBlank()) firstWord else defaultName
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
            tvUserName?.text = defaultName
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

        // Ajuste dinámico de padding superior para la barra (compatible API 24+)
        val topBar = findViewById<android.view.View>(R.id.topBar)
        topBar?.let { tb ->
            ViewCompat.setOnApplyWindowInsetsListener(tb) { view, insets ->
                val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                view.setPadding(view.paddingLeft, statusBarHeight, view.paddingRight, view.paddingBottom)
                insets
            }
            // Solicitar una aplicación inicial de insets
            ViewCompat.requestApplyInsets(tb)
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
                        FirebaseAuth.getInstance().signOut()
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
            val start = if (FirebaseAuth.getInstance().currentUser == null) {
                AuthFragment()
            } else {
                HomeFragment()
            }
            navigateToFragment(start)
        }

        loadUserPhoto()

        // Actualizar motor de triage local desde Storage en segundo plano
        lifecycleScope.launch(Dispatchers.IO) {
            try { TriageEngineStore.ensureLatest(applicationContext) } catch (_: Exception) {}
        }
    }

    fun navigateToFragment(fragment: androidx.fragment.app.Fragment, addToBackStack: Boolean = false) {
        val transaction = supportFragmentManager.beginTransaction().replace(R.id.fragment_host, fragment)
        if (addToBackStack) transaction.addToBackStack(null)
        transaction.commit()
        updateTopBarVisibility()
    }
}
