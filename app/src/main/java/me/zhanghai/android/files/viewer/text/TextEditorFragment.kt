/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SubMenu
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.TextEditorFragmentBinding
import me.zhanghai.android.files.ui.ThemedFastScroller
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.fadeInUnsafe
import me.zhanghai.android.files.util.fadeOutUnsafe
import me.zhanghai.android.files.util.isReady
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.viewModels

class TextEditorFragment : Fragment(), ConfirmReloadDialogFragment.Listener,
    ConfirmCloseDialogFragment.Listener {
    private val args by args<Args>()
    private lateinit var argsFile: Path

    private lateinit var binding: TextEditorFragmentBinding

    private lateinit var menuBinding: MenuBinding

    private val viewModel by viewModels { { TextEditorViewModel(argsFile) } }

    private lateinit var onBackPressedCallback: OnBackPressedCallback

    private var isSettingText = false
    private var lastFindText = ""
    private var isMonospace = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)

        lifecycleScope.launchWhenStarted {
            onBackPressedCallback = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    ConfirmCloseDialogFragment.show(this@TextEditorFragment)
                }
            }
            launch {
                viewModel.isTextChanged.collect {
                    onBackPressedCallback.isEnabled = viewModel.isTextChanged.value
                }
            }
            addOnBackPressedCallback(onBackPressedCallback)

            launch { viewModel.encoding.collect { onEncodingChanged(it) } }
            launch { viewModel.textState.collect { onTextStateChanged(it) } }
            launch { viewModel.isTextChanged.collect { onIsTextChangedChanged(it) } }
            launch { viewModel.writeFileState.collect { onWriteFileStateChanged(it) } }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        TextEditorFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val argsFile = args.intent.extraPath
        if (argsFile == null) {
            // TODO: Show a toast.
            finish()
            return
        }
        this.argsFile = argsFile

        val activity = requireActivity() as AppCompatActivity
        activity.lifecycleScope.launchWhenCreated {
            activity.setSupportActionBar(binding.toolbar)
            activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        }

        // TODO: Move reload-prevent here so that we can also handle save-as, etc. Or maybe just get
        //  rid of the mPathLiveData in TextEditorViewModel.
        ThemedFastScroller.create(binding.scrollView)
        setupEditorWindowInsets()
        // Manually save and restore state in view model to avoid TransactionTooLargeException.
        binding.textEdit.isSaveEnabled = false
        val textEditSavedState = viewModel.removeEditTextSavedState()
        if (textEditSavedState != null) {
            binding.textEdit.onRestoreInstanceState(textEditSavedState)
        }
        binding.textEdit.doAfterTextChanged {
            if (isSettingText) {
                return@doAfterTextChanged
            }
            // Might happen if the animation is running and user is quick enough.
            if (viewModel.textState.value !is DataState.Success) {
                return@doAfterTextChanged
            }
            viewModel.isTextChanged.value = true
            updateStatusText()
        }
        binding.textEdit.setOnClickListener { updateStatusText() }
        binding.textEdit.setOnFocusChangeListener { _, _ -> updateStatusText() }
        @Suppress("ClickableViewAccessibility")
        binding.textEdit.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                binding.textEdit.post { updateStatusText() }
            }
            false
        }
        updateStatusText()

        // TODO: Request storage permission if not granted.
    }


    private fun setupEditorWindowInsets() {
        val initialPaddingBottom = binding.editorContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.editorContainer) { view, insets ->
            val navigationBarsBottom = insets
                .getInsets(WindowInsetsCompat.Type.navigationBars())
                .bottom
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val imeExtraBottom = (imeBottom - navigationBarsBottom).coerceAtLeast(0)
            view.updatePadding(bottom = initialPaddingBottom + imeExtraBottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.editorContainer)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        viewModel.setEditTextSavedState(binding.textEdit.onSaveInstanceState())
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        menuBinding = MenuBinding.inflate(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        updateSaveMenuItem()
        updateEncodingMenuItems()
        updateMonospaceMenuItem()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_save -> {
                save()
                true
            }
            R.id.action_reload -> {
                onReload()
                true
            }
            R.id.action_find -> {
                showFindDialog()
                true
            }
            R.id.action_replace -> {
                showReplaceDialog()
                true
            }
            R.id.action_go_to_line -> {
                showGoToLineDialog()
                true
            }
            R.id.action_monospace -> {
                setMonospace(!isMonospace)
                true
            }
            Menu.FIRST -> {
                viewModel.encoding.value = TextEncoding.byId(item.titleCondensed!!.toString())
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    fun onSupportNavigateUp(): Boolean {
        if (onBackPressedCallback.isEnabled) {
            onBackPressedCallback.handleOnBackPressed()
            return true
        }
        return false
    }

    override fun finish() {
        requireActivity().finish()
    }

    private fun onEncodingChanged(encoding: TextEncoding) {
        updateEncodingMenuItems()
        updateStatusText()
    }

    private fun updateEncodingMenuItems() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val encodingId = viewModel.encoding.value.id
        val encodingItem = menuBinding.encodingSubMenu.children
            .find { it.titleCondensed == encodingId }!!
        encodingItem.isChecked = true
    }

    private fun onTextStateChanged(state: DataState<String>) {
        updateTitle()
        when (state) {
            is DataState.Loading -> {
                binding.progress.fadeInUnsafe()
                binding.errorText.fadeOutUnsafe()
                binding.textEdit.fadeOutUnsafe()
            }
            is DataState.Success -> {
                binding.progress.fadeOutUnsafe()
                binding.errorText.fadeOutUnsafe()
                binding.textEdit.fadeInUnsafe()
                if (!viewModel.isTextChanged.value) {
                    setText(state.data)
                }
                updateStatusText()
            }
            is DataState.Error -> {
                state.throwable.printStackTrace()
                binding.progress.fadeOutUnsafe()
                binding.errorText.fadeInUnsafe()
                binding.errorText.text = state.throwable.toString()
                binding.textEdit.fadeOutUnsafe()
            }
        }
    }

    private fun setText(text: String?) {
        isSettingText = true
        binding.textEdit.setText(text)
        isSettingText = false
        viewModel.isTextChanged.value = false
        binding.textEdit.post { updateStatusText() }
    }

    private fun onIsTextChangedChanged(changed: Boolean) {
        updateTitle()
    }

    private fun updateTitle() {
        val fileName = viewModel.file.value.fileName.toString()
        val changed = viewModel.isTextChanged.value
        requireActivity().title = getString(
            if (changed) {
                R.string.text_editor_title_changed_format
            } else {
                R.string.text_editor_title_format
            }, fileName
        )
    }

    private fun onReload() {
        if (viewModel.isTextChanged.value) {
            ConfirmReloadDialogFragment.show(this)
        } else {
            reload()
        }
    }

    override fun reload() {
        viewModel.isTextChanged.value = false
        viewModel.reload()
    }

    private fun save() {
        val text = binding.textEdit.text.toString()
        viewModel.writeFile(argsFile, text, requireContext())
    }

    private fun onWriteFileStateChanged(state: ActionState<Pair<Path, String>, Unit>) {
        when (state) {
            is ActionState.Ready, is ActionState.Running -> updateSaveMenuItem()
            is ActionState.Success -> {
                showToast(R.string.text_editor_save_success)
                viewModel.finishWritingFile()
                viewModel.isTextChanged.value = false
            }
            // The error will be toasted by service so we should never show it in UI.
            is ActionState.Error -> viewModel.finishWritingFile()
        }
    }

    private fun updateSaveMenuItem() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        menuBinding.saveItem.isEnabled = viewModel.writeFileState.value.isReady
    }

    private fun showFindDialog() {
        val edit = createDialogEditText(R.string.text_editor_find_text).apply {
            setText(lastFindText)
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editor_find)
            .setView(wrapDialogView(edit))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.text_editor_find) { _, _ ->
                val query = edit.text.toString()
                if (query.isNotEmpty()) {
                    lastFindText = query
                    findNext(query)
                }
            }
            .show()
    }

    private fun showReplaceDialog() {
        val findEdit = createDialogEditText(R.string.text_editor_find_text).apply {
            setText(lastFindText)
            setSelection(text.length)
        }
        val replaceEdit = createDialogEditText(R.string.text_editor_replace_with)
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(findEdit)
            addView(replaceEdit)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editor_replace)
            .setView(wrapDialogView(container))
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.text_editor_replace_all) { _, _ ->
                replaceAll(findEdit.text.toString(), replaceEdit.text.toString())
            }
            .setPositiveButton(R.string.text_editor_replace) { _, _ ->
                replaceNext(findEdit.text.toString(), replaceEdit.text.toString())
            }
            .show()
    }

    private fun showGoToLineDialog() {
        val edit = createDialogEditText(R.string.text_editor_line_number).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editor_go_to_line)
            .setView(wrapDialogView(edit))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val line = edit.text.toString().toIntOrNull()
                if (line != null) {
                    goToLine(line)
                }
            }
            .show()
    }

    private fun createDialogEditText(hintRes: Int): EditText = EditText(requireContext()).apply {
        hint = getString(hintRes)
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT
        setSelectAllOnFocus(false)
    }

    private fun wrapDialogView(view: View): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        val padding = resources.getDimensionPixelSize(R.dimen.screen_edge_margin)
        setPadding(padding, padding / 2, padding, 0)
        addView(view)
    }

    private fun findNext(query: String): Boolean {
        val text = binding.textEdit.text.toString()
        val start = binding.textEdit.selectionEnd.coerceAtLeast(0)
        var index = text.indexOf(query, start, ignoreCase = false)
        if (index < 0 && start > 0) {
            index = text.indexOf(query, 0, ignoreCase = false)
        }
        return if (index >= 0) {
            binding.textEdit.requestFocus()
            binding.textEdit.setSelection(index, index + query.length)
            binding.scrollView.post { binding.scrollView.smoothScrollTo(0, binding.textEdit.layout?.getLineTop(
                binding.textEdit.layout?.getLineForOffset(index) ?: 0
            ) ?: 0) }
            updateStatusText()
            true
        } else {
            showToast(R.string.text_editor_not_found)
            false
        }
    }

    private fun replaceNext(query: String, replacement: String) {
        if (query.isEmpty()) {
            return
        }
        val selectionStart = minOf(
            binding.textEdit.selectionStart.coerceAtLeast(0),
            binding.textEdit.selectionEnd.coerceAtLeast(0)
        )
        val selectionEnd = maxOf(
            binding.textEdit.selectionStart.coerceAtLeast(0),
            binding.textEdit.selectionEnd.coerceAtLeast(0)
        )
        val editable = binding.textEdit.text ?: return
        val selectedText = editable.substring(selectionStart, selectionEnd)
        if (selectedText != query && !findNext(query)) {
            return
        }
        val start = minOf(binding.textEdit.selectionStart, binding.textEdit.selectionEnd)
        val end = maxOf(binding.textEdit.selectionStart, binding.textEdit.selectionEnd)
        editable.replace(start, end, replacement)
        binding.textEdit.setSelection(start + replacement.length)
        viewModel.isTextChanged.value = true
        updateStatusText()
    }

    private fun replaceAll(query: String, replacement: String) {
        if (query.isEmpty()) {
            return
        }
        val text = binding.textEdit.text.toString()
        var count = 0
        var index = text.indexOf(query)
        while (index >= 0) {
            ++count
            index = text.indexOf(query, index + query.length)
        }
        if (count == 0) {
            showToast(R.string.text_editor_not_found)
            return
        }
        binding.textEdit.setText(text.replace(query, replacement))
        viewModel.isTextChanged.value = true
        showToast(getString(R.string.text_editor_replaced_count_format, count))
        updateStatusText()
    }

    private fun goToLine(line: Int) {
        val layout = binding.textEdit.layout ?: return
        val targetLine = line.coerceIn(1, layout.lineCount)
        val offset = layout.getLineStart(targetLine - 1)
        binding.textEdit.requestFocus()
        binding.textEdit.setSelection(offset)
        binding.scrollView.post { binding.scrollView.smoothScrollTo(0, layout.getLineTop(targetLine - 1)) }
        updateStatusText()
    }

    private fun setMonospace(enabled: Boolean) {
        isMonospace = enabled
        binding.textEdit.typeface = if (enabled) Typeface.MONOSPACE else Typeface.DEFAULT
        updateMonospaceMenuItem()
    }

    private fun updateMonospaceMenuItem() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        menuBinding.monospaceItem.isChecked = isMonospace
    }

    private fun updateStatusText() {
        if (!this::binding.isInitialized) {
            return
        }
        val selection = binding.textEdit.selectionStart.coerceAtLeast(0)
        val layout = binding.textEdit.layout
        val line: Int
        val column: Int
        val editable = binding.textEdit.text
        if (layout != null && !editable.isNullOrEmpty()) {
            line = layout.getLineForOffset(selection) + 1
            column = selection - layout.getLineStart(line - 1) + 1
        } else {
            line = 1
            column = selection + 1
        }
        binding.statusText.text = getString(
            R.string.text_editor_status_format,
            line,
            column,
            viewModel.encoding.value.displayName
        )
    }

    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs

    private class MenuBinding private constructor(
        val menu: Menu,
        val saveItem: MenuItem,
        val monospaceItem: MenuItem,
        val encodingSubMenu: SubMenu
    ) {
        companion object {
            fun inflate(menu: Menu, inflater: MenuInflater): MenuBinding {
                inflater.inflate(R.menu.text_editor, menu)
                val encodingSubMenu = menu.findItem(R.id.action_encoding).subMenu!!
                for (encoding in TextEncoding.VALUES) {
                    // HACK: Use titleCondensed to store encoding id.
                    encodingSubMenu.add(Menu.NONE, Menu.FIRST, Menu.NONE, encoding.displayName)
                        .titleCondensed = encoding.id
                }
                encodingSubMenu.setGroupCheckable(Menu.NONE, true, true)
                return MenuBinding(
                    menu,
                    menu.findItem(R.id.action_save),
                    menu.findItem(R.id.action_monospace),
                    encodingSubMenu
                )
            }
        }
    }
}
