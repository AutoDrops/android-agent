package com.autodrops.agent

import android.annotation.TargetApi
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: one tap opens DRON.exe into a quick screen-OCR "ask about my screen" flow.
 * It only OPENS the app and routes to the gated capture flow — it never captures silently in the background.
 */
@TargetApi(Build.VERSION_CODES.N)
class QuickTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("tile_action", "quick_screen")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startActivityAndCollapse(
            android.app.PendingIntent.getActivity(this, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE)
        ) else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }
}
