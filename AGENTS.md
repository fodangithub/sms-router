# AGENTS.md

Guidance for AI agents and human teammates working on **sms-router**.

---

## 1. What this project is

An Android app that listens for incoming SMS messages and forwards each one to a
pre-configured mailbox over SMTP. The user configures host / port / encryption /
account / recipients inside the app.

It is a **personal-use utility**, not a Play Store product. That matters: we do not
need to satisfy Google's SMS permission policy, and we optimise for
"it must never miss a message" over "it must be well-behaved".

- Package name: `com.smsrouter`
- Language: Kotlin
- `minSdk 28` / `targetSdk 34` / `compileSdk 34`

---

## 2. Target device

| Item | Value |
|---|---|
| Device | HONOR LLD-AL00 (HONOR 20) |
| Android | 9.0, **API 28** |
| ROM | **EMUI 9.1.0** |
| ABI | arm64-v8a |
| Serial | (redacted — device-identifying, kept off version control) |

Everything about the architecture is shaped by API 28 + EMUI. Do not "modernise"
the code in ways that assume Android 10+ behaviour — for example,
`startForegroundService()` is fine here and background-start restrictions are
milder than on newer Android, but **EMUI's own process killer is far stricter than
stock Android** and is the real adversary. See §7.

---

## 3. Development environment (machine-specific — read before building)

This repo assumes the toolchain lives on **D: drive**, not in the default
`%LOCALAPPDATA%` locations. Paths are hard requirements for the current machine;
if you rebuild the environment elsewhere, update `local.properties` and the
environment variables accordingly.

| Component | Path |
|---|---|
| JDK 17 (Temurin 17.0.20.1) | `D:\Dev\Java\jdk-17` |
| Android SDK | `D:\Dev\Android\Sdk` |
| cmdline-tools | `D:\Dev\Android\Sdk\cmdline-tools\latest` |
| Gradle 8.14.5 (standalone) | `D:\Dev\Gradle\gradle-8.14.5` |
| Gradle user home / caches | `D:\Dev\Gradle` |
| Maven 3.9.16 | `D:\Dev\Maven\apache-maven-3.9.16` |
| adb | `D:\Dev\Android\Sdk\platform-tools\adb.exe` |

User-level environment variables already set: `JAVA_HOME`, `ANDROID_HOME`,
`ANDROID_SDK_ROOT`, `GRADLE_USER_HOME`, `MAVEN_HOME`, plus the proxy variables.

### Network: the proxy is mandatory, not optional

Outbound connections to Google / Maven / Gradle **time out without a proxy** on this
machine. Everything must go through:

```
http://127.0.0.1:10808
```

It is configured in four places (keep them in sync if it ever changes):

1. User env vars `HTTP_PROXY` / `HTTPS_PROXY`
2. `GRADLE_OPTS` and `MAVEN_OPTS` (JVM-level)
3. `D:\Dev\Gradle\gradle.properties` — global `systemProp.*` entries
4. `gradle.properties` in this repo — **project-level, overrides the global one**

Point 4 is easy to miss: Gradle treats `org.gradle.jvmargs` as a single value, so a
project-level definition *replaces* the global one instead of merging. That is why
the proxy JVM args are duplicated in the repo's `gradle.properties`.

When scripting downloads with `curl`, add `--ssl-no-revoke`, otherwise schannel
fails with `CRYPT_E_REVOCATION_OFFLINE (0x80092013)` behind the proxy.

---

## 4. Project structure

```
sms-router/
├── settings.gradle.kts            # repo list (google + mavenCentral only)
├── build.gradle.kts               # plugin versions: AGP 8.7.3, Kotlin 2.0.21
├── gradle.properties              # JVM args + proxy (see §3)
├── local.properties               # sdk.dir — gitignored, regenerate if missing
├── gradle/wrapper/                # Gradle 8.14.5 wrapper
└── app/
    ├── build.gradle.kts           # dependencies, SDK levels, packaging rules
    ├── proguard-rules.pro         # keeps javax.mail / javax.activation
    └── src/main/
        ├── AndroidManifest.xml    # permissions, receiver, service, boot receiver
        ├── java/com/smsrouter/
        │   ├── Config.kt          # SmtpConfig data class + Prefs (SharedPreferences)
        │   ├── PendingQueue.kt    # persistent outbox: filesDir/pending.json + deadletter.json
        │   ├── MailSender.kt      # synchronous SMTP send via JavaMail
        │   ├── SmsForward.kt      # shared enqueue logic for both receive paths (no network!)
        │   ├── SmsReceiver.kt     # SMS_RECEIVED -> enqueue (only when NOT default SMS app)
        │   ├── SmsDeliverReceiver.kt # SMS_DELIVER -> write to provider + enqueue (default app path)
        │   ├── SmsMarkRead.kt     # mark SMS read after its mail was sent (needs default-app role)
        │   ├── KeepAliveService.kt# foreground service + adaptive send loop (backoff, wakelock)
        │   ├── BootReceiver.kt    # re-arm service after boot / update
        │   ├── MmsReceiver.kt     # role placeholder (WAP_PUSH_DELIVER, MMS not supported)
        │   ├── ComposeSmsActivity.kt # role placeholder (ACTION_SENDTO, finishes immediately)
        │   ├── HeadlessSmsSendService.kt # role placeholder (RESPOND_VIA_MESSAGE, no-op)
        │   └── MainActivity.kt    # settings UI (auto-saving switches), permissions, role buttons
        └── res/
            ├── layout/activity_main.xml
            ├── values/            # strings, theme, launcher background colour
            ├── drawable/          # ic_launcher_foreground, ic_notification
            └── mipmap-anydpi-v26/ # adaptive icons (minSdk 28, so no PNG fallback)
```

---

## 5. Data flow

```
incoming SMS
    │
    ├─ app is NOT default SMS app:
    │    system broadcast SMS_RECEIVED → SmsReceiver
    └─ app IS default SMS app:
         SMS_DELIVER → SmsDeliverReceiver → writes row to SMS provider (read=0)
    │
    ▼  (both paths converge in SmsForward.handle)
SmsForward                      ← must return in milliseconds, no network!
    │  reads config, builds subject + body + SMS service-center timestamp
    ▼
PendingQueue (pending.json)     ← persisted atomically (tmp+fsync+rename),
    │                              corrupt files are quarantined, never silently dropped
    ▼  (KeepAliveService.start — acquires a bridging PARTIAL_WAKE_LOCK)
KeepAliveService  ── foreground notification (keeps EMUI from killing us)
    │  worker thread under PARTIAL_WAKE_LOCK; adaptive poll:
    │  30 s when work is due / sleep-until-backoff / 2 min offline / 5 min idle
    ▼
MailSender.send()  ── JavaMail SMTP (SSL 465 / STARTTLS 587 / plain 25)
    │
    ├─ success → removeById; if markRead and we are the default SMS app,
    │            SmsMarkRead sets read=1 on the provider row (unread on device
    │            = "not yet forwarded", a visible signal)
    ├─ offline → deferred, does NOT consume a retry
    └─ failure → exponential backoff (30 s → 1 m → … capped at 30 min),
                 stop this round; after MAX_RETRY (50) attempts the mail moves
                 to deadletter.json — NEVER deleted; UI has a "恢复死信队列" button
```

---

## 6. Design decisions — why the code looks like this

**Never do network I/O inside the SMS receivers.** A `BroadcastReceiver` gets roughly
10 seconds from `goAsync()`. SMTP handshake + auth routinely exceeds that, and the
process can be reclaimed mid-send. So the receivers only write to the queue (via
`SmsForward.handle`), which is a sub-millisecond operation and safe on the main
thread. If you add work to a receiver, keep it synchronous and fast.

**The outbox is persisted, not in-memory.** A message that arrives while the
network is down must still be delivered later. `PendingQueue` writes JSON to
`filesDir`, so it survives process death and reboot.

**A foreground service is load-bearing, not decoration.** On EMUI, a process with
no visible notification is killed aggressively, and then SMS arrives with nobody
alive to deliver it. The persistent notification in `KeepAliveService` is the
single most important reliability feature of this app. Do not remove it, and do
not switch to a bare `WorkManager` periodic job — WorkManager intervals are
deferred by Doze and are *not* guaranteed to run promptly on this ROM.

**The receivers are statically registered with maximum priority and require
`BROADCAST_SMS`.** `SMS_RECEIVED` is on the implicit-broadcast exemption list, so a
manifest-registered receiver still works on API 28. Priority `2147483647` makes us
run ahead of other SMS apps. Both SMS receivers set
`android:permission="android.permission.BROADCAST_SMS"`: only the system phone
process (a signature-permission holder) may deliver to them, so local apps cannot
forge `SMS_RECEIVED` broadcasts and inject fake "SMS" into the user's mailbox.
(An earlier revision omitted this on a vendor-ROM rumour; the check is against the
*broadcaster*, and the broadcaster is always the system — verify with a real SMS
after installing, per §8.)

**Default-SMS-app role is optional but load-bearing for mark-as-read.** Since
API 19 only the default SMS app may write to the SMS provider (`WRITE_SMS` is
dead), so marking a forwarded SMS as read requires the role. The four role
components (`SmsDeliverReceiver`, `MmsReceiver`, `ComposeSmsActivity`,
`HeadlessSmsSendService`) exist to make the app eligible. Consequences when the
user actually selects it as default: incoming SMS arrive via `SMS_DELIVER` (the
`SMS_RECEIVED` path self-skips to avoid double-enqueue), this app becomes
responsible for writing messages into the provider (unread until the mail sends),
MMS is logged and dropped, and the stock Messaging app stops handling new
messages. Everything degrades gracefully when the role is not held.

**The UI never lies about the config.** Switches auto-save the whole form the
moment they are toggled, and `MainActivity.onRestoreInstanceState` re-applies
`loadToUi()` — Android restores view state *after* `onCreate`, which previously
let a stale "转发 on" switch display over an `enabled=false` pref (a real
field bug: SMS silently not forwarded). Keep prefs as the single source of
truth: any code path that sets a switch programmatically must do it inside
`loadToUi()` with the `loading` guard set.

**Kotlin, with a deliberately small dependency set.** Only `core-ktx`, `appcompat`
and JavaMail. No Material, no ConstraintLayout, no Jetpack Compose, no WorkManager
(the dependency was removed — see the WorkManager note above). The UI is a single
scrollable `LinearLayout` and that is intentional — fewer dependencies means a
smaller APK and less that can break on API 28.

**Gradle is pinned to 8.x.** Gradle 9.x is out but is incompatible with the Android
Gradle Plugin. Do not "upgrade to latest".

---

## 7. EMUI 9.1 — the #1 support issue

Code correctness is not enough. On a HONOR/EMUI device the user must manually
whitelist the app, or **nothing works**:

1. 手机管家 → 启动管理 → allow auto-start *and* background execution
2. Settings → Battery → ignore battery optimisation for this app
3. Lock-screen cleanup must not kill the app; add it to protected apps if the ROM
   exposes that list
4. Leave the persistent notification visible (do not hide it in notification
   settings)
5. *(only for mark-as-read)* set the app as the default SMS app —
   `MainActivity` has a button for it (`ACTION_CHANGE_DEFAULT` on API 28)

`MainActivity` has buttons that jump to the permission / battery / auto-start /
default-SMS screens. The auto-start one tries several HONOR and Huawei component
names in turn and falls back to the system Settings page, because there is no
public Intent for it.

If a user reports "no emails arrive", **check these four things before reading any
code**. It is almost always #1.

---

## 8. Common commands

```bash
# build
.\gradlew.bat assembleDebug

# build + install
.\gradlew.bat installDebug
# or
adb install -r app\build\outputs\apk\debug\app-debug.apk

# watch logs (components log under consistent tags)
adb logcat -s SmsReceiver SmsDeliverReceiver SmsForward SmsMarkRead KeepAliveService MailSender BootReceiver MainActivity PendingQueue

# grant SMS permissions without tapping through the UI (all one permission group)
adb shell pm grant com.smsrouter android.permission.RECEIVE_SMS
adb shell pm grant com.smsrouter android.permission.READ_SMS
adb shell pm grant com.smsrouter android.permission.SEND_SMS
adb shell pm grant com.smsrouter android.permission.RECEIVE_MMS
adb shell pm grant com.smsrouter android.permission.RECEIVE_WAP_PUSH

# confirm the receivers are registered and check their priority/permission
adb shell dumpsys package com.smsrouter | grep -A3 SmsReceiver

# inspect the outbox / dead letters on device
adb shell run-as com.smsrouter cat files/pending.json
adb shell run-as com.smsrouter cat files/deadletter.json
# a queue corrupted by an old build is quarantined as files/pending.json.corrupt-<ts>

# inspect the persisted config — the source of truth the UI must match
adb shell run-as com.smsrouter cat shared_prefs/sms_router.xml

# exercise the queue→SMTP half without a real SMS: inject a test item, kick the service
adb shell run-as com.smsrouter sh -c 'echo "[{\"id\":\"test-1\",\"subject\":\"[短信转发] adb 注入测试\",\"body\":\"injected via adb\",\"createdAt\":0,\"retries\":0,\"nextAttemptAt\":0,\"smsTs\":0}]" > files/pending.json'
# NOTE: the service is exported=false, so it cannot be kicked with `am`. The running
# worker picks injected items up at its next poll (<=5 min when idle). To force an
# immediate drain, open the app and toggle any switch (auto-save re-applies the
# service state), or wait for the poll. Verified live on the LLD-AL00 on 2026-09-12.
```

Log tags: `SmsReceiver`, `SmsDeliverReceiver`, `SmsForward`, `SmsMarkRead`,
`KeepAliveService`, `MailSender`, `BootReceiver`, `MainActivity`, `PendingQueue`,
`MmsReceiver`, `ComposeSmsActivity`, `HeadlessSmsSendService`.

**Testing note:** you cannot fake an `SMS_RECEIVED` broadcast with `am broadcast`:
the intent carries a `byte[][]` PDU extra that `adb` cannot express, and since the
receiver now requires `BROADCAST_SMS` (a signature permission `adb` does not hold)
a forged broadcast would be rejected anyway. Verify end-to-end by sending a real
SMS to the device; verify the queue→SMTP half with the injection recipe above or
the "发送测试邮件" button; verify mark-as-read by checking
`adb shell content query --uri content://sms --where "read=0"` before/after.

---

## 9. Extending the app — where to change what

| Goal | Touch |
|---|---|
| Filter which messages get forwarded (keywords, sender allow-list) | `SmsForward.kt` before enqueuing (covers both receive paths) |
| Change the mail body / subject format | `SmsForward.kt` |
| Change what gets written to the SMS provider as default app | `SmsDeliverReceiver.writeToProvider` |
| Mark-as-read behaviour / matching rule | `SmsMarkRead.kt` (requires the default-SMS-app role; no-ops otherwise) |
| Add another destination (Telegram, webhook, DingTalk) | New module alongside `MailSender`; dispatch from `KeepAliveService.drainLocked()`. Keep the outbox as the single source of truth so retries still work. |
| Encrypt the stored password | `Prefs` in `Config.kt`. Currently stored as plaintext in SharedPreferences — acceptable only because this is a personal-use app on a personal device (`allowBackup=false` blocks the `adb backup` extraction path). Flag it if the threat model changes. |
| Retry policy / backoff / dead-letter threshold / polling intervals | Constants in `KeepAliveService.Companion` |
| New settings field | `Config.kt` (model + Prefs), `strings.xml`, `activity_main.xml`, `MainActivity` (`readFromUi`/`loadToUi`; switches auto-save — respect the `loading` guard) |

---

## 10. Traps

- **Do not** move network calls into `SmsReceiver` / `SmsDeliverReceiver` / `SmsForward`.
- **Do not** bump Gradle to 9.x, or AGP/Kotlin to versions unverified against each other.
- **Do not** remove the foreground notification or the `KeepAliveService`.
- **Do not** remove `android:permission="android.permission.BROADCAST_SMS"` from the
  SMS receivers — without it any local app can forge SMS and have us email it.
- **Do not** remove the default-app dedup guard in `SmsReceiver` (`isDefaultSmsApp`
  check) — it is what prevents double-forwarding once the role is held.
- **Do not** remove the four default-SMS role components, or the system will stop
  offering the app in the default-SMS picker and mark-as-read becomes impossible.
- **Do not** set any switch programmatically in `MainActivity` outside `loadToUi()`'s
  `loading` guard — switches auto-save on change and would clobber the stored config.
- Kotlin 2.0: `break`/`continue` inside `synchronized { }` / other inline lambdas is
  an experimental feature — use a flag variable (see the worker loop in
  `KeepAliveService`).
- **Do not** delete from `deadletter.json` — it is the last-resort record of
  undelivered messages; only the UI's requeue action may move entries out.
- **Do not** commit `local.properties` (it is gitignored and machine-specific).
- **Do not** edit `D:\Dev\Gradle\gradle.properties` alone — the repo-level
  `gradle.properties` overrides `org.gradle.jvmargs`, so proxy settings must exist
  in both places.
- **Do not** add a PNG launcher icon; `minSdk 28` means adaptive XML icons are
  sufficient.
- JavaMail needs the `proguard-rules.pro` keep rules. If you ever enable
  `isMinifyEnabled` for release, verify a real send still works.
- Building without the proxy produces confusing "could not resolve" / timeout
  errors rather than a clear network message. Check the proxy first.

---

## 11. Signing / release

Debug builds use the auto-generated debug keystore. For a real release:

```bash
keytool -genkey -v -keystore D:\Dev\keystores\sms-router.jks \
        -keyalg RSA -keysize 2048 -validity 10000 -alias smsrouter
```

Then wire a `signingConfigs` block into `app/build.gradle.kts`. Keystores are
gitignored (`*.jks`, `*.keystore`) — never commit one.
