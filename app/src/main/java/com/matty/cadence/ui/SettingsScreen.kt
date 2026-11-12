package com.matty.cadence.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matty.cadence.data.SyntheticTiming

@Composable
fun SettingsScreen() {
    val pal = LocalPalette.current
    val prefs = LocalPrefs.current
    val library = LocalLibrary.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        Text("Settings", color = pal.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Appearance")
            FlowRow2 {
                Pill("Dark", selected = prefs.themeMode == ThemeMode.DARK, icon = Icons.Outlined.DarkMode) { prefs.chooseTheme(ThemeMode.DARK) }
                Pill("Light", selected = prefs.themeMode == ThemeMode.LIGHT, icon = Icons.Outlined.LightMode) { prefs.chooseTheme(ThemeMode.LIGHT) }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Reading style")
            ReadingStyleControls(compact = false, amharic = false)
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Reading speed (books without audio) · words per minute")
            FlowRow2 {
                for (v in SyntheticTiming.speeds) {
                    Pill("${v.toInt()}", selected = prefs.wpm.toInt() == v.toInt()) { prefs.chooseWpm(v) }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Library")
            if (library.auth.signedIn) {
                Text("Reading from OneDrive › Apps › Cadence — the same library as your Apple TV.", color = pal.secondary, fontSize = 16.sp)
                Pill("Sign out / switch account", icon = Icons.Outlined.Logout) { library.signOut() }
            } else {
                SignInCard()
            }
            Text("Your place in each book is saved on this tablet.", color = pal.secondary, fontSize = 14.sp)
        }
    }
}

/** Font, size, page, margins and line spacing — shared by Settings and the reader's Aa panel. */
@Composable
fun ReadingStyleControls(compact: Boolean, amharic: Boolean) {
    val pal = LocalPalette.current
    val prefs = LocalPrefs.current
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 22.dp)) {
        if (amharic) {
            Text("Amharic books use Android's built-in Ethiopic font.", color = pal.secondary, fontSize = 14.sp)
        } else {
            Label("Font")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (f in readingFonts) {
                    val sel = prefs.fontId == f.id
                    Column(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (sel) pal.text else pal.glass)
                            .clickable { prefs.chooseFont(f.id) }
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    ) {
                        Text(f.name, color = if (sel) pal.onFocus else pal.text, fontFamily = f.family, fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        Text(f.note, color = (if (sel) pal.onFocus else pal.text).copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        FlowRow2 {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Size")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("A−") { prefs.chooseSize(prefs.sizeStep - 1) }
                    Text("${(prefs.scale * 100).toInt()}%", color = pal.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp))
                    Pill("A+") { prefs.chooseSize(prefs.sizeStep + 1) }
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Page")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (p in PageStyle.entries) Pill(p.label, selected = prefs.page == p) { prefs.choosePage(p) }
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Margins")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (m in Margins.entries) Pill(m.label, selected = prefs.margins == m) { prefs.chooseMargins(m) }
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Line spacing")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (s in LineSpacing.entries) Pill(s.label, selected = prefs.spacing == s) { prefs.chooseSpacing(s) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Label(text: String) {
    val pal = LocalPalette.current
    Text(text, color = pal.secondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}
