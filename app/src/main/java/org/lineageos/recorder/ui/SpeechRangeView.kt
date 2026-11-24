/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import org.lineageos.recorder.asr.transcription.SpeechRange
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the speech ranges as bands on the track of the [Slider] it is attached to with [attachTo].
 * It is a sibling placed over the slider, and it never takes touches, so the slider keeps every
 * tap, drag and accessibility action.
 *
 * The bands use the slider's own geometry, read at draw time, not a constant:
 * - the track starts at [Slider.getTrackSidePadding] and is [Slider.getTrackWidth] wide, which
 *   is where the slider places value [Slider.getValueFrom] and value [Slider.getValueTo];
 * - the track is [Slider.getTrackHeight] high and centred on the slider;
 * - in a right-to-left layout the slider maps a value to `1 - fraction`, and the bands do too.
 */
class SpeechRangeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    /** The bands currently drawn. Read-only; changed only through [bind]. */
    var ranges: List<SpeechRange> = emptyList()
        private set
    var durationMs = 0L
        private set

    private var track: Slider? = null
    private val trackLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> invalidate() }
    private val minBandWidth = resources.displayMetrics.density * 2f
    private val bandRect = RectF()
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ColorUtils.setAlphaComponent(
            MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiary, 0),
            BAND_ALPHA,
        )
    }

    init {
        // A custom View draws in onDraw, so it must not be treated as a plain container
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Uses [slider]'s track for the bands. Call once after inflation. The bands are redrawn when the
     * slider is laid out again, for example after a rotation or a font scale change.
     */
    fun attachTo(slider: Slider) {
        track?.removeOnLayoutChangeListener(trackLayoutListener)
        track = slider
        slider.addOnLayoutChangeListener(trackLayoutListener)
        invalidate()
    }

    /** Replaces the bands. Does nothing when they are unchanged, so progress ticks never rebuild them. */
    fun bind(newRanges: List<SpeechRange>, newDurationMs: Long) {
        if (newRanges == ranges && newDurationMs == durationMs) return
        ranges = newRanges
        durationMs = newDurationMs
        invalidate()
    }

    /** Never consumes a touch, so the slider underneath receives it. */
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val slider = track ?: return
        if (durationMs <= 0L || ranges.isEmpty()) return
        val span = slider.valueTo - slider.valueFrom
        val trackWidth = slider.trackWidth.toFloat()
        if (span <= 0f || trackWidth <= 0f) return

        // The slider and this view are siblings with the same parent, so the slider's origin is
        // at (slider.left - left, slider.top - top) in this view's coordinates.
        val originX = (slider.left - left).toFloat() + slider.trackSidePadding
        val centreY = (slider.top - top).toFloat() + slider.height / 2f
        val halfHeight = slider.trackHeight / 2f
        val rtl = slider.layoutDirection == View.LAYOUT_DIRECTION_RTL

        fun xOf(ms: Long): Float {
            var fraction = ((ms - slider.valueFrom) / span).coerceIn(0f, 1f)
            if (rtl) fraction = 1f - fraction
            return originX + fraction * trackWidth
        }

        for (range in ranges) {
            val a = xOf(range.startMs)
            val b = xOf(range.endMs)
            val from = min(a, b)
            // A very short range stays visible as a thin band
            val to = max(max(a, b), from + minBandWidth)
            bandRect.set(from, centreY - halfHeight, to, centreY + halfHeight)
            canvas.drawRoundRect(bandRect, halfHeight, halfHeight, bandPaint)
        }
    }

    private companion object {
        /** About 55% opaque: visible over the inactive and the played part of the track */
        const val BAND_ALPHA = 140
    }
}
