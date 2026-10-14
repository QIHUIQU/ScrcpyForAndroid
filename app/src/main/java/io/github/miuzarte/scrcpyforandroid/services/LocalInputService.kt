package io.github.miuzarte.scrcpyforandroid.services

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager

object LocalInputService {
    private const val TAG = "ScrcpyIme"

    fun showSoftKeyboard(view: View) {
        Log.i(TAG, "showSoftKeyboard: ${view.javaClass.simpleName} isShown=${view.isShown} hasFocus=${view.hasFocus()}")
        view.requestFocus()
        Log.i(TAG, "showSoftKeyboard: after requestFocus hasFocus=${view.hasFocus()}")
        val inputManager =
            view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                ?: return
        // 强制重建输入会话: 会话重连/窗口重建后, 输入法可能仍持有旧视图的失效连接
        // (届时其打字不会进入注入路径 — D 回归根因之一); restartInput 让系统重新分配
        // InputConnection, 输入法必须重新取连接后再显示
        inputManager.restartInput(view)
        inputManager.showSoftInput(view, 0)
        Log.i(TAG, "showSoftKeyboard: restartInput+showSoftInput issued")
    }

    fun getClipboardText(context: Context): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        if (!clipboard.hasPrimaryClip()) {
            return null
        }
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount <= 0) {
            return null
        }
        val description = clipboard.primaryClipDescription
        if (description != null &&
            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) &&
            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)
        ) {
            return null
        }
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    fun setClipboardText(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("scrcpy", text))
    }
}
