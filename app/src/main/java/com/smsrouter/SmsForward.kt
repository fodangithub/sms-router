package com.smsrouter

import android.content.Context
import android.telephony.SmsMessage
import android.util.Log

/**
 * SmsReceiver / SmsDeliverReceiver 共用的转发入队逻辑。
 *
 * 绝不在广播线程里做网络 I/O:BroadcastReceiver 只有约 10 秒配额,SMTP 握手+认证
 * 经常超时。这里只入队(毫秒级)并唤醒 KeepAliveService,由它真正发送。
 */
object SmsForward {
    private const val TAG = "SmsForward"

    fun handle(context: Context, messages: Array<SmsMessage>?, source: String) {
        if (messages.isNullOrEmpty()) return

        val config = Prefs.load(context)
        if (!config.enabled) {
            Log.d(TAG, "[$source] forwarding disabled, ignore")
            return
        }
        if (!config.isComplete) {
            Log.w(TAG, "[$source] smtp config incomplete, sms NOT queued (fix config in app)")
            return
        }

        val body = StringBuilder()
        var sender = ""
        var ts = 0L
        for (sms in messages) {
            if (sender.isEmpty()) sender = sms.originatingAddress ?: ""
            if (ts == 0L) ts = sms.timestampMillis
            body.append(sms.messageBody ?: "")
        }

        val from = if (sender.isBlank()) "未知号码" else sender
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())

        val subject = "${context.getString(R.string.title_subject_prefix)} $from"
        val text = "发件人：$from\n时间：$time\n\n$body"

        PendingQueue.add(context, subject, text, ts)
        Log.i(TAG, "[$source] queued sms from $from ts=$ts")

        // 无论 keepAlive 开关,都唤醒服务把队列发出去;
        // keepAlive=false 时服务会在无事可做后自行 stopSelf(见 KeepAliveService)
        KeepAliveService.start(context)
    }
}
