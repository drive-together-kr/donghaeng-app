package app.donghaeng.companion.demo.donghaeng

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 카메라 없이 QR 읽기 — 사진(차 화면 QR 을 찍은 사진·스크린샷)에서 ML Kit 로 QR 문자열을 꺼낸다.
 * 카메라가 없는 PC 의 에뮬레이터, 카메라가 고장 난 폰, 메신저로 받은 QR 이미지에 쓴다. 결과는 카메라 스캔과 같은 흐름([ScanScreen] handle)으로 간다.
 */
object PhotoQr {
    /** [uri] 이미지에서 첫 QR 문자열. 없거나 읽지 못하면 null(예외를 던지지 않는다) */
    suspend fun decode(context: Context, uri: Uri): String? {
        val image = runCatching { InputImage.fromFilePath(context, uri) }.getOrNull() ?: return null
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        return try {
            suspendCancellableCoroutine { cont ->
                scanner.process(image)
                    .addOnSuccessListener { codes -> cont.resume(firstValue(codes.map { it.rawValue })) }
                    .addOnFailureListener { cont.resume(null) }
            }
        } finally {
            scanner.close()
        }
    }

    /** 순수 함수(단위 테스트) — 비어 있지 않은 첫 값 */
    fun firstValue(values: List<String?>): String? = values.firstOrNull { !it.isNullOrBlank() }?.trim()
}

/**
 * 링크로 열기 — 메신저로 받은 초대·등록 주소(`{서버}/j/inv_…`, `{서버}/pair?c=…`)를 누르면 이 앱이 열리고 스캔 화면이 그 주소를 처리한다.
 * MainActivity 가 VIEW 인텐트의 주소를 [offer] 하고, 로그인된 상태에서 [KiaApp] 이 스캔 화면으로 옮긴 뒤 [ScanScreen] 이 [take] 한다.
 */
object IncomingLink {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun offer(url: String?) {
        if (!url.isNullOrBlank()) _pending.value = url.trim()
    }

    /** 한 번만 꺼낸다 */
    fun take(): String? = _pending.value.also { _pending.value = null }
}
