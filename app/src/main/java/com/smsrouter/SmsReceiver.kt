package com.smsrouter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * 非默认短信应用路径:监听 SMS_RECEIVED,把短信入队后唤醒 KeepAliveService 发送。
 *
 * 这里刻意不做网络操作:BroadcastReceiver 的 goAsync 只有约 10 秒配额,
 * SMTP 握手+认证经常超时。入队是毫秒级操作,可以安全地在主线程完成。
 *
 * 若本应用已被设为默认短信应用,新短信会以 SMS_DELIVER 送达
 * (见 SmsDeliverReceiver),这里直接跳过,避免重复入队。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        if (SmsMarkRead.isDefaultSmsApp(context)) {
            Log.d(TAG, "we are the default SMS app; message is handled via SMS_DELIVER, skip")
            return
        }

        SmsForward.handle(
            context,
            Telephony.Sms.Intents.getMessagesFromIntent(intent),
            "SMS_RECEIVED"
        )
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}
