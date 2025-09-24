package com.loveito.demo.pets

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * ViewModel para mantener en cache datos de la mascota y métricas de crisis
 * evitando parpadeos al volver de edición.
 */
class PetDetailsViewModel(
    private val petId: String,
    private val repo: PetsRepository
) : ViewModel() {

    data class CrisisMetrics(
        val loaded: Boolean = false,
        val hasCrises: Boolean = false,
        val lastStartedAt: Long? = null,
        val avgDurationSec: Int? = null
    )

    private val _pet = MutableLiveData<Pet?>()
    val pet: LiveData<Pet?> = _pet

    private val _metrics = MutableLiveData<CrisisMetrics>()
    val metrics: LiveData<CrisisMetrics> = _metrics

    fun loadPet(loadCrisesIfNeeded: Boolean) {
        repo.getPet(petId,
            onSuccess = { p ->
                _pet.postValue(p)
                if (loadCrisesIfNeeded && _metrics.value?.loaded != true) {
                    loadCrises(false)
                }
            },
            onError = { /* ignore - UI se encargará */ }
        )
    }

    fun loadCrises(force: Boolean) {
        if (!force && _metrics.value?.loaded == true) return
        repo.getCrisesForPet(petId,
            onSuccess = { list ->
                if (list.isEmpty()) {
                    _metrics.postValue(CrisisMetrics(loaded = true, hasCrises = false))
                } else {
                    val last = list.maxByOrNull { it.startedAt }!!
                    val avgSec = list.map { it.durationSec }.average().toInt()
                    _metrics.postValue(
                        CrisisMetrics(
                            loaded = true,
                            hasCrises = true,
                            lastStartedAt = last.startedAt,
                            avgDurationSec = avgSec
                        )
                    )
                }
            },
            onError = {
                // si nunca cargó, marcamos como cargado sin datos
                if (_metrics.value == null) {
                    _metrics.postValue(CrisisMetrics(loaded = true, hasCrises = false))
                }
            }
        )
    }

    fun invalidateCrises() {
        _metrics.value = _metrics.value?.copy(loaded = false) ?: CrisisMetrics()
    }

    class Factory(private val petId: String, private val repo: PetsRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PetDetailsViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return PetDetailsViewModel(petId, repo) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

