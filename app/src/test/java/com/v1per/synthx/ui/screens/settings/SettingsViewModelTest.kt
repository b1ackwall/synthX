package com.v1per.synthx.ui.screens.settings

import androidx.lifecycle.ViewModel
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SettingsViewModelTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private class UnrelatedViewModel : ViewModel()

    @Test fun factoryBuildsASettingsViewModel() {
        val created = SettingsViewModel.Factory().create(SettingsViewModel::class.java)
        assertIs<SettingsViewModel>(created)
    }

    @Test fun factoryRejectsAnUnrelatedViewModelClass() {
        val error = assertFailsWith<IllegalArgumentException> {
            SettingsViewModel.Factory().create(UnrelatedViewModel::class.java)
        }
        assertEquals("Unknown ViewModel class: ${UnrelatedViewModel::class.java.name}", error.message)
    }
}
