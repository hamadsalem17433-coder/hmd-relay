package com.hmd.messenger.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.hmd.messenger.Codec
import com.hmd.messenger.media.AudioPlayer

class ChatAdapter(
    private val context: Context,
    private val player: AudioPlayer
) : BaseAdapter() {

    private val items = mutableListOf<ChatMessageItem>()

    fun setItems(newItems: List<ChatMessageItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): ChatMessageItem = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val item = getItem(position)
        val isOutgoing = item.entity.outgoing

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
            gravity = if (isOutgoing) Gravity.END else Gravity.START
        }

        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 12)

            val bg = GradientDrawable().apply {
                cornerRadius = 24f
                if (isOutgoing) {
                    setColor(Color.parseColor("#1B5E20")) // أخضر داكن للرسائل الصادرة
                } else {
                    setColor(Color.parseColor("#37474F")) // رمادي داكن للرسائل الواردة
                }
            }
            background = bg
        }

        if (item.entity.isVoice) {
            val voiceRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val playBtn = Button(context).apply {
                text = "▶️"
                textSize = 14f
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    try {
                        val audioBytes = Codec.dec(item.entity.text)
                        text = "⏸️"
                        player.play(audioBytes) {
                            text = "▶️"
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            voiceRow.addView(playBtn)

            val voiceTv = TextView(context).apply {
                text = "ملاحظة صوتية (${item.formattedDuration})"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(12, 0, 12, 0)
            }
            voiceRow.addView(voiceTv)

            bubble.addView(voiceRow)
        } else {
            val msgTv = TextView(context).apply {
                text = item.entity.text
                setTextColor(Color.WHITE)
                textSize = 15f
            }
            bubble.addView(msgTv)
        }

        val footerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 4, 0, 0)
        }

        val timeTv = TextView(context).apply {
            text = item.formattedTime
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 10f
        }
        footerRow.addView(timeTv)

        if (isOutgoing) {
            val statusTv = TextView(context).apply {
                text = " " + item.statusIcon
                setTextColor(Color.parseColor("#80D8FF"))
                textSize = 10f
            }
            footerRow.addView(statusTv)
        }

        bubble.addView(footerRow)
        container.addView(bubble)

        return container
    }
}
