package com.smsrouter

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log

/**
 * 默认短信应用路径:系统把新短信以 SMS_DELIVER 只发给默认短信应用。
 *
 * 成为默认应用后,把短信写入 SMS Provider 是本应用的责任——否则短信不会出现在
 * 任何收件箱里(包括系统信息应用的界面)。流程:
 *   解析 → 写入 provider(read=0,保持未读)→ 入队转发
 * 邮件发送成功后由 KeepAliveService 调 SmsMarkRead 置 read=1。
 * 发送失败时短信保持未读:未读 = 尚未成功转发,是设备上的可见信号。
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        writeToProvider(context, messages)
        SmsForward.handle(context, messages, "SMS_DELIVER")
    }

    /** 把整条(多分片拼接后的)短信写入收件箱,保持未读 */
    private fun writeToProvider(context: Context, messages: Array<SmsMessage>) {
        try {
            val first = messages[0]
            val body = StringBuilder()
            for (m in messages) body.append(m.messageBody ?: "")
            val address = first.originatingAddress ?: ""

            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body.toString())
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.DATE_SENT, first.timestampMillis)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                if (address.isNotBlank()) {
                    put(
                        Telephony.Sms.THREAD_ID,
                        Telephony.Threads.getOrCreateThreadId(context, address)
                    )
                }
            }
            val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            Log.i(TAG, "stored inbox row: $uri (date_sent=${first.timestampMillis})")
        } catch (t: Throwable) {
            // 写库失败不阻断转发:邮件照发,只是这条短信不会出现在收件箱
            Log.e(TAG, "write to provider failed (forwarding continues)", t)
        }
    }

    private companion object {
        const val TAG = "SmsDeliverReceiver"
    }
}
