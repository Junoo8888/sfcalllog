package com.junho.sfcalllog

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.TelephonyManager

/**
 * 전화 상태 변화를 받는 곳.
 * 통화 중(OFFHOOK) → 끊김(IDLE) 이 되면 = 실제로 연결됐던 통화(발신 포함)가 끝난 것 → 팝업.
 * 받지 않은 수신 전화(RINGING → IDLE)는 무시.
 */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val raw = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val now = when (raw) {
            TelephonyManager.EXTRA_STATE_RINGING -> "RINGING"
            TelephonyManager.EXTRA_STATE_OFFHOOK -> "OFFHOOK"
            else -> "IDLE"
        }
        val prefs = Prefs(context)
        val prev = prefs.lastCallState
        if (now == prev) return            // 같은 방송이 두 번 오는 경우 무시
        prefs.lastCallState = now

        if (now == "IDLE" && prev == "OFFHOOK" && prefs.enabled) {
            @Suppress("DEPRECATION")
            val extraNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            val app = context.applicationContext
            val pending = goAsync()
            CallLogReader.readLatest(app, extraNumber) { info ->
                try {
                    if (info != null && info.number.isNotBlank() && !Prefs(app).isIgnored(info.number)) {
                        OverlayPrompt.show(app, info)
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}

/** 통화기록에서 방금 끝난 통화(번호·시간·발신/수신) 읽기 */
object CallLogReader {
    private val delays = longArrayOf(1200, 1500, 2000)   // 통화기록 저장이 늦을 수 있어 최대 3번 시도

    fun readLatest(ctx: Context, fallbackNumber: String?, attempt: Int = 0, cb: (CallInfo?) -> Unit) {
        Handler(Looper.getMainLooper()).postDelayed({
            val info = query(ctx)
            val fresh = info != null &&
                System.currentTimeMillis() - (info.dateMillis + info.durationSec * 1000L) < 90_000
            when {
                fresh -> cb(info)
                attempt + 1 < delays.size -> readLatest(ctx, fallbackNumber, attempt + 1, cb)
                !fallbackNumber.isNullOrBlank() ->
                    cb(CallInfo(fallbackNumber, 0, false, System.currentTimeMillis()))
                else -> cb(info)
            }
        }, delays[attempt])
    }

    fun query(ctx: Context): CallInfo? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null, CallLog.Calls.DATE + " DESC"
            )?.use { c ->
                if (!c.moveToFirst()) return null
                CallInfo(
                    number = c.getString(0) ?: "",
                    durationSec = c.getInt(1),
                    outgoing = c.getInt(2) == CallLog.Calls.OUTGOING_TYPE,
                    dateMillis = c.getLong(3)
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
