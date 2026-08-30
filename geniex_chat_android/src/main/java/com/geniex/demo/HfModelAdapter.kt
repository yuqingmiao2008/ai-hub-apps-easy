// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.geniex.demo.utils.HuggingFaceApi
import java.util.Locale

/**
 * One base model together with every vendor repo that publishes it.
 *
 * Searching Hugging Face returns one row per *repo*, and the same base model
 * is republished by many quant vendors — `unsloth/…`, `bartowski/…`,
 * `orcarouter/…` all end in the same name. Rendered flat, that reads as a
 * list full of duplicates.
 */
data class HfModelGroup(
    /** Repo name of the most-downloaded entry — what the user searched for. */
    val baseName: String,
    /** Vendor repos, most popular first. */
    val repos: List<HuggingFaceApi.HfModel>,
) {
    val totalDownloads: Long get() = repos.sumOf { (it.downloads ?: 0).toLong() }
}

/**
 * Strips the packaging suffix vendors append, so repos publishing the same
 * base model collapse into one group:
 * `unsloth/Qwen3.8-…Uncensored-GGUF` and `orcarouter/Qwen3.8-…Uncensored`
 * both reduce to the same key.
 */
internal fun hfGroupKey(id: String): String =
    id.substringAfterLast('/')
        .replace(Regex("[._\\-]?gguf$", RegexOption.IGNORE_CASE), "")
        .lowercase(Locale.US)

/**
 * Groups results by base model — most-downloaded group first, each group's
 * repos most-downloaded first.
 *
 * Quant quality varies a lot between vendors, so every repo stays reachable.
 * Nothing is silently dropped.
 */
fun groupHfModels(models: List<HuggingFaceApi.HfModel>): List<HfModelGroup> =
    models
        .groupBy { hfGroupKey(it.id) }
        .values
        .map { repos ->
            val sorted = repos.sortedByDescending { it.downloads ?: 0 }
            HfModelGroup(
                baseName = sorted.first().id.substringAfterLast('/'),
                repos = sorted,
            )
        }
        .sortedByDescending { it.totalDownloads }

/**
 * Two-level list: one header row per base model, expanding to its vendor
 * repos. Collapsed by default, so the list reads as "one model, one row".
 */
class HfModelGroupAdapter(
    private val groups: List<HfModelGroup>,
    private val onRepoClick: (HuggingFaceApi.HfModel) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val expanded = mutableSetOf<Int>()
    private val rows = mutableListOf<Row>()

    private sealed interface Row {
        data class Header(val groupIndex: Int) : Row

        data class Repo(val groupIndex: Int, val repoIndex: Int) : Row
    }

    init {
        rebuildRows()
    }

    private fun rebuildRows() {
        rows.clear()
        groups.forEachIndexed { gi, group ->
            rows.add(Row.Header(gi))
            if (gi in expanded) {
                group.repos.indices.forEach { ri -> rows.add(Row.Repo(gi, ri)) }
            }
        }
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_hf_group_name)
        val tvCount: TextView = view.findViewById(R.id.tv_hf_group_count)
        val tvChevron: TextView = view.findViewById(R.id.tv_hf_group_chevron)
    }

    class RepoHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvId: TextView = view.findViewById(R.id.tv_hf_model_id)
        val tvMeta: TextView = view.findViewById(R.id.tv_hf_model_meta)
    }

    override fun getItemViewType(position: Int): Int =
        when (rows[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.Repo -> TYPE_REPO
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_hf_group, parent, false))
        } else {
            RepoHolder(inflater.inflate(R.layout.item_hf_model, parent, false))
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val row = rows[position]) {
            is Row.Header -> bindHeader(holder as HeaderHolder, row.groupIndex, position)

            is Row.Repo -> bindRepo(holder as RepoHolder, groups[row.groupIndex].repos[row.repoIndex])
        }
    }

    private fun bindHeader(
        holder: HeaderHolder,
        groupIndex: Int,
        position: Int,
    ) {
        val group = groups[groupIndex]
        holder.tvName.text = group.baseName
        holder.tvCount.text =
            if (group.repos.size > 1) {
                "${group.repos.size} repos   ↓ ${formatCount(group.totalDownloads)}"
            } else {
                "↓ ${formatCount(group.totalDownloads)}"
            }
        holder.tvChevron.text = if (groupIndex in expanded) "▾" else "▸"
        holder.itemView.setOnClickListener { toggle(groupIndex, position) }
    }

    private fun bindRepo(
        holder: RepoHolder,
        model: HuggingFaceApi.HfModel,
    ) {
        // Only the org differs between vendors, so lead with it — that is
        // what the user is actually choosing between.
        val org = model.id.substringBefore('/', "")
        holder.tvId.text =
            if (org.isNotEmpty()) "$org  ·  ${model.id.substringAfter('/')}" else model.id

        val flags =
            buildList {
                if (model.gated == true) add("gated")
                if (model.isPrivate == true) add("private")
            }.joinToString(" · ")
        val meta = "↓ ${formatCount(model.downloads?.toLong())}   ♥ ${formatCount(model.likes?.toLong())}"
        holder.tvMeta.text = if (flags.isEmpty()) meta else "$meta   [$flags]"
        holder.itemView.setOnClickListener { onRepoClick(model) }
    }

    private fun toggle(
        groupIndex: Int,
        position: Int,
    ) {
        val count = groups[groupIndex].repos.size
        if (groupIndex in expanded) {
            expanded.remove(groupIndex)
            rebuildRows()
            notifyItemRangeRemoved(position + 1, count)
        } else {
            expanded.add(groupIndex)
            rebuildRows()
            notifyItemRangeInserted(position + 1, count)
        }
        notifyItemChanged(position)
    }

    override fun getItemCount(): Int = rows.size

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_REPO = 1

        fun formatCount(n: Long?): String {
            val v = n ?: return "—"
            return when {
                v >= 1_000_000 -> String.format(Locale.US, "%.1fM", v / 1_000_000.0)
                v >= 1_000 -> String.format(Locale.US, "%.1fk", v / 1_000.0)
                else -> v.toString()
            }
        }
    }
}
