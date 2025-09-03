package com.loveito.demo.pets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.loveito.demo.R

class PetsListFragment : Fragment() {

    private val repo = PetsRepository()
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private var items: MutableList<Pet> = mutableListOf()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_pets_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView = view.findViewById(R.id.listPets)
        adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter

        view.findViewById<Button>(R.id.btnAddPet).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_host, PetFormFragment())
                .addToBackStack(null)
                .commit()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val pet = items[position]
            val form = PetFormFragment.newEdit(pet.id, pet.name, pet.notes ?: "", pet.photoUrl ?: "")
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_host, form)
                .addToBackStack(null)
                .commit()
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val pet = items[position]
            AlertDialog.Builder(requireContext())
                .setTitle("Eliminar mascota")
                .setMessage("¿Seguro que querés eliminar ${pet.name}?")
                .setPositiveButton("Eliminar") { _, _ ->
                    repo.deletePet(
                        id = pet.id,
                        onSuccess = {
                            Toast.makeText(requireContext(), "Mascota eliminada", Toast.LENGTH_SHORT).show()
                            load()
                        },
                        onError = { e ->
                            Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }

        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        repo.getMyPets(
            onSuccess = { list ->
                items.clear()
                items.addAll(list)
                adapter.clear()
                adapter.addAll(list.map { it.name })
                adapter.notifyDataSetChanged()
            },
            onError = { e -> Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show() }
        )
    }
}
