package com.mt.webnest.data

import java.text.Collator
import java.util.Locale

enum class SortField(val label: String, val ascendingByDefault: Boolean) {
    NAME("Name", true), CREATED("Created", false), OPENED("Last opened", false), MODIFIED("Modified", false), CUSTOM("Custom", true)
}

data class ManagerSort(val field: SortField = SortField.CREATED, val ascending: Boolean = false) {
    fun choose(next: SortField) = if (next == SortField.CUSTOM) ManagerSort(next, true) else if (next == field) copy(ascending = !ascending) else ManagerSort(next, next.ascendingByDefault)

    fun arrange(apps: List<WebApp>, locale: Locale = Locale.getDefault()): List<WebApp> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        return apps.sortedWith { a, b ->
            if (a.pinned != b.pinned) return@sortedWith if (a.pinned) -1 else 1
            if (field == SortField.OPENED && (a.lastOpenedAt == null || b.lastOpenedAt == null)) if (a.lastOpenedAt != b.lastOpenedAt) return@sortedWith if (a.lastOpenedAt == null) 1 else -1
            val order = when (field) {
                SortField.NAME -> collator.compare(a.name, b.name)
                SortField.CREATED -> a.createdAt.compareTo(b.createdAt)
                SortField.OPENED -> (a.lastOpenedAt ?: 0).compareTo(b.lastOpenedAt ?: 0)
                SortField.MODIFIED -> a.modifiedAt.compareTo(b.modifiedAt)
                SortField.CUSTOM -> a.position.compareTo(b.position)
            }
            (if (ascending) order else -order).takeIf { it != 0 } ?: a.id.compareTo(b.id)
        }
    }
}