package com.kabshah.delivra.data

import android.content.Context
import android.provider.ContactsContract
import com.kabshah.delivra.bridge.WhatsAppContact

/**
 * Reads the device's saved contacts (name + number + photo thumbnail) so the
 * New Message screen can suggest every contact — including people the user has
 * never chatted with on WhatsApp (which Baileys' synced list misses).
 *
 * Numbers are normalized to digits-only keys so a Baileys JID
 * (e.g. 923352277929@s.whatsapp.net) can be merged with the same person saved
 * locally as "+92 335-2277929".
 */
class PhoneContactsRepository(private val context: Context) {

    fun load(): List<WhatsAppContact> {
        val byKey = LinkedHashMap<String, WhatsAppContact>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
        )

        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx)?.trim() ?: continue
                    val number = cursor.getString(numberIdx)?.trim() ?: continue
                    if (number.isEmpty()) continue
                    val photo = cursor.getString(photoIdx)
                    val key = normalize(number)
                    if (key.length < 7) continue

                    val existing = byKey[key]
                    // Keep first occurrence per number (contacts sorted by name),
                    // but backfill photo if the first row lacked one.
                    if (existing == null) {
                        byKey[key] = WhatsAppContact(
                            jid = "$key@s.whatsapp.net",
                            name = name,
                            photoUri = photo
                        )
                    } else if (existing.photoUri == null && photo != null) {
                        byKey[key] = existing.copy(photoUri = photo)
                    }
                }
            }
        }

        return byKey.values.toList()
    }

    companion object {
        /** Strip everything except digits. */
        fun normalize(raw: String): String = raw.filter { it.isDigit() }

        /**
         * Matching key tolerant of country-code differences:
         * "923352277929" and "03352277929" both → last 9 digits.
         */
        fun matchKey(jidOrNumber: String): String {
            val digits = normalize(jidOrNumber.substringBefore('@'))
            return if (digits.length > 9) digits.takeLast(9) else digits
        }

        /** True when two numbers/JIDs plausibly belong to the same person. */
        fun samePerson(a: String, b: String): Boolean {
            val ka = matchKey(a)
            val kb = matchKey(b)
            return ka.isNotEmpty() && ka == kb
        }
    }
}
