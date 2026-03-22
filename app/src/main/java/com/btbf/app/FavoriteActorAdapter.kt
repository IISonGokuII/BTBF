package com.btbf.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.btbf.app.databinding.ItemFavoriteActorBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FavoriteActorAdapter(
    private var actors: List<FavoriteActor>,
    private val onClick: (FavoriteActor) -> Unit,
    private val onRemove: (FavoriteActor) -> Unit
) : RecyclerView.Adapter<FavoriteActorAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemFavoriteActorBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFavoriteActorBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val actor = actors[position]
        holder.binding.apply {
            txtActorName.text = actor.name

            val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
            txtActorAdded.text = dateFormat.format(Date(actor.addedAt))

            if (actor.imageUrl.isNotEmpty()) {
                imgActorPhoto.load(actor.imageUrl) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                    error(android.R.drawable.ic_menu_myplaces)
                }
            }

            btnRemoveActor.setOnClickListener { onRemove(actor) }
            root.setOnClickListener { onClick(actor) }
        }
    }

    override fun getItemCount() = actors.size

    fun updateActors(newActors: List<FavoriteActor>) {
        actors = newActors
        notifyDataSetChanged()
    }
}
