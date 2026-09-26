package com.local.assistant.device

import android.Manifest
import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.local.assistant.memory.tools.Contact
import com.local.assistant.memory.tools.ContactBook
import com.local.assistant.memory.tools.ContactPhone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's contacts, read fresh for each call or message — a few thousand rows at most, and
 * nothing is copied into the app's own storage. Asks for access the first time it is needed.
 */
class AndroidContacts(
    private val context: Context,
    private val permissions: PermissionBroker,
) : ContactBook {

    override suspend fun all(): List<Contact>? {
        if (!permissions.request(Manifest.permission.READ_CONTACTS)) return null
        return withContext(Dispatchers.IO) { read() }
    }

    private fun read(): List<Contact> {
        val resolver = context.contentResolver
        val names = linkedMapOf<Long, String>()
        val phones = mutableMapOf<Long, MutableList<ContactPhone>>()
        val emails = mutableMapOf<Long, MutableList<String>>()

        resolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.IS_PRIMARY),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val number = cursor.getString(2)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                names.getOrPut(id) { cursor.getString(1).orEmpty() }
                phones.getOrPut(id) { mutableListOf() } += ContactPhone(
                    number = number,
                    primary = cursor.getInt(4) != 0,
                    mobile = cursor.getInt(3) == Phone.TYPE_MOBILE,
                )
            }
        }
        resolver.query(
            Email.CONTENT_URI,
            arrayOf(Email.CONTACT_ID, Email.DISPLAY_NAME, Email.ADDRESS),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val address = cursor.getString(2)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                names.getOrPut(id) { cursor.getString(1).orEmpty() }
                emails.getOrPut(id) { mutableListOf() } += address
            }
        }
        return names.filterValues { it.isNotBlank() }.map { (id, name) ->
            Contact(
                name = name,
                // The same number is often stored twice, once with the country code.
                phones = phones[id].orEmpty().distinctBy { it.number.filter(Char::isDigit).takeLast(10) },
                emails = emails[id].orEmpty().distinct(),
            )
        }
    }
}
