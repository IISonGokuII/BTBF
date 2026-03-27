package com.btbf.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.btbf.app.databinding.ItemSitePickerBinding
import com.btbf.app.databinding.ItemSitePickerTileBinding

data class SitePickerItem(
    val name: String,
    val url: String,
    val subtitle: String
)

/**
 * @param gridColumns 1 = eine Spalte (große Zeilen), 2 oder 3 = kompakte Kacheln
 */
class SitePickerAdapter(
    private val items: List<SitePickerItem>,
    private val gridColumns: Int,
    private val onPick: (SitePickerItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_LIST = 0
        private const val TYPE_TILE = 1
    }

    override fun getItemViewType(position: Int): Int =
        if (gridColumns <= 1) TYPE_LIST else TYPE_TILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_LIST) {
            val binding = ItemSitePickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            ListVH(binding, onPick)
        } else {
            val binding = ItemSitePickerTileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            TileVH(binding, onPick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is ListVH -> holder.bind(item)
            is TileVH -> holder.bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    private class ListVH(
        private val binding: ItemSitePickerBinding,
        private val onPick: (SitePickerItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SitePickerItem) {
            binding.tvSiteTitle.text = item.name
            binding.tvSiteSubtitle.text = item.subtitle
            binding.cardRoot.setOnClickListener { onPick(item) }
        }
    }

    private class TileVH(
        private val binding: ItemSitePickerTileBinding,
        private val onPick: (SitePickerItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SitePickerItem) {
            binding.tvSiteTitle.text = item.name
            binding.tvSiteSubtitle.text = item.subtitle
            binding.cardRoot.setOnClickListener { onPick(item) }
        }
    }
}
