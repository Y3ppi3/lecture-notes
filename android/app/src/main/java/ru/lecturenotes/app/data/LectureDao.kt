package ru.lecturenotes.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LectureDao {

    // ---------- лекции ----------

    @Query("SELECT * FROM lectures ORDER BY updatedAt DESC")
    fun observeLectures(): Flow<List<LectureEntity>>

    @Query("SELECT * FROM lectures WHERE id = :id")
    fun observeLecture(id: Long): Flow<LectureEntity?>

    @Query("SELECT * FROM lectures WHERE id = :id")
    suspend fun getLecture(id: Long): LectureEntity?

    @Insert
    suspend fun insertLecture(lecture: LectureEntity): Long

    @Update
    suspend fun updateLecture(lecture: LectureEntity)

    @Query("DELETE FROM lectures WHERE id = :id")
    suspend fun deleteLecture(id: Long)

    @Query("UPDATE lectures SET updatedAt = :time WHERE id = :id")
    suspend fun touchLecture(id: Long, time: Long)

    // ---------- блоки ----------

    @Query("SELECT * FROM blocks WHERE lectureId = :lectureId ORDER BY position ASC, id ASC")
    fun observeBlocks(lectureId: Long): Flow<List<BlockEntity>>

    @Query("SELECT * FROM blocks WHERE lectureId = :lectureId ORDER BY position ASC, id ASC")
    suspend fun getBlocks(lectureId: Long): List<BlockEntity>

    @Query("SELECT * FROM blocks WHERE id = :id")
    suspend fun getBlock(id: Long): BlockEntity?

    @Query("SELECT MAX(position) FROM blocks WHERE lectureId = :lectureId")
    suspend fun maxPosition(lectureId: Long): Double?

    @Query("SELECT * FROM blocks WHERE status IN ('RECORDING', 'PROCESSING')")
    suspend fun getUnfinishedBlocks(): List<BlockEntity>

    @Insert
    suspend fun insertBlock(block: BlockEntity): Long

    @Update
    suspend fun updateBlock(block: BlockEntity)

    @Delete
    suspend fun deleteBlock(block: BlockEntity)
}
