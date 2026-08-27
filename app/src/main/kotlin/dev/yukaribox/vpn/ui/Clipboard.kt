package dev.yukaribox.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * The app's two clipboard writes.
 *
 * Both go through the platform `ClipboardManager` rather than Compose's
 * `LocalClipboardManager`: that one is deprecated in favour of a suspend API, and — more
 * to the point — it cannot mark a clip sensitive, which the proxy-only password needs.
 * One helper per kind so the choice is made at the call site and is visible there.
 */

/** Put [text] on the clipboard. For things that are not secrets — a share link, a username. */
internal fun copyText(context: Context, text: String) {
    put(context, ClipData.newPlainText(null, text))
}

/**
 * Put [secret] on the clipboard, flagged so the system does not render it.
 *
 * Without the flag, Android 13+ shows the copied value in plaintext inside the clipboard
 * preview toast — the secret would appear on screen as a direct result of the action taken
 * to avoid revealing it. Written as a literal because `ClipDescription.EXTRA_IS_SENSITIVE`
 * only exists from API 33 while this app runs from 28; older platforms ignore an extra they
 * do not know, so there is nothing to branch on.
 */
internal fun copySecret(context: Context, secret: String) {
    put(
        context,
        ClipData.newPlainText(null, secret).apply {
            description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        },
    )
}

/** Clipboard access can be denied while the app is not focused; a failed copy is not fatal. */
private fun put(context: Context, clip: ClipData) {
    runCatching {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }
}

/**
 * Whatever is on the clipboard, as text, or blank.
 *
 * `coerceToText` rather than the plain-text item: a subscription URL copied out of a
 * browser arrives as a styled or URI item on some platforms, and reading only
 * `item.text` returned nothing for a clipboard the user could plainly see had a link in
 * it. Blank on denial too — from Android 10 a background read is refused outright, and
 * the caller already has a message for "nothing usable on the clipboard".
 */
internal fun pasteText(context: Context): String = runCatching {
    context.getSystemService(ClipboardManager::class.java)
        ?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        .orEmpty()
}.getOrDefault("")
