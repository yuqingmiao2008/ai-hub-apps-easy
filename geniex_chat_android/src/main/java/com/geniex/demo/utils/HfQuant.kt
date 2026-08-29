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
    /**
     * Sorted longest-first so the token that covers the most characters wins.
     * Without that ordering `BF16` matches as `F16` and `Q4_K_XL` as `Q4_0`'s
     * cousin `Q4_K` — both are substrings of the label the user actually sees.
     */
    private val KNOWN =
        listOf(
            // Unsloth "UD" dynamic quants. Every unsloth GGUF repo ships these
            // next to the regular ones, and they are what the smallest-file
            // fallback would otherwise land on.
            "IQ2_XXS",
            "IQ3_XXS",
            "IQ4_XS",
            "IQ1_S",
            "IQ1_M",
            "IQ2_XS",
            "IQ2_S",
            "IQ2_M",
            "IQ3_XS",
            "IQ3_S",
            "IQ3_M",
            "IQ4_NL",
            "Q2_K_XL",
            "Q3_K_XL",
            "Q4_K_XL",
            "Q5_K_XL",
            "Q6_K_XL",
            "Q8_K_XL",
            // llama.cpp standard quants
            "Q3_K_L",
            "Q3_K_M",
            "Q3_K_S",
            "Q4_K_M",
            "Q4_K_S",
            "Q5_K_M",
            "Q5_K_S",
            "Q2_K",
            "Q4_0",
            "Q4_1",
            "Q5_0",
            "Q5_1",
            "Q6_K",
            "Q8_0",
            "BF16",
            "F16",
            "F32",
        ).sortedByDescending { it.length }

    /** The quantization token in [filename], or null when none is present. */
    fun from(filename: String): String? {
        // `UD-IQ2_XXS` and `Q2_K_XL` only differ from their plain siblings by
        // the extra suffix, so drop the vendor prefix before matching.
        val name =
            filename
                .substringAfterLast('/')
                .uppercase()
                .replace("UD-", "")
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
