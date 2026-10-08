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
import com.hmd.messenger.net.Codec
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
            setPadding(24, 10, 24, 10)
            gravity = if (isOutgoing) Gravity.END else Gravity.START
        }

        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 18, 28, 14)

            val bg = GradientDrawable().apply {
                if (isOutgoing) {
                    setColor(Color.parseColor("#005C4B")) // Dark Emerald for outgoing
                    cornerRadii = floatArrayOf(28f, 28f, 28f, 28f, 8f, 8f, 28f, 28f)
                } else {
                    setColor(Color.parseColor("#1F2C34")) // Modern Slate for incoming
                    cornerRadii = floatArrayOf(28f, 28f, 28f, 28f, 28f, 28f, 8f, 8f)
                }
            }
            background = bg
        }

        if (item.entity.isVoice) {
            val voiceRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }

            val playBtnBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isOutgoing) Color.parseColor("#008069") else Color.parseColor("#2A3942"))
            }

            val playBtn = Button(context).apply {
                text = "▶"
                textSize = 14f
                setTextColor(Color.WHITE)
                background = playBtnBg
                layoutParams = LinearLayout.LayoutParams(72, 72).apply {
                    rightMargin = 16
                }
                setOnClickListener {
                    try {
                        val audioBytes = Codec.dec(item.entity.text)
                        text = "⏸"
                        player.play(audioBytes) {
                            text = "▶"
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            voiceRow.addView(playBtn)

            val voiceTv = TextView(context).apply {
                text = "ملاحظة صوتية  •  ${item.formattedDuration}"
                setTextColor(Color.parseColor("#E9EDEF"))
                textSize = 14f
                setPadding(8, 0, 8, 0)
            }
            voiceRow.addView(voiceTv)

            bubble.addView(voiceRow)
        } else {
            val msgTv = TextView(context).apply {
                text = item.entity.text
                setTextColor(Color.parseColor("#E9EDEF"))
                textSize = 15f
                setLineSpacing(4f, 1.1f)
            }
            bubble.addView(msgTv)
        }

        val footerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 8, 0, 0)
        }

        val timeTv = TextView(context).apply {
            text = item.formattedTime
            setTextColor(Color.parseColor("#8696A0"))
            textSize = 11f
        }
        footerRow.addView(timeTv)

        if (isOutgoing) {
            val statusTv = TextView(context).apply {
                text = " " + item.statusIcon
                setTextColor(if (item.entity.status == 1) Color.parseColor("#53BDEB") else Color.parseColor("#8696A0"))
                textSize = 11f
            }
            footerRow.addView(statusTv)
        }

        bubble.addView(footerRow)
        container.addView(bubble)

        return container
    }
}
