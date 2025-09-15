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

    override fun onResume() {
        super.onResume()
        updateTopBarVisibility()
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
            val popup = android.widget.PopupMenu(this, ivUserPhoto)
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
    }

    fun navigateToFragment(fragment: androidx.fragment.app.Fragment, addToBackStack: Boolean = false) {
        val transaction = supportFragmentManager.beginTransaction().replace(R.id.fragment_host, fragment)
        if (addToBackStack) transaction.addToBackStack(null)
        transaction.commit()
        updateTopBarVisibility()
    }
}
