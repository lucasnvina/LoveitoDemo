package com.loveito.demo.pets

data class Pet(
    val id: String,
    val ownerId: String,
    val name: String,
    val species: String = "dog",
    val breed: String? = null,
    val weightKg: Double? = null,
    val sex: String? = null,          // "Macho", "Hembra", "Otro"
    val birthDate: Long? = null,      // epoch millis
    val neutered: Boolean? = null,    // castrado/a
    val heightCm: Double? = null,     // alto
    val lengthCm: Double? = null,     // largo
    val notes: String? = null,        // legacy
    val photoUrl: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)