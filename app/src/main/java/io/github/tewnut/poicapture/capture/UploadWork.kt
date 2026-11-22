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
import io.github.tewnut.poicapture.core.DirectUpload
import io.github.tewnut.poicapture.core.UploadRecord
import io.github.tewnut.poicapture.core.UploadStatus
import io.github.tewnut.poicapture.core.iso
import io.github.tewnut.poicapture.core.sessionUploadMetadata
import io.github.tewnut.poicapture.net.TusClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
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
    const val DEFAULT_URL = "https://poi-uploader.4hum.ai/files/"
    /** The same Worker's first address (workers.dev): read as [DEFAULT_URL]. */
    private const val OLD_URL = "https://poi-capture-cloud.4hum-ai.workers.dev/files/"

    /** The address as typed; [DEFAULT_URL] until one is typed (a cleared field stays empty: no uploads). */
    fun url(ctx: Context): String = (sp(ctx).getString("url", DEFAULT_URL) ?: "").let {
        if (it.isNotBlank() && Tus.endpoint(it) == Tus.endpoint(OLD_URL)) DEFAULT_URL else it }
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
    /** A session the server holds in full is deleted from the phone, to keep room for the next rides. */
    fun removeAfter(ctx: Context): Boolean = sp(ctx).getBoolean("removeAfter", true)
    fun setRemoveAfter(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("removeAfter", v).apply()

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
    /** Sessions deleted from the phone after their upload ([UploadServer.removeAfter]), as (name, bytes freed). */
    val removed = MutableStateFlow<List<Pair<String, Long>>>(emptyList())

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

/** A capture session started: stop uploading (its camera, GPS and disk come first) and try again later. */
private class CaptureStarted : Exception()

/**
 * Uploads one session's zip (the same zip as Save / Send, Exporter) straight to R2 (server/README.md, "Direct uploads",
 * [DirectUpload]): the server opens the upload and hands out presigned part URLs, [DIRECT_CONNECTIONS] parts go at
 * once, each part's ETag is saved as it lands (a restart sends only the parts still missing), then the server joins
 * them. The zip is kept until the upload is done, so the bytes stay the same across attempts. Never while recording.
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
        val lock = Any()
        // parts upload concurrently: every change to the record goes through here
        fun update(f: (UploadRecord) -> UploadRecord) = synchronized(lock) { rec = f(rec); Uploads.save(ctx, rec) }
        try {
            val client = UploadServer.client(ctx)
            val zip = Uploads.zipFile(ctx, sid)
            var size = if (zip.exists()) zip.length() else -1L

            // go on with the upload an earlier attempt opened for this very zip, or build the zip and open one
            var d = rec.direct?.takeIf { size >= 0 && it.size == size && size == rec.size }
            var urls: Map<Int, String> = emptyMap()
            if (d == null) {
                rec.url?.let { u -> runCatching { client.delete(u) } }   // an unfinished tus upload of an older version
                val part = File(zip.parentFile!!.apply { mkdirs() }, "$sid.zip.part")
                part.outputStream().use { Exporter.writeZip(store, sid, it) }
                if (!part.renameTo(zip)) { zip.delete(); if (!part.renameTo(zip)) throw IOException("could not keep the zip") }
                size = zip.length()
                val metadata = sessionUploadMetadata(session, Exporter.fileName(session),
                    store.items(sid).count { it.optString("kind") == "photo" }, store.trackFile(sid))
                val (opened, first) = client.directCreate(size, metadata)
                d = opened
                urls = first
                update { it.copy(url = null, size = size, offset = 0, direct = opened) }
            }
            val d0 = d
            val progress = Progress(this@UploadWorker, size, DIRECT_CONNECTIONS)
            fun onServer() = rec.direct?.sent ?: 0L
            progress.landed(0, onServer())
            if (!d0.done) abortOnCancel(client) {
                val queue = ConcurrentLinkedQueue(d0.pending)
                val known = ConcurrentHashMap(urls)
                (0 until DIRECT_CONNECTIONS).map { slot -> async {
                    while (true) {
                        val n = queue.poll() ?: break
                        val etag = putPart(client, zip, d0, n, known) { progress.sent(slot, it, onServer()) }
                        update { r -> (r.direct ?: d0).with(n, etag).let { x -> r.copy(direct = x, offset = x.sent) } }
                        progress.landed(slot, onServer())
                    }
                } }.awaitAll()
            }
            client.directComplete(rec.direct!!)
            update { it.copy(status = UploadStatus.DONE, url = d0.location, offset = size, error = null, uploadedAt = iso(System.currentTimeMillis())) }
            zip.delete()
            // uploaded = sent: the sessions list stops counting it as not sent
            store.load(sid)?.let { it.exportedAt = iso(System.currentTimeMillis()); store.save(it) }
            // free the phone: only once the server says it holds every byte, and never the session being recorded
            if (UploadServer.removeAfter(ctx) && CaptureService.activeSession.value != sid &&
                runCatching { client.offset(d0.location) }.getOrNull() == size) {
                val name = store.load(sid)?.name ?: sid
                val bytes = store.dir(sid).walkTopDown().filter { it.isFile }.sumOf { it.length() }
                store.delete(sid)
                Uploads.forget(ctx, sid)
                Uploads.removed.value = Uploads.removed.value + (name to bytes)
            }
            Uploads.finished.value++
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CaptureStarted) {
            Result.retry()
        } catch (e: Exception) {
            // the server (or R2) no longer knows the upload, or could not join it: a new one next time
            if (e is TusClient.HttpError && (e.code == 404 || e.code == 409)) update { it.copy(direct = null, offset = 0) }
            val permanent = (e as? TusClient.HttpError)?.let { it.permanent && it.code != 409 } == true || e is IllegalArgumentException
            val msg = e.message ?: e.javaClass.simpleName
            if (permanent || runAttemptCount >= 6) {
                update { it.copy(status = UploadStatus.FAILED, error = msg) }
                Result.failure()
            } else {
                update { it.copy(status = UploadStatus.QUEUED, error = msg) }
                Result.retry()
            }
        }
    }

    /**
     * Live progress for the sessions list: the parts R2 has plus what each connection has sent of its current part;
     * reported every 256 KiB.
     */
    private class Progress(val worker: CoroutineWorker, val total: Long, n: Int) {
        private val inFlight = AtomicLongArray(n)
        private val reported = AtomicLong(-1)
        fun sent(i: Int, bytes: Long, onServer: Long) { inFlight.set(i, bytes); report(onServer, false) }
        fun landed(i: Int, onServer: Long) { inFlight.set(i, 0); report(onServer, true) }
        private fun report(onServer: Long, always: Boolean) {
            var live = 0L
            for (k in 0 until inFlight.length()) live += inFlight.get(k)
            val done = (onServer + live).coerceAtMost(total)
            val last = reported.get()
            if (always || done - last >= 256 * 1024 || done < last) {
                reported.set(done)
                worker.setProgressAsync(workDataOf("done" to done, "total" to total))
            }
        }
    }

    /**
     * PUT part [n] of [d] to R2; a failed PUT is tried again (with a new URL after 403: it expired), up to
     * [MAX_FAILURES] in a row. [urls]: the presigned URLs known so far, asked for in batches as needed. Returns the ETag.
     */
    private suspend fun putPart(client: TusClient, zip: File, d: DirectUpload, n: Int, urls: ConcurrentHashMap<Int, String>,
                                onSent: (Long) -> Unit): String {
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (CaptureService.activeSession.value != null) throw CaptureStarted()
            val url = urls[n] ?: run {
                val ask = (listOf(n) + d.pending.filter { it != n && !urls.containsKey(it) }).take(200)
                urls.putAll(client.directUrls(d.id, ask))
                urls[n] ?: throw IOException("no URL for part $n")
            }
            try {
                // up to 8 MiB a part; the timeout is the wait for R2's answer once the body is sent
                return client.putPart(url, zip, d.start(n), d.length(n), timeoutMs = 120_000, onSent = onSent)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (e is TusClient.HttpError && e.code == 404) throw e       // R2 forgot the upload: start again
                if (e is TusClient.HttpError && e.code == 403) urls.remove(n)
                if (++failures > MAX_FAILURES) throw e
            }
        }
    }

    /** Runs [block]; if this work is cancelled (or a sibling part fails) the requests in flight are cut at once. */
    private suspend fun <T> abortOnCancel(client: TusClient, block: suspend CoroutineScope.() -> T): T = coroutineScope {
        val watcher = launch { try { awaitCancellation() } finally { client.abortAll() } }
        try { block() } finally { watcher.cancel() }
    }

    private companion object {
        const val MAX_FAILURES = 4
        /** Parts sent at once to R2 (measured: 4 connections ~3x one). */
        const val DIRECT_CONNECTIONS = 4
    }
}
