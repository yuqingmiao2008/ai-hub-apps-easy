// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.utils

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Hugging Face REST client for searching GGUF model repos and
 * listing their files. Uses HttpURLConnection — no extra dependencies.
 *
 * API docs: https://huggingface.co/docs/api-server
 */
object HuggingFaceApi {
    private const val BASE = "https://huggingface.co/api"
    private const val TAG = "HuggingFaceApi"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class HfModel(
        val id: String,
        val modelId: String? = null,
        val tags: List<String> = emptyList(),
        val downloads: Int? = null,
        val likes: Int? = null,
    )

    @Serializable
    data class HfFile(
        val path: String,
        val size: Long = 0,
        val type: String? = null,
    )

    /**
     * Search Hugging Face for models matching [query], filtered to GGUF
     * repos. Returns at most [limit] results, sorted by downloads desc.
     */
    fun searchModels(query: String, limit: Int = 25): List<HfModel> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val url = "$BASE/models?search=${urlEncode(q)}&filter=gguf&limit=$limit&sort=downloads&direction=-1"
        return runCatching {
            val body = httpGet(url)
            json.decodeFromString<List<HfModel>>(body)
        }.getOrElse {
            Log.e(TAG, "searchModels failed: $it")
            emptyList()
        }
    }

    /**
     * List all files in the main branch of [modelId]. Callers should
     * filter for `.gguf` extensions.
     */
    fun listFiles(modelId: String): List<HfFile> {
        val url = "$BASE/models/${urlEncodePath(modelId)}/tree/main"
        return runCatching {
            val body = httpGet(url)
            json.decodeFromString<List<HfFile>>(body)
        }.getOrElse {
            Log.e(TAG, "listFiles failed for $modelId: $it")
            emptyList()
        }
    }

    /** Return only .gguf files in the repo, sorted by size asc (smallest first). */
    fun listGgufFiles(modelId: String): List<HfFile> =
        listFiles(modelId)
            .filter { it.path.endsWith(".gguf", ignoreCase = true) }
            .sortedBy { it.size }

    /**
     * Direct download URL for a file in a HF repo. Use this with
     * DownloadManager or OkHttp — the API `tree` endpoint only lists.
     */
    fun resolveDownloadUrl(modelId: String, filePath: String): String =
        "https://huggingface.co/${urlEncodePath(modelId)}/resolve/main/${urlEncodePath(filePath)}"

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/json")
        return conn.inputStream.bufferedReader().use { it.readText() }
            .also { conn.disconnect() }
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    private fun urlEncodePath(s: String): String =
        s.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}
