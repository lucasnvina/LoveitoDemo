
package com.loveito.demo.pets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import com.loveito.demo.R

class CrisisDetailFragment : Fragment() {

    companion object {
        fun newInstance(petId: String, crisisId: String) = CrisisDetailFragment().apply {
            arguments = Bundle().apply {
                putString("petId", petId)
                putString("crisisId", crisisId)
            }
        }
    }

    private val repo = PetsRepository()
    private var petId: String? = null
    private var crisisId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_crisis_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        petId = arguments?.getString("petId")
        crisisId = arguments?.getString("crisisId")

        val tvTitle = view.findViewById<TextView>(R.id.tvCrisisTitle)
        val tvMeta = view.findViewById<TextView>(R.id.tvCrisisMeta)
        val tvSeverity = view.findViewById<TextView>(R.id.tvCrisisSeverity)
        val containerActions = view.findViewById<LinearLayout>(R.id.containerActions)
        val btnDelete = view.findViewById<Button>(R.id.btnDeleteCrisis)

        val pid = petId ?: return
        val cid = crisisId ?: return

        repo.getCrisisDetail(
            petId = pid,
            crisisId = cid,
            onSuccess = { data ->
                val startedAt = (data["startedAt"] as? Number)?.toLong() ?: 0L
                val durationSec = (data["durationSec"] as? Number)?.toInt() ?: 0
                val note = data["note"] as? String ?: ""
                val ts = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(java.util.Date(startedAt))
                val mins = durationSec / 60
                val secs = durationSec % 60
                tvTitle.text = "Crisis del $ts"
                tvMeta.text = "Duración: ${mins}m ${secs}s" + (if (note.isNotEmpty()) "  •  Nota: $note" else "")

                val triage = data["triage"] as? Map<*, *>
                val severity = (triage?.get("severity") as? String)?.lowercase() ?: "green"
                val title = triage?.get("title") as? String ?: "OBSERVACIÓN"
                tvSeverity.text = when (severity) {
                    "red" -> "EMERGENCIA"
                    "amber" -> "URGENCIA"
                    else -> "OBSERVACIÓN"
                } + " — " + title

                val color = when (severity) {
                    "red" -> 0xFFD32F2F.toInt()
                    "amber" -> 0xFFF57C00.toInt()
                    else -> 0xFF388E3C.toInt()
                }
                tvSeverity.setTextColor(color)

                containerActions.removeAllViews()
                val actions = triage?.get("actions") as? List<*>
                if (actions != null && actions.isNotEmpty()) {
                    for (a in actions) {
                        val tv = TextView(requireContext())
                        tv.text = "• " + (a as? String ?: "")
                        tv.textSize = 16f
                        tv.setPadding(4)
                        containerActions.addView(tv)
                    }
                } else {
                    val tv = TextView(requireContext())
                    tv.text = "—"
                    containerActions.addView(tv)
                }
            },
            onError = { e: Exception ->
                Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )

        btnDelete.setOnClickListener {
            repo.deleteCrisis(pid, cid,
                onSuccess = {
                    Toast.makeText(requireContext(), "Crisis eliminada", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                },
                onError = { e: Exception ->
                    Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}
