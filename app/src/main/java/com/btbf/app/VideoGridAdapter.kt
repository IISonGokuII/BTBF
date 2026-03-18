package com.btbf.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.CachePolicy
import com.btbf.app.databinding.ItemVideoCardBinding
import com.btbf.app.scraper.VideoItem

class VideoGridAdapter(
    private val onVideoClick: (VideoItem) -> Unit,
    private val onVideoLongClick: (VideoItem) -> Unit = {}
) : RecyclerView.Adapter<VideoGridAdapter.VideoViewHolder>() {

    private val videos = mutableListOf<VideoItem>()

    fun setVideos(newVideos: List<VideoItem>) {
        videos.clear()
        videos.addAll(newVideos)
        notifyDataSetChanged()
    }

    fun addVideos(moreVideos: List<VideoItem>) {
        val start = videos.size
        videos.addAll(moreVideos)
        notifyItemRangeInserted(start, moreVideos.size)
    }

    fun getVideos(): List<VideoItem> = videos.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(videos[position])
    }

    override fun getItemCount() = videos.size

    inner class VideoViewHolder(
        private val binding: ItemVideoCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(video: VideoItem) {
            binding.tvTitle.text = video.title

            if (video.duration.isNotEmpty()) {
                binding.tvDuration.text = video.duration
                binding.tvDuration.visibility = View.VISIBLE
            } else {
                binding.tvDuration.visibility = View.GONE
            }

            if (video.quality.isNotEmpty()) {
                binding.tvQuality.text = video.quality
                binding.tvQuality.visibility = View.VISIBLE
            } else {
                binding.tvQuality.visibility = View.GONE
            }

            if (video.thumbnailUrl.isNotEmpty()) {
                binding.imgThumbnail.load(video.thumbnailUrl) {
                    crossfade(true)
                    memoryCachePolicy(CachePolicy.ENABLED)
                    diskCachePolicy(CachePolicy.ENABLED)
                    error(android.R.color.darker_gray)
                }
            }

            binding.root.setOnClickListener { onVideoClick(video) }
            binding.root.setOnLongClickListener {
                onVideoLongClick(video)
                true
            }
        }
    }
}
