// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.utils

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Hugging Face REST client for searching GGUF model repos and
 * listing their files. Uses HttpURLConnection — no extra dependencies.
 *
 * Every network call returns a [Result] so callers can surface the real
 * reason (offline, 401 gated, 404, ...) instead of an empty list.
 * The endpoint and credentials come from [HfSettings], which also decides
 * whether the mirror may be used as a fallback.
 *
 * API docs: https://huggingface.co/docs/api-server
 */
object HuggingFaceApi {
    private const val TAG = "HuggingFaceApi"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    private val json = Json { ignoreUnknownKeys = true }

    // -----------------------------------------------------------------
    // Models
    // -----------------------------------------------------------------

    @Serializable
    data class HfModel(
        val id: String = "",
        val modelId: String? = null,
        val tags: List<String> = emptyList(),
        val downloads: Int? = null,
        val likes: Int? = null,
        val gated: Boolean? = null,
        val lastModified: String? = null,
        @SerialName("private") val isPrivate: Boolean? = null,
    )

    // -----------------------------------------------------------------
    // Files
    // -----------------------------------------------------------------

    /**
     * One entry of the repo tree. `size` is the **pointer** size for files
     * stored through git-LFS (typically ~130 bytes) — the real byte count
     * lives in [lfs], so always read [effectiveSize].
     */
    @Serializable
    data class HfFile(
        val path: String = "",
        val type: String? = null,
        val size: Long = 0,
        val oid: String? = null,
        val lfs: HfLfs? = null,
    ) {
        val effectiveSize: Long get() = lfs?.size?.takeIf { it > 0 } ?: size

        fun isGguf(): Boolean = path.endsWith(".gguf", ignoreCase = true)

        /** Vision projector shipped next to multimodal weights. */
        fun isMmproj(): Boolean =
            isGguf() && path.substringAfterLast('/').contains("mmproj", ignoreCase = true)

        fun isDirectory(): Boolean = type == "directory" || path.endsWith('/')
    }

    @Serializable
    data class HfLfs(
        val oid: String? = null,
        val size: Long = 0,
        val pointerSize: Long = 0,
    )

    /** Metadata read from a `HEAD` on a resolve URL. */
    data class FileMeta(
        val contentLength: Long,
        val acceptRanges: Boolean,
        val etag: String?,
        val lastModified: String?,
    )

    // -----------------------------------------------------------------
    // Search / listing
    // -----------------------------------------------------------------

    /**
     * Search Hugging Face for GGUF repos matching [query], most downloaded
     * first.
     */
    fun searchModels(
        query: String,
        limit: Int = 30,
    ): Result<List<HfModel>> {
        val q = query.trim()
        if (q.isEmpty()) return Result.success(emptyList())
        val url =
            "${HfSettings.apiBase()}/models" +
                "?search=${urlEncode(q)}&filter=gguf&limit=$limit&sort=downloads&direction=-1"
        return runCatching {
            json.decodeFromString<List<HfModel>>(httpGet(url))
        }.recoverCatching { throw wrap("search failed", it) }
    }

    /** Metadata for a single repo (gated flag, tags, siblings, ...). */
    fun getModelInfo(
        repoId: String,
        revision: String = HfSettings.revision,
    ): Result<HfModel> {
        val url = "${HfSettings.apiBase()}/models/${urlEncodePath(repoId)}?revision=${urlEncode(revision)}"
        return runCatching {
            json.decodeFromString<HfModel>(httpGet(url))
        }.recoverCatching { throw wrap("model info failed for $repoId", it) }
    }

    /**
     * List every file in [repoId] at [revision], including files nested in
     * sub-directories (`recursive=true`).
     */
    fun listFiles(
        repoId: String,
        revision: String = HfSettings.revision,
    ): Result<List<HfFile>> {
        val url =
            "${HfSettings.apiBase()}/models/${urlEncodePath(repoId)}/tree/${urlEncode(revision)}" +
                "?recursive=true&expand=true"
        return runCatching {
            json.decodeFromString<List<HfFile>>(httpGet(url))
                .filter { !it.isDirectory() }
        }.recoverCatching { throw wrap("listFiles failed for $repoId", it) }
    }

    /** Weight files (GGUF, excluding vision projectors), smallest first. */
    fun listGgufFiles(
        repoId: String,
        revision: String = HfSettings.revision,
    ): Result<List<HfFile>> =
        listFiles(repoId, revision).map { files ->
            files.filter { it.isGguf() && !it.isMmproj() }.sortedBy { it.effectiveSize }
        }

    /** Candidate `mmproj` projectors for a multimodal repo, smallest first. */
    fun listMmprojFiles(
        repoId: String,
        revision: String = HfSettings.revision,
    ): Result<List<HfFile>> =
        listFiles(repoId, revision).map { files ->
            files.filter { it.isMmproj() }.sortedBy { it.effectiveSize }
        }

    // -----------------------------------------------------------------
    // Download URLs
    // -----------------------------------------------------------------

    /**
     * Direct download URL for a file in a HF repo, at an explicit revision.
     * Pinning the revision (instead of `main` being resolved server-side)
     * keeps resume safe: a resumed byte range must come from the same blob.
     */
    fun resolveDownloadUrl(
        repoId: String,
        filePath: String,
        revision: String = HfSettings.revision,
        endpoint: String = HfSettings.endpoint(),
    ): String =
        "$endpoint/${urlEncodePath(repoId)}/resolve/${urlEncode(revision)}/${urlEncodePath(filePath)}"

    /**
     * Ordered list of URLs to try: the configured endpoint first, then the
     * other one as a fallback. Both hosts serve byte-identical content for
     * a given repo / revision / path, so a resumed download can safely
     * switch hosts part way through.
     */
    fun downloadUrls(
        repoId: String,
        filePath: String,
        revision: String = HfSettings.revision,
    ): List<String> {
        val primary = HfSettings.endpoint()
        val fallback =
            if (primary == HfSettings.OFFICIAL_ENDPOINT) {
                HfSettings.MIRROR_ENDPOINT
            } else {
                HfSettings.OFFICIAL_ENDPOINT
            }
        return listOf(
            resolveDownloadUrl(repoId, filePath, revision, primary),
            resolveDownloadUrl(repoId, filePath, revision, fallback),
        )
    }

    /**
     * `HEAD` a resolve URL to learn the real size and whether the server
     * honours `Range` requests (needed for resumable downloads).
     *
     * LFS-backed files report their size in `X-Linked-Size`; `Content-Length`
     * on the pre-redirect response is only the pointer.
     */
    fun headFile(url: String): Result<FileMeta> =
        runCatching {
            val conn = openConnection(url)
            try {
                conn.requestMethod = "HEAD"
                conn.setRequestProperty("Accept-Encoding", "identity")
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IOException("HTTP $code for $url")
                }
                FileMeta(
                    contentLength =
                        conn.getHeaderField("X-Linked-Size")?.toLongOrNull()
                            ?: conn.getHeaderFieldLong("Content-Length", -1L),
                    acceptRanges =
                        conn.getHeaderField("Accept-Ranges")
                            ?.equals("bytes", ignoreCase = true) == true,
                    etag = conn.getHeaderField("ETag"),
                    lastModified = conn.getHeaderField("Last-Modified"),
                )
            } finally {
                conn.disconnect()
            }
        }.recoverCatching { throw wrap("HEAD failed", it) }

    // -----------------------------------------------------------------
    // HTTP helpers
    // -----------------------------------------------------------------

    internal fun openConnection(urlStr: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "geniex-chat-android/1.0")
        // HF ignores an empty bearer token but rejects a malformed one, so
        // only send it when the user actually configured one.
        HfSettings.token.takeIf { it.isNotBlank() }?.let {
            conn.setRequestProperty("Authorization", "Bearer $it")
        }
        return conn
    }

    private fun httpGet(urlStr: String): String {
        val conn = openConnection(urlStr)
        return try {
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                val detail = body?.take(200)?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
                throw IOException("HTTP $code$detail")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun wrap(
        context: String,
        cause: Throwable,
    ): IOException {
        Log.e(TAG, "$context: $cause")
        return if (cause is IOException) cause else IOException("$context: ${cause.message}", cause)
    }

    internal fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** URL-encodes each path segment so `org/repo` and `dir/file.gguf` survive. */
    internal fun urlEncodePath(s: String): String =
        s.split("/").joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
}
