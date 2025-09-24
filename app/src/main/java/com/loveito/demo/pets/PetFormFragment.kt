@file:Suppress("unused", "UNUSED_PARAMETER")
package com.loveito.demo.pets

import androidx.fragment.app.Fragment

/**
 * Obsoleto: usar [PetDetailsFragment] para visualizar y [PetEditFragment] para crear/editar.
 */
class PetFormFragment : Fragment() {
    companion object {
        fun newEdit(id: String, name: String = "", notes: String = "", photoUrl: String = "") = PetDetailsFragment.newInstance(id)
        fun newEdit(id: String) = PetDetailsFragment.newInstance(id)
    }
}
