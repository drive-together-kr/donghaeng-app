package app.donghaeng.companion.sdk

/**
 * 동행 모듈 초기화 설정. 호스트 앱(공식 앱)이 [DongHaeng.init] 에 한 번 넘긴다.
 *
 * @param publicBaseUrl 서버 공개 주소 — 서버 설정값 `DH_PUBLIC_BASE_URL` 과 같은 값
 *   (로컬 `http://10.0.2.2:8080`, 클라우드 `https://<VM-IP>.sslip.io`). 끝의 `/` 는 모듈 안에서 제거해 다룬다.
 *   QR 의 호스트가 이 주소의 호스트와 다르면 [DongHaengException.OtherServer] 가 난다.
 * @param provider 모듈을 내장한 공식 앱(계정 제공자). [AccountProvider.DEV] 면 서버 없이 메모리 Fake 로 동작한다.
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

    /** 개발·시연용. 서버 없이 [DongHaeng] 이 메모리 Fake 로 동작하며 토큰은 `dev:<email>` 형식을 쓴다 */
    DEV,
}

/**
 * 호스트 앱이 보유한 계정 액세스 토큰을 돌려준다.
 *
 * 동행 모듈은 자체 로그인 화면이 없다. 공식 앱이 이미 가진 OAuth 2.0 액세스 토큰(MVP 에서는 dev 토큰)을
 * 요청 직전마다 이 인터페이스로 받아 `Authorization: Bearer` 헤더에 싣는다. 만료 갱신은 호스트 앱 책임이다.
 * 빈 문자열을 돌려주면 [DongHaengException.Unauthorized] 가 난다.
 */
public fun interface TokenProvider {
    /** 현재 유효한 액세스 토큰. 네트워크 갱신이 필요하면 여기서 suspend 해도 된다 */
    public suspend fun accessToken(): String
}
