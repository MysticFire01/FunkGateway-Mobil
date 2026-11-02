package org.funkgateway.mobile

data class MumblePublicServer(
    val name: String,
    val host: String,
    val port: Int = 64738,
    val country: String = "Unbekannt",
    val countryCode: String = "",
    val source: String = "official",
    val username: String = ""
) {
    val id: String get() = "${host.lowercase()}:$port"
}

object MumblePublicServers {
    private const val URL =
        "https://publist.mumble.info/v1/list?version=1.5.939"

    fun load(): List<MumblePublicServer> {
        val connection = java.net.URL(URL).openConnection()
            as javax.net.ssl.HttpsURLConnection

        connection.connectTimeout = 10000
        connection.readTimeout = 20000
        connection.setRequestProperty(
            "User-Agent", "Mumble/1.5.939 FunkGateway-Mobile"
        )
        connection.setRequestProperty(
            "Accept", "application/xml,text/xml;q=0.9,*/*;q=0.8"
        )

        try {
            check(connection.responseCode == 200) {
                "Mumble-Serverliste: HTTP ${connection.responseCode}"
            }

            val parser = android.util.Xml.newPullParser()
            val servers = linkedMapOf<String, MumblePublicServer>()

            connection.inputStream.buffered().use { input ->
                parser.setInput(input, "UTF-8")

                var event = parser.eventType
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG &&
                        parser.name == "server"
                    ) {
                        fun attribute(name: String): String =
                            parser.getAttributeValue(null, name)?.trim().orEmpty()

                        val host = attribute("ip")
                        if (host.isNotEmpty()) {
                            val port = attribute("port").toIntOrNull()
                                ?.takeIf { it in 1..65535 } ?: 64738

                            val server = MumblePublicServer(
                                name = attribute("name").ifEmpty { host },
                                host = host,
                                port = port,
                                country = attribute("country")
                                    .ifEmpty { "Unbekannt" },
                                countryCode = attribute("country_code").uppercase()
                            )

                            servers.putIfAbsent(server.id, server)
                        }
                    }
                    event = parser.next()
                }
            }

            check(servers.isNotEmpty()) {
                "Die offizielle Mumble-Serverliste ist leer."
            }

            return servers.values.toList()
        } finally {
            connection.disconnect()
        }
    }
}
