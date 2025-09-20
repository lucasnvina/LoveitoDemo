package com.loveito.demo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
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
        recyclerPets.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        loadPets()

        // Listener para desplegar el menú al tocar la foto de usuario
        val ivUserPhoto = view.findViewById<ImageView>(R.id.ivUserPhoto)
        ivUserPhoto?.setOnClickListener {
            val popup = PopupMenu(requireContext(), ivUserPhoto)
            popup.menu.add("Ver Perfil")
            popup.menu.add("Cerrar Sesión")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Ver Perfil" -> {
                        parentFragmentManager.beginTransaction()
                            .replace(R.id.fragment_host, ProfileFragment())
                            .addToBackStack(null)
                            .commit()
                        true
                    }
                    "Cerrar Sesión" -> {
                        FirebaseAuth.getInstance().signOut()
                        parentFragmentManager.beginTransaction()
                            .replace(R.id.fragment_host, AuthFragment())
                            .commit()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun loadPets() {
        repo.getMyPets(onSuccess = { pets ->
            val items: List<PetGridItem> = listOf(PetGridItem.Add) + pets.map { PetGridItem.Pet(it.id, it.name, it.photoUrl) }
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

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.updateTopBarVisibility()
    }
}
