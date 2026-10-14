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
        inputManager.showSoftInput(view, 0)
        Log.i(TAG, "showSoftKeyboard: showSoftInput issued")
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
