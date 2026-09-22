package app.kotowski.keepsimalive.data

enum class FrequencyType {
    EVERY_N_DAYS,
    MONTHLY,

    // Send in the user-picked months (the set lives on the config; the schedule sends on
    // dayOfMonth of each picked month, with the monthly 29-31 clamping).
    SELECTED_MONTHS,
}

enum class EndType {
    NEVER,
    AFTER_N_SENDS,
    ON_DATE,
}
