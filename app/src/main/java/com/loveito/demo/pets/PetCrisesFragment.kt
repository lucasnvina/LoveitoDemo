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
    private val masterList: MutableList<Crisis> = mutableListOf()
    private var adapter: CrisisAdapter? = null
    private var petId: String? = null

    private var tvFilterSummary: TextView? = null
    private var tvEmpty: TextView? = null
    private var rvCrises: RecyclerView? = null
    private var btnOpenFilters: View? = null
    private var btnClearFilters: View? = null

    // Filtros actuales
    private var filterSeverities: Set<String>? = null // conjunto de códigos (red, amber, green)
    private var filterDateFrom: Long? = null
    private var filterDateTo: Long? = null
    private var filterMinDuration: Int? = null
    private var filterMaxDuration: Int? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_pet_crises, container, false)
        tvFilterSummary = view.findViewById(R.id.tvFilterSummary)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        rvCrises = view.findViewById(R.id.rvCrises)
        btnOpenFilters = view.findViewById(R.id.btnOpenFilters)
        btnClearFilters = view.findViewById(R.id.btnClearFilters)

        petId = arguments?.getString("petId")
        if (petId == null) { showEmpty(true); return view }

        rvCrises?.layoutManager = LinearLayoutManager(requireContext())
        adapter = CrisisAdapter(
            petId = petId!!,
            items = mutableListOf(),
            repo = PetsRepository(),
            onEmpty = { showEmpty(true) },
            onDelete = { removed ->
                masterList.removeAll { it.id == removed.id }
                applyFilters()
            }
        )
        rvCrises?.adapter = adapter

        btnOpenFilters?.setOnClickListener { openFilters() }
        btnClearFilters?.setOnClickListener { quickClearFilters() }

        parentFragmentManager.setFragmentResultListener("crisesFiltersApplied", this) { _, bundle ->
            if (bundle.getString("petId") != petId) return@setFragmentResultListener
            // Multi severities
            val severities = bundle.getStringArrayList("severities")
            val singleSeverity = bundle.getString("severity")
            filterSeverities = when {
                severities != null && severities.isNotEmpty() -> severities.toSet()
                !singleSeverity.isNullOrBlank() -> setOf(singleSeverity)
                else -> null
            }
            filterDateFrom = bundle.getLong("from", -1L).takeIf { it > 0 }
            filterDateTo = bundle.getLong("to", -1L).takeIf { it > 0 }
            filterMinDuration = bundle.getInt("minDuration", -1).takeIf { it >= 0 }
            filterMaxDuration = bundle.getInt("maxDuration", -1).takeIf { it >= 0 }
            applyFilters(); updateFilterSummary()
        }

        loadData()
        updateFilterSummary()
        return view
    }

    private fun openFilters() {
        val f = PetCrisesFiltersFragment().apply {
            arguments = Bundle().apply {
                putString("petId", petId)
                // pasar severidades múltiples si existen
                filterSeverities?.let { set -> if (set.isNotEmpty()) putStringArrayList("severities", ArrayList(set)) }
                filterDateFrom?.let { putLong("from", it) }
                filterDateTo?.let { putLong("to", it) }
                filterMinDuration?.let { putInt("minDuration", it) }
                filterMaxDuration?.let { putInt("maxDuration", it) }
            }
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_host, f)
            .addToBackStack(null)
            .commit()
    }

    private fun loadData() {
        val id = petId ?: return
        PetsRepository().getCrisesForPet(id,
            onSuccess = { list ->
                masterList.clear(); masterList.addAll(list)
                applyFilters()
            },
            onError = { showEmpty(true) }
        )
    }

    private fun applyFilters() {
        if (masterList.isEmpty()) { adapter?.setItems(emptyList()); showEmpty(true); return }
        var filtered = masterList.asSequence()
        // multi-severity
        filterSeverities?.let { set ->
            if (set.isNotEmpty()) {
                filtered = filtered.filter { crisis ->
                    val code = crisis.triageSeverity?.lowercase()
                    code != null && code in set
                }
            }
        }
        // date range boundaries already implemented previously (reuse existing robust range calc)
        val tz = java.util.TimeZone.getDefault()
        val dayMs = 24L * 60 * 60 * 1000
        var fromBoundary: Long? = null
        var toBoundary: Long? = null
        filterDateFrom?.let { sel ->
            val offset = tz.getOffset(sel).toLong()
            val rawStart = sel
            val localStart = sel - offset
            fromBoundary = listOf(rawStart, localStart).minOrNull()
        }
        filterDateTo?.let { sel ->
            val offset = tz.getOffset(sel).toLong()
            val rawEnd = sel + dayMs - 1L
            val localEnd = (sel - offset) + dayMs - 1L
            toBoundary = listOf(rawEnd, localEnd).maxOrNull()
        }
        fromBoundary?.let { fb -> filtered = filtered.filter { it.startedAt >= fb } }
        toBoundary?.let { tb -> filtered = filtered.filter { it.startedAt <= tb } }

        filterMinDuration?.let { md -> filtered = filtered.filter { it.durationSec >= md } }
        filterMaxDuration?.let { mx -> filtered = filtered.filter { it.durationSec <= mx } }

        val result = filtered.toList()
        adapter?.setItems(result)
        showEmpty(result.isEmpty())
    }

    private fun quickClearFilters() {
        filterSeverities = null
        filterDateFrom = null
        filterDateTo = null
        filterMinDuration = null
        filterMaxDuration = null
        applyFilters(); updateFilterSummary()
    }

    private fun updateFilterSummary() {
        val hasFilters = listOf(
            filterSeverities?.isNotEmpty(), filterDateFrom, filterDateTo, filterMinDuration, filterMaxDuration
        ).any {
            when (it) {
                is Boolean -> it
                null -> false
                else -> true
            }
        }
        tvFilterSummary?.text = if (!hasFilters) getString(R.string.filter_summary_none) else getString(R.string.filter_summary_active)
        btnClearFilters?.visibility = if (hasFilters) View.VISIBLE else View.GONE
    }

    private fun showEmpty(empty: Boolean) {
        if (empty) { tvEmpty?.visibility = View.VISIBLE; rvCrises?.visibility = View.GONE }
        else { tvEmpty?.visibility = View.GONE; rvCrises?.visibility = View.VISIBLE }
    }
}
