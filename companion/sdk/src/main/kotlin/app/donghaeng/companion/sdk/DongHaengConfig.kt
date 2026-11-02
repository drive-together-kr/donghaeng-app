package app.donghaeng.companion.sdk

/**
 * 동행 모듈 초기화 설정. 호스트 앱(공식 앱)이 [DongHaeng.init] 에 한 번 넘긴다.
 *
 * @param publicBaseUrl 서버 공개 주소 — 서버 설정값 `DH_PUBLIC_BASE_URL` 과 같은 값
 *   (로컬 `http://10.0.2.2:8080`, 클라우드 `https://<VM-IP>.sslip.io`). 끝의 `/` 는 모듈 안에서 제거해 다룬다.
 *   QR 의 호스트가 이 주소의 호스트와 다르면 [DongHaengException.OtherServer] 가 난다.
 * @param provider 모듈을 내장한 공식 앱(계정 제공자). [AccountProvider.DEV] 는 개발·시연용 dev 로그인 계정이다
 *   (서버 `DevTokenVerifier` 가 검증, docs/07 §4.2). 어느 제공자든 전송은 같은 서버 REST(`/v1/auth/exchange` → 동행 JWT)다.
 * @param appVersion 호스트 앱 버전 문자열. 서버 요청의 User-Agent·진단 로그에 쓴다.
 */
public data class DongHaengConfig(
    public val publicBaseUrl: String,
    public val provider: AccountProvider,
    public val appVersion: String,
)

/**
 * 동행 모듈을 내장하는 공식 앱(= 계정 제공자). 서버는 이 값으로 어느 IdP 의 OAuth 2.0 토큰을 검증할지 고른다.
 */
public enum class AccountProvider {
    /** 현대 블루링크 */
    BLUELINK,

    /** 마이현대 */
    MYHYUNDAI,

    /** 기아 커넥트 */
    KIA_CONNECT,

    /** 제네시스 커넥티드 서비스 */
    GENESIS_CONNECTED,

    /**
     * 개발·시연용 dev 로그인 계정. 토큰은 호스트 앱이 `POST /v1/auth/login` 으로 받은 accessToken 이며 서버
     * `DevTokenVerifier` 가 검증한다(docs/07 §4.2). 모듈은 토큰 형식을 해석하지 않고 다른 제공자와 똑같이
     * `POST /v1/auth/exchange { provider: "dev", accessToken }` 으로 동행 JWT 와 교환한다. 와이어 표기는 `dev`.
     */
    DEV,
}

/**
 * 호스트 앱이 보유한 계정 액세스 토큰을 돌려준다.
 *
 * 동행 모듈은 자체 로그인 화면이 없다. 공식 앱이 이미 가진 OAuth 2.0 액세스 토큰(MVP 에서는 dev 로그인 토큰)을
 * 이 인터페이스로 받아 서버 `POST /v1/auth/exchange { provider, accessToken }` 로 동행 JWT 와 교환하고, 모듈이 그 JWT 를
 * 캐시해 모든 요청의 `Authorization: Bearer` 에 싣는다(docs/07 §4.1). 호스트 토큰이 매 요청마다 서버로 가지는 않는다.
 * 서버가 401 을 돌려주면 교환을 한 번 다시 시도한 뒤 [DongHaengException.Unauthorized] 를 던진다.
 * 호스트 토큰의 만료 갱신은 호스트 앱 책임이다(모듈은 교환이 필요할 때만 부르므로, 부를 때마다 만료가 가까우면 여기서 갱신하면 된다 —
 * demo-host `HostTokenCache` 예). 빈 문자열을 돌려주면 [DongHaengException.Unauthorized] 가 난다.
 */
public fun interface TokenProvider {
    /**
     * 현재 유효한 액세스 토큰. 네트워크 갱신이 필요하면 여기서 suspend 해도 된다.
     * 갱신에 실패하면 빈 문자열을 돌려주거나 예외를 던진다 — 모듈이 [java.io.IOException] 은 `DongHaengException.Api("NETWORK")`(일시 오류),
     * 그 밖의 예외는 [DongHaengException.Unauthorized] 로 바꿔 호출자에게 전한다(호스트 예외가 그대로 새지 않는다).
     */
    public suspend fun accessToken(): String
}
