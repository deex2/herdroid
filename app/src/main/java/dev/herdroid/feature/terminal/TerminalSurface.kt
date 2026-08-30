package dev.herdroid.feature.terminal

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.herdroid.core.model.TerminalAttachmentKey
import dev.herdroid.core.model.TerminalFrame
import dev.herdroid.core.model.TerminalScrollDirection
import dev.herdroid.core.model.TerminalScrollSource
import dev.herdroid.core.model.TerminalState
import kotlinx.coroutines.flow.Flow
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulatorFactory

@Composable
fun TerminalSurface(
    attachmentKey: TerminalAttachmentKey,
    state: TerminalState,
    frames: Flow<TerminalFrame>,
    onSendText: (String) -> Unit,
    onSendBytes: (ByteArray) -> Unit,
    onResize: (Int, Int) -> Unit,
    onScroll: (TerminalScrollDirection, Int, TerminalScrollSource) -> Unit,
    switcherOpen: Boolean = false,
    restoreKeyboardAfterSwitcher: MutableState<Boolean?>? = null,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onTakeOver: () -> Unit = {},
    topBar: @Composable (keyboardVisible: Boolean, onShowKeyboard: () -> Unit, onHideKeyboard: () -> Unit) -> Unit,
) {
    val modifiers = remember(attachmentKey) { TerminalModifiers() }
    val savedKeyboardAfterSwitcher = restoreKeyboardAfterSwitcher ?: remember { mutableStateOf<Boolean?>(null) }
    var keyboardRequested by remember(attachmentKey) {
        mutableStateOf(if (switcherOpen) false else savedKeyboardAfterSwitcher.value ?: true)
    }
    var terminalShowCompleted by remember(attachmentKey) { mutableStateOf(false) }
    var explicitHidePending by remember(attachmentKey) { mutableStateOf(false) }
    var showAfterExplicitHide by remember(attachmentKey) { mutableStateOf(false) }
    val keyboardVisible = viewImeVisible(attachmentKey)
    var selection by remember(attachmentKey) { mutableStateOf<SelectionController?>(null) }
    var selectionActive by remember(attachmentKey) { mutableStateOf(false) }
    val focusRequester = remember(attachmentKey) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    val emulator = remember(attachmentKey) {
        TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            defaultForeground = Color.White,
            defaultBackground = Color.Black,
            onKeyboardInput = onSendBytes,
            onResize = { dimensions -> onResize(dimensions.columns, dimensions.rows) },
        )
    }

    DisposableEffect(attachmentKey, emulator) {
        onDispose {
            (emulator as? AutoCloseable)?.close()
        }
    }
    LaunchedEffect(attachmentKey, emulator, frames) {
        frames.collect { frame ->
            emulator.writeInput(frame.bytes)
        }
    }
    LaunchedEffect(attachmentKey, keyboardVisible, terminalShowCompleted) {
        if (keyboardVisible) {
            if (terminalShowCompleted) keyboardRequested = false
        } else if (explicitHidePending) {
            explicitHidePending = false
            if (showAfterExplicitHide) {
                showAfterExplicitHide = false
                keyboardRequested = true
                runCatching { focusRequester.requestFocus() }
                keyboard?.show()
            }
        }
    }

    fun showKeyboard() {
        if (explicitHidePending) {
            showAfterExplicitHide = true
            return
        } else if (!keyboardVisible) {
            terminalShowCompleted = false
            keyboardRequested = true
        }
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    fun hideKeyboard() {
        explicitHidePending = keyboardVisible
        showAfterExplicitHide = false
        keyboardRequested = false
        keyboard?.hide()
        focusManager.clearFocus()
    }

    LaunchedEffect(attachmentKey, switcherOpen) {
        if (switcherOpen) {
            if (savedKeyboardAfterSwitcher.value == null) {
                savedKeyboardAfterSwitcher.value = keyboardVisible
                hideKeyboard()
            }
        } else {
            savedKeyboardAfterSwitcher.value?.let { restore ->
                savedKeyboardAfterSwitcher.value = null
                if (restore) showKeyboard() else hideKeyboard()
            }
        }
    }

    Column(modifier.fillMaxSize().background(Color.Black).imePadding()) {
        topBar(keyboardVisible, ::showKeyboard, ::hideKeyboard)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            key(attachmentKey) {
                Terminal(
                    terminalEmulator = emulator,
                    modifier = Modifier.fillMaxSize().passiveTerminalTap(::showKeyboard),
                    keyboardEnabled = true,
                    showSoftKeyboard = keyboardRequested || keyboardVisible,
                    focusRequester = focusRequester,
                    modifierManager = modifiers,
                    onTerminalTap = ::showKeyboard,
                    onImeVisibilityChanged = { terminalShowCompleted = it },
                    onSelectionControllerAvailable = {
                        selection = it
                        selectionActive = it.isSelectionActive
                    },
                    onComposeControllerAvailable = { it.startComposeMode() },
                    backgroundColor = Color.Black,
                )
            }
            when (val current = state) {
                TerminalState.Attaching -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is TerminalState.AttachFailed -> TerminalRecovery(
                    diagnostic = current.diagnostic,
                    onRetry = onRetry,
                    onTakeOver = onTakeOver,
                    modifier = Modifier.align(Alignment.Center),
                )
                is TerminalState.Closed -> Text(
                    current.diagnostic ?: "Terminal control was lost.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp),
                )
                else -> Unit
            }
        }
        if (keyboardVisible) {
            TerminalToolbar(
                emulator = emulator,
                modifiers = modifiers,
                selectionActive = selectionActive,
                enabled = state is TerminalState.Interactive,
                onToggleSelection = {
                    selection?.toggleSelection()
                    selectionActive = selection?.isSelectionActive == true
                },
                onCopy = {
                    selection?.copySelection()?.takeIf(String::isNotEmpty)?.let {
                        clipboard.setText(AnnotatedString(it))
                        selection?.clearSelection()
                        selectionActive = false
                    }
                },
                onPaste = {
                    clipboard.getText()?.text?.let(onSendText)
                },
                onPageUp = {
                    onScroll(
                        TerminalScrollDirection.UP,
                        (state as? TerminalState.Interactive)?.rows ?: 1,
                        TerminalScrollSource.PAGE_KEY,
                    )
                },
                onPageDown = {
                    onScroll(
                        TerminalScrollDirection.DOWN,
                        (state as? TerminalState.Interactive)?.rows ?: 1,
                        TerminalScrollSource.PAGE_KEY,
                    )
                },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            )
        }
    }
}

private fun Modifier.passiveTerminalTap(onTap: () -> Unit) = pointerInput(onTap) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        var change = down
        var isTap = !down.isConsumed
        do {
            val event = awaitPointerEvent(PointerEventPass.Final)
            if (event.changes.size != 1) isTap = false
            change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (
                change.isConsumed ||
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
            ) {
                isTap = false
            }
        } while (change.pressed)
        if (isTap && !change.pressed) onTap()
    }
}

@Composable
private fun viewImeVisible(attachmentKey: TerminalAttachmentKey): Boolean {
    val view = LocalView.current
    var visible by remember(view, attachmentKey) { mutableStateOf(view.isImeVisible()) }
    DisposableEffect(view, attachmentKey) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener { visible = view.isImeVisible() }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return visible
}

private fun View.isImeVisible() = ViewCompat.getRootWindowInsets(this)
    ?.isVisible(WindowInsetsCompat.Type.ime()) == true

@Composable
private fun TerminalRecovery(
    diagnostic: String?,
    onRetry: () -> Unit,
    onTakeOver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Could not attach terminal")
        diagnostic?.let { Text(it) }
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
        Button(onClick = onTakeOver, modifier = Modifier.fillMaxWidth()) { Text("Take over") }
    }
}
