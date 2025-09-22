package com.loveito.demo.pets

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.core.content.res.ResourcesCompat
import com.loveito.demo.R

class PetMedicationsFragment : Fragment() {
    private val repo = PetsRepository()
    private var petId: String? = null

    // UI
    private lateinit var tvEmpty: TextView
    private lateinit var rv: RecyclerView
    private lateinit var fabAdd: ImageButton // cambiado a ImageButton

    // Data
    private val medications = mutableListOf<Medication>()
    private lateinit var adapter: MedicationAdapter
    private var orderDirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        petId = arguments?.getString("petId")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_pet_medications, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvEmpty = view.findViewById(R.id.tvEmptyMedications)
        rv = view.findViewById(R.id.rvMedications)
        val tvTitle = view.findViewById<TextView>(R.id.tvTitleMedications)
        fabAdd = view.findViewById(R.id.fabAddMedication)

        if (petId == null) {
            Toast.makeText(requireContext(), "PetId faltante", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack(); return
        }

        setupRecycler()
        setupFragmentResultListener()

        // Listener del FAB para abrir formulario de nueva medicación
        fabAdd.setOnClickListener { openEditMedication(index = -1, medication = null) }

        loadMedications()
    }

    private fun setupFragmentResultListener() {
        setFragmentResultListener("medicationEdited") { _, bundle ->
            val resultPetId = bundle.getString("petId")
            if (resultPetId == petId) {
                val index = bundle.getInt("index", -1)
                val name = bundle.getString("name") ?: ""
                val dose = bundle.getString("dose") ?: ""
                val unit = bundle.getString("unit") ?: ""
                val times = bundle.getStringArrayList("times")?.toList() ?: emptyList()
                val med = Medication(name = name, dose = dose, unit = unit, times = times)
                if (index in medications.indices) {
                    medications[index] = med
                } else {
                    medications.add(med)
                }
                persistAndRefresh(showToast = true)
            }
        }
        setFragmentResultListener("medicationDeleted") { _, bundle ->
            val resultPetId = bundle.getString("petId")
            if (resultPetId == petId) {
                val index = bundle.getInt("index", -1)
                if (index in medications.indices) {
                    medications.removeAt(index)
                    persistAndRefresh(showToast = true)
                }
            }
        }
    }

    private fun setupRecycler() {
        adapter = MedicationAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == to) return false
                // swap en memoria
                val item = medications.removeAt(from)
                medications.add(to, item)
                adapter.notifyItemMoved(from, to)
                orderDirty = true
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (orderDirty) {
                    persistOrder()
                    orderDirty = false
                }
            }
        })
        touchHelper.attachToRecyclerView(rv)
        adapter.onStartDrag = { vh -> touchHelper.startDrag(vh) }
        adapter.onEdit = { index -> openEditMedication(index, medications[index]) }
    }

    private fun loadMedications() {
        repo.getPet(petId!!, onSuccess = { pet ->
            medications.clear(); medications.addAll(pet.medications)
            renderList()
        }, onError = { e ->
            Toast.makeText(requireContext(), getString(R.string.error, e.localizedMessage), Toast.LENGTH_SHORT).show()
        })
    }

    private fun renderList() {
        tvEmpty.visibility = if (medications.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(medications.toList())
    }

    private fun buildTimeChip(parent: ChipGroup, text: String) {
        val chip = Chip(requireContext()).apply {
            this.text = text
            isClickable = false
            isCheckable = false
            setEnsureMinTouchTargetSize(false)
            val strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#230000"))
            chipStrokeColor = strokeColor
            chipStrokeWidth = resources.displayMetrics.density * 1f
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#b96c30"))
            val radiusPx = 16f * resources.displayMetrics.density
            shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(radiusPx).build()
            val typeface = ResourcesCompat.getFont(requireContext(), R.font.nunito_light)
            if (typeface != null) this.typeface = typeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(android.graphics.Color.parseColor("#F6E2CC"))
            val padH = (10 * resources.displayMetrics.density).toInt()
            val padV = (4 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
        }
        parent.addView(chip)
    }

    private fun openEditMedication(index: Int, medication: Medication?) {
        val f = PetMedicationEditFragment()
        f.arguments = Bundle().apply {
            putString("petId", petId)
            putInt("index", index)
            if (medication != null) {
                putString("name", medication.name)
                putString("dose", medication.dose)
                putString("unit", medication.unit)
                putStringArrayList("times", ArrayList(medication.times))
            }
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_host, f)
            .addToBackStack(null)
            .commit()
    }

    private fun persistAndRefresh(showToast: Boolean) {
        repo.updatePetMedications(petId!!, medications,
            onSuccess = {
                if (showToast) Toast.makeText(requireContext(), getString(R.string.medication_saved), Toast.LENGTH_SHORT).show()
                renderList()
                parentFragmentManager.setFragmentResult("medicationsUpdated", Bundle().apply { putString("petId", petId) })
            },
            onError = { e ->
                Toast.makeText(requireContext(), getString(R.string.error, e.localizedMessage), Toast.LENGTH_LONG).show()
                loadMedications()
            }
        )
    }

    private fun persistOrder() {
        // Guardar nuevo orden sin toast
        repo.updatePetMedications(petId!!, medications,
            onSuccess = {
                parentFragmentManager.setFragmentResult("medicationsUpdated", Bundle().apply { putString("petId", petId) })
            },
            onError = { e ->
                Toast.makeText(requireContext(), getString(R.string.error, e.localizedMessage), Toast.LENGTH_SHORT).show()
                loadMedications() // revert to server order
            }
        )
    }

    // Adapter -------------------------------------------------
    private inner class MedicationAdapter : RecyclerView.Adapter<MedicationAdapter.MedVH>() {
        private val items = mutableListOf<Medication>()
        var onEdit: ((Int) -> Unit)? = null
        var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

        fun submitList(list: List<Medication>) {
            items.clear(); items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MedVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_medication_card, parent, false)
            return MedVH(v)
        }

        override fun onBindViewHolder(holder: MedVH, position: Int) = holder.bind(items[position], position)
        override fun getItemCount(): Int = items.size

        inner class MedVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById(R.id.tvMedName)
            private val tvDose: TextView = itemView.findViewById(R.id.tvMedDose)
            private val chipGroup: ChipGroup = itemView.findViewById(R.id.chipGroupMedTimes)
            private val btnEdit: MaterialButton = itemView.findViewById(R.id.btnEditMedication)
            init {
                // Long press en toda la card inicia drag (sin mostrar menú)
                itemView.setOnLongClickListener { onStartDrag?.invoke(this); true }
            }
            fun bind(med: Medication, adapterPos: Int) {
                tvName.text = med.name.ifBlank { getString(R.string.medication_header_name) }
                tvDose.text = if (med.dose.isBlank()) getString(R.string.medication_header_dose) else listOf(med.dose, med.unit.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" ")
                chipGroup.removeAllViews()
                val ts = med.times
                if (ts.isEmpty()) {
                    buildTimeChip(chipGroup, getString(R.string.medication_no_times))
                } else {
                    ts.forEach { buildTimeChip(chipGroup, it) }
                }
                btnEdit.setOnClickListener { onEdit?.invoke(adapterPos) }
            }
        }
    }
}
