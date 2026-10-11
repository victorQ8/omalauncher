package dev.omalauncher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.omalauncher.linux.Agent
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.ui.AgentsScreen
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.LicensesScreen
import dev.omalauncher.ui.LinuxScreen
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.OverlayPanel
import dev.omalauncher.ui.PackagesScreen
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.SetupUpdates
import dev.omalauncher.ui.StyleScreen
import dev.omalauncher.ui.TextSize
import dev.omalauncher.ui.UpdatesRow

/** The screens settings opens; each returns to settings. */
private enum class Page { Style, Agents, Packages, Linux, Licenses }

/**
 * Omaterm's settings: the launcher's own screens for what the terminal app
 * has (style, agents, packages, linux, updates), and the terminal's keys.
 */
@Composable
internal fun OmatermSettings(
    runtime: Runtime,
    onDismiss: () -> Unit,
    onOpenAgent: (Agent) -> Unit,
    onEditKeys: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    BackHandler(page != null) { page = null }
    val back = { page = null }
    when (page) {
        Page.Style -> return StyleScreen(runtime, onDismiss = back)
        Page.Agents -> return AgentsScreen(runtime, onDismiss = back, onOpen = onOpenAgent)
        Page.Packages -> return PackagesScreen(runtime, onDismiss = back)
        Page.Linux -> return LinuxScreen(runtime, onDismiss = back)
        Page.Licenses -> return LicensesScreen(onDismiss = back)
        null -> {}
    }
    val linux by runtime.bootstrap.state.collectAsState()
    val installed = linux == LinuxState.Installed
    OverlayPanel("settings", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (installed) {
                LaunchedEffect(Unit) { runtime.updates.check() }
                UpdatesRow(runtime)
                SetupUpdates(runtime)
            }
            Entry("style", runtime.themes.current?.title?.lowercase() ?: "theme, the terminal's font and text size") { page = Page.Style }
            Entry(
                "coding agents",
                if (installed) runtime.agents.default?.let { "default: ${it.name}" } ?: "install Claude Code, OpenCode, …" else "needs linux",
            ) { page = if (installed) Page.Agents else Page.Linux }
            Entry("packages", if (installed) "install Linux apps and tools with pacman and the AUR" else "needs linux") {
                page = if (installed) Page.Packages else Page.Linux
            }
            Entry("linux", linuxStatus(linux)) { page = Page.Linux }
            Entry(
                "volume keys",
                if (runtime.volumeKeys) "on · volume down is ctrl; volume up + w a s d are the arrows, e esc, t tab, 1 to 0 f1 to f10" else "off · they change the volume",
                action = if (runtime.volumeKeys) "turn off" else "turn on",
            ) { runtime.volumeKeys = !runtime.volumeKeys }
            if (installed) Entry("extra keys", "edit the keys above the keyboard, in ~/.config/omalauncher/extra-keys", onClick = onEditKeys)
            Entry("licenses", "open source software in Omaterm") { page = Page.Licenses }
        }
    }
}

private fun linuxStatus(linux: LinuxState) =
    when (linux) {
        LinuxState.Installed -> "installed · size, gpu, remove"
        is LinuxState.Installing -> "installing" + (linux.progress?.let { " ${(it * 100).toInt()}%" } ?: "") + " · ${linux.stage.label}"
        is LinuxState.Failed -> "setup stopped · retry"
        LinuxState.Removing -> "removing…"
        else -> "not installed"
    }

/** A settings row as the launcher's has it: a title, a line under it and, by default, a chevron. */
@Composable
private fun Entry(
    title: String,
    subtitle: String,
    action: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LauncherStyle.shape(12.dp))
            .background(Palette.surface)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = Mono.copy(fontSize = Mono.fontSize * 1.1f))
            BasicText(subtitle, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
        BasicText(
            if (action != null) "[ $action ]" else "›",
            style = if (action != null) Mono.copy(color = Palette.accent) else Mono.copy(color = Palette.accent, fontSize = Mono.fontSize * 1.4f),
        )
    }
}
