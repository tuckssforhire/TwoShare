package com.twoshare.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

enum class TwoShareTab { Feed, Messenger, Calendar }

@Composable
fun TwoShareBottomNavigation(selected: TwoShareTab, onSelect: (TwoShareTab) -> Unit) {
    NavigationBar {
        NavigationBarItem(selected == TwoShareTab.Feed, { onSelect(TwoShareTab.Feed) }, { Icon(Icons.Default.PhotoLibrary, "Feed") }, label = { Text("Feed") })
        NavigationBarItem(selected == TwoShareTab.Messenger, { onSelect(TwoShareTab.Messenger) }, { Icon(Icons.Default.ChatBubbleOutline, "Messenger") }, label = { Text("Messenger") })
        NavigationBarItem(selected == TwoShareTab.Calendar, { onSelect(TwoShareTab.Calendar) }, { Icon(Icons.Default.CalendarMonth, "Calendar") }, label = { Text("Calendar") })
    }
}
