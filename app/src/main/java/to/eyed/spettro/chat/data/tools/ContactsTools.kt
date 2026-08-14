package to.eyed.spettro.chat.data.tools

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/** contacts-search: look up names, phone numbers, and emails by name. */
internal class ContactsTools(private val context: Context) {

    fun search(argumentsJson: String): ToolResult {
        val name = ToolArgs.string(argumentsJson, "name")?.trim()
            ?: return ToolResult("search requires a name", isError = true)
        if (name.isEmpty()) return ToolResult("search requires a non-empty name", isError = true)
        val max = (ToolArgs.int(argumentsJson, "max_results") ?: 5).coerceIn(1, 10)

        val resolver = context.contentResolver
        val filterUri = Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_FILTER_URI,
            Uri.encode(name),
        )
        data class Hit(val id: Long, val display: String)
        val hits = mutableListOf<Hit>()
        resolver.query(
            filterUri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            null, null,
            ContactsContract.Contacts.DISPLAY_NAME,
        )?.use { cursor ->
            while (cursor.moveToNext() && hits.size < max) {
                hits += Hit(cursor.getLong(0), cursor.getString(1) ?: continue)
            }
        } ?: return ToolResult("contacts unavailable on this device", isError = true)

        if (hits.isEmpty()) return ToolResult("No contacts matching \"$name\".")

        val lines = hits.map { hit ->
            val phones = mutableListOf<String>()
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(hit.id.toString()),
                null,
            )?.use { c -> while (c.moveToNext() && phones.size < 3) phones += c.getString(0) }
            val emails = mutableListOf<String>()
            resolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                arrayOf(hit.id.toString()),
                null,
            )?.use { c -> while (c.moveToNext() && emails.size < 3) emails += c.getString(0) }
            buildString {
                append(hit.display)
                if (phones.isNotEmpty()) append(" — ").append(phones.distinct().joinToString(", "))
                if (emails.isNotEmpty()) append(" — ").append(emails.distinct().joinToString(", "))
            }
        }
        return ToolResult(lines.joinToString("\n"))
    }
}
