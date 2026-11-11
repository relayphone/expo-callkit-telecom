package expo.modules.callkittelecom

import android.content.Context
import com.facebook.react.ReactApplication

internal object CallPushJavaScript {
    fun warm(context: Context) {
        (context.applicationContext as? ReactApplication)?.reactHost?.start()
    }
}
