package com.myra.assistant.ai

import com.myra.assistant.model.AppCommand

object CommandParser {

    private val OPEN_VERBS = listOf("kholo", "khol do", "khol", "open", "launch", "start karo", "chalu karo")
    private val CLOSE_VERBS = listOf("band karo", "band kar do", "band kro", "close", "exit", "kill")
    private val FILLER = setOf(
        "karo", "kro", "kar", "do", "please", "pls", "app", "ko", "ji", "mera", "meri", "mujhe",
        "the", "a", "my", "zara", "na", "yaar", "jaldi", "ab", "aur"
    )
    private val PRIME_WORDS = listOf(
        "close friend", "meri jaan", "my love", "meri gf", "my girlfriend", "my babe", "jaan",
        "prime contact", "first contact", "second contact", "third contact",
        "pehla contact", "dusra contact", "teesra contact"
    )

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    fun parse(raw: String): AppCommand? {
        val text = raw.trim().trimEnd('.', '!', '?', ',', '।').trim()
        if (text.isEmpty()) return null
        val t = text.lowercase()

        // --- device toggles
        if (t.contains("volume")) {
            if (re("badha|up|increase|zyada|jyada|high|louder").containsMatchIn(t)) return AppCommand("VOLUME_UP")
            if (re("kam|down|decrease|ghata|low|lower|halka").containsMatchIn(t)) return AppCommand("VOLUME_DOWN")
        }
        if (re("torch|flashlight|flash light").containsMatchIn(t)) {
            return when {
                re("\\boff\\b|band|bujha|bund").containsMatchIn(t) -> AppCommand("FLASHLIGHT_OFF")
                re("\\bon\\b|chalu|jala|start").containsMatchIn(t) -> AppCommand("FLASHLIGHT_ON")
                else -> null
            }
        }
        if (re("wi-?fi").containsMatchIn(t)) {
            if (re("\\boff\\b|band").containsMatchIn(t)) return AppCommand("WIFI_OFF")
            if (re("\\bon\\b|chalu").containsMatchIn(t)) return AppCommand("WIFI_ON")
        }
        if (t.contains("bluetooth")) {
            if (re("\\boff\\b|band").containsMatchIn(t)) return AppCommand("BLUETOOTH_OFF")
            if (re("\\bon\\b|chalu").containsMatchIn(t)) return AppCommand("BLUETOOTH_ON")
        }

        // --- prime contacts
        if (PRIME_WORDS.any { t.contains(it) }) {
            val idx = when {
                re("second|dusra|dusre|2nd").containsMatchIn(t) -> 1
                re("third|teesra|teesre|3rd").containsMatchIn(t) -> 2
                else -> 0
            }
            if (re("message|msg|sms|text").containsMatchIn(t))
                return AppCommand("PRIME_MSG", mapOf("index" to idx.toString()))
            if (re("call|phone|dial|ring").containsMatchIn(t))
                return AppCommand("PRIME_CALL", mapOf("index" to idx.toString()))
        }

        // --- open / close app (before WhatsApp/call patterns)
        if (CLOSE_VERBS.any { t.contains(it) } && !t.contains(" ko ")) {
            val name = stripWords(t, CLOSE_VERBS)
            if (name.isNotBlank()) return AppCommand("CLOSE_APP", mapOf("app_name" to name))
        }
        if (OPEN_VERBS.any { t.contains(it) }) {
            val name = stripWords(t, OPEN_VERBS)
            if (name.isNotBlank()) return AppCommand("OPEN_APP", mapOf("app_name" to name))
        }

        // --- WhatsApp
        re("^whatsapp\\s+(?:video\\s+)?call\\s+(?:karo\\s+)?(.+?)(?:\\s+ko)?$").find(text)?.let {
            return AppCommand("WHATSAPP_CALL", mapOf("name" to it.groupValues[1].trim()))
        }
        re("^(.+?)\\s+ko\\s+whatsapp\\s+(?:video\\s+)?call(?:\\s+karo)?$").find(text)?.let {
            return AppCommand("WHATSAPP_CALL", mapOf("name" to it.groupValues[1].trim()))
        }
        re("^whatsapp\\s+(?:karo|bhejo|message|msg|send)\\s+(.+?)\\s+ko(?:\\s+(.*))?$").find(text)?.let {
            return AppCommand("WHATSAPP_MSG", mapOf("name" to it.groupValues[1].trim(), "message" to it.groupValues[2].trim()))
        }
        re("^(.+?)\\s+ko\\s+whatsapp(?:\\s+(?:message|msg|par|pe))?(?:\\s+(?:karo|bhejo|kar do|send))?(?:\\s+(.*))?$").find(text)?.let {
            return AppCommand("WHATSAPP_MSG", mapOf("name" to it.groupValues[1].trim(), "message" to it.groupValues[2].trim()))
        }

        // --- SMS
        re("^(?:sms|message|msg|text)\\s+(?:bhejo|karo|send)\\s+(.+?)\\s+ko(?:\\s+(.*))?$").find(text)?.let {
            return AppCommand("SMS", mapOf("name" to it.groupValues[1].trim(), "message" to it.groupValues[2].trim()))
        }
        re("^(.+?)\\s+ko\\s+(?:sms|message|msg|text)\\s+(?:bhejo|karo|kar do|send)(?:\\s+(?:ki|ke)?\\s*(.*))?$").find(text)?.let {
            return AppCommand("SMS", mapOf("name" to it.groupValues[1].trim(), "message" to it.groupValues[2].trim()))
        }
        re("^send\\s+(?:an?\\s+)?(?:sms|message|text)\\s+to\\s+(\\w+(?:\\s\\w+)?)(?:\\s+(?:saying|that|says)\\s+(.+))?$").find(text)?.let {
            return AppCommand("SMS", mapOf("name" to it.groupValues[1].trim(), "message" to it.groupValues[2].trim()))
        }

        // --- Call
        re("^(?:please\\s+)?(?:call|dial|phone)\\s+(?:to\\s+)?(.+)$").find(text)?.let { m ->
            val who = cleanName(m.groupValues[1])
            if (who.isNotBlank()) return callCmd(who)
        }
        re("^(.+?)\\s+ko\\s+(?:phone|call|dial)(?:\\s+(?:karo|kro|kar do|lagao|laga do))?$").find(text)?.let { m ->
            val who = cleanName(m.groupValues[1])
            if (who.isNotBlank()) return callCmd(who)
        }
        return null
    }

    private fun callCmd(who: String): AppCommand {
        val digits = who.filter { it.isDigit() || it == '+' }
        return if (digits.length >= 6 && digits.length >= who.replace(" ", "").length - 1)
            AppCommand("CALL", mapOf("number" to digits))
        else AppCommand("CALL", mapOf("name" to who))
    }

    private fun cleanName(s: String): String =
        s.split(Regex("\\s+")).filter { it.lowercase() !in FILLER }.joinToString(" ").trim()

    private fun stripWords(t: String, verbs: List<String>): String {
        var s = t
        verbs.sortedByDescending { it.length }.forEach { s = s.replace(it, " ") }
        return s.split(Regex("\\s+")).filter { it.isNotBlank() && it !in FILLER }.joinToString(" ").trim()
    }
}
