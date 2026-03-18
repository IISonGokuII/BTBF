package com.btbf.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Favoriten Manager für Videos und Darsteller
 * Speichert und verwaltet Favoriten in SharedPreferences
 */
class FavoritesManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "btbf_favorites", 
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val KEY_FAVORITE_VIDEOS = "favorite_videos"
        private const val KEY_FAVORITE_ACTORS = "favorite_actors"
        private const val KEY_FAVORITE_CATEGORIES = "favorite_categories"
    }
    
    // ==================== VIDEO FAVORITEN ====================
    
    /**
     * Video zu Favoriten hinzufügen
     */
    fun addFavoriteVideo(videoId: String, title: String, thumbnailUrl: String, duration: String = "") {
        val videos = getFavoriteVideos().toMutableList()
        
        // Prüfen ob schon vorhanden
        if (videos.none { it.id == videoId }) {
            videos.add(FavoriteVideo(videoId, title, thumbnailUrl, duration, System.currentTimeMillis()))
            saveFavoriteVideos(videos)
        }
    }
    
    /**
     * Video aus Favoriten entfernen
     */
    fun removeFavoriteVideo(videoId: String) {
        val videos = getFavoriteVideos().filter { it.id != videoId }
        saveFavoriteVideos(videos)
    }
    
    /**
     * Alle Favoriten-Videos abrufen
     */
    fun getFavoriteVideos(): List<FavoriteVideo> {
        val json = prefs.getString(KEY_FAVORITE_VIDEOS, "[]") ?: "[]"
        val array = JSONArray(json)
        val videos = mutableListOf<FavoriteVideo>()
        
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            videos.add(
                FavoriteVideo(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    thumbnailUrl = obj.getString("thumbnailUrl"),
                    duration = obj.optString("duration", ""),
                    addedAt = obj.optLong("addedAt", System.currentTimeMillis())
                )
            )
        }
        
        return videos.sortedByDescending { it.addedAt }
    }
    
    /**
     * Prüfen ob Video Favorit ist
     */
    fun isVideoFavorite(videoId: String): Boolean {
        return getFavoriteVideos().any { it.id == videoId }
    }
    
    private fun saveFavoriteVideos(videos: List<FavoriteVideo>) {
        val array = JSONArray()
        videos.forEach { video ->
            val obj = JSONObject().apply {
                put("id", video.id)
                put("title", video.title)
                put("thumbnailUrl", video.thumbnailUrl)
                put("duration", video.duration)
                put("addedAt", video.addedAt)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_FAVORITE_VIDEOS, array.toString()).apply()
    }
    
    // ==================== DARSTELLER FAVORITEN ====================
    
    /**
     * Darsteller zu Favoriten hinzufügen
     */
    fun addFavoriteActor(actorId: String, name: String, imageUrl: String = "") {
        val actors = getFavoriteActors().toMutableList()
        
        if (actors.none { it.id == actorId }) {
            actors.add(FavoriteActor(actorId, name, imageUrl, System.currentTimeMillis()))
            saveFavoriteActors(actors)
        }
    }
    
    /**
     * Darsteller aus Favoriten entfernen
     */
    fun removeFavoriteActor(actorId: String) {
        val actors = getFavoriteActors().filter { it.id != actorId }
        saveFavoriteActors(actors)
    }
    
    /**
     * Alle Favoriten-Darsteller abrufen
     */
    fun getFavoriteActors(): List<FavoriteActor> {
        val json = prefs.getString(KEY_FAVORITE_ACTORS, "[]") ?: "[]"
        val array = JSONArray(json)
        val actors = mutableListOf<FavoriteActor>()
        
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            actors.add(
                FavoriteActor(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    imageUrl = obj.optString("imageUrl", ""),
                    addedAt = obj.optLong("addedAt", System.currentTimeMillis())
                )
            )
        }
        
        return actors.sortedByDescending { it.addedAt }
    }
    
    /**
     * Prüfen ob Darsteller Favorit ist
     */
    fun isActorFavorite(actorId: String): Boolean {
        return getFavoriteActors().any { it.id == actorId }
    }
    
    private fun saveFavoriteActors(actors: List<FavoriteActor>) {
        val array = JSONArray()
        actors.forEach { actor ->
            val obj = JSONObject().apply {
                put("id", actor.id)
                put("name", actor.name)
                put("imageUrl", actor.imageUrl)
                put("addedAt", actor.addedAt)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_FAVORITE_ACTORS, array.toString()).apply()
    }
    
    // ==================== KATEGORIE FAVORITEN ====================
    
    /**
     * Kategorie zu Favoriten hinzufügen
     */
    fun addFavoriteCategory(categoryId: String, name: String) {
        val categories = getFavoriteCategories().toMutableList()
        
        if (categories.none { it.id == categoryId }) {
            categories.add(FavoriteCategory(categoryId, name, System.currentTimeMillis()))
            saveFavoriteCategories(categories)
        }
    }
    
    /**
     * Kategorie aus Favoriten entfernen
     */
    fun removeFavoriteCategory(categoryId: String) {
        val categories = getFavoriteCategories().filter { it.id != categoryId }
        saveFavoriteCategories(categories)
    }
    
    /**
     * Alle Favoriten-Kategorien abrufen
     */
    fun getFavoriteCategories(): List<FavoriteCategory> {
        val json = prefs.getString(KEY_FAVORITE_CATEGORIES, "[]") ?: "[]"
        val array = JSONArray(json)
        val categories = mutableListOf<FavoriteCategory>()
        
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            categories.add(
                FavoriteCategory(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    addedAt = obj.optLong("addedAt", System.currentTimeMillis())
                )
            )
        }
        
        return categories.sortedByDescending { it.addedAt }
    }
    
    private fun saveFavoriteCategories(categories: List<FavoriteCategory>) {
        val array = JSONArray()
        categories.forEach { category ->
            val obj = JSONObject().apply {
                put("id", category.id)
                put("name", category.name)
                put("addedAt", category.addedAt)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_FAVORITE_CATEGORIES, array.toString()).apply()
    }
    
    // ==================== UTILS ====================
    
    /**
     * Alle Favoriten löschen
     */
    fun clearAllFavorites() {
        prefs.edit().apply {
            remove(KEY_FAVORITE_VIDEOS)
            remove(KEY_FAVORITE_ACTORS)
            remove(KEY_FAVORITE_CATEGORIES)
            apply()
        }
    }
    
    /**
     * Gesamtanzahl der Favoriten
     */
    fun getTotalFavoritesCount(): Int {
        return getFavoriteVideos().size + 
               getFavoriteActors().size + 
               getFavoriteCategories().size
    }
}

// ==================== DATA CLASSES ====================

/**
 * Datenklasse für Video-Favoriten
 */
data class FavoriteVideo(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val duration: String,
    val addedAt: Long
)

/**
 * Datenklasse für Darsteller-Favoriten
 */
data class FavoriteActor(
    val id: String,
    val name: String,
    val imageUrl: String,
    val addedAt: Long
)

/**
 * Datenklasse für Kategorie-Favoriten
 */
data class FavoriteCategory(
    val id: String,
    val name: String,
    val addedAt: Long
)
