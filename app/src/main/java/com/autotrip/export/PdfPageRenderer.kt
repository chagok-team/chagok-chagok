package com.autotrip.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.roundToInt

/**
 * 앱이 만든 운행기록부 PDF를 화면에 보여 주기 위해 쪽을 그림으로 그린다 (Android 표준 PdfRenderer).
 *
 * - 앱 전용 저장소의 파일을 읽기만 한다. 다른 앱으로 넘기지 않으므로 FileProvider·Manifest 변경이 필요 없다.
 * - PdfRenderer는 한 번에 한 쪽만 열 수 있어서 [render]와 [close]를 동기화한다.
 * - 다 쓰면 [close]로 PdfRenderer와 파일 핸들을 닫는다.
 */
class PdfPageRenderer(file: File) : AutoCloseable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (e: Exception) {
        descriptor.close()
        throw e
    }
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /** [index]쪽을 가로 [targetWidth]픽셀로 그린다. PDF 바탕은 투명이라 흰색을 먼저 칠한다. */
    @Synchronized
    fun render(index: Int, targetWidth: Int): Bitmap {
        check(!closed) { "이미 닫힌 PDF" }
        require(index in 0 until pageCount) { "없는 쪽: $index" }
        renderer.openPage(index).use { page ->
            val height = (page.height * targetWidth.toFloat() / page.width).roundToInt()
            val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        renderer.close()
        descriptor.close()
    }
}
