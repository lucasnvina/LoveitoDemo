package com.loveito.demo.pets

data class CareRecommendation(
    val title: String,
    val body: String,
    val evidence: String? = null,
    val priority: Int = 0,
    val validTo: Long? = null
)
