/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import org.lineageos.recorder.models.MarkerType
import kotlin.math.max

/**
 * Static waveform of a recording with a playhead, marker ticks and tap/drag seeking.
 */
class PlaybackWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class Tick(val fraction: Float, val type: MarkerType)

    var peaks: FloatArray? = null
        set(value) {
            field = value
            invalidate()
        }

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var ticks: List<Tick> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var onSeek: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val accent = MaterialColors.getColor(
        context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE
    )
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val unplayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(accent, 90)
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        strokeWidth = 2 * density
    }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SEGMENT_COLOR
        strokeWidth = 2 * density
    }
    private val importantPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = IMPORTANT_COLOR
        style = Paint.Style.FILL
    }

    init {
        contentDescription = context.getString(org.lineageos.recorder.R.string.playback_waveform)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            return
        }

        val data = peaks
        if (data != null && data.isNotEmpty()) {
            val slot = w / data.size
            val barWidth = max(2 * density, slot * 0.6f)
            val mid = h / 2f
            val maxHalf = mid - 8 * density
            for (i in data.indices) {
                val x = i * slot + slot / 2f
                val half = max(data[i], 0.04f) * maxHalf
                val paint = if ((i + 0.5f) / data.size <= progress) playedPaint else unplayedPaint
                paint.strokeWidth = barWidth
                canvas.drawLine(x, mid - half, x, mid + half, paint)
            }
        }

        ticks.forEach { tick ->
            val x = tick.fraction * w
            when (tick.type) {
                MarkerType.SEGMENT -> canvas.drawLine(x, 0f, x, h, segmentPaint)
                MarkerType.IMPORTANT -> canvas.drawCircle(x, 6 * density, 4 * density, importantPaint)
            }
        }

        canvas.drawLine(progress * w, 0f, progress * w, h, cursorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0) {
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                progress = event.x / width
                return true
            }

            MotionEvent.ACTION_UP -> {
                progress = event.x / width
                onSeek?.invoke(progress)
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    companion object {
        private val SEGMENT_COLOR = Color.parseColor("#1E88E5")
        private val IMPORTANT_COLOR = Color.parseColor("#E53935")
    }
}
