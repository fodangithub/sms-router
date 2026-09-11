package com.smsrouter

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.util.Log

/**
 * 邮件发送成功后把对应短信标记为已读。
 *
 * 平台限制(API 19 起,目标机 API 28 同样适用):只有「默认短信应用」可以写
 * SMS Provider——第三方应用 update content://sms 会抛 SecurityException,
 * WRITE_SMS 权限在 API 23 已废弃、形同虚设。因此:
 * - 本应用是默认短信应用 → 按短信的服务中心时间戳定位并置 read=1 / seen=1;
 * - 不是默认短信应用 → 跳过并记日志(功能休眠,完全不影响转发)。
 *
 * 未读状态因此有了额外含义:设备上仍未读的短信 = 尚未成功转发的短信。
 */
object SmsMarkRead {
    private const val TAG = "SmsMarkRead"

    fun isDefaultSmsApp(context: Context): Boolean =
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

    /**
     * 把服务中心时间戳为 [smsTs] 的未读短信标记为已读。best-effort,永不抛异常。
     * smsTs <= 0(旧队列数据或非默认应用期间入队)时直接跳过。
     */
    fun markRead(context: Context, smsTs: Long) {
        if (smsTs <= 0L) return
        if (!isDefaultSmsApp(context)) {
            Log.w(TAG, "skip mark-read: not the default SMS app (smsTs=$smsTs)")
            return
        }
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }
            // date_sent 是服务中心时间戳;个别 ROM 把它写进 date 列,用 OR 同时匹配
            val selection = "(${Telephony.Sms.DATE_SENT}=? AND ${Telephony.Sms.READ}=0)" +
                    " OR (${Telephony.Sms.DATE}=? AND ${Telephony.Sms.READ}=0)"
            val n = context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                selection,
                arrayOf(smsTs.toString(), smsTs.toString())
            )
            Log.i(TAG, "mark-read smsTs=$smsTs updated=$n")
        } catch (t: Throwable) {
            Log.w(TAG, "mark-read failed (smsTs=$smsTs): $t")
        }
    }
}
