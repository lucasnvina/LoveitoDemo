package com.loveito.demo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.firebase.auth.FirebaseAuth
import com.loveito.demo.pets.PetsListFragment
import com.loveito.demo.pets.CrisisStartFragment

class HomeFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Button>(R.id.btnOpenPets).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_host, PetsListFragment())
                .addToBackStack(null)
                .commit()
        }
        view.findViewById<Button>(R.id.btnStartCrisis).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_host, CrisisStartFragment())
                .addToBackStack(null)
                .commit()
        }
        view.findViewById<Button>(R.id.btnSignOut).setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_host, AuthFragment())
                .commit()
        }
    }
}
