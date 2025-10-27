package com.loveito.demo.pets

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.loveito.demo.R
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

class PetCareFragment : Fragment() {
    private var petId: String? = null

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var listView: RecyclerView? = null
    private var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null

    private var liveListener: com.google.firebase.firestore.ListenerRegistration? = null

    // Track pull gesture start to translate content for a more natural pull effect
    private var pullStartY: Float = -1f

    data class CareRec(
        val id: String,
        val category: String,
        val title: String,
        val body: String,
        val evidence: String?,
        val priority: Int,
        val status: String,
        val validTo: Long?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        petId = arguments?.getString("petId")
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_pet_care, container, false)
        listView = v.findViewById(R.id.rvCare)
        swipeRefresh = v.findViewById(R.id.swipeRefresh)

        listView?.layoutManager = LinearLayoutManager(requireContext())

        // Color del spinner acorde a la paleta
        swipeRefresh?.setColorSchemeResources(R.color.maroon_900, R.color.rust_500)
        // Posición del spinner y distancia de slingshot para que baje más visible
        swipeRefresh?.setProgressViewOffset(false, dpToPx(8), dpToPx(96))
        swipeRefresh?.setSlingshotDistance(dpToPx(128))

        // Pull-to-refresh triggers recompute
        swipeRefresh?.setOnRefreshListener {
            petId?.let { id -> triggerRecompute(id) } ?: run { swipeRefresh?.isRefreshing = false }
        }

        // Efecto visual: mover ligeramente el contenido mientras se arrastra si estamos en el tope
        swipeRefresh?.setOnTouchListener { _, ev ->
            val rv = listView ?: return@setOnTouchListener false
            val lm = rv.layoutManager as? LinearLayoutManager
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pullStartY = ev.y
                    rv.translationY = 0f
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!swipeRefresh!!.isRefreshing) {
                        val dy = ev.y - pullStartY
                        val firstPos = lm?.findFirstCompletelyVisibleItemPosition() ?: 0
                        val atTop = firstPos == 0 && (rv.getChildAt(0)?.top ?: 0) >= 0
                        if (dy > 0 && atTop) {
                            rv.translationY = (dy * 0.35f).coerceAtMost(dpToPx(140).toFloat())
                        } else if (dy <= 0f) {
                            rv.translationY = 0f
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    rv.animate().translationY(0f).setDuration(180).start()
                    pullStartY = -1f
                }
            }
            // No consumimos el evento para no interferir con SwipeRefresh
            false
        }
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val id = petId
        if (id == null) {
            Toast.makeText(requireContext(), getString(R.string.error, "Falta petId"), Toast.LENGTH_SHORT).show()
            return
        }
        loadRecommendations(id)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        liveListener?.remove(); liveListener = null
        listView = null
        swipeRefresh = null
    }

    private fun loadRecommendations(id: String) {
        if (auth.currentUser?.uid == null) { setupList(emptyList()); return }
        liveListener?.remove(); liveListener = null
        liveListener = db.collection("pets").document(id)
            .collection("care_recommendations")
            .whereEqualTo("status", "active")
            .orderBy("priority", Query.Direction.DESCENDING)
            .orderBy("validTo", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    val code = err.code
                    if (code == com.google.firebase.firestore.FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                        db.collection("pets").document(id)
                            .collection("care_recommendations")
                            .whereEqualTo("status", "active")
                            .get()
                            .addOnSuccessListener { qs ->
                                val list = qs.documents.map { d ->
                                    CareRec(
                                        id = d.id,
                                        category = d.getString("category") ?: "",
                                        title = d.getString("title") ?: "",
                                        body = d.getString("body") ?: "",
                                        evidence = d.getString("evidence"),
                                        priority = (d.getLong("priority") ?: 0L).toInt(),
                                        status = d.getString("status") ?: "active",
                                        validTo = (d.getTimestamp("validTo")?.toDate()?.time)
                                    )
                                }.sortedWith(compareByDescending<CareRec> { it.priority }.thenBy { it.validTo ?: Long.MAX_VALUE })
                                setupList(list)
                                Toast.makeText(requireContext(), "Ordenado localmente (índice en creación)", Toast.LENGTH_SHORT).show()
                            }
                            .addOnFailureListener { e2 ->
                                setupList(emptyList())
                                Toast.makeText(requireContext(), getString(R.string.error_generic, e2.localizedMessage ?: ""), Toast.LENGTH_SHORT).show()
                            }
                        return@addSnapshotListener
                    }
                    setupList(emptyList())
                    Toast.makeText(requireContext(), getString(R.string.error_generic, err.localizedMessage ?: ""), Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }
                val list = snap?.documents?.map { d ->
                    CareRec(
                        id = d.id,
                        category = d.getString("category") ?: "",
                        title = d.getString("title") ?: "",
                        body = d.getString("body") ?: "",
                        evidence = d.getString("evidence"),
                        priority = (d.getLong("priority") ?: 0L).toInt(),
                        status = d.getString("status") ?: "active",
                        validTo = (d.getTimestamp("validTo")?.toDate()?.time)
                    )
                } ?: emptyList()
                setupList(list)
            }
    }

    private fun setupList(items: List<CareRec>) {
        val rv = listView ?: return
        rv.adapter = CareAdapter(items,
            onDone = { rec -> updateStatus(rec, "done") },
            onDismiss = { rec -> updateStatus(rec, "dismissed") },
            onSnooze = { rec -> snooze(rec) }
        )
        rv.visibility = View.VISIBLE
    }

    private fun updateStatus(rec: CareRec, status: String) {
        val id = petId ?: return
        db.collection("pets").document(id).collection("care_recommendations").document(rec.id)
            .update(mapOf(
                "status" to status,
                "updatedAt" to com.google.firebase.Timestamp.now()
            ))
    }

    private fun snooze(rec: CareRec) {
        val id = petId ?: return
        val hours = 24
        val until = java.util.Date(System.currentTimeMillis() + hours * 60L * 60L * 1000L)
        db.collection("pets").document(id).collection("care_recommendations").document(rec.id)
            .update(mapOf(
                "status" to "snoozed",
                "snoozeUntil" to com.google.firebase.Timestamp(until.time / 1000, 0),
                "updatedAt" to com.google.firebase.Timestamp.now()
            ))
    }

    private fun triggerRecompute(id: String) {
        val api = GoogleApiAvailability.getInstance()
        val code = api.isGooglePlayServicesAvailable(requireContext())
        if (code != ConnectionResult.SUCCESS) {
            // Try to resolve (update/install Play services) and retry once if successful
            api.makeGooglePlayServicesAvailable(requireActivity())
                .addOnSuccessListener {
                    // After update availability, try again
                    triggerRecompute(id)
                }
                .addOnFailureListener {
                    swipeRefresh?.isRefreshing = false
                    Toast.makeText(requireContext(), getString(R.string.error_generic, "Actualizá Google Play Services desde Play Store"), Toast.LENGTH_LONG).show()
                }
            return
        }
        val fns = FirebaseFunctions.getInstance("southamerica-east1")
        swipeRefresh?.isRefreshing = true
        fns.getHttpsCallable("recomputeCare")
            .call(mapOf("petId" to id))
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "Recomendaciones actualizadas", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                val msg = when (e) {
                    is com.google.firebase.functions.FirebaseFunctionsException -> {
                        when (e.code) {
                            com.google.firebase.functions.FirebaseFunctionsException.Code.UNAUTHENTICATED -> "Iniciá sesión para actualizar recomendaciones"
                            com.google.firebase.functions.FirebaseFunctionsException.Code.PERMISSION_DENIED -> "No tenés permisos para esta mascota"
                            com.google.firebase.functions.FirebaseFunctionsException.Code.INVALID_ARGUMENT -> "Falta petId"
                            com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND -> "Mascota no encontrada"
                            else -> e.message ?: "Error"
                        }
                    }
                    else -> e.localizedMessage ?: "Error"
                }
                Toast.makeText(requireContext(), getString(R.string.error_generic, msg), Toast.LENGTH_SHORT).show()
            }
            .addOnCompleteListener {
                swipeRefresh?.isRefreshing = false
            }
    }

    class CareAdapter(
        private val items: List<CareRec>,
        private val onDone: (CareRec) -> Unit,
        private val onDismiss: (CareRec) -> Unit,
        private val onSnooze: (CareRec) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_HEADER = 0
        private val TYPE_ITEM = 1
        private val TYPE_EMPTY = 2

        override fun getItemCount(): Int = if (items.isEmpty()) 2 else items.size + 1

        override fun getItemViewType(position: Int): Int = when (position) {
            0 -> TYPE_HEADER
            else -> if (items.isEmpty()) TYPE_EMPTY else TYPE_ITEM
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderVH(inf.inflate(R.layout.item_care_header, parent, false))
                TYPE_EMPTY -> EmptyVH(inf.inflate(R.layout.item_care_empty, parent, false))
                else -> ItemVH(inf.inflate(R.layout.item_care_recommendation, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is ItemVH -> {
                    val it = items[position - 1]
                    holder.title.text = it.title
                    holder.body.text = it.body
                    val hasEvidence = !it.evidence.isNullOrBlank()
                    holder.evidence.visibility = if (hasEvidence) View.VISIBLE else View.GONE
                    if (hasEvidence) holder.evidence.text = it.evidence

                    holder.btnDone.setOnClickListener { _ -> onDone(it) }
                    holder.btnDismiss.setOnClickListener { _ -> onDismiss(it) }
                    holder.btnSnooze.setOnClickListener { _ -> onSnooze(it) }
                }
                is HeaderVH -> Unit
                is EmptyVH -> Unit
            }
        }

        class HeaderVH(view: View) : RecyclerView.ViewHolder(view)
        class EmptyVH(view: View) : RecyclerView.ViewHolder(view)
        class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tvCareItemTitle)
            val body: TextView = view.findViewById(R.id.tvCareItemBody)
            val evidence: TextView = view.findViewById(R.id.tvCareItemEvidence)
            val btnDone: MaterialButton = view.findViewById(R.id.btnCareDone)
            val btnDismiss: MaterialButton = view.findViewById(R.id.btnCareDismiss)
            val btnSnooze: MaterialButton = view.findViewById(R.id.btnCareSnooze)
        }
    }
}
