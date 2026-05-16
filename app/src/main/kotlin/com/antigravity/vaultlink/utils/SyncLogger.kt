package com.antigravity.vaultlink.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

object SyncLogger {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(message: String) {
        val timestamp = timeFormat.format(Date())
        val formattedMessage = "[$timestamp] $message"
        val currentList = _logs.value.toMutableList()
        currentList.add(0, formattedMessage) // Newest logs at the top
        if (currentList.size > 50) currentList.removeAt(currentList.size - 1)
        _logs.value = currentList
    }

    fun clear() {
        _logs.value = emptyList()
    }
}