
package com.loveito.demo.pets

data class Crisis(
    val id: String = "",
    val petId: String = "",
    val ownerId: String = "",
    val startedAt: Long = 0L,
    val durationSec: Int = 0,
    val note: String? = null,
    val audioUrl: String? = null,
    val triageSeverity: String? = null,
    val triageTitle: String? = null
)
