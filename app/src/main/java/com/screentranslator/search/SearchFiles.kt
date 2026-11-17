package com.screentranslator.search

import android.app.job.*
import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.screentranslator.core.TemporaryImagePolicy
import kotlinx.coroutines.*
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

class SearchFiles(private val context: Context) {
    private val directory get() = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
    companion object {
        const val DIRECTORY = "screen-search"
        private const val CLEANUP_JOB = 3401
        private val filename = Regex("[a-f0-9-]+\\.png")
        fun isImageName(name: String) = filename.matches(name)
    }
    suspend fun sharePng(crop: Bitmap): Uri = withContext(Dispatchers.IO) {
        cleanup()
        val id = UUID.randomUUID().toString()
        val pending = File(directory, "$id.part")
        val ready = File(directory, "$id.png")
        try {
            pending.outputStream().use { check(crop.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            ensureActive()
            check(pending.renameTo(ready))
            scheduleCleanup()
            FileProvider.getUriForFile(context, "${context.packageName}.search-images", ready)
        } catch (e: Exception) { pending.delete(); ready.delete(); throw e }
    }
    suspend fun save(crop: Bitmap): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ScreenTranslator-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ScreenTranslator")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("Не удалось создать изображение")
        var published = false
        try {
            resolver.openOutputStream(uri)?.use { check(crop.compress(Bitmap.CompressFormat.PNG, 100, it)) } ?: error("Не удалось записать изображение")
            ensureActive()
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
            published = true
            uri
        } finally { if (!published) resolver.delete(uri, null, null) }
    }
    fun cleanup(schedule: Boolean = true) {
        val now = System.currentTimeMillis()
        directory.listFiles()?.forEach {
            if (it.isFile && (isImageName(it.name) || it.extension == "part") && TemporaryImagePolicy.expired(it.lastModified(), now)) it.delete()
        }
        if (schedule) scheduleCleanup()
    }
    private fun scheduleCleanup() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (directory.listFiles()?.isNotEmpty() == true && scheduler.getPendingJob(CLEANUP_JOB) == null) {
            scheduler.schedule(JobInfo.Builder(CLEANUP_JOB, ComponentName(context, SearchCleanupJob::class.java))
                .setPeriodic(TemporaryImagePolicy.MAX_AGE_MS).build())
        }
    }
}

/** Only a single explicitly shared URI can be read. Expired files cannot be opened even if cleanup was delayed by Doze. */
class SearchImageProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (mode != "r" || !SearchFiles.isImageName(name)) throw FileNotFoundException()
        val file = File(requireNotNull(context).cacheDir, "${SearchFiles.DIRECTORY}/$name")
        if (!file.isFile || TemporaryImagePolicy.expired(file.lastModified(), System.currentTimeMillis())) {
            file.delete(); throw FileNotFoundException("Временное изображение больше недоступно")
        }
        return super.openFile(uri, mode) ?: throw FileNotFoundException()
    }
}
class SearchCleanupJob : JobService() {
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(Dispatchers.IO).launch {
            val retry = runCatching { SearchFiles(this@SearchCleanupJob).cleanup(schedule = false) }.isFailure
            withContext(Dispatchers.Main) { jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
    override fun onDestroy() { work?.cancel(); super.onDestroy() }
}
