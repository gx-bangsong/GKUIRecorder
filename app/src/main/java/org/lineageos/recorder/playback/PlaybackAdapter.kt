/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.playback

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.lineageos.recorder.R

class PlaybackAdapter(
    private val onClick: (PlaybackRow) -> Unit,
    private val onLongClick: (PlaybackRow) -> Unit,
) : ListAdapter<PlaybackRow, PlaybackAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_playback_row, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val iconImageView = view.findViewById<ImageView>(R.id.rowIconImageView)
        private val titleTextView = view.findViewById<TextView>(R.id.rowTitleTextView)
        private val subtitleTextView = view.findViewById<TextView>(R.id.rowSubtitleTextView)

        fun bind(row: PlaybackRow) {
            val context = itemView.context
            when (row) {
                is PlaybackRow.Chapter -> {
                    iconImageView.setImageResource(R.drawable.ic_content_cut)
                    titleTextView.text = context.getString(
                        R.string.playback_chapter_title, row.index
                    )
                    subtitleTextView.text = context.getString(
                        R.string.playback_chapter_range,
                        PlaybackRows.formatTime(row.startMs),
                        PlaybackRows.formatTime(row.endMs),
                        row.importantCount,
                    )
                }

                is PlaybackRow.Mark -> {
                    iconImageView.setImageResource(R.drawable.ic_bookmark)
                    titleTextView.text = context.getString(R.string.marker_important)
                    subtitleTextView.text = PlaybackRows.formatTime(row.marker.timeMs)
                }
            }
            itemView.setOnClickListener { onClick(row) }
            itemView.setOnLongClickListener {
                onLongClick(row)
                true
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PlaybackRow>() {
            override fun areItemsTheSame(oldItem: PlaybackRow, newItem: PlaybackRow) =
                oldItem == newItem

            override fun areContentsTheSame(oldItem: PlaybackRow, newItem: PlaybackRow) =
                oldItem == newItem
        }
    }
}
