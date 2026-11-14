package com.autotrip.ui.permission

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** 지금 허용된 권한 */
fun readGrantedPermissions(context: Context): Set<AppPermission> =
    AppPermission.values().filter {
        ContextCompat.checkSelfPermission(context, it.requiredName) == PackageManager.PERMISSION_GRANTED
    }.toSet()

/** 이 앱(빌드)이 Manifest에 선언한 권한 이름 */
fun declaredPermissions(context: Context): Set<String> = runCatching {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= 33) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    }
    info.requestedPermissions?.toSet().orEmpty()
}.getOrDefault(emptySet())

/** 허용 상태를 기억하고, 앱으로 돌아올 때(설정에서 바꾸고 온 경우) 다시 읽는다 */
@Composable
fun rememberGrantedPermissions(): MutableState<Set<AppPermission>> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(readGrantedPermissions(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = readGrantedPermissions(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}

/**
 * 앱을 열 때 빠진 권한을 한 번에 요청한다 (마이크, 실제 모드면 차량 속도·위치).
 * - 정차 중에만 창을 띄운다(주행 중 입력 제한). 주행 중에 열었으면 정차할 때까지 기다린다.
 * - 한 번 실행에 한 번만. 거절해도 앱은 그대로 쓰고, 설정 화면에서 다시 켤 수 있다.
 */
@Composable
fun RequestStartupPermissionsOnce(isDemo: Boolean, browsingAllowed: Boolean, granted: MutableState<Set<AppPermission>>) {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted.value = readGrantedPermissions(context)
    }
    LaunchedEffect(browsingAllowed) {
        if (asked || !browsingAllowed) return@LaunchedEffect
        asked = true
        val names = PermissionRules.startupRequest(isDemo, readGrantedPermissions(context), declaredPermissions(context))
        if (names.isNotEmpty()) launcher.launch(names.toTypedArray())
    }
}
