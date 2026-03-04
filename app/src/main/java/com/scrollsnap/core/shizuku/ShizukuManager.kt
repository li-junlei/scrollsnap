package com.scrollsnap.core.shizuku

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

data class ShizukuStatus(
    val isBinderAvailable: Boolean = false,
    val isPermissionGranted: Boolean = false,
    val message: String = "Shizuku not connected"
)

class ShizukuManager(
    private val appContext: Context
) {

    private val _status = MutableStateFlow(ShizukuStatus())
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        refreshStatus()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == REQUEST_CODE_SHIZUKU_PERMISSION) {
            refreshStatus()
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        requestBinder()
        refreshStatus()
    }

    fun refreshStatus() {
        val initialPing = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val requestError = if (!initialPing) requestBinder() else null
        val binderAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val binderObjectAlive = runCatching { Shizuku.getBinder() != null }.getOrDefault(false)
        val permissionGranted = binderAvailable && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        val message = when {
            !binderAvailable -> buildString {
                append("Shizuku service unavailable")
                append(" (binderObject=")
                append(if (binderObjectAlive) "present" else "null")
                append(")")
                if (requestError != null) {
                    append(", requestBinderError=")
                    append(requestError.javaClass.simpleName)
                    if (!requestError.message.isNullOrBlank()) {
                        append(": ")
                        append(requestError.message)
                    }
                }
            }
            permissionGranted -> "Ready: shell-level operations enabled"
            else -> "Permission required"
        }

        _status.value = ShizukuStatus(
            isBinderAvailable = binderAvailable,
            isPermissionGranted = permissionGranted,
            message = message
        )
    }

    fun requestPermission() {
        if (!_status.value.isBinderAvailable || _status.value.isPermissionGranted) return
        Shizuku.requestPermission(REQUEST_CODE_SHIZUKU_PERMISSION)
    }

    fun requestBinder(): Throwable? {
        return runCatching {
            ShizukuProvider.requestBinderForNonProviderProcess(appContext)
            null
        }.getOrElse { it }
    }

    fun dispose() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }

    companion object {
        private const val REQUEST_CODE_SHIZUKU_PERMISSION = 7001
    }
}
