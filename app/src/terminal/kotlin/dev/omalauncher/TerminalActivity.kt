package dev.omalauncher

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.omalauncher.linux.AGENTS
import dev.omalauncher.linux.Agent
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.shellQuote
import dev.omalauncher.ui.AgentPicker
import dev.omalauncher.ui.LinuxProgress
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.PrimaryButton
import dev.omalauncher.ui.TerminalActions
import dev.omalauncher.ui.TextSize
import dev.omalauncher.ui.ThemeStrip
import dev.omalauncher.ui.applySystemBars
import dev.omalauncher.ui.rememberLinuxInstall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The terminal app's icon: sets Linux up on first run, then brings back the
 * last terminal or opens one, and steps aside. The terminal itself is the
 * launcher's: foot in its own task (LinuxWindowActivity), with the extra keys.
 * It also opens for its shortcuts (a new terminal, a coding agent, settings)
 * and for what other apps share, which lands in ~/Downloads.
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
        val settings = intent.action == TerminalActions.SETTINGS
        setContent {
            LaunchedEffect(Palette.isLight) { applySystemBars(this@TerminalActivity, Palette.isLight) }
            Box(Modifier.fillMaxSize().background(Palette.background)) {
                if (settings) {
                    OmatermSettings(
                        runtime,
                        onDismiss = ::finish,
                        onOpenAgent = { runtime.terminal(it.bin, it.name, it) },
                        onEditKeys = { runtime.terminal("micro ~/.config/omalauncher/extra-keys", "Extra keys") },
                    )
                } else {
                    Setup(runtime, failed, onRetry = ::openTerminal)
                }
            }
        }
        // The long-press menu offers the installed agents, the default first.
        lifecycleScope.launch {
            snapshotFlow { listOfNotNull(runtime.agents.default) + runtime.agents.installed }.collect { agentShortcuts(it.distinct()) }
        }
        if (!settings) openTerminal()
    }

    /** Once Linux is up, what this was opened for; this screen closes once its window shows. */
    private fun openTerminal() {
        failed = false
        lifecycleScope.launch {
            snapshotFlow { runtime.linuxReady }.first { it }
            val opened =
                when (intent.action) {
                    TerminalActions.NEW -> open(null, "Terminal")
                    TerminalActions.AGENT -> {
                        val agent = AGENTS.firstOrNull { it.bin == intent.getStringExtra(TerminalActions.EXTRA_AGENT) }
                        // So the launcher can rank the agents' shortcuts by use.
                        agent?.let { runCatching { getSystemService(ShortcutManager::class.java).reportShortcutUsed("agent-${it.bin}") } }
                        if (agent != null) open(agent.bin, agent.name, agent) else open(null, "Terminal")
                    }
                    Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                        val names = withContext(Dispatchers.IO) { receive(intent) }
                        val list = if (names.isEmpty()) "" else " && ls -l -- " + names.joinToString(" ", transform = ::shellQuote)
                        open("cd ~/Downloads$list; exec bash", "Terminal")
                    }
                    else -> {
                        val last = runtime.lastTerminal()
                        if (last != null) runtime.show(last)
                        last != null || open(null, "Terminal")
                    }
                }
            if (opened) finish() else failed = true
        }
    }

    /** Opens [command] in a terminal, as [Runtime.terminal] does; false if foot exited before its window showed. */
    private suspend fun open(
        command: String?,
        name: String,
        agent: Agent? = null,
    ): Boolean {
        val before = runtime.wm.appWindows.toSet()
        val exited = mutableStateOf(false)
        runtime.terminal(command, name, agent, onExit = { runOnUiThread { exited.value = true } })
        // This screen stays up until the window opens: Android only lets a visible app start activities.
        return snapshotFlow {
            when {
                runtime.wm.appWindows.any { it !in before } -> true
                exited.value -> false
                else -> null
            }
        }.filterNotNull().first()
    }

    /** Copies what another app shared into ~/Downloads, text into a file of its own; returns the names it saved. */
    private fun receive(intent: Intent): List<String> {
        val dir = File(runtime.proot.home, "Downloads").apply { mkdirs() }

        @Suppress("DEPRECATION")
        val uris =
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            } else {
                listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            }
        val saved =
            uris.mapNotNull { uri ->
                runCatching {
                    val target = unique(dir, displayName(uri))
                    checkNotNull(contentResolver.openInputStream(uri)).use { input -> target.outputStream().use(input::copyTo) }
                    target.name
                }.onFailure { Log.w(TAG, "can't save $uri", it) }.getOrNull()
            }
        if (saved.isNotEmpty()) return saved
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return emptyList()
        val target = unique(dir, "shared-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.txt")
        target.writeText(text + "\n")
        return listOf(target.name)
    }

    /** The shared file's own name, as a plain file name: nothing in it can climb out of Downloads. */
    private fun displayName(uri: Uri): String {
        val name =
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment
        return name
            ?.substringAfterLast('/')
            ?.filterNot { it.isISOControl() }
            ?.trim()
            ?.takeUnless { it.isEmpty() || it == "." || it == ".." }
            ?: "shared"
    }

    /** [name] in [dir], or "name (2).ext" and on if it's taken. */
    private fun unique(
        dir: File,
        name: String,
    ): File {
        val base = name.substringBeforeLast('.')
        val ext = if (base.isEmpty() || base == name) "" else "." + name.substringAfterLast('.')
        var file = File(dir, name)
        var n = 2
        while (file.exists()) file = File(dir, if (ext.isEmpty()) "$name (${n++})" else "$base (${n++})$ext")
        return file
    }

    /** One long-press entry per installed agent, after the new terminal and settings; launchers show four or five. */
    private fun agentShortcuts(agents: List<Agent>) {
        val shortcuts =
            agents.take(MAX_AGENT_SHORTCUTS).mapIndexed { rank, agent ->
                ShortcutInfo
                    .Builder(this, "agent-${agent.bin}")
                    .setShortLabel(agent.name)
                    .setRank(rank)
                    .setIcon(agentIcon(agent))
                    .setIntent(TerminalActions.intent(this, TerminalActions.AGENT).putExtra(TerminalActions.EXTRA_AGENT, agent.bin))
                    .build()
            }
        runCatching { getSystemService(ShortcutManager::class.java).dynamicShortcuts = shortcuts }.onFailure { Log.w(TAG, "agent shortcuts", it) }
    }

    /** The agent's logo, as the guest's app icons have it. */
    private fun agentIcon(agent: Agent): Icon =
        runCatching { assets.open("icons/agent-${agent.bin}.png").use(BitmapFactory::decodeStream) }.getOrNull()?.let(Icon::createWithBitmap)
            ?: Icon.createWithResource(this, R.mipmap.ic_launcher)

    private companion object {
        const val TAG = "omaterm"
        const val MAX_AGENT_SHORTCUTS = 2
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
                is LinuxState.Installing, is LinuxState.Failed -> LinuxProgress(s, runtime.bootstrap.chosenAgents, runtime::retryLinux)
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
    // Claude Code first, like Omarchy's default agent; the first picked becomes the default.
    var picked by rememberSaveable { mutableStateOf(listOf(AGENTS.first().bin)) }
    // Each theme starts downloading as it's selected, the default one right away.
    LaunchedEffect(Unit) { themes.prefetch(themes.picked) }
    BasicText("omalauncher's terminal, on its own", style = Mono.copy(fontSize = TextSize.Large))
    Spacer(Modifier.height(10.dp))
    BasicText(
        "foot on Arch Linux ARM, in Omarchy's themes, with the keys a phone keyboard lacks. Linux runs under " +
            "proot, without root: pacman, the AUR, coding agents and Linux apps in their own windows.",
        style = Mono.copy(color = Subtle, lineHeight = 20.sp),
    )
    Spacer(Modifier.height(32.dp))
    ThemeStrip(themes, onPick = {
        themes.pick(it)
        // Once Omarchy's themes are here, a pick applies in full, Linux's configs included.
        if (themes.complete) scope.launch { themes.applyPicked() } else themes.prefetch(it.name)
    })
    Spacer(Modifier.height(32.dp))
    PrimaryButton(if (picked.isEmpty()) "install linux" else "install linux and ${picked.size} agent${if (picked.size == 1) "" else "s"}") {
        install(picked)
    }
    BasicText(
        "Linux downloads about 1 GB, so Wi-Fi is best. It installs in the background in 10 to 20 minutes; " +
            "the terminal opens when it's done. Long-press the icon for a new terminal, your agents or settings.",
        Modifier.padding(top = 10.dp).fillMaxWidth(),
        style = Mono.copy(color = Subtle, fontSize = TextSize.Small, lineHeight = 16.sp),
    )
    Spacer(Modifier.height(32.dp))
    AgentPicker(picked) { bin -> picked = if (bin in picked) picked - bin else picked + bin }
}

@Composable
private fun Status(
    text: String,
    color: Color,
) = BasicText(text, style = Mono.copy(fontSize = TextSize.Large, color = color))

/** Secondary text, as on the launcher's setup screens. */
private val Subtle get() = Palette.foreground.copy(alpha = 0.6f)
