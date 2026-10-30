package org.funkgateway.mobile.frn

object FrnChatStore {
    private const val MAX_MESSAGES = 300

    data class Entry(
        val time: Long,
        val sender: String,
        val senderId: String,
        val message: String,
        val privateMessage: Boolean,
        val outgoing: Boolean = false
    )

    private val messages = ArrayDeque<Entry>()
    private var appContext: android.content.Context? = null

    @Synchronized
    fun initialize(context: android.content.Context) {
        if (appContext != null) return
        val application = context.applicationContext
        messages.addAll(FrnChatPersistence.load(application))
        while (messages.size > MAX_MESSAGES) messages.removeFirst()
        appContext = application
    }

    private fun savePrivateHistory() {
        val context = appContext ?: return
        FrnChatPersistence.save(context, messages.toList())
    }


    @Synchronized
    fun add(message: FrnChatMessage) {
        messages.addLast(
            Entry(
                time = System.currentTimeMillis(),
                sender = message.sender,
                senderId = message.senderId,
                message = message.message,
                privateMessage = message.privateMessage
            )
        )
        while (messages.size > MAX_MESSAGES) {
            messages.removeFirst()
        }
        savePrivateHistory()
    }

    @Synchronized
    fun addOutgoing(recipientId: String, text: String, privateMessage: Boolean) {
        messages.addLast(
            Entry(
                time = System.currentTimeMillis(),
                sender = "Du",
                senderId = recipientId,
                message = text,
                privateMessage = privateMessage,
                outgoing = true
            )
        )
        while (messages.size > MAX_MESSAGES) {
            messages.removeFirst()
        }
        savePrivateHistory()
    }

    @Synchronized
    fun all(): List<Entry> = messages.toList()

    @Synchronized
    fun room(): List<Entry> =
        messages.filter { !it.privateMessage }

    @Synchronized
    fun privateWith(senderId: String): List<Entry> =
        messages.filter {
            it.privateMessage &&
                it.senderId.equals(senderId, ignoreCase = true)
        }

    @Synchronized
    fun clear() {
        messages.clear()
    }
}
