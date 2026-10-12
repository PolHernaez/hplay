package com.hplay.app

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow

/** Estado global de la sesión de emisión (un solo proceso: servicio y UI lo comparten). */
object Session {
    enum class Mode { IDLE, MIRROR, URL }

    data class State(
        val mode: Mode = Mode.IDLE,
        val receiverUrl: String? = null,
        val deviceName: String? = null,
        @StringRes val routeRes: Int? = null,
        val clients: Int = 0,
        val audio: Boolean = false,
        @StringRes val errorRes: Int? = null,
    )

    val state = MutableStateFlow(State())
}
