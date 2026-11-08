package com.junho.sfcalllog

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

class SfException(message: String, val code: Int = 0) : IOException(message)

/** 검색 결과 한 줄 */
data class Candidate(
    val type: String,          // Lead / Contact / Account
    val id: String,
    val title: String,         // 회사명(브랜드)
    val subtitle: String,      // 담당자 · 상태 · 소유자
    val accountId: String? = null,
    val personName: String = "",
    var score: Double = 0.0,
    val viaPhone: Boolean = false
)

/**
 * 세일즈포스 REST API 호출 (네트워크 작업이므로 반드시 백그라운드 스레드에서 호출)
 */
class SalesforceClient(context: Context) {
    private val prefs = Prefs(context)

    // ───────────── 로그인 (OAuth 2.0 + PKCE) ─────────────

    fun buildAuthorizeUrl(): String {
        val verifier = randomUrlSafe(64)
        prefs.pkceVerifier = verifier
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return Uri.parse(prefs.loginHost + "/services/oauth2/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", prefs.consumerKey)
            .appendQueryParameter("redirect_uri", Prefs.REDIRECT_URI)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("scope", "api refresh_token")
            .appendQueryParameter("prompt", "login consent")
            .build().toString()
    }

    fun exchangeCode(code: String) {
        val form = mutableMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to prefs.consumerKey,
            "redirect_uri" to Prefs.REDIRECT_URI,
            "code_verifier" to prefs.pkceVerifier
        )
        if (prefs.consumerSecret.isNotEmpty()) form["client_secret"] = prefs.consumerSecret
        val json = postForm(prefs.loginHost + "/services/oauth2/token", form)
        prefs.accessToken = json.getString("access_token")
        prefs.refreshToken = json.optString("refresh_token", "")
        prefs.instanceUrl = json.getString("instance_url")
        if (prefs.refreshToken.isEmpty()) {
            throw SfException("refresh_token을 못 받았습니다. Connected App의 OAuth 범위에 'refresh_token, offline_access'를 추가해주세요.")
        }
        try {
            val me = request("GET", prefs.instanceUrl + "/services/oauth2/userinfo", null)
            prefs.userName = JSONObject(me).optString("name", "")
        } catch (_: Exception) {
        }
    }

    private fun refreshAccessToken() {
        if (prefs.refreshToken.isEmpty()) throw SfException("세일즈포스 로그인이 필요합니다.")
        val form = mutableMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to prefs.refreshToken,
            "client_id" to prefs.consumerKey
        )
        if (prefs.consumerSecret.isNotEmpty()) form["client_secret"] = prefs.consumerSecret
        // 토큰 갱신은 로그인 주소 또는 인스턴스 주소 어느 쪽이든 동작
        val json = try {
            postForm(prefs.loginHost + "/services/oauth2/token", form)
        } catch (e: SfException) {
            postForm(prefs.instanceUrl + "/services/oauth2/token", form)
        }
        prefs.accessToken = json.getString("access_token")
        json.optString("instance_url", "").takeIf { it.isNotEmpty() }?.let { prefs.instanceUrl = it }
    }

    // ───────────── 데이터 API ─────────────

    private val base get() = prefs.instanceUrl + "/services/data/$API_VERSION"

    fun query(soql: String): JSONArray {
        val body = api("GET", "$base/query?q=" + enc(soql), null)
        return JSONObject(body).optJSONArray("records") ?: JSONArray()
    }

    fun search(sosl: String): JSONArray {
        val body = api("GET", "$base/search?q=" + enc(sosl), null)
        return JSONObject(body).optJSONArray("searchRecords") ?: JSONArray()
    }

    fun createTask(fields: JSONObject): String {
        val body = api("POST", "$base/sobjects/Task", fields.toString())
        return JSONObject(body).getString("id")
    }

    fun recordUrl(id: String) = prefs.instanceUrl + "/" + id

    /** 401(토큰 만료)이면 자동으로 갱신하고 한 번 더 시도 */
    private fun api(method: String, url: String, json: String?): String {
        if (prefs.accessToken.isEmpty()) refreshAccessToken()
        return try {
            request(method, url, json)
        } catch (e: SfException) {
            if (e.code == 401) {
                refreshAccessToken()
                request(method, url, json)
            } else throw e
        }
    }

    private fun request(method: String, url: String, json: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Authorization", "Bearer " + prefs.accessToken)
            setRequestProperty("Accept", "application/json")
            if (json != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
        }
        try {
            if (json != null) conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw SfException(readableError(text, code), code)
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun postForm(url: String, form: Map<String, String>): JSONObject {
        val body = form.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw SfException(readableError(text, code), code)
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun readableError(text: String, code: Int): String {
        return try {
            if (text.trim().startsWith("[")) {
                val o = JSONArray(text).getJSONObject(0)
                "${o.optString("errorCode")}: ${o.optString("message")}"
            } else {
                val o = JSONObject(text)
                val err = o.optString("error")
                val desc = o.optString("error_description")
                when {
                    err == "invalid_grant" -> "로그인이 만료됐습니다. 앱에서 다시 로그인해주세요. ($desc)"
                    err == "invalid_client_id" -> "Consumer Key가 틀렸습니다."
                    err == "invalid_client" -> "Consumer Secret이 틀렸거나 필요합니다. ($desc)"
                    err.isNotEmpty() -> "$err: $desc"
                    else -> "HTTP $code: $text"
                }
            }
        } catch (_: Exception) {
            "HTTP $code: ${text.take(300)}"
        }
    }

    // ───────────── 리드 찾기 ─────────────

    /** 1순위: 방금 통화한 전화번호로 리드/연락처 찾기 */
    fun findByPhone(number: String): List<Candidate> {
        val variants = Phone.variants(number)
        if (variants.isEmpty()) return emptyList()
        val inList = variants.joinToString(",") { "'" + soqlEscape(it) + "'" }
        val out = linkedMapOf<String, Candidate>()

        query(
            "SELECT Id, Name, Company, Phone, Status, Owner.Name FROM Lead " +
                "WHERE IsConverted = false AND (Phone IN ($inList) OR MobilePhone IN ($inList)) " +
                "ORDER BY LastModifiedDate DESC LIMIT 10"
        ).forEachObj { out[it.getString("Id")] = leadCandidate(it, viaPhone = true) }

        query(
            "SELECT Id, Name, AccountId, Account.Name, Phone, Owner.Name FROM Contact " +
                "WHERE Phone IN ($inList) OR MobilePhone IN ($inList) " +
                "ORDER BY LastModifiedDate DESC LIMIT 10"
        ).forEachObj { out[it.getString("Id")] = contactCandidate(it, viaPhone = true) }

        // 저장 형식이 특이한 경우 대비: 세일즈포스 전화번호 검색(SOSL)
        if (out.isEmpty()) {
            try {
                val n = Phone.national(number)
                val terms = listOf(n, "82" + n.removePrefix("0")).joinToString(" OR ") { "\"$it\"" }
                search(
                    "FIND {$terms} IN PHONE FIELDS RETURNING " +
                        "Lead(Id, Name, Company, Phone, Status, Owner.Name WHERE IsConverted = false LIMIT 10), " +
                        "Contact(Id, Name, AccountId, Account.Name, Phone, Owner.Name LIMIT 10)"
                ).forEachObj { addSearchRecord(out, it, viaPhone = true) }
            } catch (_: Exception) {
            }
        }
        return out.values.toList()
    }

    /** 2순위: 입력한 이름(브랜드/회사/담당자)으로 비슷한 것까지 찾기 */
    fun findByName(input: String): List<Candidate> {
        val term = cleanTerm(input)
        if (term.length < 2) return emptyList()
        val compact = term.replace(" ", "")
        val out = linkedMapOf<String, Candidate>()

        // ① 세일즈포스 전체 검색 (이름 필드)
        try {
            val words = listOf(term, compact).distinct()
                .joinToString(" OR ") { if (it.contains(' ')) "\"$it\"" else "$it*" }
            search(
                "FIND {$words} IN NAME FIELDS RETURNING " +
                    "Lead(Id, Name, Company, Phone, Status, Owner.Name WHERE IsConverted = false LIMIT 25), " +
                    "Contact(Id, Name, AccountId, Account.Name, Phone, Owner.Name LIMIT 15), " +
                    "Account(Id, Name, Owner.Name LIMIT 15)"
            ).forEachObj { addSearchRecord(out, it, viaPhone = false) }
        } catch (_: Exception) {
        }

        // ② 부분 일치 (띄어쓰기·일부만 입력한 경우)
        val likeTerms = mutableListOf(term, compact)
        if (compact.length >= 3) likeTerms += compact.take(2)   // 오타 대비: 앞 두 글자로 넓게
        for (t in likeTerms.distinct()) {
            val p = "'%" + soqlLikeEscape(t) + "%'"
            try {
                query(
                    "SELECT Id, Name, Company, Phone, Status, Owner.Name FROM Lead " +
                        "WHERE IsConverted = false AND (Company LIKE $p OR Name LIKE $p) " +
                        "ORDER BY LastModifiedDate DESC LIMIT 40"
                ).forEachObj { o -> out.getOrPut(o.getString("Id")) { leadCandidate(o, false) } }
                query(
                    "SELECT Id, Name, Owner.Name FROM Account WHERE Name LIKE $p " +
                        "ORDER BY LastModifiedDate DESC LIMIT 15"
                ).forEachObj { o -> out.getOrPut(o.getString("Id")) { accountCandidate(o) } }
            } catch (_: Exception) {
            }
        }

        // ③ 비슷한 순서대로 정렬
        val q = Fuzzy.normalize(term)
        val ranked = out.values.onEach { c ->
            c.score = maxOf(
                Fuzzy.similarity(q, Fuzzy.normalize(c.title)),
                Fuzzy.similarity(q, Fuzzy.normalize(c.personName)) - 0.05
            )
        }.filter { it.score >= 0.34 }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { typeOrder(it.type) })
        return ranked.take(12)
    }

    /** 연락처/계정을 고르면 진행 중인 영업기회에 붙이기 위해 찾음 */
    fun latestOpenOpportunity(accountId: String): String? {
        val r = query(
            "SELECT Id FROM Opportunity WHERE AccountId = '${soqlEscape(accountId)}' AND IsClosed = false " +
                "ORDER BY LastModifiedDate DESC LIMIT 1"
        )
        return if (r.length() > 0) r.getJSONObject(0).getString("Id") else null
    }

    // ───────────── 변환 도우미 ─────────────

    private fun typeOrder(t: String) = when (t) { "Lead" -> 0; "Contact" -> 1; else -> 2 }

    private fun addSearchRecord(out: MutableMap<String, Candidate>, o: JSONObject, viaPhone: Boolean) {
        val type = o.optJSONObject("attributes")?.optString("type") ?: return
        val id = o.optString("Id")
        if (id.isEmpty() || out.containsKey(id)) return
        when (type) {
            "Lead" -> out[id] = leadCandidate(o, viaPhone)
            "Contact" -> out[id] = contactCandidate(o, viaPhone)
            "Account" -> out[id] = accountCandidate(o)
        }
    }

    private fun ownerName(o: JSONObject) = o.optJSONObject("Owner")?.optString("Name").orEmpty()

    private fun leadCandidate(o: JSONObject, viaPhone: Boolean): Candidate {
        val company = o.optStr("Company")
        val name = o.optStr("Name")
        val parts = listOf("리드", name, o.optStr("Status"), ownerName(o).let { if (it.isNotEmpty()) "담당 $it" else "" })
        return Candidate("Lead", o.getString("Id"), company.ifEmpty { name },
            parts.filter { it.isNotEmpty() }.joinToString(" · "), null, name, viaPhone = viaPhone)
    }

    private fun contactCandidate(o: JSONObject, viaPhone: Boolean): Candidate {
        val acc = o.optJSONObject("Account")?.optString("Name").orEmpty()
        val name = o.optStr("Name")
        val parts = listOf("연락처", name, ownerName(o).let { if (it.isNotEmpty()) "담당 $it" else "" })
        return Candidate("Contact", o.getString("Id"), acc.ifEmpty { name },
            parts.filter { it.isNotEmpty() }.joinToString(" · "),
            o.optStr("AccountId").ifEmpty { null }, name, viaPhone = viaPhone)
    }

    private fun accountCandidate(o: JSONObject): Candidate {
        val parts = listOf("계정", ownerName(o).let { if (it.isNotEmpty()) "담당 $it" else "" })
        return Candidate("Account", o.getString("Id"), o.optStr("Name"),
            parts.filter { it.isNotEmpty() }.joinToString(" · "), o.getString("Id"), "")
    }

    companion object {
        const val API_VERSION = "v60.0"

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        fun soqlEscape(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

        fun soqlLikeEscape(s: String) = soqlEscape(s).replace("%", "\\%").replace("_", "\\_")

        /** 검색어에서 특수문자 제거 */
        fun cleanTerm(s: String): String =
            s.replace("(주)", " ").replace("㈜", " ").replace("주식회사", " ")
                .filter { it.isLetterOrDigit() || it == ' ' }
                .trim().replace(Regex("\\s+"), " ")

        private fun randomUrlSafe(bytes: Int): String {
            val b = ByteArray(bytes)
            SecureRandom().nextBytes(b)
            return base64Url(b)
        }

        private fun base64Url(b: ByteArray): String =
            Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}

fun JSONObject.optStr(key: String): String = if (isNull(key)) "" else optString(key, "")

inline fun JSONArray.forEachObj(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(block)
}
