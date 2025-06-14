package com.example.wifiawaredemo.shared

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel

class LogViewModel : ViewModel() {
    val messages = mutableStateListOf<String>()

    fun addMessage(message: String) {
        messages.add(message)
    }
}