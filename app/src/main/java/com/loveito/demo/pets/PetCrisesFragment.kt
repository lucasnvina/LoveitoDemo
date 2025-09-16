package com.loveito.demo.pets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.loveito.demo.R

class PetCrisesFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_pet_crises, container, false)
        val rvCrises = view.findViewById<RecyclerView>(R.id.rvCrises)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmpty)

        val petId = arguments?.getString("petId")
        if (petId == null) {
            tvEmpty.visibility = View.VISIBLE
            rvCrises.visibility = View.GONE
            return view
        }

        rvCrises.layoutManager = LinearLayoutManager(requireContext())
        val repo = PetsRepository()
        repo.getCrisesForPet(petId,
            onSuccess = { list ->
                if (list.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    rvCrises.visibility = View.GONE
                } else {
                    tvEmpty.visibility = View.GONE
                    rvCrises.visibility = View.VISIBLE
                    rvCrises.adapter = CrisisAdapter(list)
                }
            },
            onError = { _ ->
                tvEmpty.visibility = View.VISIBLE
                rvCrises.visibility = View.GONE
            }
        )
        return view
    }
}
