package com.btbf.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.CachePolicy
import com.btbf.app.databinding.ItemCategoryCardBinding
import com.btbf.app.scraper.CategoryItem

class CategoryGridAdapter(
    private val onClick: (CategoryItem) -> Unit
) : RecyclerView.Adapter<CategoryGridAdapter.ViewHolder>() {

    private val items = mutableListOf<CategoryItem>()

    fun setItems(newItems: List<CategoryItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCategoryCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(
        private val binding: ItemCategoryCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CategoryItem) {
            binding.tvCategoryName.text = item.name

            if (item.count.isNotEmpty()) {
                binding.tvCategoryCount.text = item.count
                binding.tvCategoryCount.visibility = View.VISIBLE
            } else {
                binding.tvCategoryCount.visibility = View.GONE
            }

            if (item.thumbnailUrl.isNotEmpty()) {
                binding.imgCategoryThumb.load(item.thumbnailUrl) {
                    crossfade(true)
                    memoryCachePolicy(CachePolicy.ENABLED)
                    diskCachePolicy(CachePolicy.ENABLED)
                    error(android.R.color.darker_gray)
                }
            }

            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
