package com.sridhar.glasshome;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the system lets Glass Home read active media sessions (Now Playing).
 * Access is granted once over ADB: cmd notification allow_listener ...
 */
public class MediaListener extends NotificationListenerService { }
