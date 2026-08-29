// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.utils

/**
 * Quantization labels are embedded in GGUF filenames
 * (`Qwen3-0.6B-Q4_K_M.gguf`) rather than stored as metadata, so picking the
 * right file inside a repo means parsing the name. Longest-first matching
 * keeps `Q5_K_M` from collapsing into `Q5_K_S`.
 */
object HfQuant {
    private val KNOWN =
        listOf(
            "Q8_0",
            "Q6_K",
            "Q5_K_M",
            "Q5_K_S",
            "Q5_0",
            "Q5_1",
            "Q4_K_M",
            "Q4_K_S",
            "Q4_0",
            "Q4_1",
            "Q3_K_L",
            "Q3_K_M",
            "Q3_K_S",
            "Q2_K",
            "IQ4_XS",
            "IQ3_M",
            "BF16",
            "F16",
            "F32",
        )

    /** The quantization token in [filename], or null when none is present. */
    fun from(filename: String): String? {
        val name = filename.substringAfterLast('/').uppercase()
        return KNOWN.firstOrNull { name.contains(it) }
    }

    /**
     * Picks the file that best matches [quant].
     *
     * Order: exact quant match (smallest file wins, which prefers the
     * base model over `-BF16` or `-F16` siblings) → substring match →
     * smallest file in the repo. Returns null only when [files] is empty.
     */
    fun pickFile(
        files: List<HuggingFaceApi.HfFile>,
        quant: String?,
    ): HuggingFaceApi.HfFile? {
        if (files.isEmpty()) return null
        val wanted = quant?.takeIf { it.isNotBlank() }?.uppercase()
        if (wanted == null) return files.minByOrNull { it.effectiveSize }

        val exact = files.filter { from(it.path)?.uppercase() == wanted }
        if (exact.isNotEmpty()) return exact.minByOrNull { it.effectiveSize }

        val partial = files.filter { it.path.uppercase().contains(wanted) }
        if (partial.isNotEmpty()) return partial.minByOrNull { it.effectiveSize }

        return files.minByOrNull { it.effectiveSize }
    }
}
