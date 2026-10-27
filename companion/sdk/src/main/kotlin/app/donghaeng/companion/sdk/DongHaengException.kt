package app.donghaeng.companion.sdk

/**
 * 동행 모듈이 던지는 예외의 공통 상위 타입. 호스트 앱은 이 타입만 잡아 사용자 안내 문구로 바꾸면 된다.
 */
public sealed class DongHaengException(message: String) : Exception(message) {

    /** [DongHaeng.init] 전에 다른 API 를 호출함 */
    public class NotInitialized : DongHaengException("DongHaeng.init() 을 먼저 호출해야 합니다")

    /** [TokenProvider] 가 빈 토큰을 돌려줬거나 서버가 토큰을 거부함(401). 호스트 앱이 재로그인·갱신을 처리한다 */
    public class Unauthorized : DongHaengException("계정 토큰이 없거나 만료됐습니다")

    /**
     * 동행 QR 이 아님(경로가 `/pair`·`/j/{token}` 이 아니거나 URL 이 아님).
     *
     * @param reason 사용자에게 보여 줄 수 있는 짧은 이유
     */
    public class InvalidQr(public val reason: String) : DongHaengException("동행 QR이 아닙니다: $reason")

    /**
     * 경로는 맞지만 호스트가 이 앱에 설정된 서버([DongHaengConfig.publicBaseUrl])와 다름.
     *
     * @param host QR 에 적힌 호스트(포트 포함)
     */
    public class OtherServer(public val host: String) : DongHaengException("다른 서버의 QR입니다: $host")

    /**
     * 서버가 오류 응답을 돌려줌 (docs/03 오류 형식의 `error.code`, 예: `INVITE_EXPIRED`, `GROUP_FULL`).
     *
     * @param code 서버 오류 코드
     * @param message 서버가 준 사용자용 문구
     */
    public class Api(public val code: String, message: String) : DongHaengException(message)
}
