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

/**
 * Simple list adapter for Hugging Face search results. Each row shows
 * the repo ID and a metadata line (downloads / likes).
 */
class HfModelAdapter(
    private val models: List<HuggingFaceApi.HfModel>,
    private val onClick: (HuggingFaceApi.HfModel) -> Unit,
) : RecyclerView.Adapter<HfModelAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvId: TextView = view.findViewById(R.id.tv_hf_model_id)
        val tvMeta: TextView = view.findViewById(R.id.tv_hf_model_meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_hf_model, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val model = models[position]
        holder.tvId.text = model.id
        val downloads = model.downloads?.let { "↓ $it" } ?: "↓ —"
        val likes = model.likes?.let { "♥ $it" } ?: "♥ —"
        holder.tvMeta.text = "$downloads   $likes"
        holder.itemView.setOnClickListener { onClick(model) }
    }

    override fun getItemCount(): Int = models.size
}
