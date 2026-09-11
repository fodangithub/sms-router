package com.smsrouter

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * 仅为满足「默认短信应用」角色要求而存在(ACTION_SENDTO, scheme sms/smsto/mms/mmsto)。
 * 本应用不提供发短信界面:提示后直接关闭。
 */
class ComposeSmsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "opened via ${intent?.action}; sending is not supported, finishing")
        Toast.makeText(this, getString(R.string.msg_send_not_supported), Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        const val TAG = "ComposeSmsActivity"
    }
}
