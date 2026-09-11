package com.smsrouter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 应用更新后拉起服务。
 *
 * 前提:用户必须在 EMUI 的「手机管家 → 启动管理」里把本应用设为允许自启动,
 * 否则这个 Receiver 根本不会被触发——这是华为机型最常见的"收不到短信"原因。
 *
 * keepAlive 关闭时也会拉起一次:把重启前积压的队列发完,服务随后会自行 stopSelf。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "onReceive: $action")

        val config = Prefs.load(context)
        if (!config.enabled) {
            Log.d(TAG, "forwarding disabled, skip")
            return
        }

        KeepAliveService.start(context)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
