package com.junho.sfcalllog

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.util.concurrent.Executors

/** 세일즈포스 로그인 후 sfcalllog://oauth/callback?code=... 로 돌아오면 토큰 교환 */
class OAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val code = data?.getQueryParameter("code")
        val error = data?.getQueryParameter("error_description") ?: data?.getQueryParameter("error")

        if (code.isNullOrEmpty()) {
            done("로그인 실패: ${error ?: "코드 없음"}")
            return
        }
        Executors.newSingleThreadExecutor().execute {
            val msg = try {
                SalesforceClient(this).exchangeCode(code)
                val name = Prefs(this).userName
                "세일즈포스 로그인 완료" + if (name.isNotEmpty()) " ($name)" else ""
            } catch (e: Exception) {
                "로그인 실패: ${e.message}"
            }
            runOnUiThread { done(msg) }
        }
    }

    private fun done(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("loginMessage", msg)
        )
        finish()
    }
}
