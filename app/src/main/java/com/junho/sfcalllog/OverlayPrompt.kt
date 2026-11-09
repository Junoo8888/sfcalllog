package com.junho.sfcalllog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** 1단계 팝업: "세포에 기록할까요?" (다른 앱 위에 뜨는 작은 카드) */
object OverlayPrompt {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var current: View? = null

    /** 미리 찾아둔 번호 검색 결과 (팝업 → 상세화면 사이 재검색 방지) */
    @Volatile var cachedNumber: String? = null
    @Volatile var cachedCandidates: List<Candidate>? = null

    fun show(ctx: Context, info: CallInfo) {
        // 통화 내용 AI 요약은 팝업이 뜨는 동시에 미리 시작 (기록하기 누를 때쯤 완료되도록)
        try { CallSummarizer.start(ctx, info) } catch (_: Exception) { }
        if (!Settings.canDrawOverlays(ctx)) {
            Notifier.notifyCall(ctx, info)
            return
        }
        dismiss(ctx)
        val c = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Light)
        val wm = ctx.getSystemService(WindowManager::class.java)

        val card = Ui.vbox(c, 18).apply {
            background = Ui.rounded(c, 0xFFFFFFFF.toInt(), 20, 0xFFE5E8EB.toInt())
            elevation = Ui.dp(c, 12).toFloat()
        }
        val kind = if (info.outgoing) "발신" else "수신"
        val contact = ContactLookup.name(ctx, info.number)
        val who = contact ?: Phone.hyphen(info.number)
        card.addView(Ui.text(c, "📞 $kind · $who · ${Ui.duration(info.durationSec)}", 13f, Ui.SUB))
        val matchLine = Ui.text(c, "세포에서 찾는 중…", 17f, Ui.TEXT, bold = true)
        card.addView(matchLine, Ui.lp(c, Ui.MATCH, Ui.WRAP, 6))
        card.addView(Ui.text(c, "세일즈포스에 기록할까요?", 15f, Ui.SUB), Ui.lp(c, Ui.MATCH, Ui.WRAP, 2))

        val row = Ui.hbox(c)
        val prefs = Prefs(ctx)
        row.addView(Ui.button(c, "X", primary = false) { dismiss(ctx) },
            LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 48)))
        row.addView(Ui.button(c, "이 번호 안 묻기", primary = false) {
            prefs.ignore(info.number)
            dismiss(ctx)
        }, LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f).apply { leftMargin = Ui.dp(c, 8) })
        row.addView(Ui.button(c, "기록하기") { openLogScreen(ctx, info) },
            LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1.3f).apply { leftMargin = Ui.dp(c, 8) })
        card.addView(row, Ui.lp(c, Ui.MATCH, Ui.WRAP, 14))

        val frame = LinearLayout(c).apply {
            val p = Ui.dp(c, 12)
            setPadding(p, p, p, p)
            addView(card, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = Ui.dp(c, 24)
        }

        try {
            wm.addView(frame, params)
            current = frame
        } catch (e: Exception) {
            Notifier.notifyCall(ctx, info)
            return
        }

        // 2분 동안 아무것도 안 누르면 자동으로 닫힘 (알림으로 남겨둠)
        main.postDelayed({
            if (current === frame) {
                dismiss(ctx)
                Notifier.notifyCall(ctx, info)
            }
        }, 120_000)

        // 번호로 리드 미리 찾아서 보여주기
        cachedNumber = null
        cachedCandidates = null
        if (prefs.isLoggedIn) {
            io.execute {
                val text = try {
                    val found = SalesforceClient(ctx).findByPhone(info.number)
                    cachedNumber = info.number
                    cachedCandidates = found
                    when {
                        found.isEmpty() && contact != null -> "번호 매칭 없음 · 연락처 '$contact'로 찾을게요"
                        found.isEmpty() -> "세포에 없는 번호예요"
                        found.size == 1 -> "${found[0].title} · ${found[0].personName.ifEmpty { found[0].type }}"
                        else -> "${found[0].title} 외 ${found.size - 1}건"
                    }
                } catch (e: Exception) {
                    "세포 연결 오류 (기록하기에서 다시 시도)"
                }
                main.post { if (current === frame) matchLine.text = text }
            }
        } else {
            matchLine.text = "세포 로그인이 필요해요 (앱에서 로그인)"
        }
    }

    private fun openLogScreen(ctx: Context, info: CallInfo) {
        val i = info.toBundleExtras(Intent(ctx, LogCallActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val before = LogCallActivity.lastOpenedAt
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
        }
        // 화면 전환이 막히는 기종 대비: 1.5초 안에 안 열리면 알림으로 대체
        main.postDelayed({
            dismiss(ctx)
            if (LogCallActivity.lastOpenedAt == before) Notifier.notifyCall(ctx, info)
        }, 1500)
    }

    fun dismiss(ctx: Context) {
        val v = current ?: return
        current = null
        try {
            ctx.getSystemService(WindowManager::class.java).removeView(v)
        } catch (_: Exception) {
        }
    }
}

/** 팝업을 못 띄우는 상황(권한 없음 등)에서는 알림으로 대체 */
object Notifier {
    private const val CHANNEL = "call_log"

    fun notifyCall(ctx: Context, info: CallInfo) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "통화 후 세포 기록", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val i = info.toBundleExtras(Intent(ctx, LogCallActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx, info.dateMillis.toInt(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val time = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(info.dateMillis))
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("세포에 기록할까요? · ${Phone.hyphen(info.number)}")
            .setContentText("$time 통화 · ${Ui.duration(info.durationSec)} · 눌러서 기록")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(Phone.national(info.number).hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}
