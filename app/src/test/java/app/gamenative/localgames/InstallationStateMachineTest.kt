package app.gamenative.localgames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallationStateMachineTest {
    @Test
    fun normalInstallerJourneyIsAllowed() {
        val states = listOf(
            InstallationState.SOURCE_STAGED,
            InstallationState.CONTAINER_CREATING,
            InstallationState.READY_TO_LAUNCH,
            InstallationState.INSTALLER_RUNNING,
            InstallationState.AWAITING_RESULT,
            InstallationState.CANDIDATE_SELECTION,
            InstallationState.COMPLETED,
        )

        states.zipWithNext().forEach { (from, to) ->
            assertTrue("Expected $from -> $to", InstallationStateMachine.canTransition(from, to))
        }
    }

    @Test
    fun completedAndCancelledSessionsAreTerminal() {
        InstallationState.entries.forEach { target ->
            assertFalse(InstallationStateMachine.canTransition(InstallationState.COMPLETED, target))
            assertFalse(InstallationStateMachine.canTransition(InstallationState.CANCELLED, target))
        }
    }

    @Test
    fun transitionRecordsPreviousStateAndError() {
        val session = testSession(InstallationState.CONTAINER_CREATING)
        val failed = session.transitionTo(
            next = InstallationState.FAILED,
            now = 42L,
            error = "container failed",
        )

        assertEquals(InstallationState.FAILED, failed.state)
        assertEquals(InstallationState.CONTAINER_CREATING, failed.previousState)
        assertEquals(42L, failed.updatedAt)
        assertEquals("container failed", failed.lastError)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidTransitionIsRejected() {
        testSession(InstallationState.SOURCE_STAGED)
            .transitionTo(InstallationState.COMPLETED)
    }

    private fun testSession(state: InstallationState) = InstallationSession(
        id = "session-id",
        title = "Test game",
        sourceUri = "content://installer",
        sourceName = "setup.exe",
        installerType = InstallerType.EXE,
        managedInstallerPath = "/managed/setup.exe",
        installerRelativePath = "setup.exe",
        state = state,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
