package com.loveito.demo.pets

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.loveito.demo.R

sealed class PetGridItem {
    data class Pet(val id: String, val name: String, val photoUrl: String?) : PetGridItem()
    object Add : PetGridItem()
}

class PetGridAdapter(
    private val items: List<PetGridItem>,
    private val onPetClick: (PetGridItem.Pet) -> Unit,
    private val onAddClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_PET = 0
        private const val TYPE_ADD = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is PetGridItem.Pet -> TYPE_PET
            is PetGridItem.Add -> TYPE_ADD
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_PET -> {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pet_grid, parent, false)
                PetViewHolder(v)
            }
            TYPE_ADD -> {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pet_add, parent, false)
                AddViewHolder(v)
            }
            else -> throw IllegalArgumentException()
        }
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is PetGridItem.Pet -> (holder as PetViewHolder).bind(item, onPetClick)
            is PetGridItem.Add -> (holder as AddViewHolder).bind(onAddClick)
        }
    }

    class PetViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivPhoto = view.findViewById<ImageView>(R.id.ivPetPhoto)
        private val tvName = view.findViewById<TextView>(R.id.tvPetName)
        fun bind(item: PetGridItem.Pet, onClick: (PetGridItem.Pet) -> Unit) {
            tvName.text = item.name
            if (!item.photoUrl.isNullOrEmpty()) {
                Glide.with(ivPhoto.context)
                    .load(item.photoUrl)
                    .centerCrop()
                    .placeholder(R.mipmap.ic_launcher)
                    .into(ivPhoto)
            } else {
                ivPhoto.setImageResource(R.mipmap.ic_launcher)
            }
            itemView.setOnClickListener { onClick(item) }
        }
    }

    class AddViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(onClick: () -> Unit) {
            itemView.setOnClickListener { onClick() }
        }
    }
}
