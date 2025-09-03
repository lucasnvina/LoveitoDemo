package com.loveito.demo

import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth

class AuthFragment : Fragment(R.layout.fragment_auth) {
    private val auth by lazy { FirebaseAuth.getInstance() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val emailEt = view.findViewById<EditText>(R.id.etEmail)
        val passEt  = view.findViewById<EditText>(R.id.etPassword)
        val status  = view.findViewById<TextView>(R.id.tvStatus)

        fun toast(s:String)=Toast.makeText(requireContext(),s,Toast.LENGTH_SHORT).show()
        fun update(){ status.text = auth.currentUser?.email?.let { "Estado: $it" } ?: "Estado: no autenticado" }
        fun goHome() {
            parentFragmentManager.beginTransaction()
                .replace((view.parent as android.view.ViewGroup).id, HomeFragment())
                .commit()
        }

        view.findViewById<Button>(R.id.btnSignUp).setOnClickListener {
            val e=emailEt.text.toString().trim(); val p=passEt.text.toString()
            if (!Patterns.EMAIL_ADDRESS.matcher(e).matches()) { toast("Email inválido"); return@setOnClickListener }
            if (p.length<6) { toast("Contraseña mínima 6"); return@setOnClickListener }
            auth.createUserWithEmailAndPassword(e,p)
                .addOnSuccessListener { auth.currentUser?.sendEmailVerification(); toast("Cuenta creada"); update(); goHome() }
                .addOnFailureListener { toast(it.localizedMessage ?: "Error al crear") }
        }
        view.findViewById<Button>(R.id.btnSignIn).setOnClickListener {
            val e=emailEt.text.toString().trim(); val p=passEt.text.toString()
            if (!Patterns.EMAIL_ADDRESS.matcher(e).matches()) { toast("Email inválido"); return@setOnClickListener }
            if (p.isEmpty()) { toast("Ingresá contraseña"); return@setOnClickListener }
            auth.signInWithEmailAndPassword(e,p)
                .addOnSuccessListener { toast("Ingreso OK"); update(); goHome() }
                .addOnFailureListener { toast(it.localizedMessage ?: "Error al ingresar") }
        }
        view.findViewById<Button>(R.id.btnForgot).setOnClickListener {
            val e=emailEt.text.toString().trim()
            if (!Patterns.EMAIL_ADDRESS.matcher(e).matches()) { toast("Ingresá un email válido"); return@setOnClickListener }
            auth.sendPasswordResetEmail(e)
                .addOnSuccessListener { toast("Email enviado") }
                .addOnFailureListener { toast(it.localizedMessage ?: "No se pudo enviar") }
        }
        update()
    }
}
