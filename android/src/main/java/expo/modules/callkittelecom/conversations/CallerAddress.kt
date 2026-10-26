package expo.modules.callkittelecom.conversations

import android.content.Context
import android.net.Uri

/** One identity for notification affinity and the platform caller filter/repeat history. */
internal fun callerAddress(context: Context, member: Map<*, *>?): Uri? {
    (member?.get("phoneNumber") as? String)?.takeIf { it.isNotBlank() }?.let { return Uri.fromParts("tel", it, null) }
    (member?.get("email") as? String)?.takeIf { it.isNotBlank() }?.let { return Uri.fromParts("mailto", it, null) }
    val id = (member?.get("id") as? String)?.takeIf { it.isNotBlank() } ?: return null
    // Android records non-tel caller URIs too. An opaque VoIP id has no contact affinity,
    // while an app-scoped stable URI lets repeat-caller policy recognize it honestly.
    return Uri.fromParts("voip", "${context.packageName}/$id", null)
}
