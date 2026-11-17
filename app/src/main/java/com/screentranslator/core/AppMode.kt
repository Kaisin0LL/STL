package com.screentranslator.core

enum class AppMode(val label: String, val model: String, val attribution: String) {
    LOCAL("Обычный", "mlkit-on-device", "Powered by Google Translate"),
    GEMINI("Gemini API", "gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite")
}
