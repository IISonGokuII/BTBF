package com.btbf.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.btbf.app.databinding.ItemFavoriteVideoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FavoriteVideoAdapter(
    private var videos: List<FavoriteVideo>,
    private val onPlay: (FavoriteVideo) -> Unit,
    private val onRemove: (FavoriteVideo) -> Unit
) : RecyclerView.Adapter<FavoriteVideoAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemFavoriteVideoBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFavoriteVideoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val video = videos[position]
        holder.binding.apply {
            txtVideoTitle.text = video.title
            txtVideoDuration.text = video.duration.ifEmpty { "Video" }

            val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
            txtVideoAdded.text = dateFormat.format(Date(video.addedAt))

            if (video.thumbnailUrl.isNotEmpty()) {
                imgVideoThumbnail.load(video.thumbnailUrl) {
                    crossfade(true)
                    error(android.R.drawable.ic_menu_gallery)
                }
            }

            btnPlayVideo.setOnClickListener { onPlay(video) }
            btnRemoveVideo.setOnClickListener { onRemove(video) }
            root.setOnClickListener { onPlay(video) }
        }
    }

    override fun getItemCount() = videos.size

    fun updateVideos(newVideos: List<FavoriteVideo>) {
        videos = newVideos
        notifyDataSetChanged()
    }
}
