package app.donghaeng.car.core.network

import app.donghaeng.car.BuildConfig
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.domain.pairing.RefreshTrigger
import app.donghaeng.car.domain.pairing.TokenRefresher
import app.donghaeng.car.domain.pairing.accessTokenOrNull
import app.donghaeng.protocol.DhJson
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * 토큰 갱신 전용 [IdentityApi] — Authenticator 가 **없는** 별도 OkHttpClient(자기 Dispatcher·연결 풀) 위에 만든다 (T33).
 * 기본 클라이언트의 Authenticator 가 OkHttp 스레드에서 갱신을 기다리는 동안 같은 Dispatcher 의 호스트당 동시 요청 한도(5)에 막혀 교착하지 않게 하고,
 * 갱신 요청의 401 이 다시 Authenticator 로 들어가는 순환도 막는다.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RefreshClient

/**
 * Retrofit 2.11 + OkHttp 4.12 + kotlinx-serialization 컨버터([DhJson.json] — ignoreUnknownKeys, explicitNulls=false).
 *
 * - base URL = [ServerConfig.publicBaseUrl] + "/" (BuildConfig ← Gradle 속성, CLAUDE.md 규칙 12)
 * - 로깅은 **디버그 빌드에서만** BASIC(메서드·URL·상태·크기, 본문·헤더 없음) + `Authorization` 헤더 가림. 출력은 [DhLog] 를 거친다.
 * - 평문 http 는 매니페스트 usesCleartextTraffic placeholder 가 base URL 스킴에 따라 허용/차단한다.
 * - T33: 인증 경로 401 → [VehicleTokenAuthenticator] 가 [TokenRefresher] 로 한 번 갱신하고 재시도한다.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                val logging = HttpLoggingInterceptor { message -> DhLog.d("http $message") }
                logging.level = HttpLoggingInterceptor.Level.BASIC
                logging.redactHeader("Authorization")
                addInterceptor(logging)
            }
        }

    private fun retrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(ServerConfig.publicBaseUrl + "/")
        .client(client)
        .addConverterFactory(DhJson.json.asConverterFactory(JSON_MEDIA_TYPE))
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(tokenStore: TokenStore, refresher: Lazy<TokenRefresher>): OkHttpClient = baseBuilder()
        // 차량 JWT (T17 그룹 API) — 인증 경로에만 붙는다. 로깅 인터셉터보다 앞(addInterceptor 순서)일 필요는 없다: 헤더는 로그에서 가린다
        .addInterceptor(VehicleAuthInterceptor(tokenStore))
        .authenticator(
            VehicleTokenAuthenticator { rejected ->
                runBlocking { refresher.get().refreshAfterRejection(rejected, RefreshTrigger.HTTP_401) }.accessTokenOrNull
            },
        )
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit = retrofit(client)

    @Provides
    @Singleton
    fun provideIdentityApi(retrofit: Retrofit): IdentityApi = retrofit.create()

    /** T33 갱신 전용(Authenticator 없음, 별도 Dispatcher) — [TokenRefresher] 만 쓴다 */
    @Provides
    @Singleton
    @RefreshClient
    fun provideRefreshIdentityApi(): IdentityApi = retrofit(baseBuilder().build()).create()

    @Provides
    @Singleton
    fun provideGroupApi(retrofit: Retrofit): GroupApi = retrofit.create()

    /** T31 서버 시각 (`GET /v1/time`, 인증 없음) */
    @Provides
    @Singleton
    fun provideTimeApi(retrofit: Retrofit): TimeApi = retrofit.create()
}
