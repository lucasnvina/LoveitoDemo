package com.loveito.demo.pets

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.loveito.demo.R

class CrisisAdapter(
    private val petId: String,
    private val items: MutableList<Crisis>,
    private val repo: PetsRepository = PetsRepository(),
    private val onEmpty: () -> Unit = {},
    private val onDelete: (Crisis) -> Unit = {}
) : RecyclerView.Adapter<CrisisAdapter.CrisisViewHolder>() {

    // Permite reemplazar dataset tras filtrar/ordenar
    fun setItems(newList: List<Crisis>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
        if (items.isEmpty()) onEmpty()
    }

    class CrisisViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvDuration: TextView = view.findViewById(R.id.tvDuration)
        val tvSeverity: TextView = view.findViewById(R.id.tvSeverity)
        val tvNote: TextView = view.findViewById(R.id.tvNote)
        val severityDot: View = view.findViewById(R.id.severityDot)
        val btnDelete: MaterialButton = view.findViewById(R.id.btnDeleteCrisis)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CrisisViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_crisis, parent, false)
        return CrisisViewHolder(view)
    }

    override fun onBindViewHolder(holder: CrisisViewHolder, position: Int) {
        val crisis = items[position]
        // Fecha y hora
        val date = java.util.Date(crisis.startedAt)
        holder.tvDate.text = android.text.format.DateFormat.format("dd/MM/yyyy", date)
        holder.tvTime.text = android.text.format.DateFormat.format("HH:mm", date)
        // Duración
        val dur = crisis.durationSec.coerceAtLeast(0)
        val mins = dur / 60
        val secs = dur % 60
        holder.tvDuration.text = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
        // Severidad
        val sevCode = crisis.triageSeverity?.lowercase()
        val (sevLabel, sevColorRes) = when (sevCode) {
            "red" -> "EMERGENCIA" to R.color.crisis_severity_red
            "amber" -> "URGENCIA" to R.color.crisis_severity_amber
            "green" -> "OBSERVACIÓN" to R.color.crisis_severity_green
            else -> "OBSERVACIÓN" to R.color.crisis_severity_green
        }
        holder.tvSeverity.text = sevLabel
        holder.tvSeverity.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.maroon_900))
        val color = ContextCompat.getColor(holder.itemView.context, sevColorRes)
        holder.severityDot.background?.let { bg ->
            val wrapped = DrawableCompat.wrap(bg.mutate())
            DrawableCompat.setTint(wrapped, color)
            holder.severityDot.background = wrapped
        }
        // Nota
        val note = crisis.note
        if (note.isNullOrBlank()) {
            holder.tvNote.visibility = View.GONE
        } else {
            holder.tvNote.visibility = View.VISIBLE
            holder.tvNote.text = note
        }
        // Borrado
        holder.btnDelete.setOnClickListener {
            val ctx = holder.itemView.context
            val dialog = AlertDialog.Builder(ctx)
                .setTitle(R.string.confirm_delete_title_crisis)
                .setMessage(R.string.confirm_delete_msg_crisis)
                .setPositiveButton(R.string.delete, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
            dialog.setOnShowListener {
                val accent = ContextCompat.getColor(ctx, R.color.accent_on)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accent)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    val adapterPos = holder.bindingAdapterPosition
                    if (adapterPos == RecyclerView.NO_POSITION) { dialog.dismiss(); return@setOnClickListener }
                    holder.btnDelete.isEnabled = false
                    repo.deleteCrisis(petId, crisis.id,
                        onSuccess = {
                            val remPos = holder.bindingAdapterPosition
                            if (remPos != RecyclerView.NO_POSITION) {
                                val removed = items.removeAt(remPos)
                                notifyItemRemoved(remPos)
                                onDelete(removed)
                                if (items.isEmpty()) onEmpty()
                            }
                            Toast.makeText(ctx, R.string.deleted_successfully, Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        },
                        onError = { e ->
                            holder.btnDelete.isEnabled = true
                            Toast.makeText(ctx, ctx.getString(R.string.error_generic, e.message ?: "Error"), Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        }
                    )
                }
            }
            dialog.show()
        }
    }

    override fun getItemCount(): Int = items.size
}
