package app.donghaeng.car.adapter.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.CapabilityDomain
import app.donghaeng.car.domain.model.CapabilityEntry
import app.donghaeng.car.domain.model.CapabilitySource
import app.donghaeng.car.domain.model.Gear
import app.donghaeng.car.domain.model.TurnSignalState
import app.donghaeng.car.domain.model.VehicleCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Pleos Vehicle SDK 2.3.2 어댑터 — T11: [vehicleId] (CarInfo) + [capabilities] (checkXxxCapabilities), T22: 신호 Flow.
 *
 * SDK 시그니처는 Gradle 캐시의 `Vehicle-2.3.2` AAR 을 javap 로 확인했다(공식 샘플 pleos-sample-vehicle-app 은 Door 만 다룬다):
 * ```
 * Vehicle(context).initialize() / release()        // initialize = bindService. 연결 전 요청은 SDK 내부 Queue 에 쌓였다가 연결 후 실행된다
 * carInfo.getVINInfo(onComplete: (String) -> Unit, onFailed: (Exception) -> Unit)          getCRNInfo(...) 동일
 * fuel.getFuelPercent / registerFuelPercent(FuelPercentListener{onFuelPercentUpdated(Int?)}) / unregisterFuelPercent
 * evBattery.getEvBatteryStateOfCharge / registerEvBatteryStateOfCharge(EvBatteryStateOfChargeListener) / unregister…
 * brake.getCurrentBrakePedalStatus / registerBrakePedalStatus(BrakePedalStatusListener{onBrakePedalStatusUpdated(Boolean?)}) / unregister…
 * turnSignal.getCurrentTurnSignalState / registerTurnSignalState(TurnSignalStateListener{onTurnSignalStateUpdated(TurnSignalValue)}) / unregister…
 * safety.checkAirbagDeployedCapabilities(onComplete: (CapabilityConfig, List<SeatArea>) -> Unit, onFailed)
 * safety.registerSeatAirbagDeployed(SeatArea, SeatAirbagDeployedListener{onSeatAirbagDeployUpdated(Boolean?)}) / unregister…(area, listener)
 * gear.getCurrentGearSelectionState / registerGearSelection(GearSelectionListener{onSelected(VehicleGear)}) / unregister…  — check 메서드 없음
 * 모든 리스너는 VehicleListener.onFailed(Exception) 을 가진다.
 * CapabilityConfig.accessType: AccessType { NONE, READ, WRITE, READ_WRITE }  — NONE = 미지원(공식 샘플 VehicleApiExplorer 와 같은 판정)
 * TurnSignalValue { OFF, RIGHT, LEFT }               — 좌·우 동시 표시 값이 없다 → 비상등(HAZARD) 미지원(docs/10 R3)
 * ```
 * 규칙(CLAUDE.md 3): 콜백은 suspendCancellableCoroutine / callbackFlow 로 감싸고 awaitClose 에서 **같은 리스너로** unregister.
 * `release()` 는 모든 콜백 이후 — 이 어댑터는 앱 수명 동안 싱글턴이라 프로세스 종료까지 유지한다.
 * 규칙 4: 실패·미지원은 null / 빈 스트림 / supported=false 로 돌려주고 절대 던지지 않는다.
 * 속도는 Pleos SDK 에 없어 AAOS Car API([CarApiSignals], `PERF_VEHICLE_SPEED`, CAR_SPEED 런타임 권한)로, 기어는 Pleos `Gear` → 실패 시 Car API `GEAR_SELECTION`.
 */
class PleosVehicleAdapter(
    context: Context,
    private val callTimeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MILLIS,
) : VehicleAdapter {

    private val appContext = context.applicationContext
    private val carApi = CarApiSignals(appContext)
    private val mutex = Mutex()
    private var vehicle: Vehicle? = null
    private var initAttempts = 0
    private var cachedVehicleId: String? = null
    private var idResolved = false
    /** 진단용 — VIN/CRN 값의 모양(길이·자리표시자 여부)만. 원문 없음 */
    private var idShape: String = "unread"
    private var cachedCapabilities: VehicleCapabilities? = null

    /**
     * SDK 바인딩. 서비스 앱(ai.pleos.playground.service.vehicle)이 없으면 예외 → 로그만 남기고 null.
     * ServiceConnection 콜백은 메인 루퍼로 오므로 bind 도 메인에서 한다. 최대 [MAX_INIT_ATTEMPTS]번만 재시도한다. [mutex] 안에서 부른다.
     */
    private suspend fun ensureInitialized(): Vehicle? {
        vehicle?.let { return it }
        if (initAttempts >= MAX_INIT_ATTEMPTS) return null
        initAttempts++
        return withContext(Dispatchers.Main.immediate) {
            try {
                Vehicle(appContext).also {
                    it.initialize()
                    vehicle = it
                    DhLog.i("PleosVehicleAdapter initialize OK (attempt=$initAttempts)")
                }
            } catch (e: Exception) {
                DhLog.w("PleosVehicleAdapter initialize 실패 (attempt=$initAttempts) — 차량 ID 없음·capability 미확인으로 진행", e)
                null
            }
        }
    }

    private suspend fun vehicleOrNull(): Vehicle? = mutex.withLock { ensureInitialized() }

    /** 모든 구독이 끝난 뒤에만 호출한다(테스트·종료 시). */
    fun release() {
        runCatching { vehicle?.release() }.onFailure { DhLog.w("PleosVehicleAdapter release 실패", it) }
        vehicle = null
        cachedCapabilities = null
        idResolved = false
        cachedVehicleId = null
    }

    // ---------------------------------------------------------------- 차량 ID

    /**
     * CarInfo VIN → 쓸 만한 값이 아니면 CRN → 둘 다 아니면 null. 결과(없음 포함)는 캐시한다(페어링·진단 화면이 반복 호출).
     * 값은 [VehicleIdNormalizer] 로 정리한다 — Pleos AVD 는 따옴표 포함 `"invalid"` 를 준다(실측). null 이면 호출 측이 설치별 폴백 ID 를 쓴다.
     */
    override suspend fun vehicleId(): String? = mutex.withLock {
        if (idResolved) return cachedVehicleId
        val v = ensureInitialized() ?: return null
        readVehicleIdUnlocked(v)
    }

    /** [mutex] 를 잡은 상태에서만 부른다. CRN 계열은 SDK 2.3.2 에서 deprecated(VIN 권고)지만 VIN 이 자리표시자인 이미지 대비 폴백으로 남긴다. */
    @Suppress("DEPRECATION")
    private suspend fun readVehicleIdUnlocked(v: Vehicle): String? {
        val carInfo = v.getCarInfo()
        // SDK 콜백은 플랫폼 타입 (String?) -> Unit 으로 보인다. 결정은 VehicleIdReader(순수 함수 — A047: 시간 초과는 캐시하지 않는다)
        val outcome = VehicleIdReader.read(
            readVin = { sdkCall<String?>("getVINInfo") { ok, fail -> carInfo.getVINInfo(ok, fail) } },
            readCrn = { sdkCall<String?>("getCRNInfo") { ok, fail -> carInfo.getCRNInfo(ok, fail) } },
            isTimeout = { it is TimeoutCancellationException },
        )
        return when (outcome) {
            is VehicleIdReader.Outcome.Resolved -> {
                idShape = outcome.shape
                DhLog.i("CarInfo ${outcome.shape} → ${if (outcome.id == null) "사용 불가(폴백 ID 사용)" else "사용"}")
                cachedVehicleId = outcome.id
                idResolved = true
                outcome.id
            }
            is VehicleIdReader.Outcome.Retry -> {
                idShape = outcome.shape
                DhLog.w("CarInfo ${outcome.shape} → 이번엔 확정하지 않음(다음 호출에서 다시 읽는다)")
                null
            }
        }
    }

    // ---------------------------------------------------------------- 신호 Flow (T22)

    /** awaitClose 에서 실행할 해제 동작 */
    private fun interface Cleanup {
        fun run()
    }

    /**
     * register → (현재값 1회 조회) → awaitClose 에서 같은 리스너로 unregister 하는 공통 골격.
     * SDK 가 없으면 빈 스트림으로 끝난다(미지원 취급). 등록 중 예외도 빈 스트림.
     */
    private fun <T> vehicleFlow(
        name: String,
        /** true 면 등록 중 동기 예외를 '실패 종료'(예외)로 닫는다 — 호출 쪽 `.catch` 가 폴백을 고를 수 있게(A049, 기어 → Car API) */
        failOnRegisterError: Boolean = false,
        register: suspend ProducerScope<T>.(Vehicle) -> Cleanup,
    ): Flow<T> = callbackFlow {
        val v = vehicleOrNull()
        if (v == null) {
            DhLog.w("Vehicle SDK 없음 → $name 스트림 없음")
            close()
            return@callbackFlow
        }
        val cleanup = try {
            register(v)
        } catch (e: Exception) {
            DhLog.w("Vehicle SDK $name register 실패 ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            if (failOnRegisterError) close(e) else close()
            return@callbackFlow
        }
        DhLog.i("Vehicle SDK $name registered")
        awaitClose {
            runCatching { cleanup.run() }.onFailure { DhLog.w("Vehicle SDK $name unregister 실패", it) }
            DhLog.i("Vehicle SDK $name unregistered")
        }
    }

    /** 미지원(onFailed) 은 null 한 번 흘리고 끝낸다 → 소비자는 "값 없음" 으로 숨기고, 수집기는 나중에 다시 시도한다 */
    private fun <T> ProducerScope<T?>.unsupported(name: String, e: Exception) {
        DhLog.w("Vehicle SDK $name 미지원/실패: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
        trySend(null)
        close()
    }

    override fun fuelPercent(): Flow<Int?> = vehicleFlow("fuelPercent") { v ->
        val fuel = v.getFuel()
        val listener = object : FuelPercentListener {
            override fun onFuelPercentUpdated(percent: Int?) {
                trySend(percent)
            }

            override fun onFailed(e: Exception) = unsupported("fuelPercent", e)
        }
        fuel.registerFuelPercent(listener)
        fuel.getFuelPercent({ trySend(it) }, { e -> unsupported("getFuelPercent", e) })
        Cleanup { fuel.unregisterFuelPercent(listener) }
    }

    override fun evStateOfCharge(): Flow<Int?> = vehicleFlow("evStateOfCharge") { v ->
        val ev = v.getEvBattery()
        val listener = object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(soc: Int?) {
                trySend(soc)
            }

            override fun onFailed(e: Exception) = unsupported("evStateOfCharge", e)
        }
        ev.registerEvBatteryStateOfCharge(listener)
        ev.getEvBatteryStateOfCharge({ trySend(it) }, { e -> unsupported("getEvBatteryStateOfCharge", e) })
        Cleanup { ev.unregisterEvBatteryStateOfCharge(listener) }
    }

    override fun brakePedal(): Flow<Boolean> = vehicleFlow("brakePedal") { v ->
        val brake = v.getBrake()
        val listener = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
                pressed?.let { trySend(it) }
            }

            override fun onFailed(e: Exception) {
                DhLog.w("Vehicle SDK brakePedal 미지원/실패: ${e.javaClass.simpleName}")
                close()
            }
        }
        brake.registerBrakePedalStatus(listener)
        brake.getCurrentBrakePedalStatus({ it?.let { b -> trySend(b) } }, { e -> DhLog.w("getCurrentBrakePedalStatus 실패 ${e.javaClass.simpleName}") })
        Cleanup { brake.unregisterBrakePedalStatus(listener) }
    }

    override fun turnSignal(): Flow<TurnSignalState> = vehicleFlow("turnSignal") { v ->
        val turn = v.getTurnSignal()
        val listener = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
                value?.let { trySend(it.toDomain()) }
            }

            override fun onFailed(e: Exception) {
                DhLog.w("Vehicle SDK turnSignal 미지원/실패: ${e.javaClass.simpleName}")
                close()
            }
        }
        turn.registerTurnSignalState(listener)
        turn.getCurrentTurnSignalState({ it?.let { v -> trySend(v.toDomain()) } }, { e -> DhLog.w("getCurrentTurnSignalState 실패 ${e.javaClass.simpleName}") })
        Cleanup { turn.unregisterTurnSignalState(listener) }
    }

    /** 지원되는 모든 좌석 영역을 구독해 하나라도 전개되면 true */
    override fun airbagDeployed(): Flow<Boolean> = vehicleFlow("airbagDeployed") { v ->
        val safety = v.getSafety()
        val probe = sdkCall<Pair<CapabilityConfig, List<SeatArea>>>("checkAirbagDeployedCapabilities") { ok, fail ->
            safety.checkAirbagDeployedCapabilities({ config, areas -> ok(config to areas) }, fail)
        }.getOrNull()
        val areas = probe?.takeIf { it.first.isReadable() }?.second.orEmpty()
        if (areas.isEmpty()) {
            DhLog.i("Vehicle SDK airbagDeployed 미지원(영역 없음) → 스트림 없음")
            close()
            return@vehicleFlow Cleanup { }
        }
        val deployed = ConcurrentHashMap<SeatArea, Boolean>()
        val listeners = areas.map { area ->
            area to object : SeatAirbagDeployedListener {
                override fun onSeatAirbagDeployUpdated(value: Boolean?) {
                    deployed[area] = value == true
                    trySend(deployed.values.any { it })
                }

                override fun onFailed(e: Exception) {
                    DhLog.w("Vehicle SDK airbagDeployed(area) 실패: ${e.javaClass.simpleName}")
                }
            }
        }
        listeners.forEach { (area, l) -> safety.registerSeatAirbagDeployed(area, l) }
        trySend(false)
        Cleanup { listeners.forEach { (area, l) -> safety.unregisterSeatAirbagDeployed(area, l) } }
    }

    /** 속도는 Pleos SDK 에 없다 → Car API. 권한 없음·속성 없음이면 빈 스트림 */
    override fun speedKph(): Flow<Float?> = carApi.speedKph()

    /** Pleos Gear(check 메서드 없음 — 등록·조회가 실패하면) → Car API GEAR_SELECTION 폴백 */
    override fun gear(): Flow<Gear?> = flow {
        if (vehicleOrNull() == null) {
            emitAll(carApi.gear())
            return@flow
        }
        emitAll(
            pleosGear().catch { e ->
                DhLog.i("Pleos Gear 실패(${e.javaClass.simpleName}) → Car API GEAR_SELECTION 폴백")
                emitAll(carApi.gear())
            },
        )
    }

    // A049: registerGearSelection 이 SecurityException 등을 바로 던지면(SDK 2.3.2 javap — CAR_DRIVE 권한 검사는 동기 throw) 예외로 끝내 gear() 의 Car API 폴백이 돈다
    private fun pleosGear(): Flow<Gear?> = vehicleFlow("gearSelection", failOnRegisterError = true) { v ->
        val gear = v.getGear()
        val listener = object : GearSelectionListener {
            override fun onSelected(value: VehicleGear?) {
                value?.let { trySend(it.toDomain()) }
            }

            override fun onFailed(e: Exception) {
                close(e)
            }
        }
        gear.registerGearSelection(listener)
        gear.getCurrentGearSelectionState({ it?.let { g -> trySend(g.toDomain()) } }, { e -> close(e) })
        Cleanup { gear.unregisterGearSelection(listener) }
    }

    private fun TurnSignalValue.toDomain(): TurnSignalState = when (this) {
        TurnSignalValue.LEFT -> TurnSignalState.LEFT
        TurnSignalValue.RIGHT -> TurnSignalState.RIGHT
        TurnSignalValue.OFF -> TurnSignalState.OFF
    }

    private fun VehicleGear.toDomain(): Gear = when (this) {
        VehicleGear.GEAR_PARK -> Gear.P
        VehicleGear.GEAR_REVERSE -> Gear.R
        VehicleGear.GEAR_NEUTRAL -> Gear.N
        VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.D   // GEAR_DRIVE, GEAR_1..8
    }

    // ---------------------------------------------------------------- capability

    /** 각 도메인의 checkXxxCapabilities 를 순서대로 호출한다. 결과는 캐시(앱 시작 시 한 번 + 진단 화면). */
    override suspend fun capabilities(): VehicleCapabilities = mutex.withLock {
        cachedCapabilities?.let { return it }
        val v = ensureInitialized()
        val raw = mapOf("adapter" to "pleos", "sdk" to "Vehicle 2.3.2", "flows" to "pleos (T22) + Car API speed/gear")
        val result = if (v == null) {
            VehicleCapabilities.allUnknown("SDK initialize 실패(서비스 앱 없음?) — 확인 못 함", CapabilitySource.PLEOS_SDK, raw)
        } else {
            VehicleCapabilities(entries = probeAll(v), raw = raw)
        }
        cachedCapabilities = result
        result
    }

    private suspend fun probeAll(v: Vehicle): List<CapabilityEntry> = buildList {
        add(probeCarInfo(v))
        add(probeConfig(CapabilityDomain.FUEL, "checkFuelPercentCapabilities") { ok, fail -> v.getFuel().checkFuelPercentCapabilities(ok, fail) })
        add(probeConfig(CapabilityDomain.EV_BATTERY, "checkEvBatteryStateOfChargeCapabilities") { ok, fail -> v.getEvBattery().checkEvBatteryStateOfChargeCapabilities(ok, fail) })
        add(probeConfig(CapabilityDomain.BRAKE, "checkBrakePedalStatusCapabilities") { ok, fail -> v.getBrake().checkBrakePedalStatusCapabilities(ok, fail) })
        val turn = probeConfig(CapabilityDomain.TURN_SIGNAL, "checkTurnSignalStateCapabilities") { ok, fail -> v.getTurnSignal().checkTurnSignalStateCapabilities(ok, fail) }
        add(turn)
        // 비상등: SDK 2.3.2 TurnSignalValue 에 좌·우 동시 값이 없으므로 방향지시등 지원 여부와 무관하게 미지원(docs/10 R3)
        add(
            CapabilityEntry(
                CapabilityDomain.HAZARD, supported = false,
                detail = "TurnSignalValue=OFF/LEFT/RIGHT 뿐(동시 표시 값 없음) → 비상등 기능 숨김; turnSignal=${turn.supportedText}",
                source = CapabilitySource.PLEOS_SDK,
            ),
        )
        add(probeAirbag(v))
        val speed = carApi.probeSpeed()
        add(CapabilityEntry(CapabilityDomain.SPEED, speed.supported, "Car API ${speed.detail}", CapabilitySource.CAR_API))
        add(probeGear(v))
    }

    /** CarInfo 는 VIN·CRN 두 속성 중 하나라도 READ 가능하면 지원 (CRN check 는 deprecated — 폴백 확인용). */
    @Suppress("DEPRECATION")
    private suspend fun probeCarInfo(v: Vehicle): CapabilityEntry {
        val carInfo = v.getCarInfo()
        val vin = sdkCall<CapabilityConfig>("checkVINInfoCapabilities") { ok, fail -> carInfo.checkVINInfoCapabilities(ok, fail) }
        val crn = sdkCall<CapabilityConfig>("checkCRNInfoCapabilities") { ok, fail -> carInfo.checkCRNInfoCapabilities(ok, fail) }
        val vinOk = vin.getOrNull()?.isReadable()
        val crnOk = crn.getOrNull()?.isReadable()
        if (!idResolved && (vinOk == true || crnOk == true)) readVehicleIdUnlocked(v)
        // "지원" = 읽을 수 있고 자리표시자가 아닌 실제 ID 가 있다. READ 인데 값이 "invalid" 면 false(폴백 ID 로 페어링).
        val supported: Boolean? = when {
            vinOk == null && crnOk == null -> null
            vinOk != true && crnOk != true -> false
            else -> cachedVehicleId != null
        }
        return CapabilityEntry(
            CapabilityDomain.CAR_INFO, supported,
            detail = "VIN ${vin.describe()}, CRN ${crn.describe()}; value $idShape",
            source = CapabilitySource.PLEOS_SDK,
        )
    }

    /** 에어백 전개 여부: accessType + 지원 좌석 영역 목록. */
    private suspend fun probeAirbag(v: Vehicle): CapabilityEntry {
        val result = sdkCall<Pair<CapabilityConfig, List<SeatArea>>>("checkAirbagDeployedCapabilities") { ok, fail ->
            v.getSafety().checkAirbagDeployedCapabilities({ config, areas -> ok(config to areas) }, fail)
        }
        return result.fold(
            onSuccess = { (config, areas) ->
                val seats = areas.flatMap { area -> area.values.map { it.name } }
                CapabilityEntry(
                    CapabilityDomain.AIRBAG, supported = config.isReadable(),
                    detail = "accessType=${config.accessType} areas=$seats",
                    source = CapabilitySource.PLEOS_SDK,
                )
            },
            onFailure = { e -> CapabilityEntry(CapabilityDomain.AIRBAG, supportedOnFailure(e), describeFailure(e), CapabilitySource.PLEOS_SDK) },
        )
    }

    /** 기어: Pleos SDK 는 check 메서드가 없어 현재값 조회로 판정 → 실패 시 Car API GEAR_SELECTION */
    private suspend fun probeGear(v: Vehicle): CapabilityEntry {
        val pleos = sdkCall<VehicleGear?>("getCurrentGearSelectionState") { ok, fail -> v.getGear().getCurrentGearSelectionState(ok, fail) }
        return pleos.fold(
            onSuccess = { g -> CapabilityEntry(CapabilityDomain.GEAR, true, "Pleos SDK Gear 현재값=$g", CapabilitySource.PLEOS_SDK) },
            onFailure = { e ->
                val car = carApi.probeGear()
                CapabilityEntry(CapabilityDomain.GEAR, car.supported, "Pleos Gear ${describeFailure(e)} → Car API ${car.detail}", CapabilitySource.CAR_API)
            },
        )
    }

    private suspend fun probeConfig(
        domain: CapabilityDomain,
        name: String,
        call: (onComplete: (CapabilityConfig) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
    ): CapabilityEntry = sdkCall<CapabilityConfig>(name, call).fold(
        onSuccess = { config -> CapabilityEntry(domain, config.isReadable(), "accessType=${config.accessType}", CapabilitySource.PLEOS_SDK) },
        onFailure = { e -> CapabilityEntry(domain, supportedOnFailure(e), describeFailure(e), CapabilitySource.PLEOS_SDK) },
    )

    /**
     * onComplete/onFailed 콜백 한 쌍을 suspend 호출로 감싼다. [callTimeoutMillis] 안에 응답이 없으면 실패(Timeout).
     * 콜백이 두 번 와도 한 번만 resume 한다. 동기 예외(권한 없음 등)도 실패로 돌린다 — 절대 던지지 않는다.
     */
    private suspend fun <T> sdkCall(
        name: String,
        call: (onComplete: (T) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
    ): Result<T> = try {
        val value = withTimeout(callTimeoutMillis) {
            suspendCancellableCoroutine<T> { cont ->
                try {
                    call(
                        { v -> if (cont.isActive) cont.resume(v) },
                        { e -> if (cont.isActive) cont.resumeWithException(e) },
                    )
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }
        Result.success(value)
    } catch (e: TimeoutCancellationException) {
        DhLog.w("Vehicle SDK $name 응답 없음(${callTimeoutMillis}ms)")
        Result.failure(e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DhLog.w("Vehicle SDK $name 실패: ${e.javaClass.simpleName}")
        Result.failure(e)
    }

    /** 타임아웃은 "모름"(null), SDK 가 onFailed 로 답한 것은 "미지원"(false). */
    private fun supportedOnFailure(e: Throwable): Boolean? = if (e is TimeoutCancellationException) null else false

    private fun describeFailure(e: Throwable): String =
        if (e is TimeoutCancellationException) "timeout ${callTimeoutMillis}ms (서비스 응답 없음)"
        else "onFailed ${e.javaClass.simpleName}: ${e.message?.take(120) ?: "-"}"

    private fun Result<CapabilityConfig>.describe(): String = fold(
        onSuccess = { "accessType=${it.accessType}" },
        onFailure = { describeFailure(it) },
    )

    private fun CapabilityConfig.isReadable(): Boolean = accessType == AccessType.READ || accessType == AccessType.READ_WRITE

    companion object {
        const val DEFAULT_CALL_TIMEOUT_MILLIS = 5_000L
        private const val MAX_INIT_ATTEMPTS = 3
    }
}
