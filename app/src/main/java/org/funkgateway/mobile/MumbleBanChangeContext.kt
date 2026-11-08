package org.funkgateway.mobile

/**
 * Hält den lokalen Sicherheitskontext einer vorbereiteten
 * Mumble-Bannlistenänderung fest.
 *
 * Die Daten sind nur innerhalb der aktuellen App-Sitzung gültig.
 * Sie stellen keine serverseitige Transaktion oder Sperre dar.
 */
internal data class MumbleBanChangeContext(
    val connectionGeneration: Long,
    val server: Pair<String, Int>,
    val banListVersion: Long
)
