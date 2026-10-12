package kr.archsurvey.engine

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kr.archsurvey.ui.DriveAccount
import kr.archsurvey.ui.DriveException
import kr.archsurvey.ui.DriveFileRef
import kr.archsurvey.ui.DrivePlatform
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Google Sign-In + Drive REST(v3). drive.file 범위만 요청(앱이 만든 파일만).
 * 액세스 토큰은 GoogleAuthUtil 로 받고 SharedPreferences/로그에 쓰지 않는다.
 * OAuth 클라이언트: Google Cloud 에 패키지명+서명 SHA-1 등록 필요. 미등록이면 연결 실패 안내.
 */
class AndroidDrivePlatform(
    private val ctx: Context,
    private val auth: DriveAuthBridge,
) : DrivePlatform {

    companion object {
        private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val APP_FOLDER = "ExcavationSurvey"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    }

    private val gso: GoogleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(SCOPE))
        .build()

    private val client get() = GoogleSignIn.getClient(ctx, gso)

    override fun currentEmail(): String? = GoogleSignIn.getLastSignedInAccount(ctx)?.email

    override fun beginSignIn(onDone: (Result<DriveAccount>) -> Unit) {
        val existing = GoogleSignIn.getLastSignedInAccount(ctx)
        if (existing != null && GoogleSignIn.hasPermissions(existing, Scope(SCOPE))) {
            onDone(Result.success(DriveAccount(existing.email ?: existing.displayName ?: "Google")))
            return
        }
        auth.launch(client.signInIntent) { data ->
            try {
                if (data == null) {
                    onDone(Result.failure(DriveException("Google 계정 연결을 취소했어요")))
                    return@launch
                }
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)
                    ?: throw DriveException("Google 계정 정보를 받지 못했어요")
                onDone(Result.success(DriveAccount(account.email ?: account.displayName ?: "Google")))
            } catch (e: ApiException) {
                val msg = when (e.statusCode) {
                    10 -> "Google Drive 연동 설정(OAuth 클라이언트·SHA-1)이 필요해요. 개발자에게 문의하세요."
                    12501 -> "Google 계정 연결을 취소했어요"
                    else -> "Google 계정 연결에 실패했어요 (코드 ${e.statusCode})"
                }
                onDone(Result.failure(DriveException(msg)))
            } catch (e: Exception) {
                onDone(Result.failure(DriveException(e.message?.takeIf { it.isNotBlank() } ?: "Google 계정 연결에 실패했어요")))
            }
        }
    }

    override fun signOut() {
        try { client.signOut() } catch (_: Exception) { }
        try { client.revokeAccess() } catch (_: Exception) { }
    }

    // 0.1.38: 네트워크·GoogleAuthUtil.getToken 은 메인 스레드에서 못 부른다(0.1.15 는 viewModelScope=메인에서 불러 실제로는 실패했을 것) → 전부 IO
    override suspend fun ensureAppFolder(cachedId: String?): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ensureAppFolderIo(cachedId) }
    private suspend fun ensureAppFolderIo(cachedId: String?): String {
        if (!cachedId.isNullOrBlank()) {
            if (fileExists(cachedId)) return cachedId
        }
        // 0.1.38: 캐시가 없으면 내 드라이브 맨 위의 같은 이름 폴더를 먼저 찾는다(매번 새 폴더가 생기지 않게)
        return ensureFolderIo("root", APP_FOLDER)
    }

    override suspend fun uploadNew(folderId: String, fileName: String, mime: String, bytes: ByteArray): DriveFileRef =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { uploadNewIo(folderId, fileName, mime, bytes) }
    private fun uploadNewIo(folderId: String, fileName: String, mime: String, bytes: ByteArray): DriveFileRef {
        val meta = JSONObject().put("name", fileName).put("parents", org.json.JSONArray().put(folderId))
        val id = multipartUpload(null, meta, mime, bytes)
        return DriveFileRef(id, fileName)
    }

    override suspend fun uploadOrUpdate(folderId: String, fileName: String, mime: String, bytes: ByteArray, existingFileId: String?): DriveFileRef =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { uploadOrUpdateIo(folderId, fileName, mime, bytes, existingFileId) }
    private fun uploadOrUpdateIo(folderId: String, fileName: String, mime: String, bytes: ByteArray, existingFileId: String?): DriveFileRef {
        if (!existingFileId.isNullOrBlank() && fileExists(existingFileId)) {
            multipartUpload(existingFileId, null, mime, bytes)
            return DriveFileRef(existingFileId, fileName)
        }
        return uploadNewIo(folderId, fileName, mime, bytes)
    }

    override suspend fun ensureFolder(parentId: String, name: String): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ensureFolderIo(parentId, name) }
    private fun ensureFolderIo(parentId: String, name: String): String {
        val esc = name.replace("\\", "\\\\").replace("'", "\\'")
        val q = "name = '$esc' and '$parentId' in parents and mimeType = '$FOLDER_MIME' and trashed = false"
        val found = jsonRequest("GET", "$API/files?fields=files(id,name)&spaces=drive&q=" + java.net.URLEncoder.encode(q, "UTF-8"))
        found.optJSONArray("files")?.takeIf { it.length() > 0 }?.getJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }?.let { return it }
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER_MIME).put("parents", org.json.JSONArray().put(parentId))
        return jsonRequest("POST", "$API/files?fields=id,name", meta.toString()).getString("id")
    }

    private fun fileExists(id: String): Boolean = try {
        jsonRequest("GET", "$API/files/$id?fields=id,trashed")
        true
    } catch (_: Exception) { false }

    /** GoogleAuthUtil 토큰. 절대 로그에 남기지 말 것. */
    private fun token(): String {
        val account = GoogleSignIn.getLastSignedInAccount(ctx)?.account
            ?: throw DriveException("Google 계정 연결이 없어요")
        return try {
            @Suppress("DEPRECATION")
            GoogleAuthUtil.getToken(ctx, account, "oauth2:$SCOPE")
        } catch (e: UserRecoverableAuthException) {
            throw DriveException("Google Drive 권한을 다시 허용해 주세요")
        } catch (e: Exception) {
            throw DriveException("Google 인증에 실패했어요 (${e.javaClass.simpleName})")
        }
    }

    private fun clearToken(t: String) {
        try { @Suppress("DEPRECATION") GoogleAuthUtil.clearToken(ctx, t) } catch (_: Exception) { }
    }

    private fun jsonRequest(method: String, url: String, body: String? = null): JSONObject {
        var t = token()
        fun once(tok: String): Pair<Int, String> {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 30_000; readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer $tok")
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                }
            }
            return try {
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                code to text
            } finally { c.disconnect() }
        }
        var (code, text) = once(t)
        if (code == 401) {
            clearToken(t); t = token(); val r = once(t); code = r.first; text = r.second
        }
        if (code !in 200..299) throw DriveException(driveErrorMessage(code, text))
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun multipartUpload(fileId: String?, meta: JSONObject?, mime: String, bytes: ByteArray): String {
        var t = token()
        val boundary = "archsurvey_" + System.currentTimeMillis()
        val url = if (fileId == null) "$UPLOAD/files?uploadType=multipart&fields=id,name"
        else "$UPLOAD/files/$fileId?uploadType=multipart&fields=id,name"
        val method = if (fileId == null) "POST" else "PATCH"

        fun bodyOf(metaJson: JSONObject?): ByteArray {
            val out = ByteArrayOutputStream()
            val w = OutputStreamWriter(out, StandardCharsets.UTF_8)
            if (metaJson != null) {
                w.append("--").append(boundary).append("\r\n")
                w.append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                w.append(metaJson.toString()).append("\r\n")
            } else {
                // 내용만 갱신할 때도 multipart 최소 메타
                w.append("--").append(boundary).append("\r\n")
                w.append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                w.append("{}").append("\r\n")
            }
            w.append("--").append(boundary).append("\r\n")
            w.append("Content-Type: ").append(mime).append("\r\n\r\n")
            w.flush()
            out.write(bytes)
            w.append("\r\n--").append(boundary).append("--\r\n")
            w.flush()
            return out.toByteArray()
        }

        val payload = bodyOf(meta)
        fun once(tok: String): Pair<Int, String> {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                doOutput = true
                connectTimeout = 30_000; readTimeout = 120_000
                setRequestProperty("Authorization", "Bearer $tok")
                setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
                setRequestProperty("Content-Length", payload.size.toString())
                outputStream.use { it.write(payload) }
            }
            return try {
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                code to text
            } finally { c.disconnect() }
        }
        var (code, text) = once(t)
        if (code == 401) {
            clearToken(t); t = token(); val r = once(t); code = r.first; text = r.second
        }
        if (code !in 200..299) throw DriveException(driveErrorMessage(code, text))
        val id = JSONObject(text).optString("id")
        if (id.isBlank()) throw DriveException("Drive 업로드 응답에 파일 id 가 없어요")
        return id
    }

    private fun driveErrorMessage(code: Int, body: String): String {
        val reason = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
        return when (code) {
            403 -> "Google Drive 권한이 거부됐어요. 계정을 다시 연결해 주세요."
            404 -> "Drive 폴더/파일을 찾지 못했어요. 다시 백업해 주세요."
            401 -> "Google 로그인이 만료됐어요. 계정을 다시 연결해 주세요."
            else -> "Drive 오류 ($code)" + (reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        }
    }
}
