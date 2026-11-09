package io.github.tewnut.poicapture.capture

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.core.Coverage
import io.github.tewnut.poicapture.core.TusClient
import io.github.tewnut.poicapture.core.UploadJob
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Background upload of finished sessions to the VMAP capture server (tus). One unique work per session; Wi-Fi only
 * unless the contributor allowed mobile data. Each run sends for up to 8 minutes, then asks to be run again
 * (WorkManager stops a worker after 10 minutes), and the tus upload resumes from the server's offset.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY) ?: return@withContext Result.failure()
        val st = Prefs.settings(applicationContext)
        if (st.uploadUrl.isBlank()) return@withContext Result.failure()
        if (CaptureService.activeSession.value == id) return@withContext Result.retry()   // still recording
        val store = CaptureService.store(applicationContext)
        if (store.load(id) == null) return@withContext Result.failure()                     // deleted meanwhile
        runCatching { Coverage.forSession(store, id) }
        when (UploadJob.run(store, id, File(applicationContext.filesDir, "uploads"), TusClient(st.uploadUrl, st.uploadToken),
            System.currentTimeMillis() + 8 * 60_000L)) {
            UploadJob.Result.DONE -> Result.success()
            UploadJob.Result.PARTIAL, UploadJob.Result.RETRY -> Result.retry()
        }
    }

    companion object { const val KEY = "sessionId" }
}

object Uploads {
    fun configured(ctx: Context) = Prefs.settings(ctx).uploadUrl.isNotBlank()

    /** Queue (or keep) the upload of one session. */
    fun enqueue(ctx: Context, sessionId: String) {
        val st = Prefs.settings(ctx)
        if (st.uploadUrl.isBlank()) return
        val store = CaptureService.store(ctx)
        val cur = store.uploadState(sessionId)
        if (cur.state == "none" || (cur.state == "uploaded" && store.contentVersion(sessionId) > cur.uploadedVersion)) {
            store.saveUploadState(sessionId, cur.copy(state = "queued", error = null))
        }
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY to sessionId))
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(if (st.uploadOnMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                .build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("upload-$sessionId", ExistingWorkPolicy.KEEP, req)
    }

    /** A deleted session: drop its pending upload and the zip waiting in uploads/ (as big as the session). */
    fun forget(ctx: Context, sessionId: String) {
        WorkManager.getInstance(ctx).cancelUniqueWork("upload-$sessionId")
        File(ctx.filesDir, "uploads/$sessionId.zip").delete()
        File(ctx.filesDir, "uploads/$sessionId.zip.tmp").delete()
    }

    /** Every finished session that is not on the server (or changed since): at app start and when settings change. */
    fun enqueuePending(ctx: Context) {
        if (!configured(ctx)) return
        val store = CaptureService.store(ctx)
        for (s in store.list()) {
            if (CaptureService.activeSession.value == s.id) continue
            val u = store.uploadState(s.id)
            val stale = u.state == "uploaded" && store.contentVersion(s.id) > u.uploadedVersion
            if (u.state != "uploaded" || stale) enqueue(ctx, s.id)
        }
    }
}

/** Optional server API (see docs/server-api.md): processing status of an uploaded session, team coverage. */
object ServerApi {
    private fun get(url: String, token: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
            if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
        } catch (e: Exception) { null } finally { c.disconnect() }
    }

    /** {state: received | processing | done | failed, poisFound?, recapture?: [...]} or null when unknown / not set up. */
    fun status(ctx: Context, sessionId: String): JSONObject? {
        val st = Prefs.settings(ctx)
        if (st.apiUrl.isBlank()) return null
        return get(st.apiUrl.trimEnd('/') + "/sessions/" + URLEncoder.encode(sessionId, "UTF-8"), st.uploadToken)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    /** The team's app config ({"photoRoles": [...]}), or null when not set up / not served / not valid. */
    fun config(ctx: Context): io.github.tewnut.poicapture.core.PhotoRoles? {
        val st = Prefs.settings(ctx)
        if (st.apiUrl.isBlank()) return null
        val body = get(st.apiUrl.trimEnd('/') + "/config", st.uploadToken) ?: return null
        return runCatching { io.github.tewnut.poicapture.core.PhotoRoles.fromJson(JSONObject(body), "server") }.getOrNull()
    }

    /** Team coverage in a box (GeoJSON), or null. */
    fun coverage(ctx: Context, s: Double, w: Double, n: Double, e: Double): String? {
        val st = Prefs.settings(ctx)
        if (st.apiUrl.isBlank()) return null
        return get(st.apiUrl.trimEnd('/') + "/coverage?bbox=$s,$w,$n,$e", st.uploadToken)
    }
}
