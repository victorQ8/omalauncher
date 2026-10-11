package dev.omalauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.ui.LinuxProgress
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.PrimaryButton
import dev.omalauncher.ui.StyleScreen
import dev.omalauncher.ui.TextSize
import dev.omalauncher.ui.ThemeStrip
import dev.omalauncher.ui.applySystemBars
import dev.omalauncher.ui.rememberLinuxInstall
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The terminal app's icon: sets Linux up on first run, then brings back the
 * last terminal or opens one, and steps aside. The terminal itself is the
 * launcher's: foot in its own task (LinuxWindowActivity), with the extra keys.
 * Its shortcuts open a new terminal, or the style screen.
 */
class TerminalActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime

    /** foot exited before its window showed. */
    private var failed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(this, Palette.isLight)
        runtime.start()
        runtime.removeStaleWindowTasks()
        val style = intent.action == ACTION_STYLE
        setContent {
            LaunchedEffect(Palette.isLight) { applySystemBars(this@TerminalActivity, Palette.isLight) }
            Box(Modifier.fillMaxSize().background(Palette.background)) {
                if (style) StyleScreen(runtime, onDismiss = ::finish) else Setup(runtime, failed, onRetry = ::openTerminal)
            }
        }
        if (!style) openTerminal()
    }

    /** Once Linux is up: the last terminal, or a new one for [ACTION_NEW]; this screen closes once it shows. */
    private fun openTerminal() {
        failed = false
        lifecycleScope.launch {
            snapshotFlow { runtime.linuxReady }.first { it }
            val last = runtime.lastTerminal()
            if (intent.action != ACTION_NEW && last != null) {
                runtime.show(last)
                finish()
                return@launch
            }
            val before = runtime.wm.appWindows.toSet()
            val exited = mutableStateOf(false)
            runtime.terminal(null, "Terminal", onExit = { runOnUiThread { exited.value = true } })
            // This screen stays up until the window opens: Android only lets a visible app start activities.
            val opened =
                snapshotFlow {
                    when {
                        runtime.wm.appWindows.any { it !in before } -> true
                        exited.value -> false
                        else -> null
                    }
                }.filterNotNull().first()
            if (opened) finish() else failed = true
        }
    }

    companion object {
        /** The "new terminal" shortcut; see res/xml/shortcuts.xml. */
        const val ACTION_NEW = "dev.omalauncher.terminal.NEW"

        /** The "style" shortcut: themes and the terminal's font. */
        const val ACTION_STYLE = "dev.omalauncher.terminal.STYLE"
    }
}

/** What shows until the terminal opens: an install offer, the install's progress, or that Linux is starting. */
@Composable
private fun Setup(
    runtime: Runtime,
    failed: Boolean,
    onRetry: () -> Unit,
) {
    val state by runtime.bootstrap.state.collectAsState()
    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            Wordmark()
            Spacer(Modifier.height(28.dp))
            when (val s = state) {
                LinuxState.Checking -> {}
                LinuxState.NotInstalled -> InstallOffer(runtime)
                is LinuxState.Installing, is LinuxState.Failed -> LinuxProgress(s, emptyList(), runtime::retryLinux)
                LinuxState.Installed ->
                    if (failed) {
                        Status("the terminal didn't start", Palette.red)
                        Spacer(Modifier.height(20.dp))
                        PrimaryButton("try again", onRetry)
                    } else {
                        Status(if (runtime.linuxReady) "opening the terminal…" else "starting linux…", Palette.foreground)
                    }
                LinuxState.Removing -> Status("removing linux…", Palette.accent)
            }
        }
    }
}

/** The theme's colors as a strip of blocks, like fastfetch's palette, over the name; as the launcher's welcome screen has it. */
@Composable
private fun Wordmark() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (name in listOf("red", "yellow", "green", "cyan", "blue", "magenta")) {
            Box(Modifier.size(width = 22.dp, height = 8.dp).background(Palette.named(name) ?: Palette.accent))
        }
    }
    Spacer(Modifier.height(20.dp))
    BasicText("◆ omaterm", style = Mono.copy(color = Palette.accent, fontSize = 28.sp, fontWeight = FontWeight.Bold))
}

@Composable
private fun InstallOffer(runtime: Runtime) {
    val themes = runtime.themes
    val install = rememberLinuxInstall(runtime)
    val scope = rememberCoroutineScope()
    // Each theme starts downloading as it's selected, the default one right away.
    LaunchedEffect(Unit) { themes.prefetch(themes.picked) }
    BasicText("omalauncher's terminal, on its own", style = Mono.copy(fontSize = TextSize.Large))
    Spacer(Modifier.height(10.dp))
    BasicText(
        "foot on Arch Linux ARM, in Omarchy's themes, with the keys a phone keyboard lacks. Linux runs under " +
            "proot, without root: pacman, the AUR and Linux apps in their own windows.",
        style = Mono.copy(color = Subtle, lineHeight = 20.sp),
    )
    Spacer(Modifier.height(32.dp))
    ThemeStrip(themes, onPick = {
        themes.pick(it)
        // Once Omarchy's themes are here, a pick applies in full, Linux's configs included.
        if (themes.complete) scope.launch { themes.applyPicked() } else themes.prefetch(it.name)
    })
    Spacer(Modifier.height(32.dp))
    PrimaryButton("install linux") { install(emptyList()) }
    BasicText(
        "Linux downloads about 1 GB, so Wi-Fi is best. It installs in the background in 10 to 20 minutes; " +
            "the terminal opens when it's done.",
        Modifier.padding(top = 10.dp).fillMaxWidth(),
        style = Mono.copy(color = Subtle, fontSize = TextSize.Small, lineHeight = 16.sp),
    )
    BasicText(
        "Long-press the icon for a new terminal, or for themes and the terminal's font.",
        Modifier.padding(top = 10.dp).fillMaxWidth(),
        style = Mono.copy(color = Subtle, fontSize = TextSize.Small, lineHeight = 16.sp),
    )
}

@Composable
private fun Status(
    text: String,
    color: Color,
) = BasicText(text, style = Mono.copy(fontSize = TextSize.Large, color = color))

/** Secondary text, as on the launcher's setup screens. */
private val Subtle get() = Palette.foreground.copy(alpha = 0.6f)
