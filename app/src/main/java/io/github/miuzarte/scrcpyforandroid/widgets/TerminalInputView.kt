package io.github.miuzarte.scrcpyforandroid.widgets

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class TerminalInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
): View(context, attrs, defStyleAttr) {
    interface InputCallbacks {
        fun handleKeyEvent(event: KeyEvent): Boolean
        fun handleCommitText(text: CharSequence): Boolean
        fun handleDeleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
    }

    var inputCallbacks: InputCallbacks? = null
    private var inputEnabled = true

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setInputEnabled(enabled: Boolean) {
        inputEnabled = enabled
        isFocusable = enabled
        isFocusableInTouchMode = enabled
        if (!enabled) {
            clearFocus()
        }
    }

    override fun onCheckIsTextEditor(): Boolean {
        return inputEnabled || super.onCheckIsTextEditor()
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (inputCallbacks?.handleKeyEvent(event) == true) return true
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!inputEnabled) return super.onCreateInputConnection(outAttrs)

        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        // 完整编辑器模式 + finishComposingText 转发 (同 ScrcpyInputSurfaceView 的 v9 已证修法):
        // 部分中文输入法提交候选只走 setComposingText+finishComposingText (不调 commitText),
        // 组合态被拒时其提交会静默丢失。
        return object: BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                if (inputCallbacks?.handleCommitText(text) == true) {
                    // 已转发: 清掉本地组合缓冲, 防止随后的 finishComposingText 重复提交
                    getEditable()?.clear()
                    return true
                }
                return super.commitText(text, newCursorPosition)
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                // 纯组合过程不转发, 等 finishComposingText (或 commitText) 落定
                return super.setComposingText(text, newCursorPosition)
            }

            override fun finishComposingText(): Boolean {
                val composed = getEditable()?.toString().orEmpty()
                val result = super.finishComposingText()
                if (composed.isNotBlank() && inputCallbacks?.handleCommitText(composed) == true) {
                    Log.i(TAG, "finishComposingText -> forwarded(len=${composed.length})")
                    getEditable()?.clear()
                }
                return result
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (inputCallbacks
                        ?.handleDeleteSurroundingText(beforeLength, afterLength) == true
                ) return true

                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (inputCallbacks?.handleKeyEvent(event) == true) return true
                return super.sendKeyEvent(event)
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                return when (actionCode) {
                    EditorInfo.IME_ACTION_DONE,
                    EditorInfo.IME_ACTION_GO,
                    EditorInfo.IME_ACTION_NEXT,
                    EditorInfo.IME_ACTION_SEARCH,
                    EditorInfo.IME_ACTION_SEND,
                        -> inputCallbacks?.handleCommitText("\n") == true

                    else -> super.performEditorAction(actionCode)
                }
            }
        }
    }

    private companion object {
        private const val TAG = "TerminalInputView"
    }
}
