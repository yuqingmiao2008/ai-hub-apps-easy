// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.bean

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

/**
 * Demo-side description of a model that the Rust model manager can
 * download and resolve. Only the fields needed by `geniex_model_pull`
 * and the UI live here — file paths come from `ModelManagerWrapper
 * .getPaths(modelName)` after the pull completes.
 */
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ModelData(
    /** Stable UI key (spinner selection, SharedPreferences). */
    val id: String,
    /** Human-readable label shown in the UI. */
    val displayName: String,
    /**
     * `org/repo` as the Rust hub expects. Not an alias — keep the
     * alias layer (`resolveAlias`) out of the demo for simplicity.
     */
    val modelName: String,
    /** "chat" / "llm" / "vlm". */
    val type: String? = null,
    /**
     * Runtime that owns this model: `"llama_cpp"` (CPU/GPU/NPU via
     * llama.cpp's per-tensor scheduler) or `"qairt"` (NPU-only AI Hub
     * bundle). Drives the compute-unit picker — see [getSupportPluginIds].
     */
    val runtime: String? = null,
    /** Quantization hint passed to `geniex_model_pull`. */
    val quant: String? = null,
    /**
     * Hub name — matches [com.geniex.sdk.bean.HubSource] by enum name.
     * Default `AUTO` lets Rust pick based on `modelName`.
     */
    val hub: String? = "AUTO",
    /** AI Hub display_name; required when [hub] is `AIHUB`. */
    val aiHubDisplayName: String? = null,
    /**
     * Target chipset for AI Hub pulls (e.g. "SM8650"). On Android the
     * Rust side has no auto-detect, so `aiHubDisplayName` entries must
     * pair with an explicit `chipset`.
     */
    val chipset: String? = null,
    /**
     * `true` for models added by the user via HF search or local import.
     * Custom models are persisted separately from the built-in catalog.
     */
    val isCustom: Boolean = false,
    /**
     * Absolute path to a locally imported GGUF file. When non-null the
     * loader bypasses `ModelManagerWrapper.getPaths` and constructs the
     * input directly — the model is already on disk and needs no pull.
     */
    val localPath: String? = null,
    /**
     * Hugging Face repo id (`org/repo`) for models this app downloads
     * itself. Falls back to [modelName], which is already the repo id for
     * every `hub: HUGGINGFACE` entry in `model_list.json`.
     */
    val hfRepo: String? = null,
    /**
     * Exact file inside [hfRepo] to download, e.g. `Qwen3-0.6B-Q4_K_M.gguf`.
     * When null the app resolves one from the repo tree using [quant].
     */
    val hfFile: String? = null,
    /**
     * `mmproj` projector to download next to [hfFile]. Only meaningful for
     * `type == "vlm"`; when null and the model is multimodal, the app picks
     * the smallest `mmproj` found in the repo.
     */
    val mmprojFile: String? = null,
    /** Absolute path of the downloaded projector, filled in at runtime. */
    val mmprojPath: String? = null,
)

/**
 * True when the app should download this model from Hugging Face itself
 * rather than handing the pull to the Rust model manager.
 */
fun ModelData.isSelfDownloaded(): Boolean = hub.equals("HUGGINGFACE", ignoreCase = true)

/** Resolved Hugging Face repo id, falling back to the model name. */
fun ModelData.hfRepoOrName(): String = hfRepo?.takeIf { it.isNotBlank() } ?: modelName

/** "vlm" and "multimodal" both mean a vision-language model here. */
fun ModelData.isVision(): Boolean = type == "vlm" || type == "multimodal"

/**
 * Compute units offered in the picker dialog for this model. QAIRT is
 * NPU-only; llama.cpp exposes all three.
 */
fun ModelData.getSupportPluginIds(): ArrayList<String> =
    when (runtime) {
        "qairt" -> arrayListOf("npu")
        else -> arrayListOf("npu", "gpu", "cpu")
    }

/** True when the model runs on the QAIRT NPU runtime. */
fun ModelData.isNpuModel(): Boolean = runtime == "qairt"
