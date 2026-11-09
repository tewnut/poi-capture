package io.github.tewnut.poicapture.capture

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.core.CatalogueError
import io.github.tewnut.poicapture.core.CatalogueException
import io.github.tewnut.poicapture.net.RoadSource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Downloads one offline road pack: resumes a partial download (HTTP Range), checks the catalogue's SHA-256, then
 * installs it atomically (the previous version stays usable until then). Wi-Fi only unless mobile data is allowed.
 */
class RoadPackWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val url = inputData.getString("url") ?: return@withContext Result.failure()
        val sha = inputData.getString("sha256").orEmpty()
        val total = inputData.getLong("bytes", 0)
        val packs = RoadSource.packs(applicationContext)
        val part = File(packs.dir.apply { mkdirs() }, ".$id.part")
        try {
            val have = if (part.exists()) part.length() else 0L
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            val append = code == 206
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            var done = if (append) have else 0L
            c.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 256 * 1024) { setProgress(workDataOf("done" to done, "total" to total)); lastReport = done }
                    }
                }
            }
            c.disconnect()
            if (sha.isNotBlank() && sha256(part) != sha) { part.delete(); throw IOException("checksum mismatch, downloading again") }
            packs.install(part)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount >= 5) { part.delete(); Result.failure(workDataOf("error" to (e.message ?: e.javaClass.simpleName))) } else Result.retry()
        }
    }
}

object RoadPackDownloads {
    private fun name(id: String) = "roadpack-$id"

    /** [anyNetwork]: the contributor asked for this pack now (size shown), so 4G is fine; otherwise Wi-Fi unless allowed. */
    fun enqueue(ctx: Context, e: RoadSource.Entry, anyNetwork: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<RoadPackWorker>()
            .setInputData(workDataOf("id" to e.id, "url" to e.url, "sha256" to e.sha256, "bytes" to e.bytes))
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(if (anyNetwork) NetworkType.CONNECTED else NetworkType.UNMETERED)
                .build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(name(e.id), ExistingWorkPolicy.KEEP, req)
    }

    fun cancel(ctx: Context, id: String) {
        WorkManager.getInstance(ctx).cancelUniqueWork(name(id))
        File(RoadSource.packs(ctx).dir, ".$id.part").delete()
    }

    fun flow(ctx: Context, id: String): Flow<List<WorkInfo>> = WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(name(id))
}

/** Fetch the pack catalogue (index.json) and keep it for offline suggestions. */
object RoadPackCatalogue {
    fun fetch(ctx: Context, url: String): List<RoadSource.Entry> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            val code = try { c.responseCode } catch (e: java.net.UnknownHostException) {
                throw CatalogueException(CatalogueError.OFFLINE, detail = e.message)
            } catch (e: java.net.SocketTimeoutException) {
                throw CatalogueException(CatalogueError.TIMEOUT)
            }
            if (code != 200) throw CatalogueException(CatalogueError.forHttp(code, url), code)
            val body = c.inputStream.bufferedReader().readText()
            val list = RoadSource.parseCatalogue(body, url)
            RoadSource.saveCatalogue(ctx, url, body)
            return list
        } finally { c.disconnect() }
    }
}

private fun sha256(f: java.io.File): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
    return md.digest().joinToString("") { "%02x".format(it) }
}
