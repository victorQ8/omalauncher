package dev.omalauncher.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.omalauncher.wayland.Keys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One row's height, keycap and the gap around it; the bar is two rows, like Termux's default. */
private val RowHeight = 40.dp
private val KeyGap = 3.dp
private val FrameWidth = 2.dp

/** The gap between the bar and the keyboard, and to the screen's sides: the tiles' gap. */
private val Gap = 6.dp

/** The bar's full height, gap included; the home screen lifts a typing tile by this much more. */
val ExtraKeysHeight = RowHeight * 2 + (FrameWidth + KeyGap) * 2 + Gap

internal sealed interface ExtraKey

/** A key that sends [code], with [held] down around it; drawn as [label], or as a chevron turned [arrow] degrees from up. */
internal data class Plain(
    val label: String,
    val code: Int,
    val repeats: Boolean = false,
    val arrow: Float? = null,
    val held: Int? = null,
) : ExtraKey

internal data class Sticky(
    val label: String,
    val modifier: (KeyboardBridge) -> StickyModifier,
) : ExtraKey

/** A key that types [text], like `|` or `ls`, with any latched modifiers. */
internal data class Typed(
    val text: String,
) : ExtraKey

private val Ctrl = Sticky("ctrl") { it.ctrl }
private val Alt = Sticky("alt") { it.alt }

/** Every key a row can name; see [EXTRA_KEYS_FILE]. */
private val Named: Map<String, ExtraKey> =
    buildMap {
        put("esc", Plain("esc", Keys.KEY_ESC))
        put("tab", Plain("tab", Keys.KEY_TAB))
        put("ctrl", Ctrl)
        put("alt", Alt)
        put("home", Plain("home", Keys.KEY_HOME))
        put("end", Plain("end", Keys.KEY_END))
        put("up", Plain("up", Keys.KEY_UP, repeats = true, arrow = 0f))
        put("down", Plain("down", Keys.KEY_DOWN, repeats = true, arrow = 180f))
        put("left", Plain("left", Keys.KEY_LEFT, repeats = true, arrow = -90f))
        put("right", Plain("right", Keys.KEY_RIGHT, repeats = true, arrow = 90f))
        put("pgup", Plain("pgup", Keys.KEY_PAGEUP, repeats = true))
        put("pgdn", Plain("pgdn", Keys.KEY_PAGEDOWN, repeats = true))
        put("ins", Plain("ins", Keys.KEY_INSERT))
        put("del", Plain("del", Keys.KEY_DELETE, repeats = true))
        put("bksp", Plain("bksp", Keys.KEY_BACKSPACE, repeats = true))
        put("enter", Plain("enter", Keys.KEY_ENTER))
        put("space", Plain("space", Keys.KEY_SPACE))
        // Ctrl+Insert and Shift+Insert: the clipboard keys foot, GTK and Qt all know.
        put("copy", Plain("copy", Keys.KEY_INSERT, held = Keys.KEY_LEFTCTRL))
        put("paste", Plain("paste", Keys.KEY_INSERT, held = Keys.KEY_LEFTSHIFT))
        for (f in 1..10) put("f$f", Plain("f$f", Keys.KEY_F1 + f - 1))
        put("f11", Plain("f11", Keys.KEY_F11))
        put("f12", Plain("f12", Keys.KEY_F12))
        // The default layout's keys that type a character send its key, as they always have.
        put("/", Plain("/", Keys.KEY_SLASH))
        put("-", Plain("-", Keys.KEY_MINUS))
    }

/** The rows of [text] in the format of [EXTRA_KEYS_FILE]; null if it names none. */
internal fun parseExtraKeys(text: String): List<List<ExtraKey>>? =
    text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line -> line.split(Regex("\\s+")).take(MAX_KEYS).map { Named[it.lowercase()] ?: Typed(it) } }
        .take(MAX_ROWS)
        .toList()
        .ifEmpty { null }

/** The terminal app's extra-keys file, in the guest's ~/.config/omalauncher. */
const val EXTRA_KEYS_NAME = "extra-keys"

/**
 * The extra-keys file, in the terminal app: one row per line, the keys separated by spaces;
 * [Named] keys by name, anything else typed as it is. Seeded with the default layout.
 */
internal val EXTRA_KEYS_FILE =
    """
    # The keys above the keyboard: one row per line, the keys separated by spaces.
    # Names: esc tab ctrl alt up down left right home end pgup pgdn ins del bksp
    # enter space copy paste f1 to f12. Anything else is typed as it is, like | ~ or ls.
    # Changes show the next time the keyboard opens.
    esc / - home up end pgup copy
    tab ctrl alt left down right pgdn paste
    """.trimIndent() + "\n"

/** The launcher's layout, and the terminal app's until its file says otherwise. */
internal val DefaultExtraKeys: List<List<ExtraKey>> = parseExtraKeys(EXTRA_KEYS_FILE)!!

private const val MAX_ROWS = 4
private const val MAX_KEYS = 12

/**
 * Whether the extra-keys row belongs on screen: only over a docked soft
 * keyboard. With a hardware keyboard, Gboard shows just a floating toolbar
 * that counts as visible but takes no height, and the hardware keyboard has
 * these keys already.
 */
@Composable
fun extraKeysWanted(): Boolean {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    // Derived, so the keyboard's slide-in animation doesn't recompose every frame.
    val docked by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    return docked
}

/**
 * The keys a phone keyboard lacks, docked above it for terminals: a framed
 * panel of keycaps, like the tiles above it. Keys never take focus, so the
 * soft keyboard stays up; ctrl and alt latch for the next key on a tap and
 * lock on a long press.
 */
@Composable
internal fun ExtraKeys(
    bridge: KeyboardBridge,
    modifier: Modifier = Modifier,
    rows: List<List<ExtraKey>> = DefaultExtraKeys,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = Gap, end = Gap, bottom = Gap)
            .frame(FrameWidth, Palette.border, Palette.background, radius = 12.dp)
            .padding(KeyGap),
    ) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth().height(RowHeight)) {
                for (key in row) {
                    val cap = Modifier.weight(1f).fillMaxHeight().padding(KeyGap)
                    when (key) {
                        is Plain -> PlainKey(key, bridge, cap)
                        is Sticky -> StickyKey(key, key.modifier(bridge), cap)
                        is Typed -> TypedKey(key, bridge, cap)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainKey(
    key: Plain,
    bridge: KeyboardBridge,
    modifier: Modifier,
) {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    KeyCap(
        modifier.pointerInput(key) {
            presses { scope ->
                pressed = true
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val press =
                    scope.launch {
                        bridge.tap(key.code, key.held)
                        if (!key.repeats) return@launch
                        delay(RepeatDelay)
                        while (true) {
                            bridge.tap(key.code, key.held)
                            delay(RepeatInterval)
                        }
                    }
                waitForUpOrCancellation()
                press.cancel()
                pressed = false
            }
        },
        fill = if (pressed) Palette.accent else Palette.surface,
    ) {
        val color = if (pressed) Palette.background else Palette.foreground
        if (key.arrow != null) {
            Image(Icons.Chevron, key.label, Modifier.size(20.dp).rotate(key.arrow), colorFilter = ColorFilter.tint(color))
        } else {
            BasicText(key.label, style = Mono.copy(color = color))
        }
    }
}

@Composable
private fun TypedKey(
    key: Typed,
    bridge: KeyboardBridge,
    modifier: Modifier,
) {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    KeyCap(
        modifier.pointerInput(key) {
            presses {
                pressed = true
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                bridge.type(key.text)
                waitForUpOrCancellation()
                pressed = false
            }
        },
        fill = if (pressed) Palette.accent else Palette.surface,
    ) {
        BasicText(key.text, style = Mono.copy(color = if (pressed) Palette.background else Palette.foreground), maxLines = 1)
    }
}

@Composable
private fun StickyKey(
    key: Sticky,
    sticky: StickyModifier,
    modifier: Modifier,
) {
    val view = LocalView.current
    val latch = sticky.latch
    KeyCap(
        modifier.pointerInput(sticky) {
            presses { scope ->
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                var locked = false
                val hold =
                    scope.launch {
                        delay(RepeatDelay)
                        sticky.latch = Latch.Locked
                        locked = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                val up = waitForUpOrCancellation() != null
                hold.cancel()
                if (up && !locked) sticky.latch = if (sticky.latch == Latch.Off) Latch.Once else Latch.Off
            }
        },
        // Once: outlined in the accent, for the next key. Locked: filled, until tapped off.
        fill = if (latch == Latch.Locked) Palette.accent else Palette.surface,
        outline = if (latch == Latch.Once) Palette.accent else null,
    ) {
        BasicText(
            key.label,
            style =
                Mono.copy(
                    color =
                        when (latch) {
                            Latch.Off -> Palette.foreground
                            Latch.Once -> Palette.accent
                            Latch.Locked -> Palette.background
                        },
                ),
        )
    }
}

@Composable
private fun KeyCap(
    modifier: Modifier,
    fill: Color,
    outline: Color? = null,
    content: @Composable () -> Unit,
) {
    val shape = LauncherStyle.shape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(fill, shape)
            .then(if (outline != null) Modifier.border(FrameWidth, outline, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Runs [press] for each touch on the key, with a scope to start key repeat or long-press timers in. */
private suspend fun PointerInputScope.presses(press: suspend AwaitPointerEventScope.(CoroutineScope) -> Unit) =
    coroutineScope {
        val scope = this
        awaitEachGesture {
            awaitFirstDown()
            press(scope)
        }
    }

private const val RepeatDelay = 400L
private const val RepeatInterval = 50L
