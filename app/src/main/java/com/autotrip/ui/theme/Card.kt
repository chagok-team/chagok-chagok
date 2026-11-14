package com.autotrip.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 카드 모서리 (Shape.kt의 large와 같게) */
val CardShape = RoundedCornerShape(16.dp)

/**
 * 바탕(Light Sand) 위에 내용을 묶는 흰 카드. 그림자 없이 색 차이로만 구분한다.
 * 목록은 카드 하나 안에 구분선으로 나눈 줄로 넣는다 (줄마다 카드를 만들지 않는다).
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier, shape = CardShape, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}
