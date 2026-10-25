package com.ytdlpgui.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.ytdlpgui.R

/**
 * Toast داخلي مخصص وموحد يعرض أيقونة التطبيق الفعلية (الكلاكيت بالتدرج الكامل)
 * دون الاعتماد على كاش رسائل النظام في MIUI أو غيرها.
 */
object AppToast {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentToastView: View? = null
    private var dismissRunnable: Runnable? = null

    fun show(context: Context?, resId: Int) {
        if (context == null) return
        show(context, context.getString(resId), false)
    }

    fun show(context: Context?, resId: Int, vararg args: Any) {
        if (context == null) return
        show(context, context.getString(resId, *args), false)
    }

    fun showLong(context: Context?, resId: Int) {
        if (context == null) return
        show(context, context.getString(resId), true)
    }

    fun show(context: Context?, message: CharSequence, isLong: Boolean = false) {
        if (context == null || message.isBlank()) return

        mainHandler.post {
            val activity = findActivity(context)
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                Toast.makeText(context.applicationContext, message, if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                return@post
            }

            dismissCurrent(immediate = true)

            val decor = activity.window.decorView as? ViewGroup ?: return@post
            val inflater = LayoutInflater.from(activity)
            val toastView = inflater.inflate(R.layout.view_app_toast, decor, false)

            val toastText = toastView.findViewById<TextView>(R.id.toastText)
            val toastIcon = toastView.findViewById<ImageView>(R.id.toastIcon)

            toastText.text = message
            toastIcon.setImageResource(R.drawable.ic_app_logo_drawer)

            toastView.setOnClickListener {
                dismissCurrent(immediate = false)
            }

            val density = activity.resources.displayMetrics.density
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = (82 * density).toInt()
                leftMargin = (24 * density).toInt()
                rightMargin = (24 * density).toInt()
            }

            decor.addView(toastView, params)
            currentToastView = toastView

            toastView.alpha = 0f
            toastView.translationY = 32f
            toastView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .start()

            val durationMs = if (isLong) 3500L else 2200L
            val runnable = Runnable {
                dismissCurrent(immediate = false)
            }
            dismissRunnable = runnable
            mainHandler.postDelayed(runnable, durationMs)
        }
    }

    private fun dismissCurrent(immediate: Boolean) {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null

        val view = currentToastView ?: return
        currentToastView = null

        if (immediate) {
            (view.parent as? ViewGroup)?.removeView(view)
        } else {
            view.animate()
                .alpha(0f)
                .translationY(25f)
                .setDuration(180)
                .withEndAction {
                    (view.parent as? ViewGroup)?.removeView(view)
                }
                .start()
        }
    }

    private fun findActivity(context: Context): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
