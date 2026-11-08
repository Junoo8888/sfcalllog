package com.junho.sfcalllog

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 화면을 코드로 그리기 위한 작은 도구 모음 */
object Ui {
    const val BLUE = 0xFF3182F6.toInt()
    const val GRAY_BG = 0xFFF2F4F6.toInt()
    const val TEXT = 0xFF191F28.toInt()
    const val SUB = 0xFF6B7684.toInt()
    const val RED = 0xFFF04452.toInt()
    const val GREEN = 0xFF03B26C.toInt()

    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Int = 12, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(ctx, 1), strokeColor)
        }

    fun vbox(ctx: Context, padDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp)
        setPadding(p, p, p, p)
    }

    fun hbox(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun text(ctx: Context, s: CharSequence, sizeSp: Float = 15f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun button(ctx: Context, label: String, primary: Boolean = true, onClick: (View) -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (primary) Color.WHITE else TEXT)
            stateListAnimator = null
            val bg = rounded(ctx, if (primary) BLUE else GRAY_BG, 12)
            background = RippleDrawable(ColorStateList.valueOf(0x22000000), bg, null)
            minHeight = dp(ctx, 48)
            setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0)
            setOnClickListener(onClick)
        }

    fun chip(ctx: Context, label: String, onClick: (View) -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(TEXT)
            background = RippleDrawable(ColorStateList.valueOf(0x22000000), rounded(ctx, GRAY_BG, 18), null)
            setPadding(dp(ctx, 14), dp(ctx, 9), dp(ctx, 14), dp(ctx, 9))
            setOnClickListener(onClick)
        }

    fun lp(ctx: Context, w: Int, h: Int, topDp: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(ctx, topDp) }

    const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    fun duration(sec: Int): String = when {
        sec <= 0 -> "연결 안 됨"
        sec < 60 -> "${sec}초"
        else -> "${sec / 60}분 ${sec % 60}초"
    }
}
