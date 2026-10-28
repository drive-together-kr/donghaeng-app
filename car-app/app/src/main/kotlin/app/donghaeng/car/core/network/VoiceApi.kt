package app.donghaeng.car.core.network

import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.VoiceUploadResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * F16 무전 REST (docs/03 §2.6) — 차량 JWT. `Authorization` 은 [VehicleAuthInterceptor](`/v1/groups/…`), 401 갱신·재시도는 [VehicleTokenAuthenticator](T33) 가
 * 맡으므로 기본(인증) OkHttpClient 의 Retrofit 으로 만든다.
 */
interface VoiceApi {

    /** `multipart/form-data`: `audio`(파일, Content-Type = mime) + `durationMs`(정수) → 201 [VoiceUploadResponse] + 그룹 이벤트 VOICE_MESSAGE */
    @Multipart
    @POST("${ApiPaths.GROUPS}/{groupId}/voice")
    suspend fun upload(
        @Path("groupId") groupId: String,
        @Part audio: MultipartBody.Part,
        @Part("durationMs") durationMs: RequestBody,
    ): Response<VoiceUploadResponse>

    /** 200 오디오 바이트(Content-Type = 올린 mime). 만료·삭제·멤버 아님 404 */
    @Streaming
    @GET("${ApiPaths.GROUPS}/{groupId}/voice/{voiceId}")
    suspend fun download(@Path("groupId") groupId: String, @Path("voiceId") voiceId: String): Response<ResponseBody>
}
