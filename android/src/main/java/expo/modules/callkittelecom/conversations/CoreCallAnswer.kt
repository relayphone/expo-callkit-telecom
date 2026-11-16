package expo.modules.callkittelecom.conversations

import android.os.Build
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope

/** Avoid the platform's call-type/video-state remap on transactional audio answers. */
internal suspend fun answerCoreCall(controls: CallControlScope, video: Boolean,
    sdkInt: Int = Build.VERSION.SDK_INT): CallControlResult =
    if (!video && sdkInt in 34..36) controls.setActive()
    else controls.answer(if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
