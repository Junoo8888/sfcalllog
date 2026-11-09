package com.junho.sfcalllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/** 폰에 저장된 연락처 이름으로 브랜드 추정 (예: "에스트라 우상아 팀장" → "에스트라") */
object ContactLookup {

    fun name(ctx: Context, number: String): String? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        if (number.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private val TITLES = listOf(
        "대표님", "대표", "사장님", "사장", "이사님", "이사", "본부장", "실장님", "실장", "팀장님", "팀장",
        "부장", "차장", "과장", "대리", "주임", "사원", "매니저", "MD", "md", "담당자", "담당", "마케터",
        "마케팅", "님", "선생님", "고객", "거래처", "아임웹"
    )

    /** 연락처 이름에서 검색어 후보 뽑기: 앞 단어부터 최대 3개 */
    fun searchTerms(contactName: String): List<String> {
        var s = contactName.replace(Regex("[()\\[\\]{}<>/|_\\-·,.]"), " ")
        for (t in TITLES) s = s.replace(t, " ")
        val words = s.split(Regex("\\s+")).map { it.trim() }.filter { it.length >= 2 && !it.all(Char::isDigit) }
        // 보통 "브랜드 이름 직함" 순서로 저장하므로 앞 단어부터
        val out = linkedSetOf<String>()
        out += words
        return out.take(3)
    }
}
