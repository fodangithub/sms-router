package com.smsrouter

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * 仅为满足「默认短信应用」角色要求而存在(ACTION_RESPOND_VIA_MESSAGE,
 * 即通知栏"快速回复")。本应用不支持回复短信:记录日志后立即结束。
 */
class HeadlessSmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.w(TAG, "respond-via-message requested; not supported by this app, ignoring")
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private companion object {
        const val TAG = "HeadlessSmsSendService"
    }
}
