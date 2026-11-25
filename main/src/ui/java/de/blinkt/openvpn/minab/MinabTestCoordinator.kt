/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabTestCoordinator: lightweight app-wide tracker for whether any
 * Ping test or Real test is currently running (on Home or on Configs).
 * Home listens to this so the Connect section stays dimmed and locked
 * for the entire duration of ANY test until it finishes or is cancelled,
 * even if the user started the test on the Configs tab and switched back.
 */

package de.blinkt.openvpn.minab

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

object MinabTestCoordinator {

    enum class Source {
        HOME_PING,
        HOME_REAL,
        CONFIGS_PING,
        CONFIGS_REAL
    }

    fun interface Listener {
        fun onActiveTestsChanged(anyRunning: Boolean)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeSources = mutableSetOf<Source>()
    private val listeners = CopyOnWriteArraySet<Listener>()

    @Synchronized
    fun setRunning(source: Source, running: Boolean) {
        val changed = if (running) activeSources.add(source) else activeSources.remove(source)
        if (!changed) return
        val any = activeSources.isNotEmpty() || RealTester.isRunning()
        notifyListeners(any)
    }

    @Synchronized
    fun isAnyTestRunning(): Boolean {
        return activeSources.isNotEmpty() || RealTester.isRunning()
    }

    @Synchronized
    fun isRealTestRunning(): Boolean {
        return activeSources.contains(Source.HOME_REAL) ||
            activeSources.contains(Source.CONFIGS_REAL) ||
            RealTester.isRunning()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyListeners(anyRunning: Boolean) {
        val dispatch = Runnable {
            for (listener in listeners) {
                try {
                    listener.onActiveTestsChanged(anyRunning)
                } catch (_: Exception) {
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch.run()
        } else {
            mainHandler.post(dispatch)
        }
    }
}
