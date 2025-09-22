package com.loveito.demo.pets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.loveito.demo.R

class PetProfessionalsFragment : Fragment() {
    private val repo = PetsRepository()
    private var petId: String? = null

    private lateinit var tvEmpty: TextView
    private lateinit var rv: RecyclerView
    private lateinit var fabAdd: ImageButton

    private val professionals = mutableListOf<Professional>()
    private lateinit var adapter: ProfessionalsAdapter
    private var orderDirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        petId = arguments?.getString("petId")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_pet_professionals, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvEmpty = view.findViewById(R.id.tvEmptyProfessionals)
        rv = view.findViewById(R.id.rvProfessionals)
        fabAdd = view.findViewById(R.id.fabAddProfessional)
        val tvTitle = view.findViewById<TextView>(R.id.tvTitleProfessionals)

        if (petId == null) {
            Toast.makeText(requireContext(), "PetId faltante", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack(); return
        }

        setupRecycler()
        setupFragmentResultListener()

        fabAdd.setOnClickListener { openEditProfessional(index = -1, professional = null) }

        loadProfessionals()
    }

    private fun setupFragmentResultListener() {
        setFragmentResultListener("professionalEdited") { _, bundle ->
            val resultPetId = bundle.getString("petId")
            if (resultPetId == petId) {
                val index = bundle.getInt("index", -1)
                val name = bundle.getString("name") ?: ""
                val lastName = bundle.getString("lastName") ?: ""
                val specialty = bundle.getString("specialty") ?: ""
                val phone = bundle.getString("phone") ?: ""
                val email = bundle.getString("email") ?: ""
                val prevFav = if (index in professionals.indices) professionals[index].isFavorite else false
                val anyFavorite = professionals.any { it.isFavorite }
                val makeFavorite = if (index !in professionals.indices && !anyFavorite) true else prevFav
                val pro = Professional(name = name, lastName = lastName, specialty = specialty, phone = phone, email = email, isFavorite = makeFavorite)
                if (index in professionals.indices) {
                    professionals[index] = pro
                } else {
                    professionals.add(pro)
                }
                persistAndRefresh(showToast = true)
            }
        }
        setFragmentResultListener("professionalDeleted") { _, bundle ->
            val resultPetId = bundle.getString("petId")
            if (resultPetId == petId) {
                val index = bundle.getInt("index", -1)
                if (index in professionals.indices) {
                    val wasFavorite = professionals[index].isFavorite
                    professionals.removeAt(index)
                    if (wasFavorite && professionals.isNotEmpty()) {
                        // Asignar favorito al siguiente en la misma posición si existe, sino al último.
                        val newIndex = if (index < professionals.size) index else professionals.lastIndex
                        professionals.replaceAllIndexed { i, p -> if (i == newIndex) p.copy(isFavorite = true) else p.copy(isFavorite = false) }
                    }
                    persistAndRefresh(showToast = true)
                }
            }
        }
    }

    // Helper para replaceAll con índice
    private inline fun <T> MutableList<T>.replaceAllIndexed(transform: (Int, T) -> T) {
        for (i in indices) this[i] = transform(i, this[i])
    }

    private fun setupRecycler() {
        adapter = ProfessionalsAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == to) return false
                val item = professionals.removeAt(from)
                professionals.add(to, item)
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
        adapter.onEdit = { index -> openEditProfessional(index, professionals[index]) }
        adapter.onToggleFavorite = { index -> toggleFavorite(index) }
    }

    private fun toggleFavorite(index: Int) {
        if (index !in professionals.indices) return
        professionals.replaceAll { p -> p.copy(isFavorite = false) }
        professionals[index] = professionals[index].copy(isFavorite = true)
        adapter.submitList(professionals.toList())
        persistAndRefresh(showToast = false)
    }

    private fun loadProfessionals() {
        repo.getPet(petId!!, onSuccess = { pet ->
            professionals.clear(); professionals.addAll(pet.professionals)
            renderList()
        }, onError = { e ->
            Toast.makeText(requireContext(), getString(R.string.error, e.localizedMessage), Toast.LENGTH_SHORT).show()
        })
    }

    private fun renderList() {
        tvEmpty.visibility = if (professionals.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(professionals.toList())
    }

    private fun openEditProfessional(index: Int, professional: Professional?) {
        val f = com.loveito.demo.pets.PetProfessionalEditFragment()
        f.arguments = Bundle().apply {
            putString("petId", petId)
            putInt("index", index)
            professional?.let {
                putString("name", it.name)
                putString("lastName", it.lastName)
                putString("specialty", it.specialty)
                putString("phone", it.phone)
                putString("email", it.email)
            }
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_host, f)
            .addToBackStack(null)
            .commit()
    }

    private fun persistAndRefresh(showToast: Boolean) {
        repo.updatePetProfessionals(petId!!, professionals,
            onSuccess = {
                if (showToast) Toast.makeText(requireContext(), getString(R.string.professional_saved), Toast.LENGTH_SHORT).show()
                renderList()
                parentFragmentManager.setFragmentResult("professionalsUpdated", Bundle().apply { putString("petId", petId) })
            },
            onError = { e ->
                Toast.makeText(requireContext(), getString(R.string.error, e.localizedMessage), Toast.LENGTH_LONG).show()
                loadProfessionals()
            }
        )
    }

    private fun persistOrder() {
        repo.updatePetProfessionals(petId!!, professionals,
            onSuccess = {
                parentFragmentManager.setFragmentResult("professionalsUpdated", Bundle().apply { putString("petId", petId) })
            },
            onError = { e ->
                Toast.makeText(requireContext(), getString(R.string.error, e.localizedMessage), Toast.LENGTH_SHORT).show()
                loadProfessionals()
            }
        )
    }

    private inner class ProfessionalsAdapter : RecyclerView.Adapter<ProfessionalsAdapter.ProVH>() {
        private val items = mutableListOf<Professional>()
        var onEdit: ((Int) -> Unit)? = null
        var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null
        var onToggleFavorite: ((Int) -> Unit)? = null

        fun submitList(list: List<Professional>) {
            items.clear(); items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_professional_card, parent, false)
            return ProVH(v)
        }

        override fun onBindViewHolder(holder: ProVH, position: Int) = holder.bind(items[position], position)
        override fun getItemCount(): Int = items.size

        inner class ProVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById(R.id.tvProName)
            private val tvSpecialty: TextView = itemView.findViewById(R.id.tvProSpecialty)
            private val tvPhone: TextView = itemView.findViewById(R.id.tvProPhone)
            private val tvEmail: TextView = itemView.findViewById(R.id.tvProEmail)
            private val btnEdit: MaterialButton = itemView.findViewById(R.id.btnEditProfessional)
            private val ivFavorite: ImageView = itemView.findViewById(R.id.ivFavoriteStar)
            init { itemView.setOnLongClickListener { onStartDrag?.invoke(this); true } }
            fun bind(pro: Professional, adapterPos: Int) {
                val ctx = itemView.context
                val fullName = listOf(pro.name, pro.lastName).filter { it.isNotBlank() }.joinToString(" ")
                tvName.text = if (fullName.isBlank()) ctx.getString(R.string.professional_header_name) else fullName
                tvSpecialty.text = pro.specialty.ifBlank { ctx.getString(R.string.professional_header_specialty) }
                tvPhone.text = pro.phone.ifBlank { ctx.getString(R.string.professional_header_phone) }
                tvEmail.text = pro.email.ifBlank { ctx.getString(R.string.professional_header_email) }
                btnEdit.setOnClickListener { onEdit?.invoke(adapterPos) }
                ivFavorite.setImageResource(if (pro.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
                ivFavorite.setOnClickListener { onToggleFavorite?.invoke(adapterPos) }
            }
        }
    }
}
