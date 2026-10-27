# 동행 SDK consumer rules — 호스트 앱(블루링크·마이현대·기아 커넥트·제네시스) R8 설정에 자동으로 합쳐진다.

# kotlinx.serialization — protocol 스키마 클래스의 생성된 직렬화기를 유지
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.donghaeng.protocol.**$$serializer { *; }
-keepclassmembers class app.donghaeng.protocol.** { *** Companion; }
-keepclasseswithmembers class app.donghaeng.protocol.** { kotlinx.serialization.KSerializer serializer(...); }

# 공개 API 는 호스트 앱이 직접 호출하므로 난독화·제거 대상에서 제외
-keep public class app.donghaeng.companion.sdk.DongHaeng { public *; }
-keep public class app.donghaeng.companion.sdk.DongHaengConfig { public *; }
-keep public class app.donghaeng.companion.sdk.DongHaengException { public *; }
-keep public class app.donghaeng.companion.sdk.DongHaengException$* { public *; }

# OkHttp (T13 전송 구현 예정)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
