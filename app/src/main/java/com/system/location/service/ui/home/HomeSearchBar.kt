package com.system.location.service.ui.home

import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.system.location.service.R

/** The search field expands leftwards between two fixed map controls. */
@Composable
internal fun HomeSearchBar(expanded: Boolean, query: String, onQuery: (String) -> Unit,
    onExpand: () -> Unit, onCollapse: () -> Unit, onSubmit: () -> Unit, onMenu: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("home-search-controls"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeHeaderButton(R.drawable.ic_home_menu, "打开菜单", onClick = onMenu)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            androidx.compose.animation.AnimatedVisibility(expanded,
                enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut()) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(query, onQuery,
                    placeholder = { Text("搜索地点") }, singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    trailingIcon = {
                        IconButton(onClick = onCollapse) {
                            Icon(painterResource(R.drawable.baseline_close_24), "收起搜索")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("home-search-input"))
            }
        }
        HomeHeaderButton(R.drawable.ic_home_search, "搜索地点",
            modifier = Modifier.semantics { stateDescription = if (expanded) "已展开" else "已收起" },
            onClick = { if (expanded) onSubmit() else onExpand() })
    }
}

@Composable
private fun HomeHeaderButton(icon: Int, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp, shadowElevation = 2.dp) {
        IconButton(onClick, Modifier.size(48.dp)) {
            Icon(painterResource(icon), description, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}
