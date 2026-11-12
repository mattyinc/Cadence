package com.matty.cadence.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.matty.cadence.data.Book
import com.matty.cadence.data.BookSummary
import com.matty.cadence.data.Library
import kotlinx.coroutines.delay

enum class Section { BOOKS, AUDIOBOOKS, SETTINGS }

private data class ReaderLaunch(val book: Book, val chapter: Int, val segment: Int, val time: Double)

@Composable
fun CadenceApp(library: Library, prefs: Prefs) {
    val pal = palette(prefs)
    val scheme = if (pal.dark)
        darkColorScheme(primary = pal.text, onPrimary = pal.onFocus, background = pal.bg, surface = pal.bg, onSurface = pal.text, onBackground = pal.text)
    else
        lightColorScheme(primary = pal.text, onPrimary = pal.onFocus, background = pal.bg, surface = pal.bg, onSurface = pal.text, onBackground = pal.text)

    CompositionLocalProvider(LocalPalette provides pal, LocalPrefs provides prefs, LocalLibrary provides library) {
        MaterialTheme(colorScheme = scheme) {
            var section by rememberSaveable { mutableStateOf(Section.BOOKS) }
            var open by remember { mutableStateOf<BookSummary?>(null) }
            var reader by remember { mutableStateOf<ReaderLaunch?>(null) }

            // keep the shelves current (new imports, transcription progress)
            LaunchedEffect(Unit) {
                library.refresh()
                while (true) {
                    delay(30_000)
                    library.refresh(quiet = true)
                }
            }

            BackHandler(enabled = open != null && reader == null) { open = null }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(pal.bg)
                    .background(Brush.radialGradient(listOf(pal.bgGlow, pal.bg), center = Offset(300f, 100f), radius = 1400f))
            ) {
                val r = reader
                val o = open
                when {
                    r != null -> ReaderScreen(r.book, r.chapter, r.segment, r.time, onClose = { reader = null })
                    o != null -> Box(Modifier.safeDrawingPadding()) {
                        BookScreen(o, onBack = { open = null }, onRead = { b, c, s, t -> reader = ReaderLaunch(b, c, s, t) })
                    }
                    else -> Row(Modifier.safeDrawingPadding()) {
                        NavigationRail(containerColor = pal.bg.copy(alpha = 0.6f)) {
                            Spacer(Modifier.height(16.dp))
                            RailItem(section == Section.BOOKS, "Books", Icons.AutoMirrored.Outlined.MenuBook) { section = Section.BOOKS }
                            RailItem(section == Section.AUDIOBOOKS, "Audiobooks", Icons.Outlined.Headphones) { section = Section.AUDIOBOOKS }
                            RailItem(section == Section.SETTINGS, "Settings", Icons.Outlined.Settings) { section = Section.SETTINGS }
                        }
                        when (section) {
                            Section.BOOKS -> ShelfScreen(Shelf.BOOKS) { open = it }
                            Section.AUDIOBOOKS -> ShelfScreen(Shelf.AUDIOBOOKS) { open = it }
                            Section.SETTINGS -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RailItem(selected: Boolean, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val pal = LocalPalette.current
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
        colors = NavigationRailItemDefaults.colors(
            selectedIconColor = pal.onFocus,
            selectedTextColor = pal.text,
            indicatorColor = pal.text,
            unselectedIconColor = pal.secondary,
            unselectedTextColor = pal.secondary,
        ),
    )
}
