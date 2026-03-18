package com.btbf.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.CachePolicy
import coil.transform.CircleCropTransformation
import com.btbf.app.databinding.ItemActorCardBinding
import com.btbf.app.scraper.ActorItem

class ActorGridAdapter(
    private val onClick: (ActorItem) -> Unit
) : RecyclerView.Adapter<ActorGridAdapter.ViewHolder>() {

    private val items = mutableListOf<ActorItem>()

    fun setItems(newItems: List<ActorItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemActorCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(
        private val binding: ItemActorCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ActorItem) {
            binding.tvActorName.text = item.name

            if (item.videoCount.isNotEmpty()) {
                binding.tvActorCount.text = item.videoCount
                binding.tvActorCount.visibility = View.VISIBLE
            } else {
                binding.tvActorCount.visibility = View.GONE
            }

            if (item.thumbnailUrl.isNotEmpty()) {
                binding.imgActorThumb.load(item.thumbnailUrl) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                    memoryCachePolicy(CachePolicy.ENABLED)
                    diskCachePolicy(CachePolicy.ENABLED)
                    error(android.R.color.darker_gray)
                }
            }

            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
