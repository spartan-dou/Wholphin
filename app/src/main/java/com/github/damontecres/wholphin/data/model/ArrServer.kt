package com.github.damontecres.wholphin.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.github.damontecres.wholphin.ui.isNotNullOrBlank

/**
 * Represents a Radarr or Sonarr instance imported from Seerr for a [JellyfinUser]
 */
@Entity(
    tableName = "arr_servers",
    foreignKeys = [
        ForeignKey(
            entity = JellyfinUser::class,
            parentColumns = arrayOf("rowId"),
            childColumns = arrayOf("jellyfinUserRowId"),
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("jellyfinUserRowId")],
)
data class ArrServer(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val jellyfinUserRowId: Int,
    val type: ArrType,
    val name: String,
    val url: String,
    val apiKey: String,
) {
    override fun toString(): String =
        "ArrServer(id=$id, jellyfinUserRowId=$jellyfinUserRowId, type=$type, name=$name, url=$url, apiKey?=${apiKey.isNotNullOrBlank()})"
}

enum class ArrType(
    val defaultPort: Int,
) {
    RADARR(7878),
    SONARR(8989),
}
