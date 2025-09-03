package com.loveito.demo.pets

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage

class PetsRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private val PETS = "pets"

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
        val data = hashMapOf(
            "id" to ref.id,
            "petId" to petId,
            "ownerId" to uid,
            "startedAt" to System.currentTimeMillis(),
            "durationSec" to (30..240).random(),
            "note" to "crisis de prueba",
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
            .orderBy("startedAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                val list = snap.documents.map { d ->
                    val triage = d.get("triage") as? Map<*,*>
                    Crisis(
                        id = d.getString("id") ?: d.id,
                        petId = d.getString("petId") ?: petId,
                        ownerId = d.getString("ownerId") ?: "",
                        startedAt = d.getLong("startedAt") ?: 0L,
                        durationSec = (d.getLong("durationSec") ?: 0L).toInt(),
                        note = d.getString("note"),
                        audioUrl = d.getString("audioUrl"),
                        triageSeverity = triage?.get("severity") as? String,
                        triageTitle = triage?.get("title") as? String
                    )
                }
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
