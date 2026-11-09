package io.github.tewnut.poicapture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tewnut.poicapture.capture.SignOcr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The bundled ML Kit model on a real device / emulator, on a rendered Vietnamese signboard. */
@RunWith(AndroidJUnit4::class)
class SignOcrTest {
    @Test fun readsAVietnameseSign() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val f = File(inst.targetContext.cacheDir, "sign_pho.jpg")
        inst.context.assets.open("sign_pho.jpg").use { i -> f.outputStream().use { i.copyTo(it) } }
        val r = SignOcr.read(f)
        android.util.Log.i("SignOcrTest", "lines=" + r.lines.joinToString(" | ") { it.text } +
            " name=${r.name} phones=${r.phones} house=${r.houseNumber} category=${r.category}")
        assertEquals(listOf("0912345678"), r.phones)
        assertEquals("13", r.houseNumber)
        assertEquals("Quán ăn", r.category)
        assertTrue("name: ${r.name}", r.name != null && r.name!!.uppercase().replace(" ", "").contains("GIATRUY"))
    }
}
