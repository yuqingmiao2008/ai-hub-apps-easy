// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.utils

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where a Hugging Face model ended up on disk, once the app downloaded it.
 *
 * Keyed by model id so it works for both built-in catalog entries (whose
 * JSON copy in `model_list.json` is read-only) and user-added models.
 */
@Serializable
data class HfLocalEntry(
    /** Absolute path of the GGUF weights. */
    val modelPath: String,
    /** Absolute path of the `mmproj` projector, for multimodal models. */
    val mmprojPath: String? = null,
    /** `org/repo` the file came from — used to avoid re-resolving later. */
    val repo: String = "",
    /** File path inside the repo. */
    val file: String = "",
    val mmprojFile: String? = null,
    /** Revision the bytes were downloaded from. */
    val revision: String = HfSettings.DEFAULT_REVISION,
    /** Size in bytes, for the "already downloaded" check. */
    val sizeBytes: Long = 0,
) {
    fun isComplete(): Boolean =
        File(modelPath).exists() && File(modelPath).length() > 0 &&
            (mmprojPath == null || File(mmprojPath).exists())
}

/**
 * Persists the on-disk location of self-downloaded Hugging Face models so
 * they survive process death and app restarts.
 */
class HfLocalStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun get(modelId: String): HfLocalEntry? {
        val raw = prefs.getString(modelId, null) ?: return null
        val entry =
            runCatching { json.decodeFromString<HfLocalEntry>(raw) }.getOrNull() ?: return null
        // The file can vanish (cache cleared, uninstall, SD swap) — treat a
        // missing file as "not downloaded" rather than handing a dead path
        // to the native loader.
        return entry.takeIf { it.isComplete() }
    }

    fun put(
        modelId: String,
        entry: HfLocalEntry,
    ) {
        prefs.edit().putString(modelId, json.encodeToString(entry)).apply()
    }

    /**
     * Forgets [modelId] and, by default, deletes the downloaded files so
     * the user reclaims the space immediately.
     */
    fun remove(
        modelId: String,
        deleteFiles: Boolean = true,
    ) {
        if (deleteFiles) {
            get(modelId)?.let { entry ->
                runCatching { File(entry.modelPath).delete() }
                entry.mmprojPath?.let { runCatching { File(it).delete() } }
            }
        }
        prefs.edit().remove(modelId).apply()
    }

    fun all(): Map<String, HfLocalEntry> =
        prefs.all.mapNotNull { (key, value) ->
            val raw = value as? String ?: return@mapNotNull null
            val entry = runCatching { json.decodeFromString<HfLocalEntry>(raw) }.getOrNull()
            entry?.let { key to it }
        }.toMap()

    /**
     * Drops entries whose files no longer exist, and deletes orphaned
     * `.part` files under [rootDir] that have no matching entry.
     */
    fun gc(rootDir: File) {
        val stale =
            prefs.all.keys.filterIsInstance<String>().filter { key ->
                val raw = prefs.getString(key, null) ?: return@filter true
                val entry = runCatching { json.decodeFromString<HfLocalEntry>(raw) }.getOrNull()
                entry == null || !entry.isComplete()
            }
        if (stale.isNotEmpty()) {
            val edit = prefs.edit()
            stale.forEach { edit.remove(it) }
            edit.apply()
        }
        if (!rootDir.exists()) return
        val liveNames =
            all().values.flatMap { listOfNotNull(it.modelPath, it.mmprojPath) }
                .map { File(it).name }.toSet()
        rootDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".part") }
            .forEach { part ->
                val finalName = part.name.removeSuffix(".part")
                if (finalName in liveNames) part.delete()
            }
    }

    companion object {
        private const val PREFS_NAME = "hf_local_models"

        /** Root directory for self-downloaded models: `<filesDir>/hf_models`. */
        fun rootDir(context: Context): File = File(context.filesDir, "hf_models")

        /**
         * Destination file for one repo file, laid out as
         * `<root>/<org>_<repo>/<revision>/<file>`.
         */
        fun destination(
            context: Context,
            repo: String,
            revision: String,
            filePath: String,
        ): File {
            val safeRepo = repo.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val safeRevision = revision.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return File(rootDir(context), "$safeRepo/$safeRevision/${filePath.substringAfterLast('/')}")
        }
    }
}
