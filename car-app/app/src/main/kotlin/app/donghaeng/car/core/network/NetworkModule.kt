package app.donghaeng.car.core.network

import app.donghaeng.car.BuildConfig
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.protocol.DhJson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Retrofit 2.11 + OkHttp 4.12 + kotlinx-serialization 컨버터([DhJson.json] — ignoreUnknownKeys, explicitNulls=false).
 *
 * - base URL = [ServerConfig.publicBaseUrl] + "/" (BuildConfig ← Gradle 속성, CLAUDE.md 규칙 12)
 * - 로깅은 **디버그 빌드에서만** BASIC(메서드·URL·상태·크기, 본문·헤더 없음) + `Authorization` 헤더 가림. 출력은 [DhLog] 를 거친다.
 * - 평문 http 는 매니페스트 usesCleartextTraffic placeholder 가 base URL 스킴에 따라 허용/차단한다.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    @Provides
    @Singleton
    fun provideOkHttpClient(tokenStore: TokenStore): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        // 차량 JWT (T17 그룹 API) — 인증 경로에만 붙는다
        .addInterceptor(VehicleAuthInterceptor(tokenStore))
        .apply {
            if (BuildConfig.DEBUG) {
                val logging = HttpLoggingInterceptor { message -> DhLog.d("http $message") }
                logging.level = HttpLoggingInterceptor.Level.BASIC
                logging.redactHeader("Authorization")
                addInterceptor(logging)
            }
        }
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(ServerConfig.publicBaseUrl + "/")
        .client(client)
        .addConverterFactory(DhJson.json.asConverterFactory(JSON_MEDIA_TYPE))
        .build()

    @Provides
    @Singleton
    fun provideIdentityApi(retrofit: Retrofit): IdentityApi = retrofit.create()

    @Provides
    @Singleton
    fun provideGroupApi(retrofit: Retrofit): GroupApi = retrofit.create()
}
