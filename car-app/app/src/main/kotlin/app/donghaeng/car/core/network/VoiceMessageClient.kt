package app.donghaeng.car.core.network

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.protocol.rest.VoiceUploadResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/**
 * F16 무전 업로드·다운로드 (docs/03 §2.6) — [VoiceApi] 를 [ApiResult] 로 감싼다.
 *
 * - 업로드 전 계약 상한을 먼저 본다: 크기 ≤ [MAX_UPLOAD_BYTES](512 KB), 길이 1..[MAX_DURATION_MS](16 000 ms), mime ∈ [ALLOWED_MIME].
 *   어기면 서버에 보내지 않고 [ApiResult.UnexpectedFailure](사유 `client-…`)로 끝낸다(413/400/415 를 기다리지 않게).
 * - 서버 오류 코드: 413 `VOICE_TOO_LARGE`, 400 `INVALID_VOICE`, 415 `UNSUPPORTED_VOICE_TYPE`, 429 `VOICE_RATE_LIMITED`(3초에 1건), 404(멤버 아님), 409 `GROUP_ENDED`
 *   — [ApiResult.HttpFailure.code] 로 그대로 올린다.
 * - 다운로드는 `cache/ptt/in/{voiceId}.{ext}` 같은 [dest] 로 스트리밍(임시 파일 → 이름 바꾸기), [MAX_DOWNLOAD_BYTES] 를 넘으면 버린다.
 * - 로그: `ptt: upload ok voiceId=… bytes=… dur=…` / `ptt: upload failed …`(녹음 내용·토큰 없음).
 */
class VoiceMessageClient(
    private val api: VoiceApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun upload(groupId: String, file: File, mime: String, durationMs: Long): ApiResult<VoiceUploadResponse> {
        clientCheck(file, mime, durationMs)?.let { reason ->
            DhLog.w("ptt: upload rejected locally reason=$reason bytes=${file.length()} dur=${durationMs}ms")
            return ApiResult.UnexpectedFailure(IllegalArgumentException(reason))
        }
        val audio = MultipartBody.Part.createFormData(PART_AUDIO, file.name, file.asRequestBody(mime.toMediaType()))
        val duration = durationMs.toString().toRequestBody(TEXT_PLAIN)
        val result = withContext(io) { safeApiCall { api.upload(groupId, audio, duration) } }
        when (result) {
            is ApiResult.Success -> DhLog.i("ptt: upload ok voiceId=${result.value.voiceId} bytes=${file.length()} dur=${durationMs}ms mime=$mime")
            is ApiResult.Failure -> DhLog.w("ptt: upload failed ${result.summary}")
        }
        return result
    }

    suspend fun download(groupId: String, voiceId: String, dest: File): ApiResult<File> = withContext(io) {
        try {
            val response = api.download(groupId, voiceId)
            if (!response.isSuccessful) {
                val failure = ApiResult.HttpFailure.of(response.code(), parseErrorBody(response.errorBody()?.string()))
                DhLog.w("ptt: download failed voiceId=$voiceId ${failure.summary}")
                return@withContext failure
            }
            val body = response.body() ?: return@withContext ApiResult.UnexpectedFailure(IllegalStateException("empty body"))
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            var total = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_DOWNLOAD_BYTES) {
                            tmp.delete()
                            return@withContext ApiResult.UnexpectedFailure(IllegalStateException("too-large"))
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            if (total == 0L) {
                tmp.delete()
                return@withContext ApiResult.UnexpectedFailure(IllegalStateException("empty audio"))
            }
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            DhLog.i("ptt: download ok voiceId=$voiceId bytes=$total")
            ApiResult.Success(dest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DhLog.w("ptt: download failed voiceId=$voiceId network ${e.javaClass.simpleName}")
            ApiResult.NetworkFailure(e)
        } catch (e: Exception) {
            DhLog.w("ptt: download failed voiceId=$voiceId ${e.javaClass.simpleName}")
            ApiResult.UnexpectedFailure(e)
        }
    }

    companion object {
        const val PART_AUDIO = "audio"
        const val MAX_UPLOAD_BYTES = 512L * 1024
        const val MAX_DURATION_MS = 16_000L
        /** 다운로드 안전 상한 — 서버 업로드 상한(512 KB)의 2배 */
        const val MAX_DOWNLOAD_BYTES = 1024L * 1024
        val ALLOWED_MIME = setOf("audio/mp4", "audio/aac", "audio/wav")
        private val TEXT_PLAIN = "text/plain".toMediaType()

        /** 계약 상한 검사(순수) — 어기면 사유, 맞으면 null */
        fun clientCheck(file: File, mime: String, durationMs: Long): String? = when {
            !file.isFile -> "client-no-file"
            file.length() <= 0L -> "client-empty"
            file.length() > MAX_UPLOAD_BYTES -> "client-too-large"
            durationMs < 1 || durationMs > MAX_DURATION_MS -> "client-bad-duration"
            mime !in ALLOWED_MIME -> "client-bad-mime"
            else -> null
        }
    }
}
