package app.kotowski.keepsimalive.util

data class NumberValidationResult(
    val value: Int,
    val isValid: Boolean,
)

// Returns the previous value unchanged when the input is empty (treated as "in edit" state).
fun validateConfigNumber(
    text: String,
    current: Int,
    min: Int,
    max: Int,
): NumberValidationResult {
    if (text.isEmpty()) {
        return NumberValidationResult(current, true)
    }
    val v = text.toIntOrNull()
    if (v == null) {
        return NumberValidationResult(current, false)
    }
    return if (v in min..max) {
        NumberValidationResult(v, true)
    } else {
        NumberValidationResult(current, false)
    }
}
