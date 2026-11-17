package com.screentranslator.gemini

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

object GeminiImages {
    suspend fun encode(bitmap: Bitmap): EncodedImage = withContext(Dispatchers.Default) {
        val factor = min(1f, 1600f / max(bitmap.width, bitmap.height))
        val scaled = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * factor).toInt()), max(1, (bitmap.height * factor).toInt()), true)
        try {
            val bytes = ByteArrayOutputStream().use { stream ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)); stream.toByteArray()
            }
            if (bytes.size > 1500_000) throw GeminiException(GeminiFailure.TOO_LARGE)
            EncodedImage(Base64.encodeToString(bytes, Base64.NO_WRAP), scaled.width, scaled.height, bitmap.width, bitmap.height)
        } finally { if (scaled !== bitmap) scaled.recycle() }
    }
}
