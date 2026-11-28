package io.github.tewnut.poicapture.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.core.ThetaSerial
import java.util.concurrent.Executors

/**
 * "Đọc số serial" (Settings → Camera 360 → Ricoh Theta): the back camera on the sticker under the Theta, ML Kit's text
 * recognition (the on-phone model the app already uses for signboards) on the frames, [ThetaSerial] on the text. A
 * serial is offered once two frames in a row read the same; the contributor confirms it ([onFound]). Asks for the
 * camera permission when missing.
 */
@Composable
fun SerialScanner(onFound: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    var candidate by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) SerialCamera { candidate = it }
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.serial_scan_hint), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp)).padding(10.dp))
                Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val found = candidate
                    Text(when {
                        !granted && asked -> stringResource(R.string.serial_scan_need_camera)
                        found != null -> stringResource(R.string.serial_scan_found, found)
                        else -> stringResource(R.string.serial_scan_searching)
                    }, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(50.dp)) {
                            Text(stringResource(R.string.serial_scan_cancel))
                        }
                        Button(onClick = { found?.let(onFound) }, enabled = found != null, modifier = Modifier.weight(1f).height(50.dp)) {
                            Text(stringResource(R.string.serial_scan_use), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/** The preview and the reading; a serial is reported once two readings in a row agree. Unbound when it leaves. */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun SerialCamera(onSerial: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    AndroidView({ previewView }, Modifier.fillMaxSize())
    DisposableEffect(owner) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val exec = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(ctx)
        var bound: Array<UseCase> = emptyArray()
        var last: String? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            // the sticker's print is small: a large frame (Full HD or the closest the camera gives)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                .build()
            analysis.setAnalyzer(exec) { proxy ->
                val img = proxy.image
                if (img == null) { proxy.close(); return@setAnalyzer }
                // one frame at a time: the next is taken once this one's reading is done (keep only latest)
                recognizer.process(InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { text ->   // main thread
                        val s = ThetaSerial.find(text.text)
                        if (s != null && s == last && !disposed) onSerial(s)
                        last = s
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            bound = arrayOf(preview, analysis)
            runCatching { provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            disposed = true
            runCatching { if (bound.isNotEmpty()) future.get().unbind(*bound) }
            recognizer.close()
            exec.shutdown()
        }
    }
}
