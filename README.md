# sms-router

**Turn an old Android phone into a standby SMS gateway: every incoming text message is
forwarded to your email mailbox over SMTP.**

Built for a dedicated device (HONOR LLD-AL00, EMUI 9.1 / Android 9, API 28) and
optimised for exactly one thing above all others: **never miss a message**.

---

## How it works

```
incoming SMS
  ├─ not the default SMS app → SMS_RECEIVED      → SmsReceiver
  └─ is the default SMS app  → SMS_DELIVER       → SmsDeliverReceiver
        │                                          (also stores the SMS in the
        │                                           system inbox, unread)
        │   receivers only enqueue — never touch the network
        ▼
   pending.json          persistent outbox: atomic writes (tmp+fsync+rename),
                         survives process death and reboot
        ▼
   KeepAliveService      foreground notification (EMUI survival) + sender loop
                         running under a PARTIAL_WAKE_LOCK
        │   SMTP via JavaMail — SSL 465 / STARTTLS 587 / plain 25
        ├─ sent     → removed from queue; SMS marked read (default-app role only)
        ├─ offline  → deferred, does not consume a retry
        └─ failed   → exponential backoff (30 s → 1 m → … capped at 30 min);
                      after 50 attempts → deadletter.json (never deleted,
                      recoverable with one tap in the app)
```

**Unread SMS on the device = not forwarded yet.** When the default-SMS-app role is
held, the read flag doubles as a delivery signal: a message stays unread in every
messaging UI until its email has actually been sent.

## Features

- **Receiver-side discipline** — broadcast receivers enqueue in milliseconds and
  return; all SMTP work happens in a foreground service, so the 10-second
  broadcast budget is never a problem.
- **Durable outbox** — queue writes are atomic; a kill mid-write can no longer
  corrupt or silently wipe pending messages (corrupt files are quarantined for
  inspection instead).
- **Honest retries** — offline is not a failure; failures back off exponentially;
  nothing is ever silently dropped (dead-letter file + in-app recovery).
- **EMUI survival** — persistent foreground notification, wake-locked send loop,
  `START_STICKY`, boot/update re-arm, plus in-app buttons for the EMUI
  whitelists that actually decide whether anything runs.
- **Mark-as-read** — optionally takes the default-SMS-app role so forwarded
  messages flip to read once their email is out.
- **A UI that cannot lie** — switches persist the moment they are toggled, so the
  screen always matches the stored configuration.

## Requirements

- Device: Android 9 (API 28) or newer (developed and tested on EMUI 9.1).
- Build: JDK 17, Android SDK (`compileSdk 34`), bundled Gradle 8.14.5 wrapper.
- Dev-machine note: on the original development machine all Gradle/Maven traffic
  must go through a local proxy configured in `gradle.properties` — see
  [AGENTS.md](AGENTS.md) §3 if builds time out resolving dependencies.

## Build & install

```bash
./gradlew assembleDebug            # gradlew.bat on Windows
adb install -r app/build/outputs/apk/debug/app-debug.apk

# grant the whole SMS permission group without tapping dialogs
adb shell pm grant com.smsrouter android.permission.RECEIVE_SMS
adb shell pm grant com.smsrouter android.permission.READ_SMS
adb shell pm grant com.smsrouter android.permission.SEND_SMS
adb shell pm grant com.smsrouter android.permission.RECEIVE_MMS
adb shell pm grant com.smsrouter android.permission.RECEIVE_WAP_PUSH
```

## First run on EMUI / HONOR — mandatory

On Huawei/HONOR ROMs the process killer wins unless you whitelist the app
**manually**. Nothing works until you do:

1. 手机管家 → 启动管理 — allow auto-start *and* background execution
   (in-app button: 打开自启动管理)
2. Settings → Battery — ignore battery optimisation for this app
   (in-app button: 加入电池优化白名单)
3. Leave the persistent service notification visible
4. Fill in the SMTP settings (host / port / encryption / account / recipients) and
   flip **启用短信转发** — switches save and apply immediately
5. Verify with **发送测试邮件**, then with one real SMS

## Default-SMS-app role (needed for mark-as-read)

Since Android 4.4 only the default SMS app may write to the SMS provider, so
marking a forwarded SMS as read requires holding that role (in-app button:
**设为默认短信应用**). While held:

- incoming SMS arrive via `SMS_DELIVER` and this app stores them in the inbox;
- each message flips to read once its email sends;
- trade-offs: the stock messaging app stops raising new-message notifications,
  MMS is dropped (logged only), and send / quick-reply entry points are stubs.

Fully reversible at any time from the system default-app settings. Without the
role the app still forwards everything exactly the same way; mark-as-read simply
logs and skips.

## When mail stops arriving — recovery & debugging

```bash
# component logs (stable tags)
adb logcat -s SmsReceiver SmsDeliverReceiver SmsForward SmsMarkRead \
    KeepAliveService MailSender BootReceiver MainActivity PendingQueue

# stored config — the source of truth the UI mirrors
adb shell run-as com.smsrouter cat shared_prefs/sms_router.xml

# live outbox / dead letters / quarantined corrupt queues
adb shell run-as com.smsrouter cat files/pending.json
adb shell run-as com.smsrouter cat files/deadletter.json
adb shell run-as com.smsrouter ls files | grep corrupt
```

Mails that failed ~50 times move to `deadletter.json` and are **never deleted**;
the **恢复死信队列** button re-queues them. On EMUI, check the §"First run"
whitelist before suspecting code — it is almost always the auto-start setting.

## Project layout & contributing

`app/src/main/java/com/smsrouter/` holds nine small components (receivers,
queue, sender, service, UI). Design decisions, EMUI traps and extension points
(extension table, retry constants, where to add new destinations) are documented
in [AGENTS.md](AGENTS.md).

## Disclaimer

Personal-use utility, not a Play Store product. The SMTP password is stored in
plaintext SharedPreferences on the device — acceptable only for a personal device
under your physical control (`allowBackup=false` blocks `adb backup` extraction).
Review the threat model before reusing this anywhere else.
