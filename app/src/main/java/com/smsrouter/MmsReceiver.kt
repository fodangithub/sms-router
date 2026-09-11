package com.smsrouter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 仅为满足「默认短信应用」角色要求而存在(WAP_PUSH_DELIVER)。
 *
 * 本应用不处理彩信:设为默认短信应用后,到达的 MMS WAP 推送会被记录日志后忽略,
 * 既不会下载也不会转发。这是已知限制(见 AGENTS.md §6)。
 */
class MmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.w(TAG, "WAP_PUSH_DELIVER received (MMS not supported by this app, ignored): ${intent.action}")
    }

    private companion object {
        const val TAG = "MmsReceiver"
    }
}
