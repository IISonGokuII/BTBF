package com.btbf.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.btbf.app.databinding.ItemBrowseChipBinding

enum class BrowseNavAction {
    HOME, NEWEST, TOP, RANDOM, LONGEST, CATEGORIES, ACTORS, TAGS, FAVORITES
}

private data class BrowseChipSpec(val action: BrowseNavAction, @StringRes val label: Int)

class BrowseChipAdapter(
    private val onAction: (BrowseNavAction) -> Unit
) : RecyclerView.Adapter<BrowseChipAdapter.VH>() {

    private val specs = listOf(
        BrowseChipSpec(BrowseNavAction.HOME, R.string.chip_home),
        BrowseChipSpec(BrowseNavAction.NEWEST, R.string.chip_new),
        BrowseChipSpec(BrowseNavAction.TOP, R.string.chip_top),
        BrowseChipSpec(BrowseNavAction.RANDOM, R.string.chip_random),
        BrowseChipSpec(BrowseNavAction.LONGEST, R.string.chip_longest),
        BrowseChipSpec(BrowseNavAction.CATEGORIES, R.string.chip_categories),
        BrowseChipSpec(BrowseNavAction.ACTORS, R.string.chip_actors),
        BrowseChipSpec(BrowseNavAction.TAGS, R.string.chip_tags),
        BrowseChipSpec(BrowseNavAction.FAVORITES, R.string.chip_favorites)
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBrowseChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val spec = specs[position]
        holder.binding.btnChip.text = holder.binding.root.context.getString(spec.label)
        holder.binding.btnChip.setOnClickListener { onAction(spec.action) }
    }

    override fun getItemCount() = specs.size

    class VH(val binding: ItemBrowseChipBinding) : RecyclerView.ViewHolder(binding.root)
}
