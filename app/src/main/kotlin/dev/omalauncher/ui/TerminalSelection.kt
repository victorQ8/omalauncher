package dev.omalauncher.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.omalauncher.wayland.XdgToplevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** A selection handle: at the first selected cell's bottom left, or the last one's bottom right. */
enum class SelectionHandle { Start, End }

/**
 * A terminal's text selection in the terminal app, as Termux has it: a long press selects the
 * word under it; then a handle at each end drags that end, and Android's text menu copies or
 * pastes. foot
 * selects (Shift-held mouse input, its selection override, so apps that take the mouse don't
 * get it); where its selection is on screen is read from the window's pixels, in the
 * background color only a selection has (GuestThemeConfig.selectionColor). Main thread.
 */
class TerminalSelection(
    private val wm: WindowManager,
    private val window: XdgToplevel,
    private val scope: CoroutineScope,
    /** The color foot selects in, as #rrggbb; null if unknown, and then there are no handles. */
    private val color: () -> String?,
    private val haptic: () -> Unit,
) : SelectionGestures {
    /** A selection is up, with its bar. */
    override var active by mutableStateOf(false)
        private set

    /** Bottom left of the first selected cell, in the view's pixels; null while it isn't known. */
    var start by mutableStateOf<Offset?>(null)
        private set

    /** Bottom right of the last selected cell. */
    var end by mutableStateOf<Offset?>(null)
        private set

    /** The window's view, whose pixels show where the selection is. */
    var view: SurfaceView? = null

    /** A cell's height in pixels, from the word a long press selected; 0 until then. */
    private var cellHeight = 0

    private var pressedAt = Offset.Zero

    /** foot is being dragged through a selection (Shift and the button are down). */
    private var dragging = false
    private var scan: Job? = null

    override fun longPress(at: Offset) {
        haptic()
        pressedAt = at
        active = true
        start = null
        end = null
        // Measured again from this word: the text may have been zoomed since.
        cellHeight = 0
        hit(at)?.let(wm::selectWord)
        locate(measure = true)
    }

    override fun longPressDrag(at: Offset) {
        end = at
        drag(pressedAt, at)
    }

    override fun longPressEnd() = release()

    override fun scrolled() {
        if (active) locate()
    }

    /** Moves [handle] to [anchor]; the other end stays put, and foot selects what's between. */
    fun moveHandle(
        handle: SelectionHandle,
        anchor: Offset,
    ) {
        val other = (if (handle == SelectionHandle.Start) end else start) ?: return
        if (handle == SelectionHandle.Start) start = anchor else end = anchor
        drag(inside(other, if (handle == SelectionHandle.Start) SelectionHandle.End else SelectionHandle.Start), inside(anchor, handle))
    }

    fun handleReleased() = release()

    fun copy() {
        wm.copySelection()
        clear()
    }

    fun paste() {
        wm.pasteClipboard()
        clear()
    }

    /** Where the selection is, in the view's pixels, for Android's text menu to sit by: its cells, or the press. */
    fun bounds(): Rect {
        val cell = (if (cellHeight > 0) cellHeight else DEFAULT_CELL).toFloat()
        val a = start ?: end ?: return Rect(pressedAt.x, pressedAt.y - cell / 2, pressedAt.x + 1, pressedAt.y + cell / 2)
        val b = end ?: a
        return Rect(minOf(a.x, b.x), minOf(a.y, b.y) - cell, maxOf(a.x, b.x), maxOf(a.y, b.y))
    }

    /** Drops the selection and its menu. */
    fun clear() {
        if (!active) return
        active = false
        start = null
        end = null
        scan?.cancel()
        hit(pressedAt)?.let(wm::clearSelection)
    }

    /** A point within the cell whose [handle]'s corner is at [anchor]: the handles hang below their cells. */
    private fun inside(
        anchor: Offset,
        handle: SelectionHandle,
    ): Offset {
        val up = (if (cellHeight > 0) cellHeight else DEFAULT_CELL) / 2f
        return anchor + Offset(if (handle == SelectionHandle.Start) 1f else -1f, -up)
    }

    /** Drags foot's selection from [from], the end that stays, to [to]. */
    private fun drag(
        from: Offset,
        to: Offset,
    ) {
        val at = hit(to) ?: return
        if (dragging) return wm.selectionMove(at)
        wm.selectionStart(hit(from) ?: return, at)
        dragging = true
    }

    private fun release() {
        if (dragging) {
            wm.selectionEnd()
            dragging = false
        }
        locate()
    }

    private fun hit(at: Offset) = window.renderer?.hitTest(at.x, at.y)

    /** Finds the selection on screen once foot has drawn it, and puts the handles at its ends. */
    private fun locate(measure: Boolean = false) {
        val previous = scan
        scan =
            scope.launch {
                previous?.join()
                delay(RENDER_MS)
                val view = view ?: return@launch
                val target = hexColor(color())?.toArgb() ?: return@launch
                val bitmap = capture(view) ?: return@launch
                val found =
                    withContext(Dispatchers.Default) {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        bitmap.recycle()
                        findSelection(pixels, bitmap.width, target, cellHeight)
                    }
                if (!active || dragging) return@launch
                if (found == null) {
                    start = null
                    end = null
                    return@launch
                }
                if (measure) cellHeight = found.firstRowHeight
                start = Offset(found.startX.toFloat(), found.startBottom.toFloat())
                end = Offset(found.endX.toFloat(), found.endBottom.toFloat())
            }
    }

    /** The view's pixels, as the screen shows them; null if they can't be read. */
    private suspend fun capture(view: SurfaceView): Bitmap? {
        if (view.width == 0 || view.height == 0 || !view.holder.surface.isValid) return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { done ->
            runCatching {
                PixelCopy.request(view, bitmap, { result -> done.resume(bitmap.takeIf { result == PixelCopy.SUCCESS }) }, Handler(Looper.getMainLooper()))
            }.onFailure { done.resume(null) }
        }
    }

    private companion object {
        /** How long foot takes to draw a selection change: a frame or two. */
        const val RENDER_MS = 120L

        /** A cell's height before one is measured, in pixels: about a phone's at the default text size. */
        const val DEFAULT_CELL = 40
    }
}

/** The selection's handles over the window, and Android's text menu by it. */
@Composable
fun BoxScope.SelectionOverlay(selection: TerminalSelection) {
    // Where the window sits in the view that hosts the menu; it doesn't take touches.
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.matchParentSize().onGloballyPositioned { origin = it.positionInRoot() })
    if (!selection.active) return
    selection.start?.let { Handle(selection, SelectionHandle.Start, it) }
    selection.end?.let { Handle(selection, SelectionHandle.End, it) }
    SelectionMenu(selection) { origin }
}

/**
 * Android's floating text menu, copy and paste, as other apps' selections get it. It goes with
 * the selection, and dismissing it (Back) drops the selection.
 */
@Composable
private fun SelectionMenu(
    selection: TerminalSelection,
    origin: () -> Offset,
) {
    val view = LocalView.current
    val context = LocalContext.current
    val shown = remember { arrayOfNulls<ActionMode>(1) }
    DisposableEffect(selection) {
        val callback =
            object : ActionMode.Callback2() {
                /** Copy or paste was picked: the selection goes by itself. */
                var picked = false

                override fun onCreateActionMode(
                    mode: ActionMode,
                    menu: Menu,
                ): Boolean {
                    menu.add(Menu.NONE, android.R.id.copy, 0, android.R.string.copy)
                    menu.add(Menu.NONE, android.R.id.paste, 1, android.R.string.paste)
                    return true
                }

                override fun onPrepareActionMode(
                    mode: ActionMode,
                    menu: Menu,
                ) = false

                override fun onActionItemClicked(
                    mode: ActionMode,
                    item: MenuItem,
                ): Boolean {
                    picked = true
                    when (item.itemId) {
                        android.R.id.copy -> {
                            selection.copy()
                            // Android 13 and newer show what was copied themselves.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                        android.R.id.paste -> selection.paste()
                    }
                    mode.finish()
                    return true
                }

                override fun onDestroyActionMode(mode: ActionMode) {
                    shown[0] = null
                    if (!picked) selection.clear()
                }

                override fun onGetContentRect(
                    mode: ActionMode,
                    view: View,
                    outRect: android.graphics.Rect,
                ) {
                    val r = selection.bounds().translate(origin())
                    outRect.set(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
                }
            }
        shown[0] = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
        onDispose { shown[0]?.finish() }
    }
    // The menu follows the handles.
    LaunchedEffect(selection.start, selection.end) { shown[0]?.invalidateContentRect() }
}

/** A teardrop under one end of the selection, its point at [anchor], that drags that end. */
@Composable
private fun Handle(
    selection: TerminalSelection,
    handle: SelectionHandle,
    anchor: Offset,
) {
    val density = LocalDensity.current
    val box = with(density) { HandleBox.toPx() }
    val r = with(density) { HandleRadius.toPx() }
    // The bulb sits in the middle of the touch area, the point at its top inner corner.
    val corner = Offset(if (handle == SelectionHandle.Start) box / 2 + r else box / 2 - r, box / 2 - r)
    var dragged by remember { mutableStateOf(anchor) }
    Canvas(
        Modifier
            .offset { (anchor - corner).round() }
            .size(HandleBox)
            .pointerInput(selection, handle) {
                detectDragGestures(
                    onDragStart = { dragged = currentAnchor(selection, handle) ?: anchor },
                    onDragEnd = selection::handleReleased,
                    onDragCancel = selection::handleReleased,
                ) { change, delta ->
                    change.consume()
                    dragged += delta
                    selection.moveHandle(handle, dragged)
                }
            },
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(Palette.accent, r, center)
        val square = if (handle == SelectionHandle.Start) Offset(center.x, center.y - r) else Offset(center.x - r, center.y - r)
        drawRect(Palette.accent, square, Size(r, r))
    }
}

private fun currentAnchor(
    selection: TerminalSelection,
    handle: SelectionHandle,
) = if (handle == SelectionHandle.Start) selection.start else selection.end

private fun Offset.round() = IntOffset(x.roundToInt(), y.roundToInt())

private val HandleBox = 48.dp
private val HandleRadius = 11.dp
