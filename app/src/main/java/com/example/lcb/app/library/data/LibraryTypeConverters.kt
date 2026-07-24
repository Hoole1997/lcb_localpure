package com.example.lcb.app.library.data

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal class LibraryTypeConverters {
    private val gson = Gson()
    private val stringListType = object : TypeToken<List<String>>() {}.type

    @TypeConverter
    fun fromStringList(value: List<String>?): String? = value?.let(gson::toJson)

    @TypeConverter
    fun toStringList(value: String?): List<String>? = value?.let { json ->
        runCatching { gson.fromJson<List<String>>(json, stringListType) }.getOrNull()
    }
}
