// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.utils

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import kotlin.coroutines.coroutineContext

/**
 * Resumable HTTP downloader used to pull GGUF weights straight from
 * Hugging Face into app-private storage.
 *
 * Design notes:
 *  - **Resume** — bytes are appended to a `.part` file and the request
 *    carries `Range: bytes=<n>-`. A cancelled or failed download keeps its
 *    partial file, so retrying resumes instead of starting over.
 *  - **Cancellation** — cooperative: the read loop checks the coroutine
 *    context, and [Progress] callbacks may return `false` to abort.
 *  - **Atomicity** — the file only appears at its final path once the byte
 *    count matches what the server announced, so a truncated download is
 *    never mistaken for a complete model.
 *  - **Fallback** — [downloadWithFallback] walks a list of equivalent URLs
 *    (official host, then mirror) and keeps the partial file across them.
 */
object HfDownloader {
    private const val TAG = "HfDownloader"
    private const val BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_INTERVAL_MS = 400L

    /**
     * Guards against trusting a git-LFS *pointer* size (~130 bytes) as the
     * real file size when deciding whether a download already finished.
     */
    private const val MIN_PLAUSIBLE_MODEL_BYTES = 1024 * 1024L

    /** Progress snapshot for one download. [totalBytes] is -1 when unknown. */
    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
    ) {
        /** 0..100, or -1 when the total size is unknown. */
        val percent: Int
            get() = if (totalBytes > 0) ((downloadedBytes * 100L) / totalBytes).toInt() else -1

        /** Remaining seconds, or -1 when unknown. */
        val etaSeconds: Long
            get() =
                if (bytesPerSecond > 0 && totalBytes > downloadedBytes) {
                    (totalBytes - downloadedBytes) / bytesPerSecond
                } else {
                    -1L
                }
    }

    /**
     * Downloads [dest] from the first URL that works.
     *
     * @param urls equivalent URLs, tried in order (see [HuggingFaceApi.downloadUrls]).
     * @param expectedBytes size reported by the repo tree, used to detect a
     *   complete download and to validate the result. Values below 1 MB are
     *   ignored because git-LFS pointers report a bogus ~130 bytes.
     */
    suspend fun downloadWithFallback(
        urls: List<String>,
        dest: File,
        token: String? = null,
        expectedBytes: Long? = null,
        onProgress: suspend (Progress) -> Boolean = { true },
    ): File {
        require(urls.isNotEmpty()) { "no download url" }
        var lastError: Throwable? = null
        for (url in urls) {
            try {
                return download(url, dest, token, expectedBytes, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "download via $url failed: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IOException("download failed: ${urls.first()}")
    }

    /**
     * Downloads [url] into [dest], resuming from an existing `.part` file.
     *
     * @throws IOException on HTTP / network / size-mismatch failure.
     * @throws CancellationException when the coroutine is cancelled or
     *   [onProgress] returns false. The partial file is kept.
     */
    suspend fun download(
        url: String,
        dest: File,
        token: String? = null,
        expectedBytes: Long? = null,
        onProgress: suspend (Progress) -> Boolean = { true },
    ): File =
        withContext(Dispatchers.IO) {
            val expected = expectedBytes?.takeIf { it >= MIN_PLAUSIBLE_MODEL_BYTES }
            val dir = dest.parentFile ?: throw IOException("no parent dir for ${dest.absolutePath}")
            if (!dir.exists() && !dir.mkdirs()) throw IOException("cannot create ${dir.absolutePath}")

            val part = File(dir, "${dest.name}.part")

            // Already fully downloaded.
            if (dest.exists() && dest.length() > 0 && (expected == null || dest.length() == expected)) {
                return@withContext dest
            }
            // Finished writing but never renamed (killed mid-rename).
            if (part.exists() && expected != null && part.length() == expected && rename(part, dest)) {
                return@withContext dest
            }

            // A 416 means the server no longer has the bytes we asked for —
            // drop the partial file and retry once from zero.
            var restart = false
            for (attempt in 0..1) {
                var startBytes = part.length()
                if (restart) {
                    part.delete()
                    startBytes = 0L
                }
                val conn = HuggingFaceApi.openConnection(url)
                conn.setRequestProperty("Accept", "*/*")
                token?.takeIf { it.isNotBlank() }
                    ?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                try {
                    if (startBytes > 0) conn.setRequestProperty("Range", "bytes=$startBytes-")
                    conn.connect()
                    when (val code = conn.responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            // Server refused the range — start over.
                            if (startBytes > 0) {
                                part.delete()
                                startBytes = 0L
                            }
                        }

                        HttpURLConnection.HTTP_PARTIAL -> Unit // resuming

                        416 -> {
                            if (attempt == 0 && startBytes > 0) {
                                restart = true
                                continue
                            }
                            throw IOException("HTTP 416 — cannot resume $url")
                        }

                        HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN ->
                            throw IOException(
                                "HTTP $code — this repo is gated or private. Add a Hugging Face access token.",
                            )

                        HttpURLConnection.HTTP_NOT_FOUND ->
                            throw IOException("HTTP 404 — file not found: $url")

                        else -> throw IOException("HTTP $code for $url")
                    }

                    val total = resolveTotalSize(conn, startBytes, expected)
                    copyBody(conn, part, startBytes, total, onProgress)
                    verify(part, total, expected)
                    if (!rename(part, dest)) {
                        throw IOException("could not move download into place: ${dest.absolutePath}")
                    }
                    return@withContext dest
                } finally {
                    conn.disconnect()
                }
            }
            throw IOException("download failed: $url")
        }

    private fun resolveTotalSize(
        conn: HttpURLConnection,
        startBytes: Long,
        expected: Long?,
    ): Long {
        // LFS files: Content-Length on the resolve response is the pointer,
        // the real size is in X-Linked-Size.
        val linked = conn.getHeaderField("X-Linked-Size")?.toLongOrNull()
        if (linked != null && linked > 0) return linked
        val contentLength = conn.getHeaderFieldLong("Content-Length", -1L)
        if (contentLength > 0) return startBytes + contentLength
        return expected ?: -1L
    }

    private suspend fun copyBody(
        conn: HttpURLConnection,
        part: File,
        startBytes: Long,
        total: Long,
        onProgress: suspend (Progress) -> Boolean,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var downloaded = startBytes
        var windowStart = System.currentTimeMillis()
        var windowBytes = startBytes

        RandomAccessFile(part, "rw").use { out ->
            out.seek(startBytes)
            conn.inputStream.use { input ->
                while (true) {
                    if (!coroutineContext.isActive) throw CancellationException("download cancelled")
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    downloaded += read

                    val now = System.currentTimeMillis()
                    val elapsed = now - windowStart
                    if (elapsed >= PROGRESS_INTERVAL_MS) {
                        val speed = (downloaded - windowBytes) * 1000L / elapsed
                        windowStart = now
                        windowBytes = downloaded
                        if (!onProgress(Progress(downloaded, total, speed))) {
                            out.fd.sync()
                            throw CancellationException("download aborted by caller")
                        }
                    }
                }
            }
            out.fd.sync()
        }

        val elapsed = (System.currentTimeMillis() - windowStart).coerceAtLeast(1)
        val speed = ((downloaded - windowBytes) * 1000L / elapsed).coerceAtLeast(0)
        if (!onProgress(Progress(downloaded, total, speed))) {
            throw CancellationException("download aborted by caller")
        }
    }

    private fun verify(
        part: File,
        total: Long,
        expected: Long?,
    ) {
        val size = part.length()
        if (total > 0 && size != total) {
            throw IOException("incomplete download: $size of $total bytes")
        }
        if (expected != null && size != expected) {
            throw IOException("size mismatch: got $size, repo tree says $expected")
        }
    }

    private fun rename(
        part: File,
        dest: File,
    ): Boolean {
        if (dest.exists()) dest.delete()
        if (part.renameTo(dest)) return true
        // Cross-device / busy target: fall back to a copy.
        return try {
            part.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            part.delete()
            dest.exists()
        } catch (e: IOException) {
            Log.w(TAG, "rename fallback failed: ${e.message}")
            false
        }
    }

    /** Deletes a half-finished download so the next attempt starts clean. */
    fun discardPartial(dest: File) {
        File(dest.parentFile, "${dest.name}.part").delete()
    }
}
