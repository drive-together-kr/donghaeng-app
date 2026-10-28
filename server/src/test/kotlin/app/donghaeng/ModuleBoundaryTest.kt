package app.donghaeng

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/06 §2 모듈 경계: 패키지 = 나중의 마이크로서비스 단위, 패키지 간 호출은 `XxxApi` 인터페이스만.
 * Kotlin `internal` 은 같은 Gradle 모듈 안에서는 막지 못하므로, 다른 모듈의 `web`·`repo` 하위 패키지(내부 구현 — 컨트롤러 보조 함수·저장소)를
 * import 하는 소스가 없는지 직접 훑는다. (`common` 은 공용 기반이라 대상이 아니다)
 */
class ModuleBoundaryTest {

    @Test
    fun `no module imports another module's web or repo package`() {
        val root = File("src/main/kotlin/app/donghaeng")
        assertTrue(root.isDirectory, "run from the server project directory: ${root.absoluteFile}")
        val internalImport = Regex("""^import app\.donghaeng\.([a-z]+)\.(web|repo)\..*$""", RegexOption.MULTILINE)
        val violations = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                val module = path.substringBefore('/')
                internalImport.findAll(file.readText())
                    .filter { it.groupValues[1] != module && it.groupValues[1] != "common" }
                    .map { "$path: ${it.value}" }
            }
            .toList()
        assertEquals(emptyList(), violations, "다른 모듈의 web·repo 를 import 하지 말고 그 모듈의 XxxApi 를 쓴다")
    }
}
