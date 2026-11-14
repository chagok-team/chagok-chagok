package com.autotrip.ui.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autotrip.domain.model.AutoTripError
import com.autotrip.domain.model.AutoTripResult
import com.autotrip.domain.model.InputMethod
import com.autotrip.domain.model.TripPurpose
import com.autotrip.domain.repository.TripRepository
import com.autotrip.ui.purpose.PurposeSelectScreen
import com.autotrip.ui.theme.ControlShape
import com.autotrip.ui.theme.PrimaryButton
import com.autotrip.ui.toUserMessage

/*
 * 운행 수정 (CLAUDE.md 4장 "수정 화면"): 목적·차량이용자·메모만 바꾼다.
 * 시각·계기판 값·위치는 읽기 전용이고, 삭제 기능은 없다. 수정 이력은 A의 Repository가 남긴다.
 */

/** 메모는 PDF ⑩ 비고에 붙으므로 두 줄 안에 들어가게 짧게 받는다 */
const val MEMO_MAX_LENGTH = 40

/** 빈 메모는 null로 저장한다 (비고에 " · " 꼬리가 붙지 않게) */
fun normalizeMemo(text: String): String? = text.trim().takeIf { it.isNotEmpty() }

class TripEditActions(private val tripRepository: TripRepository) {

    /**
     * 목적이 아직 없으면 최초 확정(confirmPurpose, 버튼 입력), 있으면 수정(updatePurpose).
     * 화면이 보던 상태와 저장소 상태가 어긋나면(다른 곳에서 먼저 확정 등) 반대쪽 메서드로 한 번 더 시도한다.
     * @return 오류 문구, 성공이면 null
     */
    suspend fun purpose(tripId: Long, pending: Boolean, purpose: TripPurpose): String? {
        val first = if (pending) confirm(tripId, purpose) else tripRepository.updatePurpose(tripId, purpose)
        val retry = when ((first as? AutoTripResult.Failure)?.error) {
            is AutoTripError.PurposeAlreadyConfirmed -> tripRepository.updatePurpose(tripId, purpose)
            is AutoTripError.PurposeNotConfirmed -> confirm(tripId, purpose)
            else -> first
        }
        return retry.errorText()
    }

    suspend fun user(tripId: Long, userId: Long?): String? =
        tripRepository.updateUser(tripId, userId).errorText()

    suspend fun memo(tripId: Long, text: String): String? {
        if (text.trim().length > MEMO_MAX_LENGTH) return "메모는 ${MEMO_MAX_LENGTH}자까지 쓸 수 있어요"
        return tripRepository.updateMemo(tripId, normalizeMemo(text)).errorText()
    }

    private suspend fun confirm(tripId: Long, purpose: TripPurpose) =
        tripRepository.confirmPurpose(tripId, purpose, InputMethod.BUTTON, userId = null, voiceRawText = null)

    private fun AutoTripResult<Unit>.errorText(): String? = when (this) {
        is AutoTripResult.Success -> null
        is AutoTripResult.Failure -> error.toUserMessage()
    }
}

/** 상세 화면이 수정 동작을 위로 넘기는 묶음 */
data class TripEditHandlers(
    val onEdit: (EditTarget) -> Unit = {},
    val onCancel: () -> Unit = {},
    val onPurpose: (TripPurpose) -> Unit = {},
    val onUser: (Long?) -> Unit = {},
    val onMemo: (String) -> Unit = {},
)

// ── 화면 ──────────────────────────────────────────────

@Composable
internal fun TripEditContent(target: EditTarget, info: TripEditInfo, handlers: TripEditHandlers, modifier: Modifier = Modifier) {
    when (target) {
        EditTarget.PURPOSE -> PurposeSelectScreen(
            reasonText = if (info.purposePending) "버튼으로 입력한 목적으로 기록돼요" else "바꾼 목적은 수정 이력에 남아요",
            candidates = setOfNotNull(info.purpose),
            onSelectPurpose = handlers.onPurpose,
            onDismiss = handlers.onCancel,
            modifier = modifier,
            trip = info.summary,
            dismissText = "취소",
        )
        EditTarget.USER -> UserSelectScreen(info, handlers, modifier)
        EditTarget.MEMO -> MemoEditScreen(info.memo, handlers, modifier)
    }
}

@Composable
private fun EditFrame(title: String, note: String, onCancel: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
            Spacer(Modifier.height(4.dp))
            Text(note, style = MaterialTheme.typography.bodyMedium, color = colors.outline)
            Spacer(Modifier.height(24.dp))
            Column(Modifier.weight(1f)) { content() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel, shape = ControlShape, modifier = Modifier.heightIn(min = 72.dp)) {
                    Text("취소", style = MaterialTheme.typography.labelLarge, color = colors.outline)
                }
            }
        }
    }
}

@Composable
private fun UserSelectScreen(info: TripEditInfo, handlers: TripEditHandlers, modifier: Modifier) {
    EditFrame("차량이용자", "바꾼 이용자는 수정 이력에 남아요", handlers.onCancel, modifier) {
        val colors = MaterialTheme.colorScheme
        if (info.users.isEmpty()) {
            Text("설정에서 차량이용자를 먼저 등록해 주세요", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            return@EditFrame
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            info.users.forEach { user ->
                ChoiceButton(user.label, selected = user.userId == info.userId) { handlers.onUser(user.userId) }
            }
            ChoiceButton("지정 안 함", selected = info.userId == null) { handlers.onUser(null) }
        }
    }
}

@Composable
private fun ChoiceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.width(480.dp).heightIn(min = 80.dp)
    val text = @Composable { Text(label, style = MaterialTheme.typography.titleLarge) }
    if (selected) PrimaryButton(onClick = onClick, shape = ControlShape, modifier = modifier) { text() }
    else OutlinedButton(onClick = onClick, shape = ControlShape, modifier = modifier) { text() }
}

@Composable
private fun MemoEditScreen(current: String?, handlers: TripEditHandlers, modifier: Modifier) {
    var text by remember { mutableStateOf(current.orEmpty()) }
    EditFrame("메모", "메모는 운행기록부 ⑩ 비고에 함께 적혀요 (${MEMO_MAX_LENGTH}자까지)", handlers.onCancel, modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= MEMO_MAX_LENGTH) text = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text("${text.length} / $MEMO_MAX_LENGTH", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(onClick = { handlers.onMemo(text) }, shape = ControlShape, modifier = Modifier.width(240.dp).heightIn(min = 80.dp)) {
                Text("저장", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
