package org.example.winsignin

import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * 扫码页：CameraX 出预览，ML Kit 逐帧识别二维码。
 *
 * ML Kit 的解码器比 ZXing 强很多，斜角度、略糊、光线差也能识别。
 * @param onResult 识别到第一个二维码时回调一次（原文）。
 * @param onCancel 点返回箭头或系统返回键时回调。
 */
@Composable
fun QrScannerScreen(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult = rememberUpdatedState(onResult)
    val handled = remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val scanner: BarcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    DisposableEffect(Unit) {
        onDispose { scanner.close() }
    }

    BackHandler { onCancel() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 双指捏合缩放：按当前比例乘缩放因子，夹在相机支持的最小/最大倍率之间
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    val cam = camera ?: return@detectTransformGestures
                    val state = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                    val ratio = (state.zoomRatio * zoomChange)
                        .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(ratio)
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)

                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage == null) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val image = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees,
                        )
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                if (handled.value) return@addOnSuccessListener
                                val raw = barcodes.firstOrNull()?.rawValue
                                if (!raw.isNullOrEmpty()) {
                                    handled.value = true
                                    currentOnResult.value(raw)
                                }
                            }
                            // 识别成功/失败都要关掉这一帧，否则相机不再出帧
                            .addOnCompleteListener { imageProxy.close() }
                    }

                    provider.unbindAll()
                    camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }, executor)

                previewView
            },
        )

        // 透明返回栏盖在相机预览上
        ScreenTopBar(
            title = "",
            onBack = onCancel,
            transparent = true,
            inset = true,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}
