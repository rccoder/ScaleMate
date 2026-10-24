package app.scalemate

// Food entry must not be interrupted by an incoming scale measurement.
internal fun pausesScaleListening(page: String): Boolean =
    page.startsWith("food")
