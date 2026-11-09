package io.github.tewnut.roadcapture.core

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

/**
 * Minimal tus 1.0 client (https://tus.io/protocols/resumable-upload): create an upload, ask its offset, append chunks.
 * Works with tusd and any tus server. Plain HttpURLConnection, which cannot send PATCH: chunks go as POST with
 * `X-HTTP-Method-Override: PATCH` (supported by tusd and the tus spec's recommendations for such clients).
 */
class TusClient(
    private val endpoint: String,
    private val token: String? = null,
    private val chunkBytes: Int = 4 * 1024 * 1024,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    class TusException(val code: Int, msg: String) : IOException(msg)

    private fun conn(url: String, method: String): HttpURLConnection = open(URL(url)).apply {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = 60_000
        setRequestProperty("Tus-Resumable", "1.0.0")
        token?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
    }

    /** New upload of `length` bytes; returns its absolute URL. */
    fun create(length: Long, metadata: Map<String, String>): String {
        val c = conn(endpoint, "POST")
        try {
            c.setRequestProperty("Upload-Length", length.toString())
            c.setRequestProperty("Upload-Metadata", metadata.entries.joinToString(",") { (k, v) ->
                k + " " + Base64.getEncoder().encodeToString(v.toByteArray(Charsets.UTF_8))
            })
            c.doOutput = true
            c.setFixedLengthStreamingMode(0)
            c.outputStream.close()
            val code = c.responseCode
            if (code != 201) throw TusException(code, "tạo upload: HTTP $code")
            val loc = c.getHeaderField("Location") ?: throw TusException(code, "tạo upload: thiếu Location")
            return URL(URL(endpoint), loc).toString()
        } finally { c.disconnect() }
    }

    /** Bytes the server already has, or null when the upload is gone (404/410: start a new one). */
    fun offset(uploadUrl: String): Long? {
        val c = conn(uploadUrl, "HEAD")
        try {
            val code = c.responseCode
            if (code == 404 || code == 410) return null
            if (code !in 200..299) throw TusException(code, "HEAD: HTTP $code")
            return c.getHeaderField("Upload-Offset")?.toLongOrNull() ?: throw TusException(code, "HEAD: thiếu Upload-Offset")
        } finally { c.disconnect() }
    }

    /**
     * Send `file` from `from` until done or until `deadlineMs` (wall clock) passes. Returns the offset reached.
     * A 409 (offset mismatch, e.g. a chunk was received but its answer lost) re-reads the offset and goes on.
     */
    fun upload(uploadUrl: String, file: File, from: Long, deadlineMs: Long, onProgress: (Long) -> Unit = {}): Long {
        var offset = from
        val total = file.length()
        val buf = ByteArray(chunkBytes)
        RandomAccessFile(file, "r").use { raf ->
            while (offset < total && System.currentTimeMillis() < deadlineMs) {
                raf.seek(offset)
                val n = raf.read(buf, 0, minOf(chunkBytes.toLong(), total - offset).toInt())
                val c = conn(uploadUrl, "POST")
                try {
                    c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                    c.setRequestProperty("Content-Type", "application/offset+octet-stream")
                    c.setRequestProperty("Upload-Offset", offset.toString())
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(n)
                    c.outputStream.use { it.write(buf, 0, n) }
                    when (val code = c.responseCode) {
                        204, 200 -> offset = c.getHeaderField("Upload-Offset")?.toLongOrNull() ?: (offset + n)
                        409 -> offset = offset(uploadUrl) ?: throw TusException(code, "upload đã hết hạn")
                        404, 410 -> throw TusException(code, "upload đã hết hạn")
                        else -> throw TusException(code, "PATCH: HTTP $code")
                    }
                } finally { c.disconnect() }
                // the server holds more than this file: the upload is not ours / corrupted, never call it complete
                if (offset > total) throw TusException(410, "máy chủ báo nhận $offset byte, file chỉ có $total: tạo upload mới")
                onProgress(offset)
            }
        }
        return offset
    }
}

/** Upload state of one session, in its own file (upload.json): only the upload worker writes it. */
data class UploadState(
    val state: String = "none",       // none | queued | uploading | uploaded | failed
    val zip: String? = null,          // zip being uploaded (kept byte-identical across retries)
    val zipBytes: Long = 0,
    val sha256: String? = null,
    val url: String? = null,          // tus upload URL
    val offset: Long = 0,
    val uploadedVersion: Long = 0,    // content version (see SessionStore.contentVersion) that was uploaded
    val uploadedAt: String? = null,
    val error: String? = null,
    val updatedAt: String? = null,
) {
    val progress: Double get() = if (zipBytes > 0) offset.toDouble() / zipBytes else 0.0

    fun toJson(): JSONObject = JSONObject().put("state", state).put("zip", zip.orNull()).put("zipBytes", zipBytes)
        .put("sha256", sha256.orNull()).put("url", url.orNull()).put("offset", offset).put("uploadedVersion", uploadedVersion)
        .put("uploadedAt", uploadedAt.orNull()).put("error", error.orNull()).put("updatedAt", updatedAt.orNull())

    companion object {
        fun fromJson(o: JSONObject) = UploadState(
            o.optString("state", "none"), o.optStr("zip"), o.optLong("zipBytes"), o.optStr("sha256"), o.optStr("url"),
            o.optLong("offset"), o.optLong("uploadedVersion"), o.optStr("uploadedAt"), o.optStr("error"), o.optStr("updatedAt"))
    }
}

/**
 * One run of the background upload of a session: build the zip once (in a durable folder, not the cache), create
 * the tus upload, send as much as the time budget allows. The worker calls it again until DONE.
 */
object UploadJob {
    enum class Result { DONE, PARTIAL, RETRY }

    fun run(store: SessionStore, sessionId: String, uploadsDir: File, client: TusClient, deadlineMs: Long,
            extraMeta: Map<String, String> = emptyMap(), nowMs: () -> Long = System::currentTimeMillis,
            onProgress: (UploadState) -> Unit = {}): Result {
        var st = store.uploadState(sessionId)
        fun save(s: UploadState) { st = s.copy(updatedAt = iso(nowMs())); store.saveUploadState(sessionId, st); onProgress(st) }
        val version = store.contentVersion(sessionId)
        if (st.state == "uploaded" && st.uploadedVersion >= version) return Result.DONE
        try {
            // 1. the zip: reuse the one being uploaded, unless the session changed since (then start over)
            var zip = st.zip?.let { File(it) }?.takeIf { it.exists() && it.length() == st.zipBytes && st.uploadedVersion == version }
            if (zip == null) {
                uploadsDir.mkdirs()
                val need = store.dir(sessionId).walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (uploadsDir.usableSpace < need + 50L * 1024 * 1024) {
                    save(st.copy(state = "failed", error = "Không đủ dung lượng để đóng gói (cần ~${need / 1048576} MB)"))
                    return Result.RETRY
                }
                st.zip?.let { File(it).delete() }
                val tmp = File(uploadsDir, "$sessionId.zip.tmp")
                tmp.outputStream().use { Exporter.writeZip(store, sessionId, it) }
                val dst = File(uploadsDir, "$sessionId.zip").also { it.delete() }
                if (!tmp.renameTo(dst)) throw IOException("không đổi tên được file zip")
                zip = dst
                save(UploadState(state = "queued", zip = dst.path, zipBytes = dst.length(), sha256 = sha256(dst), uploadedVersion = version))
            }
            // 2. the tus upload: resume it, or create a new one when it expired
            var url = st.url
            var offset = url?.let { client.offset(it) }?.takeIf { it <= zip.length() }   // more than the file: start over
            if (url == null || offset == null) {
                val session = store.load(sessionId)
                url = client.create(zip.length(), mapOf(
                    "filename" to (session?.let { Exporter.fileName(it) } ?: "$sessionId.zip"),
                    "filetype" to "application/zip", "sessionId" to sessionId, "schema" to SCHEMA,
                    "kind" to (session?.kind?.json ?: "sweep"), "contributor" to (session?.contributor ?: ""),
                    "sha256" to (st.sha256 ?: ""),
                ) + extraMeta)
                offset = 0
            }
            save(st.copy(state = "uploading", url = url, offset = offset, error = null))
            // 3. chunks until done or out of time
            val reached = client.upload(url, zip, offset, deadlineMs) { o -> save(st.copy(offset = o)) }
            if (reached < zip.length()) return Result.PARTIAL
            zip.delete()
            save(st.copy(state = "uploaded", offset = reached, zip = null, uploadedAt = iso(nowMs()), error = null))
            return Result.DONE
        } catch (e: Exception) {
            val expired = e is TusClient.TusException && (e.code == 404 || e.code == 410)
            save(st.copy(state = "failed", url = if (expired) null else st.url, offset = if (expired) 0 else st.offset,
                error = e.message ?: e.javaClass.simpleName))
            return Result.RETRY
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
