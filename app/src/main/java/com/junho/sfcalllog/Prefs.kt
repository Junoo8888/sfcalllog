package com.junho.sfcalllog

import android.content.Context
import android.content.SharedPreferences

/** 앱 설정·로그인 정보 저장소 (이 폰 안에만 저장됨) */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("sfcalllog", Context.MODE_PRIVATE)

    var consumerKey: String
        get() = sp.getString("consumerKey", "") ?: ""
        set(v) = sp.edit().putString("consumerKey", v.trim()).apply()

    var consumerSecret: String
        get() = sp.getString("consumerSecret", "") ?: ""
        set(v) = sp.edit().putString("consumerSecret", v.trim()).apply()

    var loginHost: String
        get() = sp.getString("loginHost", DEFAULT_LOGIN_HOST) ?: DEFAULT_LOGIN_HOST
        set(v) {
            var h = v.trim().trimEnd('/')
            if (h.isEmpty()) h = DEFAULT_LOGIN_HOST
            if (!h.startsWith("http")) h = "https://$h"
            sp.edit().putString("loginHost", h).apply()
        }

    var accessToken: String
        get() = sp.getString("accessToken", "") ?: ""
        set(v) = sp.edit().putString("accessToken", v).apply()

    var refreshToken: String
        get() = sp.getString("refreshToken", "") ?: ""
        set(v) = sp.edit().putString("refreshToken", v).commit().let { }

    var instanceUrl: String
        get() = sp.getString("instanceUrl", "") ?: ""
        set(v) = sp.edit().putString("instanceUrl", v).apply()

    var userName: String
        get() = sp.getString("userName", "") ?: ""
        set(v) = sp.edit().putString("userName", v).apply()

    var pkceVerifier: String
        get() = sp.getString("pkceVerifier", "") ?: ""
        set(v) = sp.edit().putString("pkceVerifier", v).commit().let { }

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** 통화 상태 추적용 (IDLE / RINGING / OFFHOOK) */
    var lastCallState: String
        get() = sp.getString("lastCallState", "IDLE") ?: "IDLE"
        set(v) = sp.edit().putString("lastCallState", v).commit().let { }

    // ── 통화 내용 AI 요약 ──
    var aiEnabled: Boolean
        get() = sp.getBoolean("aiEnabled", false)
        set(v) = sp.edit().putBoolean("aiEnabled", v).apply()

    var geminiKey: String
        get() = sp.getString("geminiKey", "") ?: ""
        set(v) = sp.edit().putString("geminiKey", v.trim()).apply()

    /** 비워두면 자동 선택 */
    var geminiModel: String
        get() = sp.getString("geminiModel", "") ?: ""
        set(v) = sp.edit().putString("geminiModel", v.trim()).apply()

    var geminiAutoModel: String
        get() = sp.getString("geminiAutoModel", "") ?: ""
        set(v) = sp.edit().putString("geminiAutoModel", v).apply()

    var saveTranscript: Boolean
        get() = sp.getBoolean("saveTranscript", false)
        set(v) = sp.edit().putBoolean("saveTranscript", v).apply()

    var ignoredNumbers: Set<String>
        get() = sp.getStringSet("ignored", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("ignored", v).apply()

    val isLoggedIn: Boolean
        get() = refreshToken.isNotEmpty() && instanceUrl.isNotEmpty()

    fun ignore(number: String) {
        val n = Phone.national(number)
        if (n.isNotEmpty()) ignoredNumbers = ignoredNumbers + n
    }

    fun isIgnored(number: String): Boolean = Phone.national(number) in ignoredNumbers

    fun logout() {
        sp.edit().remove("accessToken").remove("refreshToken").remove("instanceUrl")
            .remove("userName").apply()
    }

    companion object {
        const val DEFAULT_LOGIN_HOST = "https://login.salesforce.com"
        const val REDIRECT_URI = "sfcalllog://oauth/callback"
    }
}

/** 방금 끝난 통화 정보 */
data class CallInfo(
    val number: String,
    val durationSec: Int,
    val outgoing: Boolean,
    val dateMillis: Long
) {
    fun toBundleExtras(i: android.content.Intent): android.content.Intent = i
        .putExtra("number", number)
        .putExtra("duration", durationSec)
        .putExtra("outgoing", outgoing)
        .putExtra("date", dateMillis)

    companion object {
        fun fromIntent(i: android.content.Intent): CallInfo? {
            val n = i.getStringExtra("number") ?: return null
            return CallInfo(
                n,
                i.getIntExtra("duration", 0),
                i.getBooleanExtra("outgoing", true),
                i.getLongExtra("date", System.currentTimeMillis())
            )
        }
    }
}

/** 전화번호 형식 정리: 세일즈포스에는 821012345678 처럼 저장돼 있어서 여러 형식으로 바꿔 찾음 */
object Phone {
    fun digits(raw: String): String = raw.filter { it.isDigit() }

    /** 01012345678 형식 */
    fun national(raw: String): String {
        val d = digits(raw)
        return when {
            d.startsWith("82") && d.length >= 10 -> "0" + d.substring(2).trimStart('0')
            else -> d
        }
    }

    fun hyphen(raw: String): String {
        val n = national(raw)
        return when {
            n.startsWith("02") && n.length == 10 -> "${n.substring(0, 2)}-${n.substring(2, 6)}-${n.substring(6)}"
            n.startsWith("02") && n.length == 9 -> "${n.substring(0, 2)}-${n.substring(2, 5)}-${n.substring(5)}"
            n.length == 11 -> "${n.substring(0, 3)}-${n.substring(3, 7)}-${n.substring(7)}"
            n.length == 10 -> "${n.substring(0, 3)}-${n.substring(3, 6)}-${n.substring(6)}"
            else -> n
        }
    }

    /** 세일즈포스에 저장돼 있을 법한 모든 형식 */
    fun variants(raw: String): List<String> {
        val n = national(raw)
        if (n.length < 8) return listOf(digits(raw)).filter { it.isNotEmpty() }
        val noZero = n.removePrefix("0")
        val h = hyphen(n)
        val hNoZero = h.removePrefix("0")
        return listOf(
            n, h,
            "82$noZero", "+82$noZero",
            "82-$hNoZero", "+82-$hNoZero", "+82 $hNoZero", "82 $hNoZero",
            "+82 $noZero", "(+82)$noZero"
        ).distinct()
    }
}
