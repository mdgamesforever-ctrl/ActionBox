package com.futurepath.actionbox.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.classification.CorrectionLearning

@Dao
interface LearningPatternDao {

    @Query(
        "SELECT correctionCount FROM learning_patterns WHERE patternType = :type AND patternKey = :key AND category = :category"
    )
    suspend fun getCount(type: LearningPatternType, key: String, category: ClassifiedState): Int?

    @Query("SELECT * FROM learning_patterns WHERE patternType = :type AND patternKey = :key")
    suspend fun getPatterns(type: LearningPatternType, key: String): List<LearningPatternEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LearningPatternEntity)

    /**
     * Increments the (type, key, category) vote by one. Runs as one Room transaction for the
     * same reason [NotificationDao.captureIfNew] does: it's a read-modify-write sequence, and
     * @Transaction suspend functions on the same database are serialized against each other,
     * so two concurrent corrections can't both read the same stale count.
     */
    @Transaction
    suspend fun recordCorrection(type: LearningPatternType, key: String, category: ClassifiedState) {
        if (key.isBlank()) return
        val current = getCount(type, key, category) ?: 0
        upsert(LearningPatternEntity(type, key, category, current + 1))
    }

    /**
     * The dominant category for [key], if its correction history is strong and consistent
     * enough to trust — see [CorrectionLearning.strongCategory], which makes that call from
     * plain counts so it can be unit-tested without Room.
     */
    @Transaction
    suspend fun strongCategoryFor(type: LearningPatternType, key: String): ClassifiedState? {
        if (key.isBlank()) return null
        val counts = getPatterns(type, key).associate { it.category to it.correctionCount }
        return CorrectionLearning.strongCategory(counts)
    }
}
