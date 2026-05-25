package com.example.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri

object AnkiDroidHelper {
    const val AUTHORITY = "com.ichi2.anki.providers.CardContentProvider"
    val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")
    
    // Permission required by AnkiDroid ContentProvider
    const val PERMISSION_READ_WRITE = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    
    /**
     * Checks if AnkiDroid is installed and its ContentProvider API is available.
     */
    fun isApiAvailable(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val providerInfo = pm.resolveContentProvider(AUTHORITY, 0)
            providerInfo != null
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Fetches all decks from AnkiDroid.
     */
    fun getDeckList(context: Context): Map<Long, String> {
        val decks = mutableMapOf<Long, String>()
        val uri = Uri.withAppendedPath(CONTENT_URI, "decks")
        try {
            val cursor = context.contentResolver.query(uri, arrayOf("id", "name"), null, null, null)
            cursor?.use {
                val idCol = it.getColumnIndex("id")
                val nameCol = it.getColumnIndex("name")
                if (idCol >= 0 && nameCol >= 0) {
                    while (it.moveToNext()) {
                        val id = it.getLong(idCol)
                        val name = it.getString(nameCol)
                        decks[id] = name
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return decks
    }
    
    /**
     * Fetches all note models/templates from AnkiDroid.
     */
    fun getModelList(context: Context): Map<Long, String> {
        val models = mutableMapOf<Long, String>()
        val uri = Uri.withAppendedPath(CONTENT_URI, "models")
        try {
            val cursor = context.contentResolver.query(uri, arrayOf("id", "name"), null, null, null)
            cursor?.use {
                val idCol = it.getColumnIndex("id")
                val nameCol = it.getColumnIndex("name")
                if (idCol >= 0 && nameCol >= 0) {
                    while (it.moveToNext()) {
                        val id = it.getLong(idCol)
                        val name = it.getString(nameCol)
                        models[id] = name
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return models
    }
    
    /**
     * Resolves a deck by name. If it doesn't exist, requests AnkiDroid to create it.
     */
    fun findOrCreateDeck(context: Context, name: String): Long? {
        val decks = getDeckList(context)
        for ((id, dname) in decks) {
            if (dname.equals(name, ignoreCase = true)) {
                return id
            }
        }
        
        val uri = Uri.withAppendedPath(CONTENT_URI, "decks")
        val values = ContentValues().apply {
            put("name", name)
        }
        return try {
            val resultUri = context.contentResolver.insert(uri, values)
            resultUri?.lastPathSegment?.toLongOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Tries to find his "Basic" note model, falling back to any available model.
     */
    fun getFirstModelOrFallback(context: Context): Long? {
        val models = getModelList(context)
        if (models.isEmpty()) return null
        
        for ((id, name) in models) {
            if (name.contains("Basic", ignoreCase = true)) {
                return id
            }
        }
        return models.keys.firstOrNull()
    }
    
    /**
     * Direct API to add a single note into a deck.
     */
    fun addNote(context: Context, deckId: Long, modelId: Long, front: String, back: String, tags: String = ""): Uri? {
        val uri = Uri.withAppendedPath(CONTENT_URI, "notes")
        
        // Join card fields using a tab character \t
        val flds = "$front\t$back"
        val values = ContentValues().apply {
            put("did", deckId)
            put("mid", modelId)
            put("flds", flds)
            if (tags.isNotEmpty()) {
                put("tags", tags)
            }
        }
        return try {
            context.contentResolver.insert(uri, values)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
