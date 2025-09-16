package com.loveito.demo.pets

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.loveito.demo.R

class CrisisAdapter(private val items: List<Crisis>) : RecyclerView.Adapter<CrisisAdapter.CrisisViewHolder>() {
    class CrisisViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val tvSeverity: TextView = view.findViewById(R.id.tvSeverity)
        val tvNote: TextView = view.findViewById(R.id.tvNote)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CrisisViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_crisis, parent, false)
        return CrisisViewHolder(view)
    }

    override fun onBindViewHolder(holder: CrisisViewHolder, position: Int) {
        val crisis = items[position]
        holder.tvDate.text = android.text.format.DateFormat.format("dd/MM/yyyy HH:mm", crisis.startedAt)
        holder.tvSeverity.text = crisis.triageSeverity ?: "-"
        holder.tvNote.text = crisis.note ?: ""
    }

    override fun getItemCount(): Int = items.size
}

