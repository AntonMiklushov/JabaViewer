package com.example.jabaviewer.data.remote.adapter

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonQualifier
import com.squareup.moshi.JsonReader
import com.squareup.moshi.ToJson

@Retention(AnnotationRetention.RUNTIME)
@JsonQualifier
annotation class FlexibleString

class FlexibleStringAdapter {
    @FromJson
    @FlexibleString
    fun fromJson(reader: JsonReader): String {
        return when (reader.peek()) {
            JsonReader.Token.STRING -> reader.nextString()
            JsonReader.Token.NUMBER -> reader.nextString()
            JsonReader.Token.BOOLEAN -> reader.nextBoolean().toString()
            JsonReader.Token.NULL -> {
                reader.nextNull<Unit>()
                throw JsonDataException("Expected string but was null")
            }
            else -> throw JsonDataException("Expected string or number")
        }
    }

    @ToJson
    fun toJson(@FlexibleString value: String): String = value
}
