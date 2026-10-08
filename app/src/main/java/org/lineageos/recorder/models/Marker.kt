/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.models

import org.json.JSONArray
import org.json.JSONObject

enum class MarkerType {
    /** 重点标记：用户认为重要的位置 */
    IMPORTANT,

    /** 分段打点：用于会议/课程分段 */
    SEGMENT,
}

/**
 * A marker placed at [timeMs] milliseconds from the start of the recording.
 */
data class Marker(
    val timeMs: Long,
    val type: MarkerType,
) {
    fun toJson(): JSONObject = JSONObject()
        .put(KEY_TIME, timeMs)
        .put(KEY_TYPE, type.name)

    companion object {
        private const val KEY_TIME = "t"
        private const val KEY_TYPE = "type"

        fun fromJson(json: JSONObject) = Marker(
            json.getLong(KEY_TIME),
            MarkerType.valueOf(json.getString(KEY_TYPE)),
        )

        fun listToJson(markers: List<Marker>): String = JSONArray().apply {
            markers.forEach { put(it.toJson()) }
        }.toString()

        fun listFromJson(json: String): List<Marker> {
            val array = JSONArray(json)
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }
    }
}
