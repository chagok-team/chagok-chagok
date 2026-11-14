package com.autotrip.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * 화면의 주요 버튼 (화면당 하나).
 * - 주간: Hyundai Blue로 채운 버튼
 * - 야간: 밝은 면이 눈부시지 않도록 채우지 않고 Sky Blue 테두리(2dp) + 밝은 글자만
 */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ControlShape,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    if (colors.background.luminance() >= 0.5f) {
        Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, content = content)
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            border = BorderStroke(2.dp, if (enabled) colors.primary else colors.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = colors.onSurface),
            content = content,
        )
    }
}
