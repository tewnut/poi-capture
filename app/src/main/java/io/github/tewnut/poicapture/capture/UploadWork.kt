package io.github.tewnut.poicapture.capture

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
import io.github.tewnut.poicapture.core.Exporter
import io.github.tewnut.poicapture.core.Tus
import io.github.tewnut.poicapture.core.UploadRecord
import io.github.tewnut.poicapture.core.UploadStatus
import io.github.tewnut.poicapture.core.iso
import io.github.tewnut.poicapture.core.sessionUploadMetadata
import io.github.tewnut.poicapture.net.TusClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The upload server (Settings → Upload server, or the onboarding): the tus endpoint, the token, and when to upload.
 * The token is encrypted with a key in the Android Keystore (never in plain prefs, never in an exported file, never
 * logged); the app has allowBackup=false besides.
 */
object UploadServer {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("upload-server", Context.MODE_PRIVATE)

    /** The project's server (Cloudflare Worker + R2, server/worker); the web reviewer pulls from it by default too. */
    const val DEFAULT_URL = "https://poi-capture-cloud.4hum-ai.workers.dev/files/"

    /** The address as typed; [DEFAULT_URL] until one is typed (a cleared field stays empty: no uploads). */
    fun url(ctx: Context): String = sp(ctx).getString("url", DEFAULT_URL) ?: ""
    fun setUrl(ctx: Context, v: String) = sp(ctx).edit().putString("url", v.trim()).apply()
    /** A usable endpoint is set; the default server also needs its token (it answers 401 without one). */
    fun configured(ctx: Context): Boolean {
        val u = url(ctx)
        if (Tus.endpoint(u) == null) return false
        return Tus.endpoint(u) != Tus.endpoint(DEFAULT_URL) || token(ctx).isNotBlank()
    }
    /** Upload each session when it ends (on by default once a server is set). */
    fun auto(ctx: Context): Boolean = sp(ctx).getBoolean("auto", true)
    fun setAuto(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto", v).apply()
    /** Automatic uploads wait for Wi-Fi (or any unmetered network). */
    fun wifiOnly(ctx: Context): Boolean = sp(ctx).getBoolean("wifiOnly", true)
    fun setWifiOnly(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("wifiOnly", v).apply()

    fun token(ctx: Context): String = sp(ctx).getString("token", null)?.let { runCatching { Secret.decrypt(it) }.getOrNull() } ?: ""
    fun setToken(ctx: Context, v: String) {
        val t = v.trim()
        sp(ctx).edit().apply { if (t.isEmpty()) remove("token") else putString("token", Secret.encrypt(t)) }.apply()
    }

    fun client(ctx: Context): TusClient = TusClient(url(ctx), token(ctx))

    /** AES-GCM with a non-exportable Keystore key: what EncryptedSharedPreferences does, without the dependency. */
    private object Secret {
        private const val ALIAS = "poicapture-upload-token"
        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
        }
        fun encrypt(plain: String): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
        fun decrypt(stored: String): String {
            val b = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }
            return String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
        }
    }
}

/** Per-session upload state (kept across restarts) and the background work. */
object Uploads {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("uploads", Context.MODE_PRIVATE)
    private fun name(sessionId: String) = "upload-$sessionId"
    /** Bumped whenever a record changes, so the sessions list redraws. */
    val changed = MutableStateFlow(0)
    /** Bumped when an upload finishes (the session is then marked sent). */
    val finished = MutableStateFlow(0)

    fun zipFile(ctx: Context, sessionId: String) = File(ctx.filesDir, "uploads/$sessionId.zip")

    fun record(ctx: Context, sessionId: String): UploadRecord =
        sp(ctx).getString(sessionId, null)?.let { runCatching { UploadRecord.fromJson(JSONObject(it)) }.getOrNull() }
            ?: UploadRecord(sessionId)

    fun save(ctx: Context, r: UploadRecord) {
        sp(ctx).edit().putString(r.sessionId, r.toJson().toString()).apply()
        changed.value++
    }

    /**
     * Queue the upload of one session. [manual]: the contributor tapped Upload, so any network will do; automatic
     * uploads follow the Wi-Fi-only setting.
     */
    fun enqueue(ctx: Context, sessionId: String, manual: Boolean) {
        if (!UploadServer.configured(ctx)) return
        val r = record(ctx, sessionId)
        if (r.status == UploadStatus.DONE) return
        save(ctx, r.copy(status = UploadStatus.QUEUED, error = null))
        val anyNetwork = manual || !UploadServer.wifiOnly(ctx)
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf("sessionId" to sessionId))
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(if (anyNetwork) NetworkType.CONNECTED else NetworkType.UNMETERED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // REPLACE: a tap on Retry / Upload now overrides a queued Wi-Fi-only request
        WorkManager.getInstance(ctx).enqueueUniqueWork(name(sessionId),
            if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    /** The session ended: upload it if the contributor asked for automatic uploads. */
    fun onSessionEnded(ctx: Context, sessionId: String) {
        if (!UploadServer.configured(ctx) || !UploadServer.auto(ctx)) return
        if (CaptureService.store(ctx).items(sessionId).isEmpty()) return   // nothing recorded
        enqueue(ctx, sessionId, manual = false)
    }

    /** The session is deleted: stop its upload and forget it (what is on the server stays). */
    fun forget(ctx: Context, sessionId: String) {
        WorkManager.getInstance(ctx).cancelUniqueWork(name(sessionId))
        zipFile(ctx, sessionId).delete()
        sp(ctx).edit().remove(sessionId).apply()
        changed.value++
    }

    fun flow(ctx: Context, sessionId: String): Flow<List<WorkInfo>> =
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(name(sessionId))
}

/**
 * Uploads one session's zip (the same zip as Save / Send, Exporter) to the tus server: create, then PATCH in chunks,
 * the offset saved after each, so a dropped connection or an app restart continues where the server is (HEAD). The
 * zip is kept until the upload is done, so the bytes stay the same across attempts. Never while recording.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val sid = inputData.getString("sessionId") ?: return@withContext Result.failure()
        // a session recording: its camera, GPS and disk come first; try again later
        if (CaptureService.activeSession.value != null) return@withContext Result.retry()
        val store = CaptureService.store(ctx)
        val session = store.load(sid) ?: run { Uploads.forget(ctx, sid); return@withContext Result.failure() }
        var rec = Uploads.record(ctx, sid)
        try {
            val client = UploadServer.client(ctx)
            val zip = Uploads.zipFile(ctx, sid)
            val serverOffset = if (rec.url != null && zip.exists() && zip.length() == rec.size) client.offset(rec.url!!) else null
            var (url, offset) = when (val s = Tus.start(rec, if (zip.exists()) zip.length() else null, serverOffset)) {
                is Tus.Start.Finished -> s.url to rec.size
                is Tus.Start.Continue -> s.url to s.offset
                Tus.Start.Create -> {
                    val part = File(zip.parentFile!!.apply { mkdirs() }, "$sid.zip.part")
                    part.outputStream().use { Exporter.writeZip(store, sid, it) }
                    if (!part.renameTo(zip)) { zip.delete(); if (!part.renameTo(zip)) throw IOException("could not keep the zip") }
                    val photos = store.items(sid).count { it.optString("kind") == "photo" }
                    val u = client.create(zip.length(), sessionUploadMetadata(session, Exporter.fileName(session), photos, store.trackFile(sid)))
                    u to 0L
                }
            }
            val size = zip.length()
            rec = rec.copy(status = UploadStatus.UPLOADING, url = url, size = size, offset = offset, error = null)
            Uploads.save(ctx, rec)
            while (offset < size) {
                if (CaptureService.activeSession.value != null) return@withContext Result.retry()   // a session started
                val len = Tus.chunkLength(offset, size)
                val from = offset
                var reported = 0L
                offset = try {
                    client.patch(url, zip, from, len) { sent ->
                        if (sent - reported >= 256 * 1024) { reported = sent; setProgressAsync(workDataOf("done" to from + sent, "total" to size)) }
                    }
                } catch (e: TusClient.HttpError) {
                    if (e.code != 409) throw e
                    client.offset(url) ?: throw IOException("the server lost the upload")   // offsets disagree: ask
                }
                rec = rec.copy(offset = offset)
                Uploads.save(ctx, rec)
                setProgress(workDataOf("done" to offset, "total" to size))
            }
            Uploads.save(ctx, rec.copy(status = UploadStatus.DONE, offset = size, uploadedAt = iso(System.currentTimeMillis())))
            zip.delete()
            // uploaded = sent: the sessions list stops counting it as not sent
            store.load(sid)?.let { it.exportedAt = iso(System.currentTimeMillis()); store.save(it) }
            Uploads.finished.value++
            Result.success()
        } catch (e: Exception) {
            // the server no longer knows this upload (expired, cleaned up): start a new one next time
            if (e is TusClient.HttpError && (e.code == 404 || e.code == 410)) rec = rec.copy(url = null, offset = 0)
            val permanent = (e as? TusClient.HttpError)?.permanent == true || e is IllegalArgumentException
            val msg = e.message ?: e.javaClass.simpleName
            if (permanent || runAttemptCount >= 6) {
                Uploads.save(ctx, rec.copy(status = UploadStatus.FAILED, error = msg))
                Result.failure()
            } else {
                Uploads.save(ctx, rec.copy(status = UploadStatus.QUEUED, error = msg))
                Result.retry()
            }
        }
    }
}
