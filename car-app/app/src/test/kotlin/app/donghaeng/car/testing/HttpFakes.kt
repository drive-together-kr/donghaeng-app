package app.donghaeng.car.testing

import okhttp3.Protocol
import okhttp3.Request
import retrofit2.Response

/**
 * 실제 Retrofit 2.11 이 204 No Content 에 돌려주는 모양 — 본문 null(A002). `Response.success(204, Unit)` 처럼 본문이 있는 가짜 204 는
 * '빈 본문' 버그를 가린다.
 */
fun <T> noContent(): Response<T> = Response.success(
    null,
    okhttp3.Response.Builder()
        .code(204)
        .message("No Content")
        .protocol(Protocol.HTTP_1_1)
        .request(Request.Builder().url("http://localhost/").build())
        .build(),
)
