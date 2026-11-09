package com.junho.sfcalllog

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** AI 요약 결과 */
data class CallSummary(
    val summary: String,
    val result: String,
    val nextAction: String,
    val transcript: List<Pair<String, String>>   // (화자, 말)
) {
    /** 세포 '설명' 칸 맨 위에 들어갈 메모 */
    fun memo(): String = buildString {
        if (result.isNotBlank() && result != "기타") append(result).append("\n")
        append(summary.trim())
        if (nextAction.isNotBlank()) append("\n다음 액션: ").append(nextAction.trim())
    }.trim()

    fun transcriptText(): String = transcript.joinToString("\n") { "${it.first}: ${it.second}" }
}

/** 통화 1건의 요약 작업 상태 */
class SummaryJob {
    @Volatile var status: String = "통화 녹음 파일 찾는 중…"
    @Volatile var done: Boolean = false
    @Volatile var result: CallSummary? = null
    @Volatile var error: String? = null
}

/**
 * 통화 녹음 → (화자 구분된) 대화록 + 세포용 요약
 * 1) 갤럭시 '통화 녹음' 폴더(Recordings/Call)에서 방금 통화 파일을 찾고
 * 2) Gemini에 음성을 보내 나/고객 구분 대화록 + 요약을 JSON으로 받음
 */
object CallSummarizer {
    private val io = Executors.newFixedThreadPool(2)
    private val jobs = ConcurrentHashMap<Long, SummaryJob>()
    private const val API = "https://generativelanguage.googleapis.com"
    private const val INLINE_LIMIT = 14L * 1024 * 1024

    fun isReady(ctx: Context): Boolean {
        val p = Prefs(ctx)
        return p.aiEnabled && p.geminiKey.isNotBlank()
    }

    /** 통화가 끝나자마자 백그라운드로 시작 (같은 통화는 한 번만) */
    fun start(ctx: Context, info: CallInfo): SummaryJob? {
        if (!isReady(ctx)) return null
        if (info.durationSec in 0..4) return null           // 연결 안 됐거나 너무 짧은 통화
        val app = ctx.applicationContext
        jobs[info.dateMillis]?.let { return it }
        val job = SummaryJob()
        jobs[info.dateMillis] = job
        io.execute {
            try {
                val rec = waitForRecording(app, info, job)
                if (rec == null) {
                    job.error = "통화 녹음 파일을 못 찾았어요. 갤럭시 '통화 자동 녹음'이 켜져 있는지, 앱의 '오디오 읽기' 권한이 있는지 확인해주세요."
                } else {
                    job.status = "AI가 통화 내용 정리 중… (보통 10~30초)"
                    job.result = summarize(app, rec)
                }
            } catch (e: Exception) {
                job.error = "AI 요약 실패: ${e.message}"
            } finally {
                job.done = true
            }
        }
        return job
    }

    fun get(info: CallInfo): SummaryJob? = jobs[info.dateMillis]

    // ───────────── 1) 녹음 파일 찾기 ─────────────

    private data class Recording(val uri: Uri, val name: String, val mime: String, val size: Long)

    private fun waitForRecording(ctx: Context, info: CallInfo, job: SummaryJob): Recording? {
        repeat(12) { i ->                     // 저장·인덱싱이 늦을 수 있어 최대 약 30초 대기
            findRecording(ctx, info)?.let { return it }
            job.status = "통화 녹음 파일 찾는 중… (${i + 1})"
            Thread.sleep(2500)
        }
        return null
    }

    private fun findRecording(ctx: Context, info: CallInfo): Recording? {
        val col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH
        else @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA
        val startSec = info.dateMillis / 1000
        val endSec = startSec + info.durationSec
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE, pathCol
        )
        return try {
            ctx.contentResolver.query(
                col, proj,
                "$pathCol LIKE ? AND ${MediaStore.MediaColumns.DATE_MODIFIED} >= ?",
                arrayOf("%Call%", (startSec - 30).toString()),
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val modified = c.getLong(2)
                    val size = c.getLong(3)
                    if (size <= 0) continue
                    if (modified > endSec + 600) continue      // 너무 늦게 생긴 파일은 다른 통화
                    val id = c.getLong(0)
                    return Recording(
                        ContentUris.withAppendedId(col, id),
                        c.getString(1) ?: "call.m4a",
                        c.getString(4) ?: "audio/mp4",
                        size
                    )
                }
                null
            }
        } catch (_: SecurityException) {
            null
        }
    }

    // ───────────── 2) Gemini로 대화록 + 요약 ─────────────

    private fun prompt(): String = """
이 음성은 아임웹(웹사이트 빌더·자사몰 구축 플랫폼) 브랜드전략팀 SDR '나'와 잠재 고객(브랜드·쇼핑몰 담당자) '고객'의 전화 통화 녹음입니다.
화자는 2명입니다. 아임웹 쪽 사람을 "나", 상대방을 "고객"으로 구분하세요.

반드시 아래 JSON 형식으로만 답하세요.
{
  "transcript": [{"speaker": "나" 또는 "고객", "text": "말한 내용"}],
  "summary": "세일즈포스 활동 기록용 요약 2~5줄. 고객의 현재 상황(사용 중인 플랫폼, 운영 방식, 고민), 관심도, 결정권자 여부, 합의된 사항을 사실 위주로. 통화에 없는 내용은 쓰지 말 것",
  "result": "다음 중 하나: 부재중 / 타겟 아님, 퀄리파잉 탈락 / 퀄리파잉 탈락 / 기 어레인지 건 / 후속 연락 예정 / 미팅 어레인지 / 담당자 연결 요청 / 자료 발송 요청 / 기타",
  "next_action": "합의된 다음 액션과 날짜(없으면 빈 문자열)"
}

규칙:
- 대화록은 실제 말한 내용 그대로, 맞춤법만 자연스럽게.
- 연결음·안내멘트·음성사서함뿐이면 result는 "부재중", transcript는 빈 배열.
- 금액·날짜·이름 등 고유명사는 들린 그대로 적고, 불확실하면 (불확실) 표시.
""".trimIndent()

    private fun summarize(ctx: Context, rec: Recording): CallSummary {
        val prefs = Prefs(ctx)
        val key = prefs.geminiKey
        val model = resolveModel(ctx)
        val bytes = ctx.contentResolver.openInputStream(rec.uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("녹음 파일을 열 수 없어요")

        val mimes = linkedSetOf(normalizeMime(rec.mime, rec.name), "audio/mp4", "audio/m4a", "audio/aac")
        var lastErr: Exception? = null
        for (mime in mimes) {
            try {
                val audioPart = if (bytes.size <= INLINE_LIMIT) {
                    JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", mime)
                        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))
                } else {
                    val uri = uploadFile(key, bytes, mime)
                    JSONObject().put("file_data", JSONObject().put("mime_type", mime).put("file_uri", uri))
                }
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(audioPart).put(JSONObject().put("text", prompt())))))
                    .put("generationConfig", JSONObject()
                        .put("responseMimeType", "application/json")
                        .put("temperature", 0.2))
                val res = http("POST", "$API/v1beta/models/$model:generateContent", key, body.toString())
                return parse(res)
            } catch (e: HttpError) {
                lastErr = e
                // 형식 문제(400)면 다른 MIME으로 재시도, 그 외는 바로 실패
                if (e.code != 400) throw e
            }
        }
        throw lastErr ?: IllegalStateException("알 수 없는 오류")
    }

    private fun normalizeMime(mime: String, name: String): String = when {
        name.endsWith(".m4a", true) -> "audio/mp4"
        name.endsWith(".mp3", true) -> "audio/mp3"
        name.endsWith(".amr", true) -> "audio/amr"
        name.endsWith(".ogg", true) -> "audio/ogg"
        name.endsWith(".wav", true) -> "audio/wav"
        else -> mime
    }

    private fun parse(res: String): CallSummary {
        val parts = JSONObject(res).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("AI 응답이 비어 있어요: ${res.take(200)}")
        val text = buildString {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought", false)) continue
                append(p.optString("text", ""))
            }
        }.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val o = JSONObject(text)
        val t = mutableListOf<Pair<String, String>>()
        o.optJSONArray("transcript")?.let { arr ->
            for (i in 0 until arr.length()) {
                val x = arr.optJSONObject(i) ?: continue
                t += x.optString("speaker", "?") to x.optString("text", "")
            }
        }
        return CallSummary(
            o.optString("summary", ""), o.optString("result", ""), o.optString("next_action", ""), t
        )
    }

    /** 모델 자동 선택: 설정값 → 저장된 값 → 사용 가능한 최신 flash 모델 */
    private fun resolveModel(ctx: Context): String {
        val prefs = Prefs(ctx)
        prefs.geminiModel.trim().takeIf { it.isNotEmpty() }?.let { return it.removePrefix("models/") }
        prefs.geminiAutoModel.takeIf { it.isNotEmpty() }?.let { return it }
        val list = JSONObject(http("GET", "$API/v1beta/models?pageSize=200", prefs.geminiKey, null))
            .optJSONArray("models") ?: JSONArray()
        val bad = listOf("lite", "image", "tts", "live", "transcribe", "embedding", "audio", "exp", "learnlm", "robotics", "computer")
        val re = Regex("gemini-(\\d+(?:\\.\\d+)?)-flash")
        var best: String? = null
        var bestScore = -1.0
        for (i in 0 until list.length()) {
            val m = list.optJSONObject(i) ?: continue
            val name = m.optString("name").removePrefix("models/")
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
            if (!methods.contains("generateContent")) continue
            if (bad.any { name.contains(it) }) continue
            val v = re.find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val score = v * 10 - (if (name.contains("preview")) 1 else 0) - (if (Regex("-\\d{3}$").containsMatchIn(name)) 0.5 else 0.0)
            if (score > bestScore) { bestScore = score; best = name }
        }
        val chosen = best ?: "gemini-2.5-flash"
        prefs.geminiAutoModel = chosen
        return chosen
    }

    /** 14MB 넘는 긴 통화: Files API로 올리고 주소로 전달 */
    private fun uploadFile(key: String, bytes: ByteArray, mime: String): String {
        val start = (URL("$API/upload/v1beta/files").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("x-goog-api-key", key)
            setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            setRequestProperty("X-Goog-Upload-Command", "start")
            setRequestProperty("X-Goog-Upload-Header-Content-Length", bytes.size.toString())
            setRequestProperty("X-Goog-Upload-Header-Content-Type", mime)
            setRequestProperty("Content-Type", "application/json")
        }
        val uploadUrl = try {
            start.outputStream.use { it.write("""{"file":{"display_name":"call"}}""".toByteArray()) }
            if (start.responseCode !in 200..299) throw HttpError(start.responseCode, readErr(start))
            start.getHeaderField("x-goog-upload-url") ?: throw IllegalStateException("업로드 주소 없음")
        } finally { start.disconnect() }

        val up = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 180000
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("X-Goog-Upload-Offset", "0")
            setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
        }
        val fileJson = try {
            up.outputStream.use { it.write(bytes) }
            if (up.responseCode !in 200..299) throw HttpError(up.responseCode, readErr(up))
            JSONObject(up.inputStream.bufferedReader().use { it.readText() }).getJSONObject("file")
        } finally { up.disconnect() }

        // 처리 완료(ACTIVE)까지 대기
        var f = fileJson
        repeat(30) {
            if (f.optString("state") != "PROCESSING") return f.getString("uri")
            Thread.sleep(2000)
            f = JSONObject(http("GET", "$API/v1beta/${f.getString("name")}", key, null))
        }
        return f.getString("uri")
    }

    class HttpError(val code: Int, msg: String) : Exception(msg)

    private fun readErr(c: HttpURLConnection): String {
        val t = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        return try {
            JSONObject(t).optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() } ?: "HTTP ${c.responseCode}"
        } catch (_: Exception) { "HTTP ${c.responseCode}: ${t.take(200)}" }
    }

    private fun http(method: String, url: String, key: String, json: String?): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 180000
            setRequestProperty("x-goog-api-key", key)
            if (json != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
        }
        try {
            if (json != null) c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) {
                val msg = readErr(c)
                throw HttpError(code, when (code) {
                    400 -> "요청 형식 오류: $msg"
                    401, 403 -> "Gemini API 키를 확인해주세요 ($msg)"
                    404 -> "모델을 찾을 수 없어요 ($msg)"
                    429 -> "사용량 한도 초과 — 잠시 후 다시 시도 ($msg)"
                    else -> msg
                })
            }
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** 설정 화면 '연결 테스트'용: 키가 유효한지 + 쓸 모델 확인 */
    fun testKey(ctx: Context): String {
        Prefs(ctx).geminiAutoModel = ""
        return resolveModel(ctx)
    }
}
