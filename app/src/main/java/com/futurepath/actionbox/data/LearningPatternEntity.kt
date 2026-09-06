package com.futurepath.actionbox.data

import androidx.room.Entity
import com.futurepath.actionbox.classification.ClassifiedState

/** What a correction pattern is keyed on. See [LearningPatternEntity]. */
enum class LearningPatternType { SENDER, APP, PHRASE }

/**
 * One vote count: "[patternKey] of type [patternType] has been corrected to [category] this
 * many times." Purely local — tied to no account, synced nowhere. [LearningPatternDao] rolls
 * these votes up into a per-key dominant category used to bias future classification.
 */
@Entity(tableName = "learning_patterns", primaryKeys = ["patternType", "patternKey", "category"])
data class LearningPatternEntity(
    val patternType: LearningPatternType,
    val patternKey: String,
    val category: ClassifiedState,
    val correctionCount: Int
)
