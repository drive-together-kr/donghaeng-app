package app.donghaeng.car.adapter.sdk

import app.donghaeng.car.core.common.DhLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** fake flavor — Pleos SDK 가 의존성에 없으므로 점검할 것이 없다. */
@Singleton
class NoSdkProbe @Inject constructor() : SdkProbe {

    private val _report = MutableStateFlow<List<ProbeLine>?>(null)
    override val report: StateFlow<List<ProbeLine>?> = _report.asStateFlow()

    override fun runOnce() {
        if (_report.value != null) return
        _report.value = listOf(ProbeLine("Pleos SDK", available = false, detail = "fake flavor — SDK 미포함, 전부 Fake 어댑터"))
        DhLog.i("sdk probe: fake flavor (Pleos SDK 미포함)")
    }
}
