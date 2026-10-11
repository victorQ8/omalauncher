package dev.omalauncher.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.wayland.XdgToplevel

/** What the terminal app's TerminalActivity (src/terminal) opens for; the shared screens ask it by these. */
object TerminalActions {
    /** A new terminal, rather than the last one back. */
    const val NEW = "dev.omalauncher.terminal.NEW"

    /** Omaterm's settings: style, agents, packages, linux. */
    const val SETTINGS = "dev.omalauncher.terminal.SETTINGS"

    /** A session of the coding agent [EXTRA_AGENT] names, by its bin. */
    const val AGENT = "dev.omalauncher.terminal.AGENT"
    const val EXTRA_AGENT = "agent"

    fun intent(
        context: Context,
        action: String,
    ): Intent = Intent(action).setClassName(context, "dev.omalauncher.TerminalActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * The terminal app's open windows, like Termux's session drawer: each is still its own
 * task, and picking one brings it to the front. [current] is the window this shows over.
 */
@Composable
fun SessionsPanel(
    runtime: Runtime,
    current: XdgToplevel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val windows = runtime.wm.appWindows
    OverlayPanel("sessions", onDismiss) {
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(windows.toList(), key = { System.identityHashCode(it) }) { window ->
                SessionRow(
                    name = runtime.displayName(window.appId, window.title),
                    title = window.title,
                    current = window === current,
                    onOpen = {
                        onDismiss()
                        if (window !== current) runtime.show(window)
                    },
                    onClose = { runtime.wm.close(window) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BracketAction("new terminal", Palette.accent) {
                onDismiss()
                runtime.terminal(null, "Terminal")
            }
            runtime.agents.default?.let { agent ->
                BracketAction(agent.name.lowercase(), Palette.accent) {
                    onDismiss()
                    runtime.terminal(agent.bin, agent.name, agent)
                }
            }
            BracketAction("settings", Palette.muted) {
                onDismiss()
                context.startActivity(TerminalActions.intent(context, TerminalActions.SETTINGS))
            }
            BracketAction("exit", Palette.red, runtime::exit)
        }
    }
}

@Composable
private fun SessionRow(
    name: String,
    title: String,
    current: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LauncherStyle.shape(12.dp))
            .background(if (current) Palette.accent.copy(alpha = 0.15f) else Palette.surface)
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(name, style = Mono.copy(fontSize = Mono.fontSize * 1.1f, color = if (current) Palette.accent else Palette.foreground))
            if (title.isNotEmpty() && title != name) {
                BasicText(title, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        BracketAction("close", Palette.red, onClose)
    }
}
