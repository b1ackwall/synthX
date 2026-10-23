package com.v1per.synthx.ui.screens.home

import androidx.lifecycle.ViewModel
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class HomeViewModelTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private class UnrelatedViewModel : ViewModel()

    @Test fun factoryBuildsAHomeViewModel() {
        val created = HomeViewModel.Factory().create(HomeViewModel::class.java)
        assertIs<HomeViewModel>(created)
    }

    @Test fun factoryRejectsAnUnrelatedViewModelClass() {
        val error = assertFailsWith<IllegalArgumentException> {
            HomeViewModel.Factory().create(UnrelatedViewModel::class.java)
        }
        assertEquals("Unknown ViewModel class: ${UnrelatedViewModel::class.java.name}", error.message)
    }
}
