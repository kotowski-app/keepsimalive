package app.kotowski.keepsimalive.util

// ITU-T E.164: '+' followed by 7 to 15 digits, first digit non-zero.
fun isValidE164(text: String): Boolean {
    if (text.length < 8 || text.length > 16) return false
    if (!text.startsWith("+")) return false
    for (i in 1 until text.length) {
        if (text[i] !in '0'..'9') return false
    }
    return text[1] != '0'
}
