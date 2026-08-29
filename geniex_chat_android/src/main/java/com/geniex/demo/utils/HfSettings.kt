// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.utils

import android.content.Context

/**
 * Runtime configuration for the in-app Hugging Face client.
 *
 * Two things are configurable:
 *  - **Endpoint** — `huggingface.co` by default, with `hf-mirror.com` as an
 *    automatic fallback. The mirror is a byte-identical read-through proxy
 *    that is reachable from networks where the official host is not; it is
 *    only ever tried after the official host fails, and only when the user
 *    has enabled it.
 *  - **Token** — required for gated / private repos. Sent as
 *    `Authorization: Bearer <token>` on both API and resolve requests.
 *
 * Values are process-wide (`@Volatile` so a background download coroutine
 * sees updates made on the main thread) and persisted in SharedPreferences.
 */
object HfSettings {
    private const val PREFS_NAME = "hf_settings"
    private const val KEY_USE_MIRROR = "use_mirror"
    private const val KEY_TOKEN = "token"
    private const val KEY_REVISION = "revision"

    const val OFFICIAL_ENDPOINT = "https://huggingface.co"
    const val MIRROR_ENDPOINT = "https://hf-mirror.com"
    const val DEFAULT_REVISION = "main"

    /** Try the mirror when the official host fails. */
    @Volatile
    var useMirror: Boolean = false

    /** HF access token for gated / private repos. Empty means anonymous. */
    @Volatile
    var token: String = ""

    /** Branch / tag / commit SHA to download from. */
    @Volatile
    var revision: String = DEFAULT_REVISION

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        useMirror = prefs.getBoolean(KEY_USE_MIRROR, false)
        token = prefs.getString(KEY_TOKEN, "") ?: ""
        revision = prefs.getString(KEY_REVISION, DEFAULT_REVISION)?.ifBlank { DEFAULT_REVISION }
            ?: DEFAULT_REVISION
    }

    fun setUseMirror(
        context: Context,
        enabled: Boolean,
    ) {
        useMirror = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_USE_MIRROR, enabled).apply()
    }

    fun setToken(
        context: Context,
        value: String,
    ) {
        token = value.trim()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token).apply()
    }

    fun setRevision(
        context: Context,
        value: String,
    ) {
        revision = value.trim().ifBlank { DEFAULT_REVISION }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_REVISION, revision).apply()
    }

    /** Base host used for both the REST API and `resolve` downloads. */
    fun endpoint(): String = if (useMirror) MIRROR_ENDPOINT else OFFICIAL_ENDPOINT

    fun apiBase(): String = "${endpoint()}/api"
}
