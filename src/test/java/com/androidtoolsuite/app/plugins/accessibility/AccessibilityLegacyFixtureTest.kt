package com.androidtoolsuite.app.plugins.accessibility

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityLegacyFixtureTest {
    @Test fun fixturesCoverEmptyAndMultipleFavorites() {
        val empty = fixture("accessibility-settings-empty.json")
        val populated = fixture("accessibility-settings.json")
        assertEquals(0, empty.getJSONArray("favorites").length())
        assertFalse(empty.getBoolean("autoGrant"))
        assertTrue(populated.getJSONArray("favorites").length() > 1)
        assertTrue(populated.getBoolean("autoGrant"))
    }

    private fun fixture(name: String) = JSONObject(
        checkNotNull(javaClass.getResource("/legacy/$name")).readText(),
    )
}
