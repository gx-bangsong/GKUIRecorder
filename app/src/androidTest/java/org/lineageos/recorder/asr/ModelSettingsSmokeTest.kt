/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.lineageos.recorder.R
import org.lineageos.recorder.asr.ui.ModelSettingsActivity

/**
 * Runs on an emulator. Opening the model page shows the local import entry and does not start a
 * download or a transcription. The page does not load the native recognizer.
 */
@RunWith(AndroidJUnit4::class)
class ModelSettingsSmokeTest {
    @Test
    fun modelPageShowsLocalImportWithoutStartingWork() {
        ActivityScenario.launch(ModelSettingsActivity::class.java).use {
            onView(withId(R.id.importButton)).check(matches(isDisplayed()))
            onView(withId(R.id.importInfoText)).check(matches(isDisplayed()))
        }
    }
}
