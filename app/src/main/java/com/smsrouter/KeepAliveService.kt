package com.smsrouter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 常驻前台服务 + 发信循环。
 *
 * 存在意义:EMUI 对后台进程管控极严,没有可见通知的进程很容易被回收,
 * 导致短信广播到了但没进程去发邮件。挂一个前台通知能显著提高存活率。
 *
 * 发送策略:
 * - 指数退避:失败后 30s → 1m → 2m → … 封顶 30 分钟,离线不消耗重试次数;
 * - 绝不静默丢弃:超过 [MAX_RETRY] 次的消息移入 deadletter.json,可在 UI 一键恢复;
 * - 发送前检查网络连通性;
 * - drain 全程持有 PARTIAL_WAKE_LOCK,避免广播返回后 CPU 休眠把发送卡在半路;
 * - keepAlive=false 时,队列处理完(或暂时无法处理)服务自行 stopSelf,不做常驻。
 *
 * 并发安全:
 * - drain 循环每一步都检查 [running],onDestroy 会 interrupt 工作线程;
 * - [drainInFlight] 是进程级互斥:服务被快速销毁重建时,新旧实例的 drain 不会
 *   同时跑(否则会出现重复发送/误删未发送消息的竞态);
 * - [kickFlag] + monitor 配合,消除"notify 发生在 wait 之前"导致的丢失唤醒。
 */
class KeepAliveService : Service() {

    private val running = AtomicBoolean(false)
    private val kickFlag = AtomicBoolean(false)
    private val monitor = Object()
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureWorker()
        synchronized(monitor) {
            kickFlag.set(true)
            monitor.notifyAll()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        // interrupt 唤醒 wait();drain 内部每步检查 running,最多再完成当前一封
        worker?.interrupt()
        synchronized(monitor) { monitor.notifyAll() }
        releaseWakeLock()
        super.onDestroy()
        Log.i(TAG, "service destroyed")
    }

    private fun ensureWorker() {
        if (running.get()) return
        running.set(true)
        worker = Thread {
            Log.i(TAG, "worker started")
            while (running.get()) {
                kickFlag.set(false)

                acquireWakeLock(this)
                try {
                    drainOnce()
                } catch (t: Throwable) {
                    Log.e(TAG, "drain error", t)
                } finally {
                    releaseWakeLock()
                }
                if (!running.get()) break

                val cfg = Prefs.load(this)
                if (!cfg.keepAlive) {
                    // 非常驻模式:无事可做就自我停止,下次短信/开机再拉起
                    val pending = PendingQueue.size(this)
                    val hasDue = pending > 0 &&
                            PendingQueue.nextDue(this, System.currentTimeMillis()) != null
                    if (!cfg.enabled || !cfg.isComplete || !hasDue || !isNetworkAvailable()) {
                        Log.i(TAG, "keep-alive off and nothing actionable now (pending=$pending), stopSelf")
                        running.set(false)
                        stopSelf()
                        break
                    }
                }

                val interval = computeInterval()
                // 注意:break 不能直接写在 synchronized 的 lambda 里
                // (Kotlin 2.0 的 "break/continue in inline lambdas" 仍是实验特性),用标志位代替
                var shouldExit = false
                synchronized(monitor) {
                    if (!running.get()) {
                        shouldExit = true
                    } else if (!kickFlag.get()) {
                        // 双重检查 kickFlag:避免与 onStartCommand 的 notifyAll 交错丢失唤醒
                        try {
                            monitor.wait(interval)
                        } catch (e: InterruptedException) {
                            // interrupt 只来自 onDestroy,直接退出,避免置位标志导致忙轮询
                            Log.i(TAG, "worker interrupted, exiting")
                            shouldExit = true
                        }
                    }
                }
                if (shouldExit) break
            }
            Log.i(TAG, "worker exit")
        }.apply {
            isDaemon = true
            name = "sms-router-sender"
            start()
        }
    }

    /** 轮询间隔:队列空 → 5 分钟;有到期消息 → 30 秒;退避中 → 睡到最早到期;离线 → 2 分钟 */
    private fun computeInterval(): Long {
        val earliest = PendingQueue.earliestAttemptAt(this) ?: return IDLE_INTERVAL_MS
        val now = System.currentTimeMillis()
        if (earliest > now) return minOf(earliest - now, IDLE_INTERVAL_MS)
        return if (isNetworkAvailable()) BUSY_INTERVAL_MS else OFFLINE_INTERVAL_MS
    }

    private fun drainOnce() {
        // 进程级互斥:旧实例的 drain 还在收尾时,新实例本轮跳过,杜绝并发双写队列
        if (!drainInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "another drain still in flight, skip this round")
            return
        }
        try {
            drainLocked()
        } finally {
            drainInFlight.set(false)
        }
    }

    private fun drainLocked() {
        val config = Prefs.load(this)
        if (!config.enabled || !config.isComplete) {
            Log.d(TAG, "drain skipped: enabled=${config.enabled} complete=${config.isComplete}")
            return
        }

        var sentThisRound = 0
        while (running.get() && sentThisRound < MAX_PER_ROUND) {
            val mail = PendingQueue.nextDue(this, System.currentTimeMillis()) ?: break

            if (!isNetworkAvailable()) {
                // 离线不算失败:不消耗重试次数,等网络恢复
                Log.w(TAG, "network unavailable, defer sending (not counted as a retry)")
                return
            }

            try {
                Log.i(TAG, "sending: ${mail.subject} (retries=${mail.retries})")
                MailSender.send(config, mail.subject, mail.body)
                PendingQueue.removeById(this, mail.id)
                Log.i(TAG, "mail sent: ${mail.subject}")
                if (config.markRead) {
                    SmsMarkRead.markRead(this, mail.smsTs)
                }
                sentThisRound++
            } catch (t: Throwable) {
                if (mail.retries + 1 >= MAX_RETRY) {
                    // 绝不静默删除:移入死信文件,UI 可恢复
                    PendingQueue.moveToDeadLetter(this, mail.id, t.toString())
                } else {
                    val backoff = backoffMs(mail.retries)
                    val attempt = PendingQueue.markFailed(
                        this, mail.id, System.currentTimeMillis() + backoff
                    )
                    Log.w(TAG, "send failed (attempt $attempt/$MAX_RETRY), retry in ${backoff}ms: ${t.message}")
                }
                // 本轮到此为止,避免连续敲打服务器
                return
            }
        }
        if (sentThisRound > 0) Log.i(TAG, "round done, sent $sentThisRound mail(s)")
    }

    /** 指数退避:30s → 1m → 2m → 4m → … 封顶 30 分钟 */
    private fun backoffMs(retries: Int): Long {
        val exp = BACKOFF_BASE_MS * (1L shl minOf(retries, 30))
        return minOf(exp, BACKOFF_CAP_MS)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "sms_router_keepalive"
        private const val BUSY_INTERVAL_MS = 30_000L          // 有到期消息时
        private const val OFFLINE_INTERVAL_MS = 2 * 60_000L    // 离线时(不消耗重试)
        private const val IDLE_INTERVAL_MS = 5 * 60_000L      // 队列空时(新短信会立即唤醒)
        private const val MAX_PER_ROUND = 20
        private const val MAX_RETRY = 50                       // ≈ 持续失败一天后进死信,绝不删除
        private const val BACKOFF_BASE_MS = 30_000L
        private const val BACKOFF_CAP_MS = 30 * 60_000L
        private const val WAKELOCK_TIMEOUT_MS = 5 * 60_000L    // 保险丝:忘记 release 也最多持有 5 分钟

        /** 进程级 drain 互斥(跨服务实例) */
        private val drainInFlight = AtomicBoolean(false)

        @Volatile
        private var wakeLock: PowerManager.WakeLock? = null

        private fun acquireWakeLock(context: Context) {
            try {
                if (wakeLock == null) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sms-router:send").apply {
                        setReferenceCounted(false)
                    }
                }
                wakeLock?.let { if (!it.isHeld) it.acquire(WAKELOCK_TIMEOUT_MS) }
            } catch (t: Throwable) {
                Log.e(TAG, "acquire wakelock failed", t)
            }
        }

        private fun releaseWakeLock() {
            try {
                wakeLock?.takeIf { it.isHeld }?.release()
            } catch (t: Throwable) {
                Log.e(TAG, "release wakelock failed", t)
            }
        }

        fun start(context: Context) {
            // 桥接:广播返回后 CPU 可能立刻休眠,先保住到服务的 worker 接手
            acquireWakeLock(context)
            val intent = Intent(context, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                releaseWakeLock()
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (t: Throwable) {
                Log.e(TAG, "stop failed", t)
            }
        }
    }
}
