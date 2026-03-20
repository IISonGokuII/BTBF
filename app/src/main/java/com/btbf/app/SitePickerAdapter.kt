package com.btbf.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.btbf.app.databinding.ItemSitePickerBinding

data class SitePickerItem(
    val name: String,
    val url: String,
    val subtitle: String
)

class SitePickerAdapter(
    private val items: List<SitePickerItem>,
    private val onPick: (SitePickerItem) -> Unit
) : RecyclerView.Adapter<SitePickerAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSitePickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, onPick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    class VH(
        private val binding: ItemSitePickerBinding,
        private val onPick: (SitePickerItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SitePickerItem) {
            binding.tvSiteTitle.text = item.name
            binding.tvSiteSubtitle.text = item.subtitle
            binding.cardRoot.setOnClickListener { onPick(item) }
        }
    }
}
