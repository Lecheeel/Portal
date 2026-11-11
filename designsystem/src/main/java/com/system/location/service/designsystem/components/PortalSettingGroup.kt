package com.system.location.service.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** Connected surfaces keep a group legible without card borders or divider lines. */
@Composable
fun PortalSettingGroup(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PortalSectionTitle(title, modifier = Modifier.padding(start = 8.dp, top = 12.dp))
        repeat(count) { index ->
            content(index, Modifier
                .clip(RoundedCornerShape(
                    topStart = if (index == 0) 24.dp else 4.dp,
                    topEnd = if (index == 0) 24.dp else 4.dp,
                    bottomStart = if (index == count - 1) 24.dp else 4.dp,
                    bottomEnd = if (index == count - 1) 24.dp else 4.dp,
                ))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(PaddingValues(horizontal = 16.dp, vertical = 4.dp)))
        }
    }
}
