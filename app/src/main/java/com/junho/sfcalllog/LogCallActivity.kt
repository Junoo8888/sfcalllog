package com.junho.sfcalllog

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** 2단계: 어떤 리드에 기록할지 고르고 결과 입력 → 세포 Task 생성 */
class LogCallActivity : Activity() {

    companion object {
        @Volatile var lastOpenedAt: Long = 0L

        /** 준호님 실제 기록 패턴에서 뽑은 자주 쓰는 결과 */
        val QUICK_RESULTS = listOf(
            "부재중", "타겟 아님, 퀄리파잉 탈락", "퀄리파잉 탈락", "기 어레인지 건",
            "후속 연락 예정", "미팅 어레인지", "담당자 연결 요청", "자료 발송 요청"
        )
    }

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var info: CallInfo
    private lateinit var sf: SalesforceClient

    private lateinit var listBox: LinearLayout
    private lateinit var listTitle: TextView
    private lateinit var searchInput: EditText
    private lateinit var memo: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var saveBtn: View

    private lateinit var aiBox: LinearLayout
    private lateinit var aiStatus: TextView
    private lateinit var transcriptView: TextView
    private lateinit var transcriptToggle: TextView
    private lateinit var saveTranscriptBox: android.widget.CheckBox
    private var aiJob: SummaryJob? = null
    private var autoMemo: String = ""
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    private var candidates: List<Candidate> = emptyList()
    private var selected: Candidate? = null
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastOpenedAt = System.currentTimeMillis()
        OverlayPrompt.dismiss(applicationContext)
        val ci = CallInfo.fromIntent(intent)
        if (ci == null) { finish(); return }
        info = ci
        sf = SalesforceClient(this)
        buildUi()

        if (!Prefs(this).isLoggedIn) {
            showStatus("세일즈포스 로그인이 필요해요. 앱 첫 화면에서 로그인해주세요.", Ui.RED)
            listTitle.text = "로그인 필요"
            return
        }
        if (info.durationSec <= 0 && info.outgoing) setAutoMemo("부재중")
        loadByPhone()
        startAi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    // ───────────── 화면 ─────────────

    private fun buildUi() {
        val c = this
        window.statusBarColor = 0xFFFFFFFF.toInt()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = Ui.vbox(c).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        val scroll = ScrollView(c).apply { isFillViewport = true }
        val body = Ui.vbox(c, 20)
        scroll.addView(body)

        // 통화 정보
        val time = SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(info.dateMillis))
        val kind = if (info.outgoing) "발신" else "수신"
        body.addView(Ui.text(c, "세포에 통화 기록", 22f, Ui.TEXT, bold = true))
        body.addView(Ui.text(c, "📞 ${Phone.hyphen(info.number)} · $kind · ${Ui.duration(info.durationSec)} · $time", 14f, Ui.SUB),
            Ui.lp(c, Ui.MATCH, Ui.WRAP, 6))

        // 후보 목록
        val head = Ui.hbox(c)
        listTitle = Ui.text(c, "번호로 찾는 중…", 16f, Ui.TEXT, bold = true)
        head.addView(listTitle, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        progress = ProgressBar(c).apply { isIndeterminate = true }
        head.addView(progress, LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)))
        body.addView(head, Ui.lp(c, Ui.MATCH, Ui.WRAP, 24))

        listBox = Ui.vbox(c)
        body.addView(listBox, Ui.lp(c, Ui.MATCH, Ui.WRAP, 8))

        // 이름 검색
        val searchRow = Ui.hbox(c)
        searchInput = EditText(c).apply {
            hint = "회사·브랜드·담당자 이름 (예: 아임웹)"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = Ui.rounded(c, Ui.GRAY_BG, 12)
            setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12))
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) { runNameSearch(); true } else false
            }
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        searchRow.addView(Ui.button(c, "검색", primary = false) { runNameSearch() },
            LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(c, 48)).apply { leftMargin = Ui.dp(c, 8) })
        body.addView(searchRow, Ui.lp(c, Ui.MATCH, Ui.WRAP, 12))

        // AI 통화 요약
        aiBox = Ui.vbox(c, 14).apply {
            background = Ui.rounded(c, 0xFFF4F8FF.toInt(), 14, 0xFFD6E6FF.toInt())
            visibility = View.GONE
        }
        aiBox.addView(Ui.text(c, "🤖 통화 내용 AI 요약", 15f, Ui.BLUE, bold = true))
        aiStatus = Ui.text(c, "", 14f, Ui.SUB)
        aiBox.addView(aiStatus, Ui.lp(c, Ui.MATCH, Ui.WRAP, 4))
        transcriptToggle = Ui.text(c, "▸ 대화록 보기", 14f, Ui.BLUE).apply {
            visibility = View.GONE
            setPadding(0, Ui.dp(c, 8), 0, 0)
            setOnClickListener {
                val show = transcriptView.visibility != View.VISIBLE
                transcriptView.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "▾ 대화록 접기" else "▸ 대화록 보기"
            }
        }
        aiBox.addView(transcriptToggle)
        transcriptView = Ui.text(c, "", 13f, Ui.TEXT).apply {
            visibility = View.GONE
            setTextIsSelectable(true)
        }
        aiBox.addView(transcriptView, Ui.lp(c, Ui.MATCH, Ui.WRAP, 6))
        saveTranscriptBox = android.widget.CheckBox(c).apply {
            text = "대화록도 세포에 함께 저장"
            textSize = 13f
            isChecked = Prefs(c).saveTranscript
            visibility = View.GONE
            setOnCheckedChangeListener { _, on -> Prefs(c).saveTranscript = on }
        }
        aiBox.addView(saveTranscriptBox, Ui.lp(c, Ui.MATCH, Ui.WRAP, 4))
        body.addView(aiBox, Ui.lp(c, Ui.MATCH, Ui.WRAP, 24))

        // 결과 빠른 선택
        body.addView(Ui.text(c, "통화 결과", 16f, Ui.TEXT, bold = true), Ui.lp(c, Ui.MATCH, Ui.WRAP, 24))
        val chipScroll = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false }
        val chips = Ui.hbox(c)
        for (r in QUICK_RESULTS) {
            chips.addView(Ui.chip(c, r) { setMemoChip(r) },
                LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = Ui.dp(c, 8) })
        }
        chipScroll.addView(chips)
        body.addView(chipScroll, Ui.lp(c, Ui.MATCH, Ui.WRAP, 8))

        memo = EditText(c).apply {
            hint = "메모 (세포 '설명'에 들어갑니다)"
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = Ui.rounded(c, Ui.GRAY_BG, 12)
            setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12))
        }
        body.addView(memo, Ui.lp(c, Ui.MATCH, Ui.WRAP, 10))

        status = Ui.text(c, "", 14f, Ui.SUB)
        body.addView(status, Ui.lp(c, Ui.MATCH, Ui.WRAP, 12))

        root.addView(scroll, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        // 하단 버튼
        val bottom = Ui.hbox(c).apply {
            val p = Ui.dp(c, 16)
            setPadding(p, Ui.dp(c, 8), p, p)
        }
        bottom.addView(Ui.button(c, "닫기", primary = false) { finish() },
            LinearLayout.LayoutParams(0, Ui.dp(c, 54), 1f))
        saveBtn = Ui.button(c, "세포에 등록") { save() }
        bottom.addView(saveBtn, LinearLayout.LayoutParams(0, Ui.dp(c, 54), 2f).apply { leftMargin = Ui.dp(c, 8) })
        root.addView(bottom, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        setContentView(root)
    }

    private fun setMemoChip(r: String) {
        val cur = memo.text.toString().trim()
        memo.setText(if (cur.isEmpty() || cur in QUICK_RESULTS) r else "$cur / $r")
        memo.setSelection(memo.text.length)
    }

    private fun renderList() {
        listBox.removeAllViews()
        if (candidates.isEmpty()) {
            listBox.addView(Ui.text(this, "찾은 결과가 없어요. 아래에 이름을 입력해 검색하세요.", 14f, Ui.SUB))
            return
        }
        for (cand in candidates) {
            val isSel = selected?.id == cand.id
            val row = Ui.vbox(this, 14).apply {
                background = Ui.rounded(this@LogCallActivity,
                    if (isSel) 0xFFE8F3FF.toInt() else 0xFFFFFFFF.toInt(), 14,
                    if (isSel) Ui.BLUE else 0xFFE5E8EB.toInt())
                setOnClickListener {
                    selected = cand
                    renderList()
                    hideKeyboard()
                }
            }
            val mark = if (isSel) "✓ " else ""
            val phoneTag = if (cand.viaPhone) "  📞" else ""
            row.addView(Ui.text(this, mark + cand.title + phoneTag, 16f, if (isSel) Ui.BLUE else Ui.TEXT, bold = true))
            row.addView(Ui.text(this, cand.subtitle, 13f, Ui.SUB), Ui.lp(this, Ui.MATCH, Ui.WRAP, 2))
            listBox.addView(row, Ui.lp(this, Ui.MATCH, Ui.WRAP, 8))
        }
    }

    private fun showStatus(msg: String, color: Int = Ui.SUB) {
        status.text = msg
        status.setTextColor(color)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    // ───────────── AI 요약 ─────────────

    private fun setAutoMemo(text: String) {
        autoMemo = text
        memo.setText(text)
        memo.setSelection(memo.text.length)
    }

    private fun startAi() {
        if (!CallSummarizer.isReady(this)) return
        aiJob = CallSummarizer.get(info) ?: CallSummarizer.start(this, info)
        if (aiJob == null) return            // 연결 안 된 통화 등
        aiBox.visibility = View.VISIBLE
        pollAi()
    }

    private fun pollAi() {
        val job = aiJob ?: return
        if (isFinishing || isDestroyed) return
        if (!job.done) {
            aiStatus.text = job.status
            ui.postDelayed({ pollAi() }, 800)
            return
        }
        val r = job.result
        if (r == null) {
            aiStatus.text = job.error ?: "요약 실패"
            aiStatus.setTextColor(Ui.RED)
            return
        }
        aiStatus.text = "✓ 요약 완료 — 아래 메모에 넣었어요. 고칠 부분만 고치세요."
        aiStatus.setTextColor(Ui.GREEN)
        // 사용자가 메모를 직접 쓰고 있었다면 덮어쓰지 않고 뒤에 붙임
        val cur = memo.text.toString()
        if (cur == autoMemo || cur.isBlank()) setAutoMemo(r.memo())
        else memo.setText(cur.trimEnd() + "\n\n" + r.memo())
        if (r.transcript.isNotEmpty()) {
            transcriptView.text = r.transcriptText()
            transcriptToggle.visibility = View.VISIBLE
            saveTranscriptBox.visibility = View.VISIBLE
        }
    }

    // ───────────── 검색 ─────────────

    private fun loadByPhone() {
        val cached = OverlayPrompt.cachedCandidates
        if (OverlayPrompt.cachedNumber == info.number && cached != null) {
            onPhoneResult(cached)
            return
        }
        progress.visibility = View.VISIBLE
        io.execute {
            try {
                val found = sf.findByPhone(info.number)
                runOnUiThread { onPhoneResult(found) }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    listTitle.text = "번호 검색 실패"
                    showStatus("세포 연결 오류: ${e.message}", Ui.RED)
                    renderList()
                }
            }
        }
    }

    private fun onPhoneResult(found: List<Candidate>) {
        progress.visibility = View.GONE
        candidates = found
        selected = if (found.size == 1) found[0] else null
        listTitle.text = when {
            found.isEmpty() -> "번호로 찾은 리드 없음"
            found.size == 1 -> "이 리드가 맞나요?"
            else -> "번호로 ${found.size}건 찾음 — 골라주세요"
        }
        renderList()
        if (found.isEmpty()) {
            searchInput.requestFocus()
            searchInput.postDelayed({
                getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
            }, 250)
        }
    }

    private fun runNameSearch() {
        val q = searchInput.text.toString().trim()
        if (SalesforceClient.cleanTerm(q).length < 2) {
            showStatus("두 글자 이상 입력해주세요.", Ui.RED)
            return
        }
        hideKeyboard()
        progress.visibility = View.VISIBLE
        listTitle.text = "'$q' 검색 중…"
        showStatus("")
        io.execute {
            try {
                val found = sf.findByName(q)
                runOnUiThread {
                    progress.visibility = View.GONE
                    candidates = found
                    selected = if (found.size == 1) found[0] else null
                    listTitle.text = if (found.isEmpty()) "'$q' 비슷한 결과 없음" else "'$q' 검색 결과 ${found.size}건"
                    renderList()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    listTitle.text = "검색 실패"
                    showStatus("세포 연결 오류: ${e.message}", Ui.RED)
                }
            }
        }
    }

    // ───────────── 등록 ─────────────

    private fun save() {
        if (saving) return
        val target = selected
        if (target == null) {
            showStatus("먼저 위 목록에서 기록할 리드를 눌러 선택해주세요.", Ui.RED)
            return
        }
        val transcript = aiJob?.result?.takeIf { saveTranscriptBox.isChecked && it.transcript.isNotEmpty() }
            ?.transcriptText()
        val description = (memo.text.toString().trim() +
            (if (transcript != null) "\n\n--- 통화 대화록 (AI 자동 작성) ---\n$transcript" else "")).take(31000)
        saving = true
        saveBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        showStatus("등록 중…")

        io.execute {
            try {
                val task = JSONObject().apply {
                    put("Subject", "Call")
                    put("TaskSubtype", "Call")
                    put("Type", "Call")
                    put("Status", "완료")
                    put("ActivityDate", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(info.dateMillis)))
                    put("Description", description)
                    put("CallType", if (info.outgoing) "Outbound" else "Inbound")
                    put("CallDurationInSeconds", info.durationSec)
                }
                when (target.type) {
                    "Lead" -> task.put("WhoId", target.id)
                    "Contact" -> {
                        task.put("WhoId", target.id)
                        target.accountId?.let { acc ->
                            task.put("WhatId", runCatching { sf.latestOpenOpportunity(acc) }.getOrNull() ?: acc)
                        }
                    }
                    "Account" -> task.put(
                        "WhatId",
                        runCatching { sf.latestOpenOpportunity(target.id) }.getOrNull() ?: target.id
                    )
                }
                sf.createTask(task)
                runOnUiThread {
                    Toast.makeText(this, "✓ ${target.title}에 기록 완료", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    saving = false
                    saveBtn.isEnabled = true
                    progress.visibility = View.GONE
                    showStatus("등록 실패: ${e.message}\n네트워크 확인 후 다시 눌러주세요.", Ui.RED)
                }
            }
        }
    }
}
