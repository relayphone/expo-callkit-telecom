package expo.modules.callkittelecom.conversations

internal enum class NotificationPostRefusal { INVALID_NOTIFICATION, PERMISSION_DENIED }

/** Platform notification refusals must not escape into call control or a main-thread refresh. */
internal fun postNotification(post: () -> Unit, refused: (NotificationPostRefusal) -> Unit): Boolean {
    try { post(); return true }
    catch (_: IllegalArgumentException) { refused(NotificationPostRefusal.INVALID_NOTIFICATION) }
    catch (_: SecurityException) { refused(NotificationPostRefusal.PERMISSION_DENIED) }
    return false
}
