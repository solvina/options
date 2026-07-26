package cz.solvina.options.adapters.outbound.ibkr.account

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class IbkrPositionsRegistryTest {
    @Test
    fun `initial download gate is not ready until accountDownloadEnd`() =
        runTest {
            val registry = IbkrPositionsRegistry()

            assertFalse(registry.awaitInitialDownload(50.milliseconds), "cold feed must not report ready")

            registry.onAccountDownloadEnd()

            assertTrue(registry.awaitInitialDownload(5.seconds), "ready once the download completed")
        }

    @Test
    fun `disconnect re-arms the gate so a reconnect must re-download`() =
        runTest {
            val registry = IbkrPositionsRegistry()
            registry.onAccountDownloadEnd()
            assertTrue(registry.awaitInitialDownload(5.seconds))

            registry.onDisconnect()

            assertFalse(registry.awaitInitialDownload(50.milliseconds), "post-disconnect feed is cold again")
        }
}
