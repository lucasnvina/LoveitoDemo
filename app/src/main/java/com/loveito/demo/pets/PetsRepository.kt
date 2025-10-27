package com.loveito.demo.pets

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage

class PetsRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private val PETS = "pets"

    private fun parseMedications(raw: Any?): List<Medication> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val name = m["name"] as? String ?: ""
            val dose = m["dose"] as? String ?: ""
            val unit = m["unit"] as? String ?: ""
            val timesAny = m["times"]
            val times = (timesAny as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            Medication(name = name, dose = dose, unit = unit, times = times)
        }
    }

    private fun parseProfessionals(raw: Any?): List<Professional> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            Professional(
                name = m["name"] as? String ?: "",
                lastName = m["lastName"] as? String ?: "",
                specialty = m["specialty"] as? String ?: "",
                phone = m["phone"] as? String ?: "",
                email = m["email"] as? String ?: "",
                isFavorite = m["isFavorite"] as? Boolean ?: false
            )
        }
    }

    fun getMyPets(onSuccess: (List<Pet>) -> Unit, onError: (Exception) -> Unit) {
        val uid = auth.currentUser?.uid ?: run { onSuccess(emptyList()); return }
        db.collection(PETS)
            .whereEqualTo("ownerId", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                val list = snap.documents.map { doc ->
                    Pet(
                        id = doc.id,
                        ownerId = doc.getString("ownerId") ?: "",
                        name = doc.getString("name") ?: "",
                        species = doc.getString("species") ?: "dog",
                        breed = doc.getString("breed"),
                        weightKg = doc.getDouble("weightKg") ?: doc.getLong("weightKg")?.toDouble(),
                        sex = doc.getString("sex"),
                        birthDate = doc.getLong("birthDate"),
                        neutered = doc.getBoolean("neutered"),
                        heightCm = doc.getDouble("heightCm") ?: doc.getLong("heightCm")?.toDouble(),
                        lengthCm = doc.getDouble("lengthCm") ?: doc.getLong("lengthCm")?.toDouble(),
                        notes = doc.getString("notes"),
                        photoUrl = doc.getString("photoUrl"),
                        createdAt = doc.getLong("createdAt") ?: 0L,
                        updatedAt = doc.getLong("updatedAt") ?: 0L,
                        medications = parseMedications(doc.get("medications")),
                        professionals = parseProfessionals(doc.get("professionals"))
                    )
                }
                onSuccess(list)
            }
            .addOnFailureListener { onError(it) }
    }

    fun getPet(id: String, onSuccess: (Pet) -> Unit, onError: (Exception) -> Unit) {
        db.collection(PETS).document(id).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) { onError(IllegalStateException("Mascota no encontrada")); return@addOnSuccessListener }
                val p = Pet(
                    id = doc.id,
                    ownerId = doc.getString("ownerId") ?: "",
                    name = doc.getString("name") ?: "",
                    species = doc.getString("species") ?: "dog",
                    breed = doc.getString("breed"),
                    weightKg = doc.getDouble("weightKg") ?: doc.getLong("weightKg")?.toDouble(),
                    sex = doc.getString("sex"),
                    birthDate = doc.getLong("birthDate"),
                    neutered = doc.getBoolean("neutered"),
                    heightCm = doc.getDouble("heightCm") ?: doc.getLong("heightCm")?.toDouble(),
                    lengthCm = doc.getDouble("lengthCm") ?: doc.getLong("lengthCm")?.toDouble(),
                    notes = doc.getString("notes"),
                    photoUrl = doc.getString("photoUrl"),
                    createdAt = doc.getLong("createdAt") ?: 0L,
                    updatedAt = doc.getLong("updatedAt") ?: 0L,
                    medications = parseMedications(doc.get("medications")),
                    professionals = parseProfessionals(doc.get("professionals"))
                )
                onSuccess(p)
            }
            .addOnFailureListener(onError)
    }

    fun createPet(
        name: String,
        breed: String?,
        weightKg: Double?,
        photo: Uri?,
        sex: String?,
        birthDate: Long?,
        neutered: Boolean?,
        heightCm: Double?,
        lengthCm: Double?,
        medications: List<Medication>? = null,
        professionals: List<Professional>? = null,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val uid = auth.currentUser?.uid ?: run { onError(IllegalStateException("No hay usuario autenticado")); return }
        val now = System.currentTimeMillis()
        val base = hashMapOf<String, Any?>(
            "ownerId" to uid,
            "name" to name,
            "species" to "dog",
            "breed" to breed,
            "weightKg" to weightKg,
            "sex" to sex,
            "birthDate" to birthDate,
            "neutered" to neutered,
            "heightCm" to heightCm,
            "lengthCm" to lengthCm,
            "createdAt" to now,
            "updatedAt" to now,
        )
        if (medications != null) {
            base["medications"] = medications.map { mapOf("name" to it.name, "dose" to it.dose, "unit" to it.unit, "times" to it.times) }
        }
        if (professionals != null) {
            base["professionals"] = professionals.map { mapOf(
                "name" to it.name,
                "lastName" to it.lastName,
                "specialty" to it.specialty,
                "phone" to it.phone,
                "email" to it.email,
                "isFavorite" to it.isFavorite
            ) }
        }
        val docRef = db.collection(PETS).document()
        docRef.set(base)
            .addOnSuccessListener {
                if (photo == null) {
                    onSuccess(docRef.id)
                } else {
                    uploadPetPhoto(uid, docRef.id, photo,
                        onSuccess = { url ->
                            docRef.update(mapOf("photoUrl" to url, "updatedAt" to System.currentTimeMillis()))
                                .addOnSuccessListener { onSuccess(docRef.id) }
                                .addOnFailureListener(onError)
                        },
                        onError = onError
                    )
                }
            }
            .addOnFailureListener(onError)
    }

    fun updatePet(
        id: String,
        name: String,
        breed: String?,
        weightKg: Double?,
        photo: Uri?,
        sex: String?,
        birthDate: Long?,
        neutered: Boolean?,
        heightCm: Double?,
        lengthCm: Double?,
        medications: List<Medication>? = null,
        professionals: List<Professional>? = null,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        val uid = auth.currentUser?.uid ?: run { onError(IllegalStateException("No hay usuario autenticado")); return }
        val ref = db.collection(PETS).document(id)
        val updates = hashMapOf<String, Any?>(
            "name" to name,
            "breed" to breed,
            "weightKg" to weightKg,
            "sex" to sex,
            "birthDate" to birthDate,
            "neutered" to neutered,
            "heightCm" to heightCm,
            "lengthCm" to lengthCm,
            "updatedAt" to System.currentTimeMillis()
        )
        if (medications != null) {
            updates["medications"] = medications.map { mapOf("name" to it.name, "dose" to it.dose, "unit" to it.unit, "times" to it.times) }
        }
        if (professionals != null) {
            updates["professionals"] = professionals.map { mapOf(
                "name" to it.name,
                "lastName" to it.lastName,
                "specialty" to it.specialty,
                "phone" to it.phone,
                "email" to it.email,
                "isFavorite" to it.isFavorite
            ) }
        }
        ref.update(updates)
            .addOnSuccessListener {
                if (photo == null) onSuccess()
                else uploadPetPhoto(uid, id, photo,
                    onSuccess = { url ->
                        ref.update(mapOf("photoUrl" to url, "updatedAt" to System.currentTimeMillis()))
                            .addOnSuccessListener { onSuccess() }
                            .addOnFailureListener(onError)
                    },
                    onError = onError
                )
            }
            .addOnFailureListener(onError)
    }

    fun updatePetMedications(id: String, medications: List<Medication>, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        val ref = db.collection(PETS).document(id)
        val updates = hashMapOf<String, Any?>()
        updates["medications"] = medications.map { mapOf("name" to it.name, "dose" to it.dose, "unit" to it.unit, "times" to it.times) }
        updates["updatedAt"] = System.currentTimeMillis()
        ref.update(updates)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onError)
    }

    fun updatePetProfessionals(id: String, professionals: List<Professional>, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        val ref = db.collection(PETS).document(id)
        val updates = hashMapOf<String, Any?>()
        updates["professionals"] = professionals.map { mapOf(
            "name" to it.name,
            "lastName" to it.lastName,
            "specialty" to it.specialty,
            "phone" to it.phone,
            "email" to it.email,
            "isFavorite" to it.isFavorite
        ) }
        updates["updatedAt"] = System.currentTimeMillis()
        ref.update(updates)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onError)
    }

    fun deletePet(id: String, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        val ref = db.collection(PETS).document(id)
        ref.get().addOnSuccessListener { doc ->
            val url = doc.getString("photoUrl")
            // Borrar subcolección 'crises'
            ref.collection("crises").get()
                .addOnSuccessListener { qs ->
                    val batch = db.batch()
                    for (d in qs.documents) batch.delete(d.reference)
                    batch.commit().addOnCompleteListener {
                        // Borrar mascota
                        ref.delete()
                            .addOnSuccessListener {
                                if (!url.isNullOrEmpty()) {
                                    try {
                                        val storageRef = FirebaseStorage.getInstance().getReferenceFromUrl(url)
                                        storageRef.delete()
                                            .addOnSuccessListener { onSuccess() }
                                            .addOnFailureListener { onSuccess() }
                                    } catch (e: Exception) { onSuccess() }
                                } else onSuccess()
                            }
                            .addOnFailureListener(onError)
                    }
                }
                .addOnFailureListener(onError)
        }.addOnFailureListener(onError)
    }

    // --- Crises (se mantienen igual que tu versión actual) ---

    fun createTestCrisisWithTriage(petId: String, triage: Map<String, Any?>, onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val uid = auth.currentUser?.uid ?: run { onError(IllegalStateException("No hay usuario autenticado")); return }
        val ref = db.collection(PETS).document(petId).collection("crises").document()
        // Tomar duración real del resultado del asistente si está disponible
        val durationFromTriage = (triage["duration_sec"] as? Number)?.toLong()?.coerceAtLeast(0L)?.toInt()
        val data = hashMapOf(
            "id" to ref.id,
            "petId" to petId,
            "ownerId" to uid,
            // usar timestamp del servidor (UTC)
            "startedAt" to FieldValue.serverTimestamp(),
            // Persistir duración real si existe; caso contrario, 0 como valor por defecto
            "durationSec" to (durationFromTriage ?: 0),
            "audioUrl" to null,
            "triage" to triage
        )
        ref.set(data)
            .addOnSuccessListener { onSuccess(ref.id) }
            .addOnFailureListener(onError)
    }

    fun getCrisesForPet(petId: String, onSuccess: (List<Crisis>) -> Unit, onError: (Exception) -> Unit) {
        db.collection(PETS).document(petId)
            .collection("crises")
            // Se evita orderBy en Firestore porque la mezcla de tipos (Long y Timestamp) puede causar error.
            .get()
            .addOnSuccessListener { snap ->
                val list = snap.documents.map { d ->
                    val triage = d.get("triage") as? Map<*,*>
                    val startedAtRaw = d.get("startedAt")
                    var startedAtMs = when (startedAtRaw) {
                        is com.google.firebase.Timestamp -> startedAtRaw.toDate().time
                        is Number -> startedAtRaw.toLong()
                        is java.util.Date -> startedAtRaw.time
                        else -> 0L
                    }
                    // Normalizar: si parece estar en segundos (10 dígitos típico < 1e11) convertir a ms
                    if (startedAtMs in 1_000_000_000L..99_999_999_999L) {
                        startedAtMs *= 1000
                    }
                    val durationStored = (d.getLong("durationSec") ?: 0L).toInt()
                    val durationFromTriage = (triage?.get("duration_sec") as? Number)?.toLong()?.coerceAtLeast(0L)?.toInt()
                    val duration = durationFromTriage ?: durationStored
                    Crisis(
                        id = d.getString("id") ?: d.id,
                        petId = d.getString("petId") ?: petId,
                        ownerId = d.getString("ownerId") ?: "",
                        startedAt = startedAtMs,
                        durationSec = duration,
                        note = d.getString("note"),
                        audioUrl = d.getString("audioUrl"),
                        triageSeverity = triage?.get("severity") as? String,
                        triageTitle = triage?.get("title") as? String
                    )
                }.sortedByDescending { it.startedAt }
                onSuccess(list)
            }
            .addOnFailureListener(onError)
    }

    fun getCrisisDetail(petId: String, crisisId: String, onSuccess: (Map<String, Any?>) -> Unit, onError: (Exception) -> Unit) {
        db.collection(PETS).document(petId)
            .collection("crises").document(crisisId)
            .get()
            .addOnSuccessListener { doc -> onSuccess(doc.data ?: emptyMap()) }
            .addOnFailureListener(onError)
    }

    fun deleteCrisis(petId: String, crisisId: String, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        val ref = db.collection(PETS).document(petId).collection("crises").document(crisisId)
        ref.get().addOnSuccessListener { doc ->
            val audioUrl = doc.getString("audioUrl")
            ref.delete()
                .addOnSuccessListener {
                    if (!audioUrl.isNullOrEmpty()) {
                        try {
                            val storageRef = FirebaseStorage.getInstance().getReferenceFromUrl(audioUrl)
                            storageRef.delete()
                                .addOnSuccessListener { onSuccess() }
                                .addOnFailureListener { onSuccess() }
                        } catch (e: Exception) { onSuccess() }
                    } else onSuccess()
                }
                .addOnFailureListener(onError)
        }.addOnFailureListener(onError)
    }

    fun getActiveCareRecommendations(petId: String, onSuccess: (List<CareRecommendation>) -> Unit, onError: (Exception) -> Unit) {
        db.collection(PETS).document(petId)
            .collection("care_recommendations")
            .whereEqualTo("status", "active")
            .get()
            .addOnSuccessListener { qs ->
                val list = qs.documents.map { d ->
                    CareRecommendation(
                        title = d.getString("title") ?: "",
                        body = d.getString("body") ?: "",
                        evidence = d.getString("evidence"),
                        priority = (d.getLong("priority") ?: 0L).toInt(),
                        validTo = d.getTimestamp("validTo")?.toDate()?.time
                    )
                }.sortedWith(compareByDescending<CareRecommendation> { it.priority }.thenBy { it.validTo ?: Long.MAX_VALUE })
                onSuccess(list)
            }
            .addOnFailureListener(onError)
    }

    private fun uploadPetPhoto(uid: String, petId: String, uri: Uri, onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val storage = FirebaseStorage.getInstance()
        val path = "users/$uid/pets/$petId/photo.jpg"
        val ref = storage.reference.child(path)
        ref.putFile(uri)
            .addOnSuccessListener {
                ref.downloadUrl
                    .addOnSuccessListener { onSuccess(it.toString()) }
                    .addOnFailureListener(onError)
            }
            .addOnFailureListener(onError)
    }
}
