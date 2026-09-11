package com.smsrouter

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var swEnabled: SwitchCompat
    private lateinit var swKeepAlive: SwitchCompat
    private lateinit var swMarkRead: SwitchCompat
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var spSecurity: Spinner
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etFrom: EditText
    private lateinit var etTo: EditText
    private lateinit var tvStatus: TextView

    private var lastSecurity = SEC_SSL
    private val reqSmsCode = 1001

    /** loadToUi() 期间为 true,防止程序化设值触发开关的自动保存 */
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swEnabled = findViewById(R.id.swEnabled)
        swKeepAlive = findViewById(R.id.swKeepAlive)
        swMarkRead = findViewById(R.id.swMarkRead)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        spSecurity = findViewById(R.id.spSecurity)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        etFrom = findViewById(R.id.etFrom)
        etTo = findViewById(R.id.etTo)
        tvStatus = findViewById(R.id.tvStatus)

        loadToUi()
        bindActions()
        requestSmsPermissionIfNeeded()
        refreshStatus()
    }

    /**
     * 修复线上 bug:进程在后台被杀后重建时,系统会在 onCreate 之后用旧的视图
     * 状态覆盖 loadToUi() 的结果——开关可能显示"开"而 SharedPreferences 里是"关"。
     * 这里在状态恢复完成后强制以持久化配置为准,保证 UI 与配置永远一致。
     */
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        loadToUi()
    }

    private fun loadToUi() {
        loading = true
        try {
            val c = Prefs.load(this)
            lastSecurity = c.security
            swEnabled.isChecked = c.enabled
            swKeepAlive.isChecked = c.keepAlive
            swMarkRead.isChecked = c.markRead
            etHost.setText(c.host)
            etPort.setText(c.port.toString())
            spSecurity.setSelection(c.security)
            etUser.setText(c.user)
            etPass.setText(c.pass)
            etFrom.setText(c.from)
            etTo.setText(c.to)
        } finally {
            loading = false
        }
    }

    private fun bindActions() {
        // 开关即改即存:拨动任何一个开关立刻持久化整个表单并生效,
        // 不再依赖「保存配置」按钮(那正是"UI 显示开、配置实际关"bug 的温床)
        swEnabled.setOnCheckedChangeListener { _, _ -> if (!loading) onConfigChanged() }
        swKeepAlive.setOnCheckedChangeListener { _, _ -> if (!loading) onConfigChanged() }
        swMarkRead.setOnCheckedChangeListener { _, _ -> if (!loading) onConfigChanged() }

        // 切换加密方式时,若端口仍是上一个方式的默认值就自动跟着改
        spSecurity.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (loading) return
                val current = etPort.text.toString().toIntOrNull()
                if (current == null || current == defaultPort(lastSecurity)) {
                    etPort.setText(defaultPort(position).toString())
                }
                lastSecurity = position
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val config = readFromUi()
            Prefs.save(this, config)
            applyServiceState(config)
            Toast.makeText(this, getString(R.string.msg_saved), Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            val config = readFromUi()
            if (!config.isComplete) {
                Toast.makeText(this, getString(R.string.msg_fill_all), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.save(this, config)
            setStatus(getString(R.string.msg_sending))
            val subject = "${getString(R.string.title_subject_prefix)} 测试邮件"
            val body = "这是一封来自短信转发应用的测试邮件。\n时间：${java.util.Date()}"
            thread {
                var ok = false
                var err: String? = null
                try {
                    MailSender.send(config, subject, body)
                    ok = true
                } catch (t: Throwable) {
                    ok = false
                    err = t.message ?: t.toString()
                    Log.e(TAG, "test send failed", t)
                }
                runOnUiThread {
                    if (ok) setStatus(getString(R.string.msg_sent))
                    else setStatus(getString(R.string.msg_failed, err))
                }
            }
        }

        findViewById<Button>(R.id.btnPerm).setOnClickListener {
            requestSmsPermissionIfNeeded(force = true)
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            requestBatteryWhitelist()
        }

        findViewById<Button>(R.id.btnAutostart).setOnClickListener {
            openAutoStartSettings()
        }

        findViewById<Button>(R.id.btnDefaultSms).setOnClickListener {
            openDefaultSmsSettings()
        }

        findViewById<Button>(R.id.btnRequeueDead).setOnClickListener {
            thread {
                val n = PendingQueue.requeueDeadLetters(this)
                if (n > 0) KeepAliveService.start(this)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (n > 0) getString(R.string.msg_requeued, n)
                        else getString(R.string.msg_requeued_none),
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshStatus()
                }
            }
        }
    }

    /** 开关变化 → 立刻保存整个表单(所见即所存)并应用服务状态 */
    private fun onConfigChanged() {
        val config = readFromUi()
        Prefs.save(this, config)
        applyServiceState(config)
        refreshStatus()
        Toast.makeText(this, getString(R.string.msg_saved), Toast.LENGTH_SHORT).show()
        if (config.enabled && !config.isComplete) {
            Toast.makeText(this, getString(R.string.msg_incomplete_warning), Toast.LENGTH_LONG).show()
        }
        Log.i(TAG, "config auto-saved from UI: enabled=${config.enabled} " +
                "keepAlive=${config.keepAlive} markRead=${config.markRead} complete=${config.isComplete}")
    }

    /**
     * enabled → 拉起服务(keepAlive=false 时服务发完队列会自行 stopSelf);
     * disabled → 停止服务。
     */
    private fun applyServiceState(config: SmtpConfig) {
        if (config.enabled) KeepAliveService.start(this)
        else KeepAliveService.stop(this)
    }

    private fun readFromUi(): SmtpConfig = SmtpConfig(
        enabled = swEnabled.isChecked,
        keepAlive = swKeepAlive.isChecked,
        markRead = swMarkRead.isChecked,
        host = etHost.text.toString().trim(),
        port = etPort.text.toString().toIntOrNull() ?: defaultPort(spSecurity.selectedItemPosition),
        security = spSecurity.selectedItemPosition,
        user = etUser.text.toString().trim(),
        pass = etPass.text.toString(),
        from = etFrom.text.toString().trim(),
        to = etTo.text.toString().trim()
    )

    private fun requestSmsPermissionIfNeeded(force: Boolean = false) {
        // SEND_SMS / RECEIVE_MMS / RECEIVE_WAP_PUSH 是「默认短信应用」角色要求的权限,
        // 与 RECEIVE_SMS 同属 SMS 权限组,一次对话框全部申请
        val needed = listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.RECEIVE_WAP_PUSH
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            if (force) Toast.makeText(this, getString(R.string.msg_perm_granted), Toast.LENGTH_SHORT).show()
            return
        }
        ActivityCompat.requestPermissions(this, needed.toTypedArray(), reqSmsCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqSmsCode) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            Toast.makeText(
                this,
                if (granted) getString(R.string.msg_perm_granted)
                else getString(R.string.msg_perm_denied),
                Toast.LENGTH_SHORT
            ).show()
            refreshStatus()
        }
    }

    private fun requestBatteryWhitelist() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, getString(R.string.msg_battery_already), Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    /** 华为/荣耀没有公开的自启动 Intent,只能按版本逐个试组件名 */
    private fun openAutoStartSettings() {
        val candidates = listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.ui.StartupAppControlActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        )
        for ((pkg, cls) in candidates) {
            val opened = runCatching {
                startActivity(Intent().setComponent(ComponentName(pkg, cls)))
                true
            }.getOrDefault(false)
            if (opened) {
                setStatus("已打开启动管理，请把『短信转发』设为允许自启动、允许后台运行")
                return
            }
        }
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        setStatus("未能直接打开启动管理，请手动进入：手机管家 → 启动管理")
    }

    /**
     * 「标记已读」依赖默认短信应用角色(API 19+ 只有默认短信应用能写 SMS Provider)。
     * API 28 用 ACTION_CHANGE_DEFAULT 拉起系统选择框。
     */
    private fun openDefaultSmsSettings() {
        if (SmsMarkRead.isDefaultSmsApp(this)) {
            Toast.makeText(this, getString(R.string.msg_default_sms_already), Toast.LENGTH_SHORT).show()
            return
        }
        val opened = runCatching {
            startActivity(
                Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                    .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            )
            true
        }.getOrDefault(false)
        if (!opened) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
        }
        setStatus(getString(R.string.msg_default_sms_hint))
    }

    private fun setStatus(text: String) {
        tvStatus.text = text
    }

    private fun refreshStatus() {
        val config = Prefs.load(this)
        val permOk = listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val parts = mutableListOf<String>()
        parts.add("短信权限：${if (permOk) "已授予" else "未授予"}")
        parts.add("转发：${if (config.enabled) "开启" else "关闭"}")
        parts.add("SMTP 配置：${if (config.isComplete) "完整" else "不完整"}")
        parts.add("默认短信应用：${if (SmsMarkRead.isDefaultSmsApp(this)) "是" else "否（标记已读需要）"}")
        val pending = PendingQueue.size(this)
        val dead = PendingQueue.deadLetterSize(this)
        parts.add("待发队列：$pending 封" + if (dead > 0) "，死信 $dead 封（可恢复）" else "")
        setStatus(parts.joinToString("\n"))
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
