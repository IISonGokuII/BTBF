package com.btbf.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.btbf.app.databinding.ItemFavoriteActorBinding
import com.btbf.app.databinding.ItemFavoriteVideoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FavoriteVideosAdapter(
    private val onOpen: (FavoriteVideo) -> Unit,
    private val onRemove: (FavoriteVideo) -> Unit
) : RecyclerView.Adapter<FavoriteVideosAdapter.VH>() {

    private val items = mutableListOf<FavoriteVideo>()
    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)

    fun submitList(list: List<FavoriteVideo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemFavoriteVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemFavoriteVideoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(v: FavoriteVideo) {
            binding.txtVideoTitle.text = v.title
            if (v.duration.isEmpty()) {
                binding.txtVideoDuration.visibility = View.GONE
            } else {
                binding.txtVideoDuration.visibility = View.VISIBLE
                binding.txtVideoDuration.text = v.duration
            }
            binding.txtVideoAdded.text = dateFmt.format(Date(v.addedAt))
            binding.imgVideoThumbnail.load(v.thumbnailUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_launcher_foreground)
                error(R.drawable.ic_launcher_foreground)
            }
            binding.root.setOnClickListener { onOpen(v) }
            binding.btnPlayVideo.setOnClickListener { onOpen(v) }
            binding.btnRemoveVideo.setOnClickListener { onRemove(v) }
        }
    }
}

class FavoriteActorsAdapter(
    private val onOpen: (FavoriteActor) -> Unit,
    private val onRemove: (FavoriteActor) -> Unit
) : RecyclerView.Adapter<FavoriteActorsAdapter.VH>() {

    private val items = mutableListOf<FavoriteActor>()
    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)

    fun submitList(list: List<FavoriteActor>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemFavoriteActorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemFavoriteActorBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(a: FavoriteActor) {
            binding.txtActorName.text = a.name
            binding.txtActorAdded.text = dateFmt.format(Date(a.addedAt))
            binding.imgActorPhoto.load(a.imageUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_launcher_foreground)
                error(R.drawable.ic_launcher_foreground)
            }
            binding.root.setOnClickListener { onOpen(a) }
            binding.btnRemoveActor.setOnClickListener { onRemove(a) }
        }
    }
}
