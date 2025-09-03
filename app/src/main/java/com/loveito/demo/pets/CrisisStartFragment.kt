package com.loveito.demo.pets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.loveito.demo.R

class CrisisStartFragment : Fragment() {

    private val repo = PetsRepository()
    private var pets: List<Pet> = emptyList()
    private var selectedIndex: Int = -1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_crisis_start, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<ListView>(R.id.listPets)
        val btnStart = view.findViewById<Button>(R.id.btnStart)

        // Cargar mascotas
        repo.getMyPets(onSuccess = { listPets ->
            pets = listPets
            if (pets.isEmpty()) {
                btnStart.isEnabled = false
                Toast.makeText(requireContext(), "No tenés mascotas. Creá una primero.", Toast.LENGTH_LONG).show()
                return@getMyPets
            }
            val names = pets.map { it.name }
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_single_choice, names)
            list.choiceMode = ListView.CHOICE_MODE_SINGLE
            list.adapter = adapter
            // opcional: preseleccionar la primera
            selectedIndex = 0
            list.setItemChecked(0, true)
        }, onError = { e ->
            Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        })

        list.setOnItemClickListener { _, _, position, _ ->
            selectedIndex = position
        }

        btnStart.setOnClickListener {
            if (selectedIndex < 0 || selectedIndex >= pets.size) {
                Toast.makeText(requireContext(), "Elegí una mascota", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val pet = pets[selectedIndex]
            val triage = TriageEngine.randomResult(requireContext())
            repo.createTestCrisisWithTriage(pet.id, triage,
                onSuccess = {
                    Toast.makeText(requireContext(), "Crisis de prueba creada", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                },
                onError = { e ->
                    Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}
