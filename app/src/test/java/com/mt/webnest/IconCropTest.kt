package com.mt.webnest

import com.mt.webnest.ui.IconCrop
import org.junit.Assert.*
import org.junit.Test

class IconCropTest {
    @Test fun wideAndTallPhotosKeepAnUndistortedSquare() {
        assertEquals(IconCrop.Window(100, 0, 100), IconCrop.window(300, 100, .5f, .5f, 1f))
        assertEquals(IconCrop.Window(0, 100, 100), IconCrop.window(100, 300, .5f, .5f, 1f))
    }
    @Test fun draggingCannotExposePixelsOutsideTheImage() {
        assertEquals(IconCrop.Window(200, 0, 100), IconCrop.window(300, 100, 2f, -1f, 1f))
        assertEquals(IconCrop.Window(0, 0, 100), IconCrop.window(300, 100, -1f, 2f, 1f))
    }
    @Test fun zoomNarrowsTheSourceWindowAndKeepsItsCenter() {
        assertEquals(IconCrop.Window(125, 25, 50), IconCrop.window(300, 100, .5f, .5f, 2f))
    }
}
