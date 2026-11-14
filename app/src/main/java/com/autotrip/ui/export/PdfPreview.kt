package com.autotrip.ui.export

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.autotrip.export.PdfPageRenderer
import com.autotrip.ui.theme.ControlShape
import com.autotrip.ui.theme.PrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 미리보기 쪽 그림의 가로 픽셀 (A4 가로 한 쪽을 화면 높이에 맞춰도 글자가 또렷한 정도) */
private const val PREVIEW_WIDTH_PX = 2000

/** "1 / 3쪽" */
internal fun pageLabel(index: Int, count: Int): String = if (count <= 0) "" else "${index + 1} / ${count}쪽"

/**
 * 방금 만든 운행기록부 PDF를 앱 안에서 쪽 단위로 보여 준다.
 * 정차 중에만 열린다 (출력 화면 자체가 주행 중에는 숨겨짐). 파일은 읽기만 하고 고치지 않는다.
 */
@Composable
fun PdfPreviewScreen(file: File, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val renderer = remember(file) { runCatching { PdfPageRenderer(file) }.getOrNull() }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }
    val pageCount = renderer?.pageCount ?: 0
    var pageIndex by remember(file) { mutableStateOf(0) }

    val page by produceState<PageImage>(PageImage.Loading, renderer, pageIndex) {
        value = PageImage.Loading
        value = withContext(Dispatchers.IO) {
            renderer?.let { r -> runCatching { PageImage.Ready(r.render(pageIndex, PREVIEW_WIDTH_PX)) }.getOrNull() }
                ?: PageImage.Failed
        }
    }

    Surface(modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("운행기록부 미리보기", style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
                    Text(file.name, style = MaterialTheme.typography.bodyMedium, color = colors.outline)
                }
                if (pageCount > 1) {
                    OutlinedButton(
                        onClick = { pageIndex-- },
                        enabled = pageIndex > 0,
                        shape = ControlShape,
                        modifier = Modifier.heightIn(min = 72.dp),
                    ) { Text("‹ 이전 쪽", style = MaterialTheme.typography.labelLarge) }
                    Spacer(Modifier.width(16.dp))
                    Text(pageLabel(pageIndex, pageCount), style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
                    Spacer(Modifier.width(16.dp))
                    OutlinedButton(
                        onClick = { pageIndex++ },
                        enabled = pageIndex < pageCount - 1,
                        shape = ControlShape,
                        modifier = Modifier.heightIn(min = 72.dp),
                    ) { Text("다음 쪽 ›", style = MaterialTheme.typography.labelLarge) }
                    Spacer(Modifier.width(32.dp))
                }
                PrimaryButton(onClick = onClose, shape = ControlShape, modifier = Modifier.heightIn(min = 72.dp)) {
                    Text("닫기", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when (val p = page) {
                    is PageImage.Ready -> Image(
                        bitmap = p.bitmap.asImageBitmap(),
                        contentDescription = "운행기록부 ${pageLabel(pageIndex, pageCount)}",
                        contentScale = ContentScale.Fit,
                        // 종이처럼 흰 바탕 + 얇은 테두리 (야간에도 PDF는 흰 종이로 보인다)
                        modifier = Modifier.background(Color.White).border(1.dp, colors.outlineVariant),
                    )
                    PageImage.Loading -> Text("불러오는 중", style = MaterialTheme.typography.bodyLarge, color = colors.outline)
                    PageImage.Failed -> Text("PDF를 열지 못했어요", style = MaterialTheme.typography.bodyLarge, color = colors.error)
                }
            }
        }
    }
}

private sealed interface PageImage {
    data object Loading : PageImage
    data object Failed : PageImage
    data class Ready(val bitmap: Bitmap) : PageImage
}
