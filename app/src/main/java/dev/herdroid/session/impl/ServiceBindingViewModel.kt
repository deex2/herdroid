package dev.herdroid.session.impl

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
open class ServiceBindingViewModel @Inject constructor(
    application: Application,
    private val sessionBridge: ProcessSessionBridge,
) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val monitor = Any()
    private var started = false
    private var binding: Binding? = null

    private inner class Binding : ServiceConnection {
        private var registration: ServiceRegistration? = null

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = binder as? ServiceEndpoint ?: return
            val currentRegistration = synchronized(monitor) {
                if (binding !== this) return
                sessionBridge.register(connected).also { registration = it }
            }
            connected.registered(currentRegistration)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(monitor) {
                if (binding !== this) return
                registration.also { registration = null }
            }?.close()
        }

        override fun onBindingDied(name: ComponentName?) {
            val (lostRegistration, shouldRebind) = synchronized(monitor) {
                if (binding !== this) return
                val lost = registration.also { registration = null }
                binding = null
                lost to started
            }
            lostRegistration?.close()
            try {
                unbindService(this)
            } catch (_: IllegalArgumentException) {
                // Android may already have removed a dead binding.
            }
            if (shouldRebind) bind()
        }
    }

    fun onActivityStart() {
        synchronized(monitor) { started = true }
        bind()
    }

    fun onActivityStop(changingConfigurations: Boolean) {
        if (!changingConfigurations) unbind()
    }

    override fun onCleared() {
        unbind()
    }

    protected open fun bindService(connection: ServiceConnection): Boolean = context.bindService(
        Intent(context, ConnectionService::class.java),
        connection,
        Context.BIND_AUTO_CREATE,
    )

    protected open fun unbindService(connection: ServiceConnection) = context.unbindService(connection)

    private fun bind() {
        val newBinding = synchronized(monitor) {
            if (!started || binding != null) return
            Binding().also { binding = it }
        }
        val bound = try {
            bindService(newBinding)
        } catch (failure: Throwable) {
            synchronized(monitor) { if (binding === newBinding) binding = null }
            throw failure
        }
        if (!bound) synchronized(monitor) { if (binding === newBinding) binding = null }
    }

    private fun unbind() {
        val current = synchronized(monitor) {
            started = false
            binding.also { binding = null }
        }
        if (current != null) unbindService(current)
    }
}
