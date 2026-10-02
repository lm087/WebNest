package com.mt.webnest

import com.mt.webnest.data.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ManagerSortTest {
    private val alpha =
        WebApp(
            id = 1,
            name = "alpha",
            url = "https://a.test",
            createdAt = 30,
            lastOpenedAt = 10,
            modifiedAt = 20,
        )
    private val beta =
        WebApp(
            id = 2,
            name = "Beta",
            url = "https://b.test",
            createdAt = 10,
            lastOpenedAt = 30,
            modifiedAt = 10,
        )
    private val gamma =
        WebApp(id = 3, name = "Gamma", url = "https://c.test", createdAt = 20, modifiedAt = 30)
    private val apps = listOf(gamma, beta, alpha)

    @Test
    fun repeatedChoiceReversesWhileNewChoiceUsesItsDefault() {
        val name = ManagerSort().choose(SortField.NAME)
        assertTrue(name.ascending)
        assertFalse(name.choose(SortField.NAME).ascending)
        assertFalse(name.choose(SortField.MODIFIED).ascending)
    }

    @Test
    fun eachFieldUsesItsOwnValueAndCanReverse() {
        fun order(field: SortField, ascending: Boolean) =
            ManagerSort(field, ascending).arrange(apps, Locale.US).map { it.id }
        assertEquals(listOf(1L, 2L, 3L), order(SortField.NAME, true))
        assertEquals(listOf(3L, 2L, 1L), order(SortField.NAME, false))
        assertEquals(listOf(2L, 3L, 1L), order(SortField.CREATED, true))
        assertEquals(listOf(1L, 3L, 2L), order(SortField.CREATED, false))
        assertEquals(listOf(2L, 1L, 3L), order(SortField.MODIFIED, true))
        assertEquals(listOf(3L, 1L, 2L), order(SortField.MODIFIED, false))
    }

    @Test
    fun pinnedAppsAlwaysComeFirstAndNeverOpenedAlwaysComeLastInEachGroup() {
        for (ascending in listOf(true, false)) {
            val list = apps + beta.copy(id = 4, pinned = true) + gamma.copy(id = 5, pinned = true)
            val sorted = ManagerSort(SortField.OPENED, ascending).arrange(list)
            assertEquals(listOf(4L, 5L), sorted.take(2).map { it.id })
            assertEquals(3L, sorted.last().id)
            assertEquals(
                if (ascending) listOf(1L, 2L) else listOf(2L, 1L),
                sorted.subList(2, 4).map { it.id },
            )
        }
    }

    @Test
    fun customOrderRetainsPinnedGroupsAndCannotReverse() {
        val custom = ManagerSort().choose(SortField.CUSTOM)
        assertEquals(custom, custom.choose(SortField.CUSTOM))
        val records =
            listOf(
                alpha.copy(position = 2),
                beta.copy(position = 0),
                gamma.copy(position = 1, pinned = true),
            )
        assertEquals(listOf(3L, 2L, 1L), custom.arrange(records).map { it.id })
    }

    @Test
    fun nameTiesIgnoreCaseAndHaveStableIds() {
        val result =
            ManagerSort(SortField.NAME, true)
                .arrange(listOf(alpha.copy(id = 7, name = "ALPHA"), alpha), Locale.US)
        assertEquals(listOf(1L, 7L), result.map { it.id })
    }
}
