package io.github.tewnut.poicapture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tewnut.poicapture.capture.SignDetector
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The bundled sign model through ONNX Runtime on a real device: a no-entry sign above a keep-right sign, cropped from
 * the Lương Định Của signs ride of 27 Nov 2026. Guards ONNX Runtime upgrades (1.22: the 16 KB page-size build Play needs).
 */
@RunWith(AndroidJUnit4::class)
class SignDetectorTest {
    @Test fun findsTheNoEntrySign() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val f = File(inst.targetContext.cacheDir, "sign_p102.jpg")
        inst.context.assets.open("sign_p102.jpg").use { i -> f.outputStream().use { i.copyTo(it) } }
        val (boxes, codes) = SignDetector.open(inst.targetContext).use { d -> d.detect(f).let { b -> b to b.map { d.classes[it.cls].code } } }
        android.util.Log.i("SignDetectorTest", boxes.indices.joinToString(" | ") { "${codes[it]} ${"%.2f".format(boxes[it].score)}" })
        assertTrue("no sign found: $boxes", boxes.isNotEmpty())
    }
}
