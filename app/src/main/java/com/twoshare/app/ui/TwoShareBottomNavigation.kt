package com.twoshare.app.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class TwoShareTab { Feed, Messenger, Lists, Calendar }

@Composable
fun TwoShareBottomNavigation(selected: TwoShareTab, onSelect: (TwoShareTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        TabItem(TwoShareTab.Feed, "Feed", Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary, selected, onSelect)
        TabItem(TwoShareTab.Messenger, "Messenger", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline, selected, onSelect)
        TabItem(TwoShareTab.Lists, "Lists", Icons.Filled.Checklist, Icons.Outlined.Checklist, selected, onSelect)
        TabItem(TwoShareTab.Calendar, "Calendar", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth, selected, onSelect)
    }
}

@Composable
private fun RowScope.TabItem(
    tab: TwoShareTab,
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    selected: TwoShareTab,
    onSelect: (TwoShareTab) -> Unit
) {
    val isSelected = selected == tab
    NavigationBarItem(
        selected = isSelected,
        onClick = { onSelect(tab) },
        icon = { Icon(if (isSelected) selectedIcon else unselectedIcon, contentDescription = label) },
        label = { Text(label, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}
