package com.github.damontecres.wholphin.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.github.damontecres.wholphin.data.model.ArrServer
import kotlinx.coroutines.flow.Flow

@Dao
interface ArrServerDao {
    @Query("SELECT * FROM arr_servers WHERE jellyfinUserRowId = :jellyfinUserRowId ORDER BY type, name")
    fun getServers(jellyfinUserRowId: Int): Flow<List<ArrServer>>

    @Query("DELETE FROM arr_servers WHERE jellyfinUserRowId = :jellyfinUserRowId")
    suspend fun deleteServers(jellyfinUserRowId: Int): Int

    @Insert
    suspend fun addServers(servers: List<ArrServer>)

    @Transaction
    suspend fun replaceServers(
        jellyfinUserRowId: Int,
        servers: List<ArrServer>,
    ) {
        deleteServers(jellyfinUserRowId)
        addServers(servers)
    }
}
