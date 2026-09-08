package com.davnozdu.vrapp

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Состояние сервиса для UI. Заменяет LocalBroadcastManager, который
 * объявлен устаревшим ещё в 2018 и требовал ручного register/unregister.
 */
object VrState {

    enum class Phase { IDLE, WAITING, BLOCKED, UNBLOCKED }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val secondsLeft: Int = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _rootAvailable = MutableStateFlow<Boolean?>(null)
    val rootAvailable: StateFlow<Boolean?> = _rootAvailable.asStateFlow()

    // replay нужен, чтобы Activity, открытая после старта сервиса,
    // увидела уже записанный журнал, а не пустое поле.
    private val _log = MutableSharedFlow<String>(replay = 50, extraBufferCapacity = 50)
    val log: SharedFlow<String> = _log.asSharedFlow()

    fun setPhase(phase: Phase, secondsLeft: Int = 0) {
        _status.value = Status(phase, secondsLeft)
    }

    fun setRootAvailable(available: Boolean) {
        _rootAvailable.value = available
    }

    fun log(message: String) {
        Log.i("VRapp", message)
        _log.tryEmit(message)
    }
}
