package com.screentranslator.core

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object SearchQuery {
    // Form encoding is equivalent to appendQueryParameter for a query string; never concatenate raw input.
    fun googleUrl(query: String): String? = query.trim().takeIf { it.isNotEmpty() }?.let {
        "https://www.google.com/search?q=" + URLEncoder.encode(it, StandardCharsets.UTF_8.name())
    }
}
object TemporaryImagePolicy {
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    fun expired(modifiedAt: Long, now: Long) = modifiedAt <= now - MAX_AGE_MS || modifiedAt > now
}
