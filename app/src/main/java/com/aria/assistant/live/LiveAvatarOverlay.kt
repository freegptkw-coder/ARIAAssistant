package com.aria.assistant.live

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aria.assistant.R
import java.io.File

class LiveAvatarOverlay(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: FrameLayout? = null
    private var avatarView: ImageView? = null
    private var bubbleView: TextView? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var hideTalkRunnable: Runnable? = null

    fun show() {
        mainHandler.post {
            if (!Settings.canDrawOverlays(context) || rootView != null) return@post

            val container = FrameLayout(context)
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(14, 10, 14, 10)
                background = ContextCompat.getDrawable(context, R.drawable.bg_security_card)
            }

            val avatar = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(120, 120)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            applyAvatarImage(avatar)

            val bubble = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = 12
                }
                text = "Hi, ami ARIA 🌸"
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, android.R.color.white))
                maxWidth = 520
            }

            content.addView(avatar)
            content.addView(bubble)
            container.addView(content)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 28
                y = 220
            }

            enableDrag(container, params)
            wm.addView(container, params)

            rootView = container
            avatarView = avatar
            bubbleView = bubble
            setSpeaking(false)
        }
    }

    fun hide() {
        mainHandler.post {
            pulseAnimator?.cancel()
            pulseAnimator = null
            hideTalkRunnable?.let { mainHandler.removeCallbacks(it) }
            hideTalkRunnable = null

            rootView?.let {
                runCatching { wm.removeView(it) }
            }
            rootView = null
            avatarView = null
            bubbleView = null
        }
    }

    fun updateMessage(text: String) {
        mainHandler.post {
            bubbleView?.text = text.take(160)
            hideTalkRunnable?.let { mainHandler.removeCallbacks(it) }
            hideTalkRunnable = Runnable {
                bubbleView?.text = "Listening..."
            }.also {
                mainHandler.postDelayed(it, 6000)
            }
        }
    }

    fun setSpeaking(speaking: Boolean) {
        mainHandler.post {
            val target = avatarView ?: return@post
            pulseAnimator?.cancel()
            target.scaleX = 1f
            target.scaleY = 1f
            pulseAnimator = ObjectAnimator.ofFloat(target, View.ALPHA, 1f, if (speaking) 0.72f else 0.86f, 1f).apply {
                duration = if (speaking) 420L else 900L
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        }
    }

    private fun applyAvatarImage(imageView: ImageView) {
        val candidates = listOf(
            "/sdcard/123/anime_girl.png",
            "/sdcard/123/aria_anime.png",
            "/sdcard/Download/aria_anime.png"
        )
        val file = candidates.map { File(it) }.firstOrNull { it.exists() && it.length() > 0 }
        if (file != null) {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            if (bmp != null) {
                imageView.setImageBitmap(bmp)
                return
            }
        }
        imageView.setImageResource(R.mipmap.ic_launcher_round)
    }

    private fun enableDrag(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    runCatching { wm.updateViewLayout(view, params) }
                    true
                }
                else -> false
            }
        }
    }
}
