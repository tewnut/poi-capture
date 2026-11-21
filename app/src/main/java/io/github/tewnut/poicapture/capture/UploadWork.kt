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
import io.github.tewnut.poicapture.core.ChunkSizer
import io.github.tewnut.poicapture.core.Exporter
import io.github.tewnut.poicapture.core.Tus
import io.github.tewnut.poicapture.core.UploadPart
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

/** A capture session started: stop uploading (its camera, GPS and disk come first) and try again later. */
private class CaptureStarted : Exception()

/**
 * Uploads one session's zip (the same zip as Save / Send, Exporter) to the tus server, the progress saved after each
 * PATCH so a dropped connection or an app restart continues where the server is (HEAD). The zip is kept until the
 * upload is done, so the bytes stay the same across attempts. Never while recording.
 *
 * A zip over [Tus.PARALLEL_ABOVE] goes to a server with `concatenation` as up to [Tus.MAX_PARTS] partial uploads sent
 * at once (Cloudflare throttles each connection), then one final request joins them (server/README.md, "Parallel
 * uploads"); otherwise as one upload. Each connection sizes its PATCHes from its measured speed ([ChunkSizer]).
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
            fun metadata() = sessionUploadMetadata(session, Exporter.fileName(session),
                store.items(sid).count { it.optString("kind") == "photo" }, store.trackFile(sid))
            fun parallel(size: Long): Boolean = size > Tus.PARALLEL_ABOVE && try { client.options().canConcat } catch (e: TusClient.HttpError) {
                if (e.code == 401 || e.code == 403) throw e
                false                                   // no usable OPTIONS: upload in one part
            }

            // what an earlier attempt left: the same zip and the server still has it
            var size = if (zip.exists()) zip.length() else -1L
            val sameZip = size >= 0 && size == rec.size
            var single: Pair<String, Long>? = null      // url, offset
            var parts: List<UploadPart>? = null
            var finished = false
            if (sameZip && Tus.partsFit(rec.parts, size)) {
                val fin = rec.url                       // the final upload, when it was created already
                if (fin != null && client.offset(fin) == size) finished = true else parts = rec.parts
            } else if (sameZip && rec.url != null) {
                when (val s = Tus.start(rec, size, client.offset(rec.url!!))) {
                    is Tus.Start.Finished -> finished = true
                    is Tus.Start.Continue ->
                        if (parallel(size) && !Tus.keepSingle(s.offset, size)) runCatching { client.delete(s.url) }   // faster in parts
                        else single = s.url to s.offset
                    Tus.Start.Create -> {}
                }
            }
            if (!finished && single == null && parts == null) {
                // a new upload: build the zip, then one upload or partials
                val part = File(zip.parentFile!!.apply { mkdirs() }, "$sid.zip.part")
                part.outputStream().use { Exporter.writeZip(store, sid, it) }
                if (!part.renameTo(zip)) { zip.delete(); if (!part.renameTo(zip)) throw IOException("could not keep the zip") }
                size = zip.length()
                if (parallel(size)) {
                    val fresh = Tus.splitRanges(size)
                    parts = fresh
                    update { it.copy(url = null, size = size).withParts(fresh) }
                } else {
                    val u = client.create(size, metadata())
                    single = u to 0L
                    update { it.copy(url = u, size = size, offset = 0, parts = emptyList()) }
                }
            }
            update { it.copy(status = UploadStatus.UPLOADING, size = size, error = null) }

            if (!finished) {
                val progress = Progress(this@UploadWorker, size, parts?.size ?: 1)
                val one = single
                if (one != null) {
                    update { it.copy(url = one.first, offset = one.second, parts = emptyList()) }
                    abortOnCancel(client) {
                        pump(client, one.first, zip, 0, size, one.second, { progress.sent(0, it, rec.offset) }) { off ->
                            update { it.copy(offset = off) }
                            progress.landed(0, rec.offset)
                        }
                    }
                } else {
                    abortOnCancel(client) {
                        parts!!.indices.map { i -> async { uploadPart(client, zip, i, { synchronized(lock) { rec.parts[i] } }, progress,
                            { rec.offset }) { p -> update { r -> r.withParts(r.parts.toMutableList().also { it[i] = p }) } } } }.awaitAll()
                    }
                    // every partial complete: join them
                    val urls = rec.parts.map { it.url!! }
                    val fin = try { client.createFinal(urls, metadata()) } catch (e: TusClient.HttpError) {
                        if (e.code != 409) throw e
                        // the partials are taken: done only if our own final upload is there, else start again
                        val stored = rec.url
                        if (stored != null && client.offset(stored) == size) stored
                        else {
                            update { it.copy(url = null).withParts(emptyList()) }
                            throw IOException("the parts were already joined; starting again")
                        }
                    }
                    update { it.copy(url = fin, offset = size) }
                }
            }
            update { it.copy(status = UploadStatus.DONE, offset = size, error = null, uploadedAt = iso(System.currentTimeMillis())) }
            zip.delete()
            // uploaded = sent: the sessions list stops counting it as not sent
            store.load(sid)?.let { it.exportedAt = iso(System.currentTimeMillis()); store.save(it) }
            Uploads.finished.value++
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CaptureStarted) {
            Result.retry()
        } catch (e: Exception) {
            // the server no longer knows this (single) upload (expired, cleaned up): start a new one next time
            if (e is TusClient.HttpError && (e.code == 404 || e.code == 410) && rec.parts.isEmpty())
                synchronized(lock) { rec = rec.copy(url = null, offset = 0) }
            val permanent = (e as? TusClient.HttpError)?.permanent == true || e is IllegalArgumentException
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
     * Live progress for the sessions list: bytes on the server (the record's offset) plus what each connection has
     * sent in its current PATCH; reported every 256 KiB.
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
     * One partial: HEAD it (or create it, also when the server forgot it), then send its range. [get] / [set] read and
     * store the part in the record; [onServer] the record's total offset (for progress).
     */
    private suspend fun uploadPart(client: TusClient, zip: File, i: Int, get: () -> UploadPart, progress: Progress,
                                   onServer: () -> Long, set: (UploadPart) -> Unit) {
        var recreated = 0
        while (true) {
            var p = get()
            if (p.url != null) {
                val off = client.offset(p.url!!)
                p = if (off == null || off > p.length) p.copy(url = null, offset = 0) else p.copy(offset = off)
            }
            if (p.url == null) p = p.copy(url = client.createPartial(p.length), offset = 0)
            set(p)
            progress.landed(i, onServer())
            if (p.done) return
            val url = p.url!!
            try {
                pump(client, url, zip, p.start, p.length, p.offset, { progress.sent(i, it, onServer()) }) { off ->
                    set(get().copy(offset = off))
                    progress.landed(i, onServer())
                }
                return
            } catch (e: TusClient.HttpError) {
                // the partial expired meanwhile: a new one for this range (once or twice), else let the worker retry
                if ((e.code != 404 && e.code != 410) || ++recreated > 2) throw e
                set(get().copy(url = null, offset = 0))
            }
        }
    }

    /**
     * PATCH bytes [base] + [from] until [base] + [length] of [zip] to [url] (upload offsets count from 0), sized by a
     * [ChunkSizer]. A PATCH that times out or fails asks the server where it is (HEAD), shrinks the next one and goes on;
     * after [MAX_FAILURES] failures in a row the error goes to the worker (which retries later). 404 / 410 / permanent
     * errors are thrown at once. [onOffset] gets the server's offset after each PATCH.
     */
    private suspend fun pump(client: TusClient, url: String, zip: File, base: Long, length: Long, from: Long,
                             onSent: (Long) -> Unit, onOffset: (Long) -> Unit) {
        val sizer = ChunkSizer()
        var offset = from
        var failures = 0
        while (offset < length) {
            currentCoroutineContext().ensureActive()
            if (CaptureService.activeSession.value != null) throw CaptureStarted()
            val len = sizer.next(length - offset)
            val t0 = System.nanoTime()
            val next = try {
                client.patch(url, zip, offset, len, fileOffset = base + offset, timeoutMs = sizer.timeoutMs(len), onSent = onSent)
                    .also { sizer.sent(it - offset, (System.nanoTime() - t0) / 1_000_000); failures = 0 }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()            // cut off because the work is stopping
                if (e is TusClient.HttpError && (e.permanent || e.code == 404 || e.code == 410)) throw e
                if (++failures > MAX_FAILURES) throw e
                if (!(e is TusClient.HttpError && e.code == 409)) sizer.failed()   // 409: offsets disagree, not slow
                client.offset(url) ?: throw TusClient.HttpError(404, "HEAD")
            }
            if (next < 0 || next > length) throw IOException("the server's offset is out of range")
            offset = next
            onOffset(offset)
        }
    }

    /** Runs [block]; if this work is cancelled (or a sibling part fails) the requests in flight are cut at once. */
    private suspend fun <T> abortOnCancel(client: TusClient, block: suspend CoroutineScope.() -> T): T = coroutineScope {
        val watcher = launch { try { awaitCancellation() } finally { client.abortAll() } }
        try { block() } finally { watcher.cancel() }
    }

    private companion object {
        const val MAX_FAILURES = 4
    }
}
