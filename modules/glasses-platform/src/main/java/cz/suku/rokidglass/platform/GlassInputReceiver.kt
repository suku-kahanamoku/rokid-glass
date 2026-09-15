package cz.suku.rokidglass.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class GlassInputReceiver(
    private val onInput: (GlassInput) -> Unit,
) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val input = when (intent?.action) {
            ACTION_BUTTON_CLICK,
            ACTION_LEGACY_BUTTON_CLICK,
            ACTION_SWIPE_FORWARD,
            ACTION_SWIPE_BACK,
            -> GlassInput.SUBMIT_TRANSCRIPT

            ACTION_BUTTON_DOUBLE_CLICK,
            ACTION_LEGACY_BUTTON_DOUBLE_CLICK,
            -> GlassInput.EXIT_APP

            else -> return
        }

        onInput(input)
        if (isOrderedBroadcast) abortBroadcast()
    }

    companion object {
        const val ACTION_BUTTON_CLICK = "com.rokid.glass3.action.button.CLICK"
        const val ACTION_BUTTON_DOUBLE_CLICK = "com.rokid.glass3.action.button.DOUBLE_CLICK"
        const val ACTION_LEGACY_BUTTON_CLICK =
            "com.android.action.ACTION_SPRITE_BUTTON_CLICK"
        const val ACTION_LEGACY_BUTTON_DOUBLE_CLICK =
            "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK"
        const val ACTION_SWIPE_FORWARD =
            "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"
        const val ACTION_SWIPE_BACK =
            "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"

        val actions = listOf(
            ACTION_BUTTON_CLICK,
            ACTION_BUTTON_DOUBLE_CLICK,
            ACTION_LEGACY_BUTTON_CLICK,
            ACTION_LEGACY_BUTTON_DOUBLE_CLICK,
            ACTION_SWIPE_FORWARD,
            ACTION_SWIPE_BACK,
        )
    }
}
