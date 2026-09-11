package com.smsrouter

import java.util.Date
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeUtility

object MailSender {
    private const val TAG = "MailSender"

    /**
     * 同步发送一封纯文本邮件。必须在后台线程调用。
     * @throws Throwable 配置错误、连不上服务器、认证失败等都会抛出
     */
    fun send(config: SmtpConfig, subject: String, body: String) {
        require(config.isComplete) { "SMTP 配置不完整" }

        val props = Properties().apply {
            put("mail.smtp.host", config.host.trim())
            put("mail.smtp.port", config.port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "20000")
            put("mail.smtp.writetimeout", "20000")
            // 部分服务器对 EHLO 的域名很敏感，显式指定可减少 550 错误
            put("mail.smtp.localhost", "localhost")

            when (config.security) {
                SEC_SSL -> {
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.socketFactory.port", config.port.toString())
                    put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                    put("mail.smtp.socketFactory.fallback", "false")
                }
                SEC_STARTTLS -> {
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.starttls.required", "true")
                }
                else -> {
                    put("mail.smtp.ssl.enable", "false")
                    put("mail.smtp.starttls.enable", "false")
                }
            }
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication =
                PasswordAuthentication(config.user.trim(), config.pass)
        })

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.from.trim()))
            setRecipients(
                Message.RecipientType.TO,
                InternetAddress.parse(config.to.replace("；", ";").replace("，", ","))
            )
            setSubject(MimeUtility.encodeText(subject, "UTF-8", "B"), "UTF-8")
            setText(body, "UTF-8")
            setSentDate(Date())
        }

        Transport.send(message)
    }
}
