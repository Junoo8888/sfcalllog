package com.junho.sfcalllog

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast

/** 첫 화면: 세일즈포스 연결 + 권한 설정 + 테스트 */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = 0xFFFFFFFF.toInt()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun render() {
        val c = this
        val scroll = ScrollView(c).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        val body = Ui.vbox(c, 20)
        scroll.addView(body)

        body.addView(Ui.text(c, "세포 콜로그", 26f, Ui.TEXT, bold = true))
        body.addView(Ui.text(c, "통화가 끝나면 세일즈포스에 바로 기록", 14f, Ui.SUB), Ui.lp(c, Ui.MATCH, Ui.WRAP, 4))

        // ① 세일즈포스 연결
        val sfCard = card(body, "① 세일즈포스 연결")
        if (prefs.isLoggedIn) {
            sfCard.addView(Ui.text(c, "✓ 연결됨" + (if (prefs.userName.isNotEmpty()) " · ${prefs.userName}" else ""), 15f, Ui.GREEN, bold = true))
            sfCard.addView(Ui.text(c, prefs.instanceUrl, 12f, Ui.SUB), Ui.lp(c, Ui.MATCH, Ui.WRAP, 2))
            val row = Ui.hbox(c)
            row.addView(Ui.button(c, "다시 로그인", primary = false) { startLogin() }, LinearLayout.LayoutParams(0, Ui.dp(c, 46), 1f))
            row.addView(Ui.button(c, "로그아웃", primary = false) { prefs.logout(); render() },
                LinearLayout.LayoutParams(0, Ui.dp(c, 46), 1f).apply { leftMargin = Ui.dp(c, 8) })
            sfCard.addView(row, Ui.lp(c, Ui.MATCH, Ui.WRAP, 12))
        } else {
            sfCard.addView(Ui.text(c, "Connected App 정보를 넣고 로그인하세요.", 14f, Ui.SUB))
            val key = input("Consumer Key", prefs.consumerKey, secret = false)
            val sec = input("Consumer Secret (없으면 비워두기)", prefs.consumerSecret, secret = true)
            val host = input("로그인 주소", prefs.loginHost, secret = false)
            sfCard.addView(key, Ui.lp(c, Ui.MATCH, Ui.WRAP, 10))
            sfCard.addView(sec, Ui.lp(c, Ui.MATCH, Ui.WRAP, 8))
            sfCard.addView(host, Ui.lp(c, Ui.MATCH, Ui.WRAP, 8))
            sfCard.addView(Ui.button(c, "저장하고 세일즈포스 로그인") {
                prefs.consumerKey = key.text.toString()
                prefs.consumerSecret = sec.text.toString()
                prefs.loginHost = host.text.toString()
                if (prefs.consumerKey.isEmpty()) toast("Consumer Key를 입력해주세요") else startLogin()
            }, Ui.lp(c, Ui.MATCH, Ui.dp(c, 50), 12))
            sfCard.addView(Ui.text(c, "Connected App 콜백 URL에 ${Prefs.REDIRECT_URI} 가 등록돼 있어야 합니다.", 12f, Ui.SUB),
                Ui.lp(c, Ui.MATCH, Ui.WRAP, 8))
        }

        // ② 권한
        val pCard = card(body, "② 폰 권한 (4개 모두 ✓ 되어야 함)")
        permRow(pCard, "전화 상태 · 통화기록 읽기",
            granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.READ_CALL_LOG)) {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG), 1)
        }
        permRow(pCard, "다른 앱 위에 표시 (팝업)", Settings.canDrawOverlays(c)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)
        permRow(pCard, "알림 (팝업이 막힐 때 대신 알림)", notifOk) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }
        val pm = getSystemService(PowerManager::class.java)
        permRow(pCard, "배터리 사용 제한 없음", pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        // ③ 사용
        val uCard = card(body, "③ 사용")
        val sw = Switch(c).apply {
            text = "통화 끝나면 기록 여부 묻기"
            textSize = 15f
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, on -> prefs.enabled = on }
        }
        uCard.addView(sw, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0))
        uCard.addView(Ui.button(c, "팝업 미리보기 (마지막 통화로)", primary = false) { previewPopup() },
            Ui.lp(c, Ui.MATCH, Ui.dp(c, 48), 12))
        uCard.addView(Ui.button(c, "마지막 통화 지금 기록하기", primary = false) { openLastCall() },
            Ui.lp(c, Ui.MATCH, Ui.dp(c, 48), 8))
        val ignored = prefs.ignoredNumbers.size
        if (ignored > 0) {
            uCard.addView(Ui.button(c, "'안 묻기' 번호 ${ignored}개 초기화", primary = false) {
                prefs.ignoredNumbers = emptySet(); render()
            }, Ui.lp(c, Ui.MATCH, Ui.dp(c, 48), 8))
        }

        // ④ 통화 내용 AI 요약
        val aCard = card(body, "④ 통화 내용 AI 요약 (화자 구분)")
        aCard.addView(Ui.text(c, "갤럭시 통화 녹음 → 나/고객 대화록 + 요약 → 세포 메모에 자동 입력", 13f, Ui.SUB))
        val audioPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        permRow(aCard, "통화 녹음 파일 읽기", granted(audioPerm)) {
            requestPermissions(arrayOf(audioPerm), 3)
        }
        val gKey = input("Gemini API 키", prefs.geminiKey, secret = true)
        aCard.addView(gKey, Ui.lp(c, Ui.MATCH, Ui.WRAP, 10))
        val gModel = input("모델 (비워두면 자동 선택)", prefs.geminiModel, secret = false)
        aCard.addView(gModel, Ui.lp(c, Ui.MATCH, Ui.WRAP, 8))
        if (prefs.geminiAutoModel.isNotEmpty() && prefs.geminiModel.isEmpty()) {
            aCard.addView(Ui.text(c, "자동 선택된 모델: ${prefs.geminiAutoModel}", 12f, Ui.SUB), Ui.lp(c, Ui.MATCH, Ui.WRAP, 4))
        }
        val aiSw = Switch(c).apply {
            text = "통화 끝나면 AI 요약 켜기"
            textSize = 15f
            isChecked = prefs.aiEnabled
            setOnCheckedChangeListener { _, on -> prefs.aiEnabled = on }
        }
        aCard.addView(aiSw, Ui.lp(c, Ui.MATCH, Ui.WRAP, 10))
        aCard.addView(Ui.button(c, "저장하고 연결 테스트") { btn ->
            prefs.geminiKey = gKey.text.toString()
            prefs.geminiModel = gModel.text.toString()
            if (prefs.geminiKey.isEmpty()) { toast("Gemini API 키를 입력해주세요"); return@button }
            btn.isEnabled = false
            java.util.concurrent.Executors.newSingleThreadExecutor().execute {
                val msg = try {
                    val m = CallSummarizer.testKey(this)
                    prefs.aiEnabled = true
                    "✓ 연결 성공 · 모델 $m"
                } catch (e: Exception) {
                    "연결 실패: ${e.message}"
                }
                runOnUiThread { toast(msg); render() }
            }
        }, Ui.lp(c, Ui.MATCH, Ui.dp(c, 48), 10))

        intent?.getStringExtra("loginMessage")?.let {
            body.addView(Ui.text(c, it, 13f, if (it.contains("실패")) Ui.RED else Ui.GREEN), Ui.lp(c, Ui.MATCH, Ui.WRAP, 16))
        }

        setContentView(scroll)
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val box = Ui.vbox(this, 16).apply { background = Ui.rounded(this@MainActivity, 0xFFF9FAFB.toInt(), 16, 0xFFE5E8EB.toInt()) }
        box.addView(Ui.text(this, title, 17f, Ui.TEXT, bold = true), Ui.lp(this, Ui.MATCH, Ui.WRAP, 0).apply { bottomMargin = Ui.dp(this@MainActivity, 8) })
        parent.addView(box, Ui.lp(this, Ui.MATCH, Ui.WRAP, 20))
        return box
    }

    private fun permRow(parent: LinearLayout, label: String, ok: Boolean, onFix: () -> Unit) {
        val row = Ui.hbox(this)
        row.addView(Ui.text(this, (if (ok) "✓  " else "✗  ") + label, 15f, if (ok) Ui.GREEN else Ui.TEXT),
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        if (!ok) row.addView(Ui.button(this, "설정") { onFix() }, LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 40)))
        parent.addView(row, Ui.lp(this, Ui.MATCH, Ui.WRAP, 8))
    }

    private fun input(hint: String, value: String, secret: Boolean): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine()
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        background = Ui.rounded(this@MainActivity, 0xFFFFFFFF.toInt(), 10, 0xFFE5E8EB.toInt())
        setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 10))
    }

    private fun startLogin() {
        try {
            val url = SalesforceClient(this).buildAuthorizeUrl()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("브라우저를 열 수 없어요: ${e.message}")
        }
    }

    private fun lastCallOrToast(): CallInfo? {
        if (!granted(Manifest.permission.READ_CALL_LOG)) { toast("먼저 통화기록 권한을 켜주세요"); return null }
        val info = CallLogReader.query(this)
        if (info == null) toast("통화기록이 없어요")
        return info
    }

    private fun previewPopup() {
        val info = lastCallOrToast() ?: return
        if (!Settings.canDrawOverlays(this)) { toast("'다른 앱 위에 표시' 권한을 먼저 켜주세요"); return }
        OverlayPrompt.show(applicationContext, info)
        moveTaskToBack(true)
    }

    private fun openLastCall() {
        val info = lastCallOrToast() ?: return
        startActivity(info.toBundleExtras(Intent(this, LogCallActivity::class.java)))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
