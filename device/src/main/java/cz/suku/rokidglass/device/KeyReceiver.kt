package cz.suku.rokidglass.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

enum class GlassInput {
    NEXT_PROFILE,
    PREVIOUS_PROFILE,
    EXIT_APP,
}

class KeyReceiver(
    private val onInput: (GlassInput) -> Unit,
) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val input = when (intent?.action) {
            ACTION_SWIPE_FORWARD -> GlassInput.NEXT_PROFILE
            ACTION_SWIPE_BACK -> GlassInput.PREVIOUS_PROFILE
            ACTION_DOUBLE_CLICK -> GlassInput.EXIT_APP
            else -> return
        }

        onInput(input)
        if (isOrderedBroadcast) abortBroadcast()
    }

    companion object {
        const val ACTION_DOUBLE_CLICK =
            "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK"
        const val ACTION_SWIPE_FORWARD =
            "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"
        const val ACTION_SWIPE_BACK =
            "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"

        val ACTIONS = listOf(
            ACTION_DOUBLE_CLICK,
            ACTION_SWIPE_FORWARD,
            ACTION_SWIPE_BACK,
        )
    }
}
