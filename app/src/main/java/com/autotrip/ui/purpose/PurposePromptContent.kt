package com.autotrip.ui.purpose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autotrip.domain.model.InputMethod
import com.autotrip.domain.model.TripPurpose
import com.autotrip.ui.theme.ControlShape
import com.autotrip.voice.ButtonReason
import com.autotrip.voice.PurposePromptState
import com.autotrip.voice.VoicePrompts
import com.autotrip.voice.spokenLabel

/** 차량 화면 버튼 최소 높이 (손가락으로 누르기 쉬운 크기) */
private val CarTouchTarget = 88.dp

/**
 * 화면에 쓰는 문구. 음성으로 읽는 문장([VoicePrompts])과 따로 둔다.
 * 화면의 제목·안내·버튼은 짧게 쓰고 끝에 마침표를 찍지 않는다.
 */
internal object PromptCopy {
    const val ASKING = "이번 운행 목적은 무엇인가요?"
    const val ASKING_AGAIN = "다시 한 번 말씀해 주세요"
    const val LISTENING = "듣고 있어요"
    const val PREPARING = "잠시만요"
    const val PREPARING_HINT = "마이크를 준비하고 있어요"
    const val LISTENING_HINT = "예: 거래처 방문, 회의, 출퇴근"
    const val SELECT_TITLE = "이번 운행 목적을 선택해 주세요"
    const val SECTION = "운행 목적"
    const val SELECT_WHILE_DRIVING = "정차한 뒤 화면에서 목적을 선택해 주세요"
    const val LATER = "나중에 입력"
    const val SAVE_FAILED = "저장하지 못했어요"
    const val SAVE_FAILED_WHILE_DRIVING = "정차한 뒤 다시 선택해 주세요"
    const val BY_VOICE = "음성으로 입력했어요"
    const val BY_BUTTON = "버튼으로 입력했어요"
    const val YES_NO_HINT = "네 또는 아니요로 답해 주세요"
    const val ARRIVED = "목적지에 도착했어요"

    fun suggestClient(clientName: String) = "$clientName 방문으로 기록할까요?"
    fun suggestPrevious(purpose: TripPurpose) = "지난번처럼 ${VoicePrompts.withEuro(purpose.spokenLabel)} 기록할까요?"
    fun confirming(purpose: TripPurpose) = "${VoicePrompts.withEuro(purpose.spokenLabel)} 기록할게요"
    fun completed(purpose: TripPurpose) = "${purpose.spokenLabel} 기록 완료"
    fun recorded(purpose: TripPurpose) = "${VoicePrompts.withEuro(purpose.spokenLabel)} 기록했어요"
    fun saveFailedReason(reason: String) = "$SAVE_FAILED · $reason"
}

/**
 * 목적 질문 흐름의 상태를 그린다. [PurposePromptState.Idle]이면 아무것도 그리지 않는다.
 *
 * @param buttonsAllowed 정차 중이면 true. 주행 중(CarUxRestrictions)에는 버튼 선택 화면 대신 안내만 보여준다.
 */
@Composable
fun PurposePromptContent(
    state: PurposePromptState,
    buttonsAllowed: Boolean,
    onSelectPurpose: (TripPurpose) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    trip: TripSummaryText? = null,
) {
    when (state) {
        PurposePromptState.Idle -> Unit
        is PurposePromptState.SuggestingClient -> VoiceStatus(
            title = PromptCopy.suggestClient(state.clientName),
            detail = PromptCopy.YES_NO_HINT,
            modifier = modifier,
        )
        is PurposePromptState.SuggestingPrevious -> VoiceStatus(
            title = PromptCopy.suggestPrevious(state.purpose),
            detail = PromptCopy.YES_NO_HINT,
            modifier = modifier,
        )
        is PurposePromptState.Asking -> VoiceStatus(
            title = if (state.attempt == 1) PromptCopy.ASKING else PromptCopy.ASKING_AGAIN,
            modifier = modifier,
        )
        // 엔진 준비 전에는 깜빡이는 점 없이 "잠시만요" → 준비되면 "● 듣고 있어요" (그때부터 말하면 된다)
        is PurposePromptState.Listening -> if (!state.ready) {
            VoiceStatus(title = PromptCopy.PREPARING, detail = PromptCopy.PREPARING_HINT, modifier = modifier)
        } else {
            VoiceStatus(
                title = PromptCopy.LISTENING,
                detail = state.partialText.ifBlank {
                    if (state.attempt == 0) PromptCopy.YES_NO_HINT else PromptCopy.LISTENING_HINT
                },
                live = true,
                modifier = modifier,
            )
        }
        is PurposePromptState.Confirming -> VoiceStatus(
            title = PromptCopy.confirming(state.purpose),
            modifier = modifier,
        )
        is PurposePromptState.ArrivalNotice -> VoiceStatus(
            title = PromptCopy.recorded(state.purpose),
            detail = PromptCopy.ARRIVED,
            modifier = modifier,
        )
        is PurposePromptState.Completed -> VoiceStatus(
            title = PromptCopy.completed(state.purpose),
            detail = if (state.method == InputMethod.VOICE) PromptCopy.BY_VOICE else PromptCopy.BY_BUTTON,
            modifier = modifier,
        )
        is PurposePromptState.AwaitingButton -> if (buttonsAllowed) {
            PurposeSelectScreen(
                reasonText = reasonText(state.reason),
                candidates = state.candidates,
                onSelectPurpose = onSelectPurpose,
                onDismiss = onDismiss,
                modifier = modifier,
                trip = trip,
            )
        } else {
            VoiceStatus(title = PromptCopy.SELECT_WHILE_DRIVING, modifier = modifier)
        }
        is PurposePromptState.SaveFailed -> if (buttonsAllowed) {
            PurposeSelectScreen(
                reasonText = PromptCopy.saveFailedReason(state.reason),
                candidates = setOf(state.purpose),
                onSelectPurpose = onSelectPurpose,
                onDismiss = onDismiss,
                modifier = modifier,
                trip = trip,
            )
        } else {
            VoiceStatus(title = PromptCopy.SAVE_FAILED, detail = PromptCopy.SAVE_FAILED_WHILE_DRIVING, modifier = modifier)
        }
    }
}

internal fun reasonText(reason: ButtonReason): String = when (reason) {
    ButtonReason.NOT_UNDERSTOOD -> "음성을 알아듣지 못했어요"
    ButtonReason.AMBIGUOUS -> "여러 목적이 함께 들렸어요"
    ButtonReason.VOICE_UNAVAILABLE -> "지금은 음성 입력을 사용할 수 없어요"
    // 화면에도 거래처 이름은 쓰지 않는다 (동승자가 볼 수 있음). 고르지 않고 출발하면 목적 미입력으로 남는다.
    ButtonReason.QUIET_MODE -> "조용한 모드예요 · 출발 전에 고르지 않으면 목적 미입력으로 남아요"
}

/** 후보를 앞에, 나머지는 enum 순서대로 */
internal fun orderedPurposes(candidates: Set<TripPurpose>): List<TripPurpose> =
    TripPurpose.values().sortedBy { if (it in candidates) 0 else 1 }

/**
 * 큰 글씨 상태 표시 (주행 중에도 보여줄 수 있는 화면: 입력 요소 없음).
 * @param live 마이크가 실제로 열려 있을 때만 true. 제목 앞에 강조색 점을 찍는다(깜빡이지 않음).
 */
@Composable
private fun VoiceStatus(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    live: Boolean = false,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    Box(
                        Modifier
                            .size(16.dp)
                            // 마이크가 열려 있는 "살아 있는 상태" → Active Blue
                            .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
            }
            if (detail != null) {
                Spacer(Modifier.height(20.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 정차 중 버튼 선택 화면 (절충안: 계기판처럼 절제된 분위기 + 분명한 버튼).
 *
 * - [trip]이 있으면 위쪽에 이번 운행을 큰 숫자로 보여주고, 아래쪽에 "운행 목적"과 버튼을 둔다.
 *   없으면(운행 데이터 연결 전 등) 제목과 버튼 묶음을 화면 세로 가운데에 둔다.
 * - 일반 목적: 흰 카드 모양 + 얇은 테두리. 후보 목적: 주간은 Hyundai Blue 채우기, 야간은 Sky Blue 테두리.
 * - "나중에 입력"은 오른쪽 아래, 흐린 글자.
 */
@Composable
fun PurposeSelectScreen(
    reasonText: String,
    candidates: Set<TripPurpose>,
    onSelectPurpose: (TripPurpose) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    trip: TripSummaryText? = null,
    dismissText: String = PromptCopy.LATER,
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier = modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp)) {
            if (trip != null) TripSummaryHeader(trip)
            Spacer(Modifier.weight(1f))

            if (trip != null) {
                Text(PromptCopy.SECTION, style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
            } else {
                Text(PromptCopy.SELECT_TITLE, style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
            }
            Spacer(Modifier.height(4.dp))
            Text(reasonText, style = MaterialTheme.typography.bodyMedium, color = colors.outline)
            Spacer(Modifier.height(20.dp))

            orderedPurposes(candidates).chunked(PURPOSES_PER_ROW).forEachIndexed { index, rowPurposes ->
                if (index > 0) Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    rowPurposes.forEach { purpose ->
                        PurposeButton(
                            purpose = purpose,
                            highlighted = purpose in candidates,
                            onClick = { onSelectPurpose(purpose) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(PURPOSES_PER_ROW - rowPurposes.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TextButton(onClick = onDismiss, shape = ControlShape, modifier = Modifier.heightIn(min = CarTouchTarget)) {
                    Text(dismissText, style = MaterialTheme.typography.labelLarge, color = colors.outline)
                }
            }
        }
    }
}

@Composable
private fun PurposeButton(purpose: TripPurpose, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.5f
    val buttonModifier = modifier.heightIn(min = PurposeButtonHeight)
    val label = @Composable { weight: FontWeight ->
        Text(purpose.spokenLabel, style = MaterialTheme.typography.titleLarge.copy(fontWeight = weight))
    }

    when {
        // 후보 · 주간: 남색 채우기
        highlighted && !isDark -> Button(onClick = onClick, modifier = buttonModifier, shape = ControlShape) {
            label(FontWeight.Medium)
        }
        // 후보 · 야간: 밝은 면 대신 남색 테두리 + 진한 글자 (눈부심 방지)
        highlighted -> OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            shape = ControlShape,
            border = BorderStroke(2.dp, colors.primary),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = colors.onBackground),
        ) { label(FontWeight.SemiBold) }
        // 일반: 바탕 위 흰 카드 모양 (주간 Light Sand 위 흰색, 야간 카드색) + 얇은 테두리
        else -> OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            shape = ControlShape,
            border = BorderStroke(1.dp, colors.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = colors.surface, contentColor = colors.onSurface),
        ) { label(FontWeight.Normal) }
    }
}

private val PurposeButtonHeight = 96.dp

private const val PURPOSES_PER_ROW = 4
