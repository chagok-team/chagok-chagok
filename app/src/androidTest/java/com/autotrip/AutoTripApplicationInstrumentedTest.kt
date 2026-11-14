
package com.autotrip

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autotrip.di.FakeAppContainer
import com.autotrip.fake.FakeTripTracker
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoTripApplicationInstrumentedTest {

    @Test
    fun debugBuildUsesFakeAppContainer() {
        val application =
            ApplicationProvider.getApplicationContext<AutoTripApplication>()

        assertTrue(BuildConfig.USE_FAKE_DATA)
        assertTrue(application.container is FakeAppContainer)
    }

    @Test
    fun containerProvidesAllDependencies() {
        val application =
            ApplicationProvider.getApplicationContext<AutoTripApplication>()

        val container = application.container

        assertNotNull(container.tripTracker)
        assertNotNull(container.tripRepository)
        assertNotNull(container.profileRepository)
        assertNotNull(container.reportRepository)

        assertTrue(container.tripTracker is FakeTripTracker)
    }

    @Test
    fun containerIsSingletonWithinApplication() {
        val application =
            ApplicationProvider.getApplicationContext<AutoTripApplication>()

        val first = application.container
        val second = application.container

        assertSame(first, second)
    }
}
