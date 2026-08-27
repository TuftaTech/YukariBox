package dev.yukaribox.vpn.ui

import android.content.Context
import dev.yukaribox.vpn.core.Logs

/**
 * The app's own version name, or `"?"` when the platform will not tell us.
 *
 * One home for a lookup the about screen and the drawer footer each carried verbatim,
 * fallback and all. `getPackageInfo` can throw `NameNotFoundException` for our own package
 * while an update is being applied, and `versionName` is nullable, so both halves of the
 * guard are load-bearing rather than defensive habit.
 */
internal fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.onFailure { Logs.w("About", "version name unavailable", it) }.getOrNull() ?: "?"
