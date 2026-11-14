package com.autotrip.ui.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.autotrip.voice.PermissionCheckedSpeechInput
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.autotrip.ui.permission.AppPermission
import com.autotrip.ui.permission.PermissionRules
import com.autotrip.ui.permission.RequestStartupPermissionsOnce
import com.autotrip.ui.permission.declaredPermissions
import com.autotrip.ui.permission.readGrantedPermissions
import com.autotrip.ui.permission.rememberGrantedPermissions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.autotrip.domain.repository.ProfileRepository
import com.autotrip.domain.repository.ReportRepository
import com.autotrip.domain.repository.TripRepository
import com.autotrip.domain.repository.TripTracker
import com.autotrip.ui.export.ExportScreen
import com.autotrip.ui.export.ExportViewModel
import com.autotrip.ui.home.HomeNavigation
import com.autotrip.ui.home.HomeScreen
import com.autotrip.ui.home.HomeViewModel
import com.autotrip.ui.purpose.PendingPurposeScreen
import com.autotrip.ui.purpose.PendingPurposeViewModel
import com.autotrip.ui.purpose.PurposePromptContent
import com.autotrip.ui.purpose.PurposePromptViewModel
import com.autotrip.ui.restriction.rememberBrowsingAllowed
import com.autotrip.ui.settings.SettingsScreen
import com.autotrip.ui.settings.SettingsViewModel
import com.autotrip.ui.theme.ControlShape
import com.autotrip.ui.trip.TripDetailScreen
import com.autotrip.ui.trip.TripDetailViewModel
import com.autotrip.ui.trip.TripListScreen
import com.autotrip.ui.trip.TripListViewModel
import com.autotrip.ui.home.QuietModeControl
import com.autotrip.voice.PurposePromptState
import com.autotrip.voice.QuietModeSetting
import com.autotrip.voice.SpeechInput
import com.autotrip.voice.SpeechOutput
import com.autotrip.ui.review.ReviewScreen
import com.autotrip.ui.review.ReviewViewModel
import kotlinx.coroutines.delay
import java.io.File

/** 화면 경로 */
object Routes {
    const val HOME = "home"
    const val TRIPS = "trips"
    const val TRIP_DETAIL = "trips/{tripId}"
    const val PENDING = "pending"
    const val EXPORT = "export"
    const val SETTINGS = "settings"
    const val REVIEW = "review"

    fun tripDetail(tripId: Long) = "trips/$tripId"
}

/** 완료·도착 안내를 화면에 남겨 두는 시간 */
private const val NOTICE_MILLIS = 4_000L

/**
 * 앱 전체 화면 연결.
 *
 * MainActivity에서 A의 AppContainer 값을 그대로 넘긴다 (AutoTripApplication이 main에 들어온 뒤):
 * ```
 * val c = (application as AutoTripApplication).container
 * AutoTripNavHost(c.tripTracker, c.tripRepository, c.profileRepository, c.reportRepository,
 *     isDemo = BuildConfig.USE_FAKE_DATA,
 *     speechOutput = { PleosSpeechOutput(applicationContext) }, speechInput = { PleosSpeechInput(applicationContext) },
 *     debugContent = (c.tripTracker as? FakeTripTracker)?.let { fake -> { DemoButtons(fake) } })
 * ```
 * - 주행 중(CarUxRestrictions)에는 각 화면이 스스로 입력 요소를 숨긴다. 이 함수는 그 값을 한 번 구해서 내려 준다.
 * - 목적 질문은 어느 화면에 있든 위에 덮어 그린다.
 */
@Composable
fun AutoTripNavHost(
    tripTracker: TripTracker,
    tripRepository: TripRepository,
    profileRepository: ProfileRepository,
    reportRepository: ReportRepository,
    isDemo: Boolean,
    speechOutput: () -> SpeechOutput,
    speechInput: () -> SpeechInput,
    modifier: Modifier = Modifier,
    debugContent: (@Composable () -> Unit)? = null,
    navController: NavHostController = rememberNavController(),
) {
    val browsingAllowed = rememberBrowsingAllowed()
    val context = LocalContext.current
    // 앱을 열 때 빠진 권한을 한 번에 요청 (마이크 + 실제 모드면 차량 속도·위치). 정차 중에만.
    val grantedPermissions = rememberGrantedPermissions()
    RequestStartupPermissionsOnce(isDemo, browsingAllowed, grantedPermissions)
    val prompt: PurposePromptViewModel = viewModel(
        factory = PurposePromptViewModel.factory(
            tripTracker, tripRepository, profileRepository, speechOutput,
            speechInput = { PermissionCheckedSpeechInput({ hasMicrophonePermission(context) }, speechInput()) },
            quietMode = { savedQuietMode(context) },
        ),
    )
    val promptState by prompt.state.collectAsState()
    val quietMode by prompt.quietModeEnabled.collectAsState()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    // 화면 바깥 영역(배너·"← 홈" 줄)도 테마 배경색을 쓰도록 바탕을 깐다 (야간에 흰 띠가 생기지 않게)
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (isDemo) DemoBanner()
        if (route != null && route != Routes.HOME && route != Routes.TRIP_DETAIL) {
            BackToHome { navController.popBackStack(Routes.HOME, inclusive = false) }
        }
        Box(Modifier.weight(1f)) {
            Screens(
                navController, tripTracker, tripRepository, profileRepository, reportRepository,
                isDemo, browsingAllowed, debugContent,
                QuietModeControl(quietMode, prompt::setQuietMode),
                grantedPermissions,
            )
            PurposePromptOverlay(promptState, browsingAllowed, prompt)
        }
    }
}

@Composable
private fun Screens(
    navController: NavHostController,
    tripTracker: TripTracker,
    tripRepository: TripRepository,
    profileRepository: ProfileRepository,
    reportRepository: ReportRepository,
    isDemo: Boolean,
    browsingAllowed: Boolean,
    debugContent: (@Composable () -> Unit)?,
    quietMode: QuietModeControl,
    grantedPermissions: MutableState<Set<AppPermission>>,
) {
    NavHost(navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(
                factory = HomeViewModel.factory(tripTracker, tripRepository, profileRepository, reportRepository),
            )
            val ui by vm.state.collectAsState()
            val message by vm.message.collectAsState()
            HomeScreen(
                ui, message, browsingAllowed, vm::runManual,
                HomeNavigation(
                    onOpenTrips = { navController.navigate(Routes.TRIPS) },
                    onOpenPending = { navController.navigate(Routes.PENDING) },
                    onOpenExport = { navController.navigate(Routes.EXPORT) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenReview = { navController.navigate(Routes.REVIEW) },
                ),
                debugContent = debugContent,
                quietMode = quietMode,
            )
        }
        composable(Routes.TRIPS) {
            val vm: TripListViewModel = viewModel(factory = TripListViewModel.factory(tripRepository, profileRepository))
            val state by vm.state.collectAsState()
            TripListScreen(state, browsingAllowed, onTripClick = { navController.navigate(Routes.tripDetail(it)) })
        }
        composable(Routes.TRIP_DETAIL, arguments = listOf(navArgument("tripId") { type = NavType.LongType })) { entry ->
            val tripId = entry.arguments?.getLong("tripId") ?: return@composable
            val vm: TripDetailViewModel = viewModel(factory = TripDetailViewModel.factory(tripId, tripRepository, profileRepository))
            val state by vm.state.collectAsState()
            val editing by vm.editing.collectAsState()
            val message by vm.message.collectAsState()
            TripDetailScreen(
                state, browsingAllowed, onBack = { navController.popBackStack() },
                editing = editing, message = message, handlers = vm.handlers,
            )
        }
        composable(Routes.REVIEW) {
            val vm: ReviewViewModel = viewModel(factory = ReviewViewModel.factory(tripRepository, profileRepository))
            val items by vm.items.collectAsState()
            ReviewScreen(items, browsingAllowed, onTripClick = { navController.navigate(Routes.tripDetail(it)) })
        }
        composable(Routes.PENDING) {
            val vm: PendingPurposeViewModel = viewModel(factory = PendingPurposeViewModel.factory(tripRepository))
            val items by vm.items.collectAsState()
            val selected by vm.selectedTripId.collectAsState()
            val message by vm.message.collectAsState()
            PendingPurposeScreen(items, selected, message, browsingAllowed, vm::select, vm::confirm)
        }
        composable(Routes.EXPORT) {
            val exportDir = File(LocalContext.current.filesDir, "exports")
            val vm: ExportViewModel = viewModel(
                factory = ExportViewModel.factory(reportRepository, profileRepository, exportDir, isDemo),
            )
            val year by vm.year.collectAsState()
            val summary by vm.summary.collectAsState()
            val busy by vm.busy.collectAsState()
            val outcome by vm.outcome.collectAsState()
            ExportScreen(
                year, summary, busy, outcome, browsingAllowed,
                onChangeYear = vm::changeYear, onExport = vm::export,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(profileRepository))
            val saved by vm.saved.collectAsState()
            val outcome by vm.lastOutcome.collectAsState()
            // 권한 카드: 꺼진 권한은 [허용]으로 다시 요청. 창이 안 뜨면(다시 묻지 않음) 차량 설정 안내
            val context = LocalContext.current
            val declared = remember { declaredPermissions(context) }
            var blockedHint by remember { mutableStateOf(false) }
            val requestOne = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                grantedPermissions.value = readGrantedPermissions(context)
                blockedHint = it.values.none { ok -> ok }
            }
            SettingsScreen(
                saved, browsingAllowed, outcome,
                onSaveBusiness = vm::saveBusiness, onSaveVehicle = vm::saveVehicle,
                onAddUser = vm::addUser, onSetDefaultUser = vm::setDefaultUser,
                onAddClient = vm::addClient, onRemoveClient = vm::removeClient,
                permissions = PermissionRules.rows(isDemo, grantedPermissions.value, declared),
                permissionHint = if (blockedHint) PermissionRules.BLOCKED_HINT else null,
                onRequestPermission = { p -> requestOne.launch(p.androidNames.filter { it in declared }.toTypedArray()) },
            )
        }
    }
}

/** 목적 질문을 현재 화면 위에 덮는다. 완료·도착 안내는 잠시 보여 주고 치운다. */
@Composable
private fun PurposePromptOverlay(state: PurposePromptState, browsingAllowed: Boolean, prompt: PurposePromptViewModel) {
    if (state == PurposePromptState.Idle) return
    if (state is PurposePromptState.Completed || state is PurposePromptState.ArrivalNotice) {
        LaunchedEffect(state) {
            delay(NOTICE_MILLIS)
            prompt.dismiss()
        }
    }
    PurposePromptContent(
        state = state,
        buttonsAllowed = browsingAllowed,
        onSelectPurpose = prompt::selectPurpose,
        onDismiss = prompt::dismiss,
    )
}

private const val UI_PREFS = "autotrip_ui"
private const val KEY_QUIET_MODE = "quiet_mode"

/** 조용한 모드는 앱을 다시 켜도 유지한다 (앱 전용 SharedPreferences, Manifest 변경 없음) */
private fun savedQuietMode(context: Context): QuietModeSetting {
    val prefs = context.applicationContext.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
    return QuietModeSetting(prefs.getBoolean(KEY_QUIET_MODE, false)) {
        prefs.edit().putBoolean(KEY_QUIET_MODE, it).apply()
    }
}

private fun hasMicrophonePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED


/** Fake 모드 표시 (CLAUDE.md 4장): 앱 상단 "시뮬레이션 데이터" 배너 */
@Composable
fun DemoBanner(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "시뮬레이션 데이터 · 실제 차량에서 수집한 기록이 아니에요",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackToHome(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(start = 24.dp, top = 8.dp)) {
        TextButton(onClick = onClick, shape = ControlShape, modifier = Modifier.heightIn(min = 72.dp)) {
            Text("← 홈", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
