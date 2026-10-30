package org.funkgateway.mobile.frn

import android.content.Context

object FrnHostTransferSafety {
    private const val PREFS = "funkgateway_host_transfer_safety"
    private const val KEY_PENDING = "critical_transfer_pending"
    private const val KEY_GROUP = "critical_transfer_group"
    private const val KEY_TRANSFER = "critical_transfer_id"

    @Synchronized
    fun markCriticalTransfer(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PENDING, true)
            .commit()
    }

    @Synchronized
    fun markCriticalTransfer(
        context: Context,
        groupId: String,
        transferId: String
    ): Boolean {
        if (groupId.isBlank() || transferId.isBlank()) return false

        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (prefs.getBoolean(KEY_PENDING, false)) return false

        return prefs.edit()
            .putString(KEY_GROUP, groupId)
            .putString(KEY_TRANSFER, transferId)
            .putBoolean(KEY_PENDING, true)
            .commit()
    }

    @Synchronized
    fun criticalGroupId(context: Context): String? {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (!prefs.getBoolean(KEY_PENDING, false)) return null
        return prefs.getString(KEY_GROUP, null)
    }

    @Synchronized
    fun criticalTransferId(context: Context): String? {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (!prefs.getBoolean(KEY_PENDING, false)) return null
        return prefs.getString(KEY_TRANSFER, null)
    }

    @Synchronized
    fun hasCriticalTransfer(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PENDING, false)
    }
}
