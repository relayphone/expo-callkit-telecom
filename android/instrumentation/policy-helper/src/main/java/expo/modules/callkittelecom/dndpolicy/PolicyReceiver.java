package expo.modules.callkittelecom.dndpolicy;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** CI-only global policy writer invoked through adb; never packaged in the library/example. */
public final class PolicyReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context context, Intent intent) {
    int categories = intent.getBooleanExtra("calls", true) ? NotificationManager.Policy.PRIORITY_CATEGORY_CALLS : 0;
    if (intent.getBooleanExtra("repeat", false)) categories |= NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS;
    String requested = intent.getStringExtra("senders");
    int senders = "starred".equals(requested) ? NotificationManager.Policy.PRIORITY_SENDERS_STARRED
        : "contacts".equals(requested) ? NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS
        : NotificationManager.Policy.PRIORITY_SENDERS_ANY;
    context.getSystemService(NotificationManager.class).setNotificationPolicy(
        new NotificationManager.Policy(categories, senders, NotificationManager.Policy.PRIORITY_SENDERS_ANY));
    setResultCode(0);
  }
}
