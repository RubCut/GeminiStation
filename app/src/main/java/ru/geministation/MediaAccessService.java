package ru.geministation;

import android.service.notification.NotificationListenerService;

/** Android requires a user-enabled notification listener to access other apps' media sessions.
 * No notification callback is overridden; notification contents are not processed or stored. */
public final class MediaAccessService extends NotificationListenerService { }
