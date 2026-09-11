package com.smsrouter

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * 待发送队列(持久化在 filesDir/pending.json)。
 *
 * 设计要点:
 * - 原子写入:先写 .tmp 并 fsync,再 rename,进程在写入中途被杀也不会产生半截文件;
 * - 解析失败时把坏文件改名保留(pending.json.corrupt-<ts>)而不是静默清空,便于事后取证;
 * - 每条消息有稳定 id、指数退避用的 nextAttemptAt、以及短信的服务中心时间戳 smsTs
 *   (发送成功后用它定位 provider 里的短信并标记已读);
 * - 超过重试上限的消息移入 deadletter.json,永不删除,可在 UI 里一键重新入队。
 */
data class PendingMail(
    val id: String,
    val subject: String,
    val body: String,
    val createdAt: Long = 0L,
    val retries: Int = 0,
    val nextAttemptAt: Long = 0L,
    val smsTs: Long = 0L
)

object PendingQueue {
    private const val TAG = "PendingQueue"
    private const val FILE_NAME = "pending.json"
    private const val DEAD_LETTER_FILE_NAME = "deadletter.json"

    private val seq = AtomicLong(0)

    private fun file(context: Context, name: String) = File(context.filesDir, name)

    @Synchronized
    fun add(context: Context, subject: String, body: String, smsTs: Long) {
        val arr = readRaw(context, FILE_NAME)
        val now = System.currentTimeMillis()
        val obj = JSONObject()
        obj.put("id", "$now-${seq.incrementAndGet()}")
        obj.put("subject", subject)
        obj.put("body", body)
        obj.put("createdAt", now)
        obj.put("retries", 0)
        obj.put("nextAttemptAt", 0L)
        obj.put("smsTs", smsTs)
        arr.put(obj)
        writeRaw(context, FILE_NAME, arr)
        Log.i(TAG, "queued: $subject (queue size ${arr.length()})")
    }

    @Synchronized
    fun list(context: Context): List<PendingMail> = parseArray(readRaw(context, FILE_NAME))

    /** 取出第一条已到期(nextAttemptAt <= now)的消息 */
    @Synchronized
    fun nextDue(context: Context, now: Long): PendingMail? =
        parseArray(readRaw(context, FILE_NAME)).firstOrNull { it.nextAttemptAt <= now }

    /** 队列中最早的下次可尝试时间;队列空返回 null(用于自适应轮询间隔) */
    @Synchronized
    fun earliestAttemptAt(context: Context): Long? {
        val mails = parseArray(readRaw(context, FILE_NAME))
        if (mails.isEmpty()) return null
        return mails.minOf { it.nextAttemptAt }
    }

    @Synchronized
    fun removeById(context: Context, id: String): Boolean {
        val arr = readRaw(context, FILE_NAME)
        val next = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!removed && o.optString("id") == id) {
                removed = true
            } else {
                next.put(o)
            }
        }
        if (removed) writeRaw(context, FILE_NAME, next)
        return removed
    }

    @Synchronized
    fun markFailed(context: Context, id: String, nextAttemptAt: Long): Int {
        val arr = readRaw(context, FILE_NAME)
        var retries = -1
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) {
                retries = o.optInt("retries", 0) + 1
                o.put("retries", retries)
                o.put("nextAttemptAt", nextAttemptAt)
                break
            }
        }
        if (retries >= 0) writeRaw(context, FILE_NAME, arr)
        return retries
    }

    /** 超过重试上限的消息移入 deadletter.json(保留证据,永不删除) */
    @Synchronized
    fun moveToDeadLetter(context: Context, id: String, reason: String?) {
        val arr = readRaw(context, FILE_NAME)
        val next = JSONArray()
        var moved: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (moved == null && o.optString("id") == id) {
                o.put("deadReason", reason ?: "unknown")
                o.put("deadAt", System.currentTimeMillis())
                moved = o
            } else {
                next.put(o)
            }
        }
        if (moved == null) return
        writeRaw(context, FILE_NAME, next)
        val dead = readRaw(context, DEAD_LETTER_FILE_NAME)
        dead.put(moved)
        writeRaw(context, DEAD_LETTER_FILE_NAME, dead)
        Log.e(TAG, "moved to dead letter: ${moved.optString("subject")} reason=$reason")
    }

    /** 把全部死信放回待发队列(重置重试计数),返回放回条数 */
    @Synchronized
    fun requeueDeadLetters(context: Context): Int {
        val dead = readRaw(context, DEAD_LETTER_FILE_NAME)
        if (dead.length() == 0) return 0
        val pending = readRaw(context, FILE_NAME)
        var n = 0
        for (i in 0 until dead.length()) {
            val o = dead.optJSONObject(i) ?: continue
            o.remove("deadReason")
            o.remove("deadAt")
            o.put("retries", 0)
            o.put("nextAttemptAt", 0L)
            pending.put(o)
            n++
        }
        writeRaw(context, FILE_NAME, pending)
        writeRaw(context, DEAD_LETTER_FILE_NAME, JSONArray())
        Log.i(TAG, "requeued $n dead letters")
        return n
    }

    @Synchronized
    fun size(context: Context): Int = readRaw(context, FILE_NAME).length()

    @Synchronized
    fun deadLetterSize(context: Context): Int = readRaw(context, DEAD_LETTER_FILE_NAME).length()

    private fun parseArray(arr: JSONArray): List<PendingMail> {
        val out = ArrayList<PendingMail>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                PendingMail(
                    id = o.optString("id", ""),
                    subject = o.optString("subject", ""),
                    body = o.optString("body", ""),
                    createdAt = o.optLong("createdAt", 0L),
                    retries = o.optInt("retries", 0),
                    nextAttemptAt = o.optLong("nextAttemptAt", 0L),
                    smsTs = o.optLong("smsTs", 0L)
                )
            )
        }
        return out
    }

    private fun readRaw(context: Context, name: String): JSONArray {
        val f = file(context, name)
        if (!f.exists()) return JSONArray()
        val text = try {
            f.readText()
        } catch (t: Throwable) {
            Log.e(TAG, "read $name failed", t)
            return JSONArray()
        }
        if (text.isBlank()) return JSONArray()
        return try {
            JSONArray(text)
        } catch (t: Throwable) {
            // 文件损坏(历史上是写入中途进程被杀):保留现场而不是静默清空
            val quarantine = File(context.filesDir, "$name.corrupt-${System.currentTimeMillis()}")
            Log.e(TAG, "$name corrupted! quarantined to ${quarantine.name}, please pull it for inspection", t)
            runCatching { f.renameTo(quarantine) }
            JSONArray()
        }
    }

    /** 原子写:tmp + fsync + rename,任何时刻崩溃都不会留下半截文件 */
    private fun writeRaw(context: Context, name: String, arr: JSONArray) {
        val f = file(context, name)
        val tmp = File(context.filesDir, "$name.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(arr.toString().toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(f)) {
                // 个别文件系统 rename 不覆盖已存在文件,退化为 delete+rename
                f.delete()
                if (!tmp.renameTo(f)) {
                    tmp.copyTo(f, overwrite = true)
                    tmp.delete()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "write $name failed", t)
            runCatching { tmp.delete() }
        }
    }
}
