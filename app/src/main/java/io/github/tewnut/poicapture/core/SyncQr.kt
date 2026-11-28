package io.github.tewnut.poicapture.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The sync QR code's modules (docs/camera360.md §2): error correction M, a quiet zone of [QUIET] modules around it.
 * One cell per module, so the screen scales it up without blur (drawn with no filtering).
 */
class QrModules(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int) = dark[y * size + x]

    /** ARGB pixels, one per module: black on white. */
    fun pixels(): IntArray = IntArray(size * size) { if (dark[it]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }

    companion object {
        /** Quiet zone in modules (the QR standard's 4). */
        const val QUIET = 4

        fun encode(text: String): QrModules {
            val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to QUIET,
                EncodeHintType.CHARACTER_SET to "ISO-8859-1")
            // width/height 0: the natural size (one pixel per module, quiet zone included)
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            val n = m.width
            return QrModules(n, BooleanArray(n * n) { m.get(it % n, it / n) })
        }
    }
}
