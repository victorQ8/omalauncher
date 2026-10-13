package dev.omalauncher

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.ui.DefaultExtraKeys
import dev.omalauncher.ui.EXTRA_KEYS_NAME
import dev.omalauncher.ui.ExtraKey
import dev.omalauncher.ui.ExtraKeys
import dev.omalauncher.theme.GuestThemeConfig
import dev.omalauncher.ui.SelectionOverlay
import dev.omalauncher.ui.SessionsPanel
import dev.omalauncher.ui.TerminalSelection
import dev.omalauncher.ui.parseExtraKeys
import dev.omalauncher.ui.KeyboardSink
import dev.omalauncher.ui.extraKeysWanted
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.linuxWindowInput
import dev.omalauncher.ui.applySystemBars
import dev.omalauncher.wayland.WindowRenderer
import dev.omalauncher.wayland.XdgToplevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shows a single Linux window as its own Android task, so it gets its own
 * Recents card and behaves like a separate app.
 */
class LinuxWindowActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime
    private var window: XdgToplevel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val window = runtime.wm.windowByHandle(intent.data?.lastPathSegment)
        if (window == null) {
            // The window is gone, e.g. the process restarted since Recents was built.
            finishAndRemoveTask()
            return
        }
        this.window = window
        applySystemBars(this, Palette.isLight)
        setContent {
            LaunchedEffect(Palette.isLight) { applySystemBars(this@LinuxWindowActivity, Palette.isLight) }
            AppWindow(runtime, window)
            BackHandler { moveTaskToBack(true) }
            LaunchedEffect(window) {
                snapshotFlow { window in runtime.wm.appWindows }.first { !it }
                finishAndRemoveTask()
            }
            // Terminals retitle constantly; only a new label changes the Recents card.
            val label = runtime.displayName(window.appId, window.title)
            val appIcon = runtime.iconNamed(runtime.agentOf(window.appId, label)?.name ?: label)
            LaunchedEffect(label, appIcon, Palette.accent) {
                val icon = appIcon?.asAndroidBitmap() ?: taskIcon(label, Palette.background.toArgb(), Palette.accent.toArgb())
                val description =
                    if (Build.VERSION.SDK_INT >= 37) {
                        ActivityManager.TaskDescription
                            .Builder()
                            .setLabel(label)
                            .setIcon(Icon.createWithBitmap(icon))
                            .setPrimaryColor(Palette.background.toArgb())
                            .build()
                    } else {
                        // Builder.setIcon(Icon) is API 37+; the bitmap constructor still works before that.
                        @Suppress("DEPRECATION")
                        ActivityManager.TaskDescription(label, icon, Palette.background.toArgb())
                    }
                setTaskDescription(description)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runtime.screenStarted()
    }

    override fun onResume() {
        super.onResume()
        window?.let(runtime.wm::focus)
    }

    override fun onStop() {
        super.onStop()
        runtime.screenStopped()
    }

    /** Swiping the card away in Recents closes the Linux window, like closing an app. */
    override fun onDestroy() {
        super.onDestroy()
        val w = window ?: return
        if (isFinishing && w in runtime.wm.appWindows) runtime.wm.close(w)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (runtime.keyboard.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }
}

/**
 * Recents shows the manifest label ("Linux", or an alias's), but it does use the
 * task icon: the app's drawer icon, else its initials in the theme colors.
 */
private fun taskIcon(
    name: String,
    background: Int,
    accent: Int,
): Bitmap {
    val size = 192
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(background)
    val words = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    val initials =
        when {
            words.size >= 2 -> "${words[0][0]}${words[1][0]}".uppercase()
            words.isNotEmpty() -> words[0].take(2).replaceFirstChar(Char::uppercase)
            else -> "◆"
        }
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            textSize = size * 0.42f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    val y = size / 2f - (paint.descent() + paint.ascent()) / 2
    canvas.drawText(initials, size / 2f, y, paint)
    return bmp
}

@Composable
private fun AppWindow(
    runtime: Runtime,
    window: XdgToplevel,
) {
    var sink by remember { mutableStateOf<KeyboardSink?>(null) }
    var sessions by remember { mutableStateOf(false) }
    // Whether a docked keyboard is up; a hidden one may still count as wanted after Back put it away.
    val keyboardShown by rememberUpdatedState(extraKeysWanted())
    val background = Palette.surface.toArgb()
    // In the terminal app, a terminal's text is selected as in Termux: hold, then drag the handles.
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val selection =
        remember(window) {
            if (BuildConfig.TERMINAL_ONLY && runtime.isTerminal(window.appId)) {
                TerminalSelection(
                    runtime.wm,
                    window,
                    scope,
                    color = { runtime.themes.current?.colors?.let(GuestThemeConfig::selectionColor) },
                    haptic = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) },
                )
            } else {
                null
            }
        }
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .systemBarsPadding()
            .imePadding(),
    ) {
        AndroidView(
            factory = { KeyboardSink(it, runtime.keyboard).also { v -> sink = v } },
            modifier = Modifier.size(1.dp),
        )
        Column(Modifier.fillMaxSize()) {
            // A resize (the keyboard opening) moves the text: the selection's handles go after it.
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { selection?.scrolled() }) {
                AndroidView(
                    factory = { context -> WindowSurfaceView(context, runtime, window).also { selection?.view = it } },
                    update = { it.background = background },
                    modifier = Modifier.fillMaxSize(),
                )
                // Over the SurfaceView, which Compose's pointer input doesn't reach. In the terminal
                // app, tapping a terminal brings up the keyboard, as in Termux, or ends a selection.
                Box(
                    Modifier.fillMaxSize().linuxWindowInput(
                        runtime.wm,
                        window,
                        onTap =
                            selection?.let {
                                {
                                    when {
                                        it.active -> it.clear()
                                        !keyboardShown -> sink?.show()
                                    }
                                }
                            },
                        selection = selection,
                    ),
                )
                selection?.let { SelectionOverlay(it) }
                Row(Modifier.align(Alignment.BottomEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The terminal app's open windows, like Termux's drawer.
                    if (BuildConfig.TERMINAL_ONLY) RoundButton("≡") { sessions = true }
                    RoundButton("⌨") { sink?.toggle() }
                }
            }
            // Below the window rather than over it, so the window shrinks and nothing hides behind the keys.
            if (extraKeysWanted()) ExtraKeys(runtime.keyboard, rows = extraKeyRows(runtime))
        }
        if (sessions) SessionsPanel(runtime, window, onDismiss = { sessions = false })
    }
}

@Composable
private fun RoundButton(
    label: String,
    onClick: () -> Unit,
) = Box(
    Modifier
        .size(40.dp)
        .background(Palette.overlay, CircleShape)
        .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) {
    BasicText(label, style = Mono.copy(color = Palette.foreground))
}

/**
 * The terminal app's extra keys, from ~/.config/omalauncher/extra-keys; read as the keys show,
 * so an edit shows the next time the keyboard opens. The launcher keeps its own.
 */
@Composable
private fun extraKeyRows(runtime: Runtime): List<List<ExtraKey>> {
    if (!BuildConfig.TERMINAL_ONLY) return DefaultExtraKeys
    val rows by produceState(DefaultExtraKeys) {
        withContext(Dispatchers.IO) { runCatching { parseExtraKeys(File(runtime.proot.launcherConfig, EXTRA_KEYS_NAME).readText()) }.getOrNull() }?.let { value = it }
    }
    return rows
}

/**
 * The window's pixels: a [WindowRenderer] draws the Linux surfaces straight
 * into this view's surface, without Compose or bitmaps in between. Its size
 * is the window's size.
 */
private class WindowSurfaceView(
    context: Context,
    private val runtime: Runtime,
    private val window: XdgToplevel,
) : SurfaceView(context),
    SurfaceHolder.Callback {
    private val renderer = WindowRenderer(runtime.server, window)

    var background by renderer::background

    init {
        holder.addCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        window.renderer = renderer
    }

    override fun onDetachedFromWindow() {
        if (window.renderer === renderer) window.renderer = null
        renderer.release()
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        renderer.attach(holder.surface, width, height)
        runtime.wm.applyAppLayout(window, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = renderer.detach()
}
