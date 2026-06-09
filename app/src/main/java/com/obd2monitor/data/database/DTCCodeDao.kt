package com.obd2monitor.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DTCCodeDao {
    @Query("SELECT * FROM dtc_codes WHERE code = :code")
    suspend fun getByCode(code: String): DTCCodeEntity?

    @Query("SELECT * FROM dtc_codes WHERE code IN (:codes)")
    suspend fun getByCodes(codes: List<String>): List<DTCCodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(codes: List<DTCCodeEntity>)

    @Query("SELECT COUNT(*) FROM dtc_codes")
    suspend fun count(): Int
}
