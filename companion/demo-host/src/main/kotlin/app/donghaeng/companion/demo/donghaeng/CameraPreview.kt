package app.donghaeng.companion.demo.donghaeng

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.donghaeng.companion.demo.DemoApplication
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * CameraX 미리보기 + ML Kit QR 분석. 공식 앱은 자기 스캐너를 쓰므로 이 파일은 데모 호스트에만 있다(삽입 지점 A 의 스캐너 자리).
 * 값이 잡힐 때마다 [onQr] 를 부르고, 첫 값만 쓰는 건 호출 쪽이 정한다.
 */
@Composable
fun CameraPreview(onQr: (String) -> Unit, modifier: Modifier = Modifier, onUnavailable: () -> Unit = {}) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnQr by rememberUpdatedState(onQr)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    val analyzer = remember { QrAnalyzer { currentOnQr(it) } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var bindError by remember { mutableStateOf<String?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProvider?.unbindAll() }
            executor.shutdown()
            analyzer.close()
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        try {
                            val provider = future.get()
                            cameraProvider = provider
                            val preview = Preview.Builder().build()
                            preview.setSurfaceProvider(surfaceProvider)
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            analysis.setAnalyzer(executor, analyzer)
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                            Log.i(DemoApplication.TAG, "camera bound (back camera, QR analyzer)")
                        } catch (e: Exception) {
                            Log.w(DemoApplication.TAG, "camera bind failed: ${e.message}")
                            bindError = e.message ?: "알 수 없는 오류"
                            currentOnUnavailable() // 화면은 안내로 바꾼다(원인 원문은 로그에만)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
        )
        // 열지 못하면 호출 쪽(ScanScreen)이 onUnavailable 로 '카메라 없음' 안내로 바꾼다 — 원인 원문(예: 에뮬레이터 가상 카메라의
        // 동적 범위 형식 오류)은 로그에만 남기고, 안내를 받지 못한 호출 쪽을 위해 짧은 문구만 둔다
        if (bindError != null) {
            Text(
                "이 기기에서는 카메라를 쓸 수 없어요",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
            )
        }
    }
}

/** 프레임마다 QR 만 찾는 ML Kit 분석기(번들 모델) */
class QrAnalyzer(private val onQr: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    )

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        try {
            val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(input)
                .addOnSuccessListener { barcodes ->
                    barcodes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }?.let(onQr)
                }
                .addOnFailureListener { Log.w(DemoApplication.TAG, "barcode analyze failed: ${it.message}") }
                .addOnCompleteListener { imageProxy.close() }
        } catch (e: Exception) {
            Log.w(DemoApplication.TAG, "barcode analyze threw: ${e.message}")
            imageProxy.close()
        }
    }

    fun close() = scanner.close()
}
