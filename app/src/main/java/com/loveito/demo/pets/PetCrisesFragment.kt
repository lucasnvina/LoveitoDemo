package com.loveito.demo.pets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.loveito.demo.R

class PetCrisesFragment : Fragment() {

    private enum class SortType { DATE, DURATION, SEVERITY }

    private var sortType: SortType = SortType.DATE
    private var ascending: Boolean = false // Por defecto fecha descendente (más reciente primero)

    private val masterList: MutableList<Crisis> = mutableListOf()
    private var adapter: CrisisAdapter? = null

    private var petId: String? = null

    private var spinner: Spinner? = null
    private var btnSortDate: MaterialButton? = null
    private var btnSortDuration: MaterialButton? = null
    private var btnSortSeverity: MaterialButton? = null
    private var tvEmpty: TextView? = null
    private var rvCrises: RecyclerView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_pet_crises, container, false)
        rvCrises = view.findViewById(R.id.rvCrises)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        spinner = view.findViewById(R.id.spSeverityFilter)
        btnSortDate = view.findViewById(R.id.btnSortDate)
        btnSortDuration = view.findViewById(R.id.btnSortDuration)
        btnSortSeverity = view.findViewById(R.id.btnSortSeverity)

        petId = arguments?.getString("petId")
        if (petId == null) {
            tvEmpty?.visibility = View.VISIBLE
            rvCrises?.visibility = View.GONE
            return view
        }

        rvCrises?.layoutManager = LinearLayoutManager(requireContext())
        adapter = CrisisAdapter(
            petId = petId!!,
            items = mutableListOf(),
            repo = PetsRepository(),
            onEmpty = { showEmpty(true) },
            onDelete = { removed ->
                // Sincronizar con la master list y re-aplicar filtros
                masterList.removeAll { it.id == removed.id }
                applyFilters()
            }
        )
        rvCrises?.adapter = adapter

        setupSeveritySpinner()
        setupSortButtons()
        loadData()
        return view
    }

    private fun loadData() {
        val id = petId ?: return
        val repo = PetsRepository()
        repo.getCrisesForPet(id,
            onSuccess = { list ->
                masterList.clear()
                masterList.addAll(list) // list ya está ordenada descending por fecha desde el repo
                applyFilters() // aplica filtro + orden actuales
            },
            onError = { _ ->
                showEmpty(true)
            }
        )
    }

    private fun setupSeveritySpinner() {
        val ctx = context ?: return
        val entries = listOf(
            getString(R.string.severity_all),
            getString(R.string.severity_emergency),
            getString(R.string.severity_urgency),
            getString(R.string.severity_observation)
        )
        val ad = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, entries)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner?.adapter = ad
        spinner?.setSelection(0)
        spinner?.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyFilters()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
    }

    private fun setupSortButtons() {
        btnSortDate?.setOnClickListener { changeSort(SortType.DATE) }
        btnSortDuration?.setOnClickListener { changeSort(SortType.DURATION) }
        btnSortSeverity?.setOnClickListener { changeSort(SortType.SEVERITY) }
        reflectSortUI()
    }

    private fun changeSort(type: SortType) {
        if (sortType == type) {
            // Toggle asc/desc si se presiona el mismo
            ascending = !ascending
        } else {
            sortType = type
            ascending = when (type) {
                SortType.DATE -> false // fecha descendente por defecto
                else -> true // otros ascendente por defecto
            }
        }
        applyFilters()
        reflectSortUI()
    }

    private fun reflectSortUI() {
        // Cambiar alpha para indicar selección y sufijo de orden
        val selAlpha = 1f
        val offAlpha = 0.55f
        btnSortDate?.alpha = if (sortType == SortType.DATE) selAlpha else offAlpha
        btnSortDuration?.alpha = if (sortType == SortType.DURATION) selAlpha else offAlpha
        btnSortSeverity?.alpha = if (sortType == SortType.SEVERITY) selAlpha else offAlpha
        // Opcional: podríamos cambiar texto para indicar dirección (flecha) – evitar recargar strings
    }

    private fun applyFilters() {
        if (masterList.isEmpty()) {
            adapter?.setItems(emptyList())
            showEmpty(true)
            return
        }
        val severityCode = mapSpinnerSelectionToCode()
        var filtered = if (severityCode == null) masterList else masterList.filter { it.triageSeverity?.equals(severityCode, true) == true }
        // Orden
        filtered = when (sortType) {
            SortType.DATE -> if (ascending) filtered.sortedBy { it.startedAt } else filtered.sortedByDescending { it.startedAt }
            SortType.DURATION -> if (ascending) filtered.sortedBy { it.durationSec } else filtered.sortedByDescending { it.durationSec }
            SortType.SEVERITY -> {
                val weight: (String?) -> Int = { code ->
                    when (code?.lowercase()) {
                        "red" -> 3
                        "amber" -> 2
                        "green" -> 1
                        else -> 0
                    }
                }
                if (ascending) filtered.sortedBy { weight(it.triageSeverity) } else filtered.sortedByDescending { weight(it.triageSeverity) }
            }
        }
        adapter?.setItems(filtered)
        showEmpty(filtered.isEmpty())
    }

    private fun mapSpinnerSelectionToCode(): String? {
        val label = spinner?.selectedItem as? String ?: return null
        return when (label) {
            getString(R.string.severity_emergency) -> "red"
            getString(R.string.severity_urgency) -> "amber"
            getString(R.string.severity_observation) -> "green"
            else -> null // Todas
        }
    }

    private fun showEmpty(empty: Boolean) {
        if (empty) {
            tvEmpty?.visibility = View.VISIBLE
            rvCrises?.visibility = View.GONE
        } else {
            tvEmpty?.visibility = View.GONE
            rvCrises?.visibility = View.VISIBLE
        }
    }
}
