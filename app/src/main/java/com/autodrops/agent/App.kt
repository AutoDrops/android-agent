package com.autodrops.agent

import android.app.Application
import android.content.Intent
import android.os.Build
import kotlin.system.exitProcess

/**
 * v3.17: installs a global uncaught-exception handler so a tool/UI bug shows a recovery screen and a
 * saved report instead of a silent hard crash. The default handler is still invoked afterwards.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val report = CrashReport.format(
                    thread.name, throwable, System.currentTimeMillis(),
                    appVersion = BuildConfig.VERSION_NAME,
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                    sdkInt = Build.VERSION.SDK_INT
                )
                CrashReport.save(this, report)
                val i = Intent(this, RecoveryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(i)
            }
            // Give the recovery activity a moment to come up, then end this (broken) process.
            runCatching { Thread.sleep(400) }
            if (previous != null) runCatching { previous.uncaughtException(thread, throwable) }
            runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
            exitProcess(10)
        }
    }
}
