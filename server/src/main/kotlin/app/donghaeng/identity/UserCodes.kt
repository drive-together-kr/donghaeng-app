package app.donghaeng.identity

import java.security.SecureRandom

/**
 * 화면 표시용 사용자 코드 (docs/03 §1.2 `userCode`): 6자, 하이픈 없음.
 * 알파벳은 헷갈리는 글자(0/O, 1/I/L)를 뺀 31자 — 31^6 ≈ 8.9억 가지.
 */
object UserCodes {
    const val LENGTH = 6
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    private val random = SecureRandom()
    private val pattern = Regex("^[$ALPHABET]{$LENGTH}$")

    fun generate(): String {
        val out = CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return String(out)
    }

    /**
     * 사용자가 입력하거나 QR 에서 읽은 코드를 저장 형태로 맞춘다: 대문자, 하이픈·공백 제거 (`kq7-4mz` → `KQ74MZ`).
     * 형식이 맞지 않으면 null (호출자가 404 CODE_NOT_FOUND 로 처리).
     */
    fun normalize(raw: String?): String? {
        val s = raw.orEmpty().filterNot { it == '-' || it.isWhitespace() }.uppercase()
        return s.takeIf { pattern.matches(it) }
    }
}
