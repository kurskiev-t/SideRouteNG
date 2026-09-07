package com.kurskievtr.siderouteng

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppLog {
    const val TAG = "SideRouteNG"
    private const val MAX_LINES = 500
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val lines = LinkedList<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Synchronized
    fun i(message: String) {
        Log.i(TAG, message)
        append("I", message)
    }

    @Synchronized
    fun w(message: String, error: Throwable? = null) {
        if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
        append("W", message)
    }

    @Synchronized
    fun e(message: String, error: Throwable? = null) {
        if (error != null) Log.e(TAG, message, error) else Log.e(TAG, message)
        append("E", message)
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
        listener(snapshot())
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    private fun append(level: String, message: String) {
        val line = "${timeFormat.format(Date())} $level  $message"
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(line)
        val text = lines.joinToString("\n")
        listeners.forEach { it(text) }
    }
}
