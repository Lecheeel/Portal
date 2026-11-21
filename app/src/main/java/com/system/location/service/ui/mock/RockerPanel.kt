package com.system.location.service.ui.mock

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.system.location.service.R
import com.system.location.service.android.widget.RockerView
import kotlin.math.ceil

@Composable
internal fun RockerPanel(minimized: Boolean, expanded: Boolean, status: String, speed: Double,
    route: Boolean, canMove: Boolean, targetsEnabled: Boolean, rocker: RockerView,
    onMinimize: (Boolean) -> Unit, onExpand: () -> Unit, onTargets: () -> Unit,
    onSpeed: (Double) -> Unit, onDraggingSpeed: (Boolean) -> Unit,
    onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit) {
    val drag = Modifier.pointerInput(Unit) {
        detectDragGestures(onDragEnd = onDragEnd, onDragCancel = onDragEnd) { change, delta ->
            change.consume()
            onDrag(delta.x, delta.y)
        }
    }
    val title = stringResource(if (route) R.string.overlay_route_speed else R.string.rocker_speed)
    Surface(Modifier.padding(6.dp).testTag("rocker_overlay"), shape = MaterialTheme.shapes.large,
        shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface) {
        if (minimized) IconButton(onClick = { onMinimize(false) },
            modifier = drag.size(48.dp).semantics { stateDescription = status }.testTag("rocker_expand")) {
            Icon(painterResource(R.drawable.ic_home_rocker), stringResource(R.string.overlay_expand),
                tint = MaterialTheme.colorScheme.primary)
        } else Column(Modifier.width(184.dp).padding(8.dp)) {
            Row {
                Icon(painterResource(R.drawable.rocker_grip), stringResource(R.string.overlay_drag),
                    modifier = drag.weight(1f).height(48.dp).padding(12.dp).semantics { stateDescription = status })
                IconButton(onClick = onTargets, enabled = targetsEnabled, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.baseline_robot_24), stringResource(R.string.overlay_target_menu))
                }
                IconButton(onClick = { onMinimize(true) }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.baseline_close_24), stringResource(R.string.overlay_minimize))
                }
            }
            AndroidView(factory = {
                (rocker.parent as? android.view.ViewGroup)?.removeView(rocker)
                rocker
            }, update = { it.isEnabled = canMove; it.alpha = if (canMove) 1f else .55f; it.stateDescription = status },
                modifier = Modifier.fillMaxWidth().height(152.dp).testTag("rocker_direction"))
            TextButton(onClick = onExpand, modifier = Modifier.fillMaxWidth().testTag("rocker_speed_toggle")) {
                Text(stringResource(R.string.overlay_speed_value, speed))
            }
            if (expanded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { onSpeed(speed - .1) }, enabled = speed > 0) { Text("−") }
                    Text(stringResource(R.string.overlay_speed_number, speed), Modifier.padding(top = 12.dp))
                    IconButton(onClick = { onSpeed(speed + .1) }, enabled = speed < 1000) { Text("+") }
                }
                Text(stringResource(R.string.speed_kmh, speed * 3.6), style = MaterialTheme.typography.bodySmall)
                Slider(value = speed.toFloat(), onValueChange = { onDraggingSpeed(true); onSpeed(it.toDouble()) },
                    onValueChangeFinished = { onDraggingSpeed(false) },
                    valueRange = 0f..maxOf(50.0, ceil(speed / 10.0) * 10.0).toFloat(),
                    modifier = Modifier.semantics { contentDescription = title }.testTag("rocker_speed_slider"))
            }
        }
    }
}
