package com.v1per.synthx.ui

/**
 * The tab the bottom bar lights: [currentRoute] when it is a tab root, otherwise the nearest
 * tab root at or below the visible entry in [backStackRoutes] (oldest first). Entries above the
 * visible one are ignored. Null when no tab root is on the stack.
 */
internal fun selectedTabRoute(currentRoute: String?, backStackRoutes: List<String?>, tabRoutes: Set<String>): String? {
    if (currentRoute == null) return null
    if (currentRoute in tabRoutes) return currentRoute
    val visibleIndex = backStackRoutes.lastIndexOf(currentRoute)
    val belowAndAt = if (visibleIndex >= 0) backStackRoutes.subList(0, visibleIndex + 1) else backStackRoutes
    return belowAndAt.lastOrNull { it != null && it in tabRoutes }
}
