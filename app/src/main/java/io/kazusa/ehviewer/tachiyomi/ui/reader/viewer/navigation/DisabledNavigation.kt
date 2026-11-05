package io.kazusa.ehviewer.tachiyomi.ui.reader.viewer.navigation

import io.kazusa.ehviewer.tachiyomi.ui.reader.viewer.ViewerNavigation

/**
 * Visualization of default state without any inversion
 * +---+---+---+
 * | M | M | M |   P: Previous
 * +---+---+---+
 * | M | M | M |   M: Menu
 * +---+---+---+
 * | M | M | M |   N: Next
 * +---+---+---+
*/
object DisabledNavigation : ViewerNavigation() {
    override val originalRegions = emptyList<Region>()
}
