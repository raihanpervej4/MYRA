package com.myra.assistant.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R

// ---------------------------------------------------------------- Waveform
class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bars = 20
    private val heights = FloatArray(bars) { 0.05f }
    private val targets = FloatArray(bars) { 0.05f }
    private var amplitude = 0f
    private var running = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            for (i in 0 until bars) {
                targets[i] = (0.05f + amplitude * (0.4f + Math.random().toFloat() * 0.6f)).coerceIn(0.05f, 1f)
                heights[i] += (targets[i] - heights[i]) * 0.3f
            }
            invalidate()
            postOnAnimationDelayed(this, 40)
        }
    }

    fun startAnimation() { if (!running) { running = true; post(tick) } }
    fun stopAnimation() { running = false; amplitude = 0f; invalidate() }
    fun setAmplitude(rms: Float) { amplitude = rms.coerceIn(0f, 1f) }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); startAnimation() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val slot = width.toFloat() / bars
        val bw = slot * 0.55f
        for (i in 0 until bars) {
            val h = (heights[i] * height).coerceAtLeast(3f)
            val alpha = (150 + 105 * heights[i]).toInt().coerceIn(150, 255)
            paint.color = Color.argb(alpha, 255, 23, 68)
            val left = i * slot + (slot - bw) / 2
            rect.set(left, (height - h) / 2f, left + bw, (height + h) / 2f)
            canvas.drawRoundRect(rect, bw / 2, bw / 2, paint)
        }
    }
}

// ---------------------------------------------------------------- Chat
data class ChatMessage(val text: String, val isUser: Boolean, val timestamp: Long = System.currentTimeMillis())

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    private val items = mutableListOf<ChatMessage>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.chatText)
    }

    fun lastMyraText(): String? = items.lastOrNull { !it.isUser }?.text

    /** Returns true if added (MYRA duplicates of the last MYRA message are skipped). */
    fun addMessage(m: ChatMessage): Boolean {
        if (!m.isUser && items.lastOrNull()?.let { !it.isUser && it.text == m.text } == true) return false
        items.add(m)
        notifyItemInserted(items.size - 1)
        return true
    }

    override fun getItemViewType(position: Int) = if (items[position].isUser) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == 0) R.layout.item_chat_user else R.layout.item_chat_myra
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) { holder.text.text = items[position].text }
    override fun getItemCount() = items.size
}
