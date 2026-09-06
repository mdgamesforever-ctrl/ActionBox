package com.futurepath.actionbox.data

import androidx.room.TypeConverter
import com.futurepath.actionbox.classification.ClassifiedState

class Converters {
    @TypeConverter
    fun fromClassifiedState(state: ClassifiedState?): String? = state?.name

    @TypeConverter
    fun toClassifiedState(value: String?): ClassifiedState? = value?.let { ClassifiedState.valueOf(it) }
}
