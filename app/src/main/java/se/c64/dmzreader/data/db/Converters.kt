package se.c64.dmzreader.data.db

import androidx.room3.ColumnTypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Listfälten flags och coverFlags lagras som en JSON-lista i en textkolumn.
 * I Room 3 heter annoteringen @ColumnTypeConverter (Room 2: @TypeConverter).
 */
class Converters {
    @ColumnTypeConverter
    fun stringListToJson(value: List<String>): String = Json.encodeToString(serializer, value)

    @ColumnTypeConverter
    fun jsonToStringList(value: String): List<String> = Json.decodeFromString(serializer, value)

    private companion object {
        val serializer = ListSerializer(String.serializer())
    }
}
