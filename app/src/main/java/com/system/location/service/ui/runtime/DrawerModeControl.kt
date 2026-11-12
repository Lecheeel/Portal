package com.system.location.service.ui.runtime

import android.animation.ValueAnimator
import android.content.Context
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.Lifecycle
import com.system.location.service.R
import com.system.location.service.core.backend.BackendType
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.displayLabel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.PI

private val DrawerModes = listOf(BackendType.MOCK_PROVIDER, BackendType.XPOSED, BackendType.NATIVE)
private data class DrawerModeState(val backend: BackendType, val active: Boolean)

/** A quick selection of the existing runtime backend; never prepares or starts a scenario. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrawerModeControl(drawerVisible: Boolean) {
    val context = LocalContext.current
    val stateFlow = remember {
        ScenarioRuntime.state.map { DrawerModeState(it.backend, it.isActive) }.distinctUntilChanged()
    }
    val state by stateFlow.collectAsStateWithLifecycle(initialValue = ScenarioRuntime.state.value.let {
        DrawerModeState(it.backend, it.isActive)
    })
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableFloatStateOf(DrawerModes.indexOf(state.backend).toFloat()) }
    var confirmingNative by rememberSaveable { mutableStateOf(false) }
    var observedBackend by rememberSaveable { mutableStateOf(state.backend) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.backend, state.active) {
        if (observedBackend != state.backend || state.active) {
            draft = DrawerModes.indexOf(state.backend).toFloat()
            confirmingNative = false
        }
        observedBackend = state.backend
    }

    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val highlightTrack = state.backend == BackendType.NATIVE && draft == 2f && !busy &&
        drawerVisible && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && ValueAnimator.areAnimatorsEnabled()
    // Compose's infinite transition is frame driven and stops when the drawer leaves view.
    val energyPhase = if (highlightTrack) {
        rememberInfiniteTransition(label = "native-track-energy").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(6_000, easing = LinearEasing)),
            label = "energy-phase")
    } else remember { mutableFloatStateOf(0f) }

    fun resetDraft() { draft = DrawerModes.indexOf(ScenarioRuntime.state.value.backend).toFloat() }
    fun applyMode(target: BackendType) {
        if (busy) return
        if (ScenarioRuntime.state.value.isActive) {
            resetDraft()
            error = "请先停止场景再切换模式"
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                if (ScenarioRuntime.selectBackend(target).await()) {
                    if (target == BackendType.NATIVE) {
                        context.getSharedPreferences("scenario_runtime", Context.MODE_PRIVATE).edit()
                            .putBoolean("native_opt_in", true).apply()
                    }
                } else error = "请先停止场景并完成资源清理"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "模式切换失败，请重试"
            } finally {
                busy = false
                resetDraft()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(state.backend.displayLabel(), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Slider(value = draft, onValueChange = { draft = it; error = null },
                valueRange = 0f..2f, steps = 1, enabled = !state.active && !busy,
                onValueChangeFinished = {
                    val target = DrawerModes[draft.roundToInt().coerceIn(0, 2)]
                    if (target == BackendType.NATIVE && state.backend != target) confirmingNative = true
                    else if (target != state.backend) applyMode(target)
                },
                track = { sliderState ->
                    SliderDefaults.Track(sliderState = sliderState, enabled = !state.active && !busy,
                        modifier = Modifier.drawWithCache {
                            val trackClip = Path().apply {
                                addRoundRect(RoundRect(0f, 0f, size.width, size.height,
                                    CornerRadius(size.height / 2)))
                            }
                            onDrawWithContent {
                                drawContent()
                                // Draw only over the track; no overlay View can intercept dragging.
                                if (highlightTrack) clipPath(trackClip) {
                                    drawNativeTrackEnergy(energyPhase.value)
                                }
                            }
                        })
                },
                modifier = Modifier.fillMaxWidth().testTag("drawer-mode-slider").semantics {
                    contentDescription = "快速切换模式"
                    stateDescription = state.backend.displayLabel()
                })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("无 Root", "Xposed", "Native").forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(when {
            busy -> "正在切换模式"
            state.active -> "运行中 · 停止场景后可切换"
            state.backend == BackendType.NATIVE -> "扩展档 · 需 Root + Xposed，实验性支持"
            state.backend == BackendType.XPOSED -> "需启用 Xposed 模块"
            else -> "使用系统模拟定位权限"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
    if (confirmingNative) AlertDialog(
        onDismissRequest = { confirmingNative = false; resetDraft() },
        title = { Text(stringResource(R.string.native_risk_title)) },
        text = { Text(stringResource(R.string.native_risk_message), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = {
            confirmingNative = false
            applyMode(BackendType.NATIVE)
        }) { Text(stringResource(R.string.native_risk_accept)) } },
        dismissButton = { TextButton(onClick = { confirmingNative = false; resetDraft() }) { Text("取消") } },
    )
}


/** A seeded particle field follows a shared flow, with no rows or connecting strokes. */
private fun DrawScope.drawNativeTrackEnergy(phase: Float) {
    val mint = Color(0xFF91FFD0)
    val cyan = Color(0xFF66EDE6)
    val lime = Color(0xFFD3FF86)
    val tau = 2f * PI.toFloat()
    val count = (size.width / 1.8f.dp.toPx()).toInt().coerceIn(48, 200)
    fun seed(index: Int, salt: Int): Float {
        var value = index * 0x45d9f3b + salt * 0x119de1f3
        value = (value xor (value ushr 16)) * 0x45d9f3b
        return (value xor (value ushr 16)).ushr(8) / 16777215f
    }
    for (index in 0 until count) {
        val origin = seed(index, 1)
        val depth = seed(index, 2)
        // Two circulation speeds and shared eddies produce coherent, uneven currents.
        val x = (origin + phase * (1 + index % 2)) % 1f
        val eddy = sin((x * 2f - phase + depth * .25f) * tau)
        val ripple = sin((x * 3f + phase * 2f + origin) * tau)
        val y = (.5f + (depth - .5f) * .62f + eddy * .19f + ripple * .07f)
            .coerceIn(.04f, .96f)
        val breathe = .5f + .5f * sin((phase * 2f + origin + depth) * tau)
        val edgeFade = (minOf(x, 1f - x) * 15f).coerceIn(0f, 1f)
        val alpha = (.22f + .50f * breathe) * edgeFade
        val pixel = (1f + seed(index, 3) * 1.25f).dp.toPx()
        val tint = if (x < .5f) lerp(lime, mint, x * 2f) else lerp(mint, cyan, (x - .5f) * 2f)
        val position = Offset(x * size.width - pixel / 2f, y * size.height - pixel / 2f)
        // A faint local halo softens the flow while retaining separate square pixels.
        if (index % 3 == 0) drawRect(tint.copy(alpha = alpha * .12f),
            topLeft = position - Offset(pixel * .4f, pixel * .4f), size = Size(pixel * 1.8f, pixel * 1.8f))
        drawRect(tint.copy(alpha = alpha), topLeft = position, size = Size(pixel, pixel))
        if (breathe > .75f) drawRect(Color.White.copy(alpha = alpha * .55f),
            topLeft = position, size = Size(pixel * .45f, pixel * .45f))
    }
}
