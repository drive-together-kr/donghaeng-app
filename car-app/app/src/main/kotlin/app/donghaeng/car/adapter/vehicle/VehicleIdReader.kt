package app.donghaeng.car.adapter.vehicle

/**
 * CarInfo VIN → CRN 차량 ID 읽기 결정 (A047) — SDK 타입 없이 순수 Kotlin(단위 테스트). 실제 SDK 호출은 `PleosVehicleAdapter` 가 넘긴다.
 *
 * - VIN 이 쓸 만하면 그것으로 확정. 자리표시자(`"invalid"` 등)·onFailed 면 CRN 을 본다. 둘 다 아니면 '없음'으로 **확정**(설치별 폴백 ID).
 * - **응답 시간 초과는 확정하지 않는다**([Outcome.Retry]) — 앱 시작 직후 서비스 바인딩이 늦어 5초를 넘긴 한 번 때문에 그 프로세스의 페어링이
 *   설치별 폴백 ID 로 굳고(재설치하면 다른 차가 된다) VIN 기반 기존 등록과 갈라지던 문제. 다음 호출에서 VIN 부터 다시 읽는다.
 *   VIN 이 시간 초과면 CRN 으로 넘어가지 않는다(VIN 이 있는 차가 CRN 해시로 등록되지 않게).
 */
object VehicleIdReader {

    sealed interface Outcome {
        /** 확정 — [id] null = CarInfo 로는 없음(폴백 ID 사용) */
        data class Resolved(val id: String?, val shape: String) : Outcome

        /** 응답 시간 초과 — 캐시하지 말고 다음에 다시 */
        data class Retry(val shape: String) : Outcome
    }

    /**
     * @param readVin  VIN 원값 읽기. 실패면 Result.failure
     * @param readCrn  CRN 원값 읽기(VIN 을 못 쓸 때만 부른다)
     * @param isTimeout 실패 원인이 응답 시간 초과인가
     */
    suspend fun read(
        readVin: suspend () -> Result<String?>,
        readCrn: suspend () -> Result<String?>,
        isTimeout: (Throwable) -> Boolean,
    ): Outcome {
        val vinResult = readVin()
        val vinError = vinResult.exceptionOrNull()
        if (vinError != null && isTimeout(vinError)) return Outcome.Retry("VIN=timeout")
        val vinRaw = vinResult.getOrNull()
        val vin = VehicleIdNormalizer.normalize(vinRaw)
        if (vin != null) return Outcome.Resolved(vin, "VIN=${VehicleIdNormalizer.shape(vinRaw)}")
        val crnResult = readCrn()
        val crnError = crnResult.exceptionOrNull()
        val shape = "VIN=${VehicleIdNormalizer.shape(vinRaw)} CRN=${if (crnError != null && isTimeout(crnError)) "timeout" else VehicleIdNormalizer.shape(crnResult.getOrNull())}"
        if (crnError != null && isTimeout(crnError)) return Outcome.Retry(shape)
        return Outcome.Resolved(VehicleIdNormalizer.normalize(crnResult.getOrNull()), shape)
    }
}
