package com.autotrip.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.autotrip.domain.model.BusinessType
import com.autotrip.domain.model.Client
import com.autotrip.ui.permission.AppPermission
import com.autotrip.ui.permission.PermissionRowUi
import com.autotrip.ui.theme.AppCard
import com.autotrip.ui.theme.ControlShape
import com.autotrip.ui.theme.PrimaryButton
import com.autotrip.ui.trip.DrivingRestrictedNotice

private val FieldHeight = 72.dp
private val ButtonHeight = 72.dp

/**
 * 설정 화면: 사업자 정보, 차량, 차량이용자.
 * 입력 요소가 있으므로 주행 중(browsingAllowed = false)에는 보여주지 않는다.
 */
@Composable
fun SettingsScreen(
    saved: SettingsSaved?,
    browsingAllowed: Boolean,
    lastOutcome: SettingsActions.Outcome?,
    onSaveBusiness: (BusinessType, String, String) -> Unit,
    onSaveVehicle: (String, String) -> Unit,
    onAddUser: (String, String) -> Unit,
    onSetDefaultUser: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onAddClient: (String) -> Unit = {},
    onRemoveClient: (Client) -> Unit = {},
    /** 이 실행 모드에서 쓰는 권한과 허용 상태 (비어 있으면 권한 카드를 그리지 않는다) */
    permissions: List<PermissionRowUi> = emptyList(),
    /** 허용 창이 뜨지 않을 때(다시 묻지 않음) 보여 줄 안내 */
    permissionHint: String? = null,
    onRequestPermission: (AppPermission) -> Unit = {},
) {
    if (!browsingAllowed) {
        DrivingRestrictedNotice(modifier)
        return
    }
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxSize(), color = colors.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp, vertical = 32.dp),
        ) {
            Text("설정", style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
            if (saved != null && !saved.readyForExport) {
                Spacer(Modifier.height(8.dp))
                Text("운행기록부를 출력하려면 사업자 정보와 차량 정보가 필요해요", style = MaterialTheme.typography.bodyLarge, color = colors.primary)
            }
            if (lastOutcome != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    lastOutcome.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (lastOutcome is SettingsActions.Outcome.Saved) colors.onSurfaceVariant else colors.error,
                )
            }
            Spacer(Modifier.height(24.dp))
            if (saved == null) return@Column

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    AppCard(Modifier.fillMaxWidth()) { BusinessSection(saved, onSaveBusiness) }
                    AppCard(Modifier.fillMaxWidth()) { VehicleSection(saved, onSaveVehicle) }
                    // 왼쪽 열 아래가 비어 있어서 스크롤 없이 보이도록 여기에 둔다
                    if (permissions.isNotEmpty()) {
                        AppCard(Modifier.fillMaxWidth()) { PermissionsSection(permissions, permissionHint, onRequestPermission) }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    AppCard(Modifier.fillMaxWidth()) { UsersSection(saved, onAddUser, onSetDefaultUser) }
                    AppCard(Modifier.fillMaxWidth()) { ClientsSection(saved, onAddClient, onRemoveClient) }
                }
            }
        }
    }
}

/** 권한: 무엇에 쓰는지 / 꺼져 있으면 어떻게 되는지와 [허용] 버튼. 허용됐으면 버튼 없이 "허용됨" */
@Composable
private fun PermissionsSection(rows: List<PermissionRowUi>, hint: String?, onRequest: (AppPermission) -> Unit) {
    val colors = MaterialTheme.colorScheme
    SectionTitle("권한")
    Text("꺼 두어도 앱은 쓸 수 있어요. 해당 기능만 다른 방법으로 기록해요", style = MaterialTheme.typography.bodyMedium, color = colors.outline)
    Spacer(Modifier.height(8.dp))
    rows.forEach { row ->
        Row(Modifier.fillMaxWidth().heightIn(min = ButtonHeight).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.permission.title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                Text(row.detailText, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            if (row.granted) {
                Text(row.stateText, style = MaterialTheme.typography.labelLarge, color = colors.tertiary)
            } else {
                Text(row.stateText, style = MaterialTheme.typography.labelLarge, color = colors.error)
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { onRequest(row.permission) }, shape = ControlShape, modifier = Modifier.heightIn(min = ButtonHeight)) {
                    Text("허용", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    if (hint != null) {
        Spacer(Modifier.height(8.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = colors.primary)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "") {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = ControlShape,
        modifier = Modifier.fillMaxWidth().heightIn(min = FieldHeight),
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SaveButton(text: String, onClick: () -> Unit) {
    PrimaryButton(onClick = onClick, shape = ControlShape, modifier = Modifier.heightIn(min = ButtonHeight)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

// ── 사업자 정보 ──────────────────────────────────────

@Composable
private fun BusinessSection(saved: SettingsSaved, onSave: (BusinessType, String, String) -> Unit) {
    var type by remember(saved.business) { mutableStateOf(saved.business?.type ?: BusinessType.CORPORATION) }
    var name by remember(saved.business) { mutableStateOf(saved.business?.name.orEmpty()) }
    var number by remember(saved.business) { mutableStateOf(saved.business?.registrationNumber.orEmpty()) }

    SectionTitle("사업자 정보")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TypeToggle("법인", type == BusinessType.CORPORATION, Modifier.weight(1f)) { type = BusinessType.CORPORATION }
        TypeToggle("개인사업자", type == BusinessType.SOLE_PROPRIETOR, Modifier.weight(1f)) { type = BusinessType.SOLE_PROPRIETOR }
    }
    Spacer(Modifier.height(12.dp))
    Field(SettingsRules.nameLabel(type), name, { name = it })
    Field("사업자등록번호", number, { number = it }, placeholder = "123-45-67890")
    SaveButton("사업자 정보 저장") { onSave(type, name, number) }
}

@Composable
private fun TypeToggle(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        shape = ControlShape,
        modifier = modifier.heightIn(min = ButtonHeight),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.onBackground.copy(alpha = 0.12f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = colors.onBackground),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

// ── 차량 ────────────────────────────────────────────

@Composable
private fun VehicleSection(saved: SettingsSaved, onSave: (String, String) -> Unit) {
    var model by remember(saved.vehicle) { mutableStateOf(saved.vehicle?.model.orEmpty()) }
    var plate by remember(saved.vehicle) { mutableStateOf(saved.vehicle?.plateNumber.orEmpty()) }

    SectionTitle("차량")
    Field("① 차종", model, { model = it }, placeholder = "비우면 차량 정보에서 읽어요")
    Field("② 자동차등록번호", plate, { plate = it }, placeholder = "12가 3456")
    SaveButton("차량 정보 저장") { onSave(model, plate) }
}

// ── 차량이용자 ──────────────────────────────────────

@Composable
private fun UsersSection(saved: SettingsSaved, onAdd: (String, String) -> Unit, onSetDefault: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var dept by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    SectionTitle("차량이용자")
    Text(
        "운행기록부 ④에는 운전자가 아니라 차량을 이용한 사람을 적어요. 목적을 입력하면 기본 이용자로 기록돼요",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    if (saved.users.isEmpty()) {
        Text("등록된 차량이용자가 없어요", style = MaterialTheme.typography.bodyLarge, color = colors.outline)
    }
    saved.users.forEach { user ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable { onSetDefault(user.id) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = user.id == saved.defaultUserId, onClick = { onSetDefault(user.id) })
            Spacer(Modifier.width(8.dp))
            Text("${user.department} ${user.name}", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground, modifier = Modifier.weight(1f))
            if (user.id == saved.defaultUserId) {
                Text("기본", style = MaterialTheme.typography.bodyMedium, color = colors.primary)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
    }
    Spacer(Modifier.height(24.dp))
    Text("새 차량이용자", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Field("부서", dept, { dept = it }) }
        Column(Modifier.weight(1f)) { Field("성명", name, { name = it }) }
    }
    SaveButton("추가") {
        onAdd(dept, name)
        // 검사를 통과한 입력만 비운다 (실패하면 고칠 수 있게 남겨 둔다)
        if (SettingsRules.user(dept, name) is Checked.Ok) {
            dept = ""
            name = ""
        }
    }
}

// ── 거래처 (v1.1) ───────────────────────────────────

@Composable
private fun ClientsSection(saved: SettingsSaved, onAdd: (String) -> Unit, onRemove: (Client) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("") }

    SectionTitle("자주 가는 거래처")
    Text(
        "내비 목적지에 거래처 이름이 들어 있으면 출발할 때 \"○○ 방문으로 기록할까요?\"라고 먼저 물어요",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    if (saved.clients.isEmpty()) {
        Text("등록된 거래처가 없어요", style = MaterialTheme.typography.bodyLarge, color = colors.outline)
    }
    saved.clients.forEach { client ->
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(client.name, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground, modifier = Modifier.weight(1f))
            TextButton(onClick = { onRemove(client) }, shape = ControlShape, modifier = Modifier.heightIn(min = 64.dp)) {
                Text("빼기", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
    }
    Spacer(Modifier.height(16.dp))
    Field("거래처 이름", name, { if (it.length <= SettingsRules.CLIENT_NAME_MAX) name = it }, placeholder = "예: 현대오토에버 판교")
    SaveButton("거래처 추가") {
        onAdd(name)
        if (SettingsRules.clientName(name, saved.clients.map { it.name }) is Checked.Ok) name = ""
    }
}
