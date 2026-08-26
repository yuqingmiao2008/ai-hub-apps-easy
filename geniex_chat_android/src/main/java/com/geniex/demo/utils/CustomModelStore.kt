// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.utils

import android.content.Context
import android.util.Log
import com.geniex.demo.bean.ModelData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromString
import kotlinx.serialization.json.encodeToString

/**
 * Persists user-added models (HF search results and locally imported
 * GGUF files) in SharedPreferences so they survive app restarts and
 * appear in the model spinner alongside the built-in catalog.
 */
class CustomModelStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): MutableList<ModelData> {
        val raw = prefs.getString(KEY_CUSTOM_MODELS, null) ?: return mutableListOf()
        return runCatching {
            json.decodeFromString<List<ModelData>>(raw).toMutableList()
        }.getOrElse {
            Log.e(TAG, "failed to parse custom models: $it")
            mutableListOf()
        }
    }

    fun save(models: List<ModelData>) {
        val custom = models.filter { it.isCustom }
        val raw = json.encodeToString(custom)
        prefs.edit().putString(KEY_CUSTOM_MODELS, raw).apply()
    }

    fun add(model: ModelData): List<ModelData> {
        val all = load()
        if (all.any { it.id == model.id }) {
            Log.w(TAG, "model ${model.id} already exists, replacing")
            all.removeAll { it.id == model.id }
        }
        all.add(model)
        save(all)
        return all
    }

    fun remove(modelId: String): List<ModelData> {
        val all = load()
        all.removeAll { it.id == modelId }
        save(all)
        return all
    }

    companion object {
        private const val TAG = "CustomModelStore"
        private const val PREFS_NAME = "custom_models"
        private const val KEY_CUSTOM_MODELS = "custom_models_json"
    }
}
