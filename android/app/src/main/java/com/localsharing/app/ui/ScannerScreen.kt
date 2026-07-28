package com.localsharing.app.ui

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ScannerScreen"

@Composable
fun ScannerScreen(onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanned = remember { AtomicBoolean(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val scanner: BarcodeScanner = BarcodeScanning.getClient()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                                processImage(imageProxy, scanner) { value ->
                                    if (scanned.compareAndSet(false, true)) onResult(value)
                                }
                            }
                        }
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "camera bind failed: ${e.message}")
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )

        // 顶部渐变遮罩
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            val topMask = MaterialTheme.colorScheme.primary
            Box(
                Modifier.fillMaxSize()
                    .align(Alignment.TopStart),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(topMask.copy(alpha = 0.55f), Color.Transparent),
                        ),
                        size = size.copy(height = size.height * 0.25f),
                    )
                }
            }
            Text("将二维码放入取景框", color = Color.White, modifier = Modifier.padding(top = 48.dp), style = MaterialTheme.typography.titleMedium)
        }

        // 取景框（圆角描边 + 四角括号）
        Box(Modifier.align(Alignment.Center).size(240.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val len = 28.dp.toPx()
                val color = Color.White.copy(alpha = 0.95f)
                val stroke = 3.dp.toPx()
                // 四角
                // 左上
                drawLine(color, Offset(0f, len), Offset(0f, 0f), strokeWidth = stroke)
                drawLine(color, Offset(0f, 0f), Offset(len, 0f), strokeWidth = stroke)
                // 右上
                drawLine(color, Offset(w - len, 0f), Offset(w, 0f), strokeWidth = stroke)
                drawLine(color, Offset(w, 0f), Offset(w, len), strokeWidth = stroke)
                // 左下
                drawLine(color, Offset(0f, h - len), Offset(0f, h), strokeWidth = stroke)
                drawLine(color, Offset(0f, h), Offset(len, h), strokeWidth = stroke)
                // 右下
                drawLine(color, Offset(w - len, h), Offset(w, h), strokeWidth = stroke)
                drawLine(color, Offset(w, h), Offset(w, h - len), strokeWidth = stroke)
            }
        }

        // 底部提示条
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 3.dp,
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("保持稳定，自动识别", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                androidx.compose.material3.TextButton(onClick = onCancel) { Text("取消") }
            }
        }
    }
}

private fun processImage(proxy: ImageProxy, scanner: BarcodeScanner, onResult: (String) -> Unit) {
    val mediaImage = proxy.image
    if (mediaImage == null) {
        proxy.close()
        return
    }
    val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            for (b in barcodes) {
                b.rawValue?.let { onResult(it) }
                break
            }
        }
        .addOnFailureListener { Log.w(TAG, "barcode scan failed: ${it.message}") }
        .addOnCompleteListener { proxy.close() }
}
