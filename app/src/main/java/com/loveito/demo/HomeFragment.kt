package com.loveito.demo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.loveito.demo.pets.CrisisStartFragment
import com.loveito.demo.pets.PetFormFragment
import com.loveito.demo.pets.PetGridAdapter
import com.loveito.demo.pets.PetGridItem
import com.loveito.demo.pets.PetsRepository

class HomeFragment : Fragment() {

    private val repo = PetsRepository()
    private lateinit var recyclerPets: RecyclerView
    private lateinit var adapter: PetGridAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recyclerPets = view.findViewById(R.id.recyclerPets)
        recyclerPets.layoutManager = GridLayoutManager(requireContext(), 2)
        loadPets()
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

    private fun loadPets() {
        repo.getMyPets(onSuccess = { pets ->
            val items: List<PetGridItem> = pets.map { PetGridItem.Pet(it.id, it.name, it.photoUrl) } + PetGridItem.Add
            adapter = PetGridAdapter(items,
                onPetClick = { pet: PetGridItem.Pet ->
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.fragment_host, PetFormFragment.newEdit(pet.id))
                        .addToBackStack(null)
                        .commit()
                },
                onAddClick = {
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.fragment_host, PetFormFragment())
                        .addToBackStack(null)
                        .commit()
                }
            )
            recyclerPets.adapter = adapter
        }, onError = {
            Toast.makeText(requireContext(), "Error cargando mascotas", Toast.LENGTH_SHORT).show()
        })
    }
}
