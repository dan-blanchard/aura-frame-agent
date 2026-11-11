package net.blanchard.frameagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class MorningTest {

    private val leave = Instant.parse("2026-11-02T12:30:00Z") // 7:30 EST

    private fun kid(cleared: Boolean = false) = MorningKid("Alex", "#2bb3a3", leave, "", cleared)

    private fun stageAt(before: Duration, cleared: Boolean = false, dismissed: Boolean = false) =
        MorningClock.stage(kid(cleared), leave.minus(before), dismissed)

    @Test
    fun `stages change exactly on their boundaries`() {
        assertEquals(KidStage.WAITING, stageAt(Duration.ofMinutes(15).plusSeconds(1)))
        assertEquals(KidStage.COUNTDOWN, stageAt(Duration.ofMinutes(15)))
        assertEquals(KidStage.COUNTDOWN, stageAt(Duration.ofMinutes(5).plusSeconds(1)))
        assertEquals(KidStage.FINAL, stageAt(Duration.ofMinutes(5)))
        assertEquals(KidStage.FINAL, stageAt(Duration.ofSeconds(1)))
        assertEquals(KidStage.ALERT, stageAt(Duration.ZERO))
        assertEquals(KidStage.ALERT, stageAt(Duration.ofMinutes(-10).plusSeconds(1)))
        assertEquals(KidStage.DONE, stageAt(Duration.ofMinutes(-10)))
    }

    @Test
    fun `cleared or dismissed kids are done even mid-alert`() {
        assertEquals(KidStage.DONE, stageAt(Duration.ZERO, cleared = true))
        assertEquals(KidStage.DONE, stageAt(Duration.ZERO, dismissed = true))
    }

    @Test
    fun `a payload left over from a previous day shows nothing`() {
        val stage = MorningClock.stage(kid(), leave.plus(Duration.ofDays(1)))
        assertEquals(MorningState.IDLE, MorningClock.state(MorningLayout.BAND, listOf(stage)))
    }

    @Test
    fun `band shows from activation but a kid card waits for the countdown`() {
        val waiting = listOf(KidStage.WAITING)
        assertEquals(MorningState.BAND, MorningClock.state(MorningLayout.BAND, waiting))
        assertEquals(MorningState.IDLE, MorningClock.state(MorningLayout.CARD, waiting))
        assertEquals(MorningState.BAND, MorningClock.state(MorningLayout.CARD, listOf(KidStage.COUNTDOWN)))
        assertEquals(MorningState.BAND, MorningClock.state(MorningLayout.CARD, listOf(KidStage.FINAL)))
    }

    @Test
    fun `any alert wins and no kids means idle`() {
        val mixed = listOf(KidStage.DONE, KidStage.ALERT, KidStage.WAITING)
        assertEquals(MorningState.ALERT, MorningClock.state(MorningLayout.BAND, mixed))
        assertEquals(MorningState.IDLE, MorningClock.state(MorningLayout.BAND, emptyList()))
        assertEquals(MorningState.IDLE, MorningClock.state(MorningLayout.BAND, listOf(KidStage.DONE)))
    }

    @Test
    fun `a kid without a countdown keeps the band up but never counts down or alerts`() {
        val quiet = MorningKid("Alex", "#2bb3a3", leave, "", false, countdown = false)
        fun at(before: Duration) = MorningClock.stage(quiet, leave.minus(before))
        assertEquals(KidStage.WAITING, at(Duration.ofMinutes(10)))
        assertEquals(KidStage.WAITING, at(Duration.ofMinutes(2)))
        assertEquals(KidStage.WAITING, at(Duration.ZERO))
        assertEquals(KidStage.DONE, at(Duration.ofMinutes(-10)))
        assertEquals(MorningState.BAND, MorningClock.state(MorningLayout.BAND, listOf(at(Duration.ZERO))))
        assertEquals(MorningState.IDLE, MorningClock.state(MorningLayout.CARD, listOf(at(Duration.ZERO))))
    }

    @Test
    fun `countdown defaults to on`() {
        val plan = MorningPlan.parse(
            """{"active":true,"kids":[{"name":"F","leave_at":"2026-11-02T08:10:00-05:00"},
                {"name":"Alex","leave_at":"2026-11-02T07:30:00-05:00","countdown":false}]}""",
        )!!
        assertEquals(listOf(true, false), plan.kids.map { it.countdown })
    }

    @Test
    fun `countdown rounds up and switches to seconds at five minutes`() {
        fun at(seconds: Long) = MorningClock.countdown(leave.minusSeconds(seconds), leave)
        assertEquals("23 min", at(22 * 60 + 1))
        assertEquals("6 min", at(5 * 60 + 1))
        assertEquals("5:00", at(5 * 60))
        assertEquals("4:32", at(4 * 60 + 32))
        assertEquals("0:01", at(1))
        assertEquals("0:00", at(0))
        assertEquals("0:00", at(-30))
    }

    @Test
    fun `parses the contract payload`() {
        val plan = MorningPlan.parse(
            """{"active":true,"events":[{"start":"07:45","title":"Piano lesson"}],
                "kids":[{"name":"Alex","color":"#2bb3a3","leave_at":"2026-11-02T07:30:00-05:00","note":"","cleared":false}]}""",
        )!!
        assertEquals(listOf(MorningEvent("07:45", "Piano lesson")), plan.events)
        assertEquals(leave, plan.kids.single().leaveAt)
    }

    @Test
    fun `parses the microseconds a Home Assistant timestamp can carry`() {
        val plan = MorningPlan.parse(
            """{"active":true,"kids":[{"name":"F","leave_at":"2026-11-02T08:10:00.000000-05:00"}]}""",
        )!!
        assertEquals(Instant.parse("2026-11-02T13:10:00Z"), plan.kids.single().leaveAt)
        assertEquals(emptyList<MorningEvent>(), plan.events)
    }

    @Test
    fun `inactive or empty payloads mean nothing to show`() {
        assertNull(MorningPlan.parse("""{"active":false}"""))
        assertNull(MorningPlan.parse(""))
        assertNull(MorningPlan.parse("{}"))
    }
}
