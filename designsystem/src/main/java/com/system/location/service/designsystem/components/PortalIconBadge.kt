package com.system.location.service.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Decorative setting icon; the adjacent row supplies the accessible action label. */
@Composable
fun PortalIconBadge(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.size(40.dp), shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.primary) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
