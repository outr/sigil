package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.workflow.trigger.CronExpression

/**
 * Pure-function tests for [[CronExpression.matches]] — the field
 * grammar (literals, lists, ranges, steps) plus malformed-input
 * defenses. Wall-clock concerns (per-minute deduplication, the
 * trigger run loop) are exercised through the higher-level workflow
 * specs; this spec stays driven by constructed timestamps so it's
 * deterministic regardless of when it runs.
 */
class TimeTriggerCronSpec extends AnyWordSpec with Matchers {

  /** Build an epoch-millis at a specific wall-clock minute / hour
    * pinned to 2025-06-18 (a Wednesday — DOW=3). Tests that probe
    * day-of-month / month / day-of-week boundaries call
    * [[atCalendar]] directly. */
  private def at(minute: Int, hour: Int = 12): Long =
    atCalendar(2025, 6, 18, hour, minute)

  private def atCalendar(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long = {
    val cal = java.util.Calendar.getInstance()
    cal.clear()
    cal.set(year, month - 1, day, hour, minute, 0)
    cal.getTimeInMillis
  }

  "CronExpression — minute field" should {
    "match `*` every minute" in {
      CronExpression.matches("* * * * *", at(0)) shouldBe true
      CronExpression.matches("* * * * *", at(37)) shouldBe true
    }
    "match a literal minute" in {
      CronExpression.matches("15 * * * *", at(15)) shouldBe true
      CronExpression.matches("15 * * * *", at(16)) shouldBe false
    }
    "match a comma-separated list" in {
      CronExpression.matches("0,15,30,45 * * * *", at(15)) shouldBe true
      CronExpression.matches("0,15,30,45 * * * *", at(45)) shouldBe true
      CronExpression.matches("0,15,30,45 * * * *", at(20)) shouldBe false
    }
    "match an inclusive range" in {
      CronExpression.matches("10-20 * * * *", at(10)) shouldBe true
      CronExpression.matches("10-20 * * * *", at(15)) shouldBe true
      CronExpression.matches("10-20 * * * *", at(20)) shouldBe true
      CronExpression.matches("10-20 * * * *", at(9))  shouldBe false
      CronExpression.matches("10-20 * * * *", at(21)) shouldBe false
    }
    "match `*/N` step expressions" in {
      CronExpression.matches("*/5 * * * *", at(0))  shouldBe true
      CronExpression.matches("*/5 * * * *", at(5))  shouldBe true
      CronExpression.matches("*/5 * * * *", at(55)) shouldBe true
      CronExpression.matches("*/5 * * * *", at(7))  shouldBe false
    }
    "match a range-with-step" in {
      CronExpression.matches("0-30/10 * * * *", at(0))  shouldBe true
      CronExpression.matches("0-30/10 * * * *", at(10)) shouldBe true
      CronExpression.matches("0-30/10 * * * *", at(20)) shouldBe true
      CronExpression.matches("0-30/10 * * * *", at(30)) shouldBe true
      CronExpression.matches("0-30/10 * * * *", at(40)) shouldBe false
      CronExpression.matches("0-30/10 * * * *", at(15)) shouldBe false
    }
  }

  "CronExpression — multi-field" should {
    "AND every field" in {
      // 09:30 on the 1st of January (Wednesday in 2025)
      val ts = atCalendar(2025, 1, 1, 9, 30)
      CronExpression.matches("30 9 1 1 *", ts) shouldBe true
      CronExpression.matches("30 9 1 1 3", ts) shouldBe true   // DOW = 3 (Wed)
      CronExpression.matches("30 9 2 1 *", ts) shouldBe false  // DOM mismatch
    }
    "match day-of-week ranges" in {
      // 2025-06-16 is a Monday (DOW=1)
      val mon = atCalendar(2025, 6, 16, 12, 0)
      CronExpression.matches("* * * * 1-5", mon) shouldBe true   // weekdays
      CronExpression.matches("* * * * 0,6", mon) shouldBe false  // weekends
    }
    "support combined lists, ranges, and steps inside one field" in {
      // 14:23 should match `5,20-30/5,55`: 23 is in [20-30] step-5 from 20? 23-20=3, not divisible by 5 → false
      // But 25 is divisible: 25-20=5
      CronExpression.matches("5,20-30/5,55 * * * *", at(25)) shouldBe true
      CronExpression.matches("5,20-30/5,55 * * * *", at(23)) shouldBe false
      CronExpression.matches("5,20-30/5,55 * * * *", at(5))  shouldBe true
      CronExpression.matches("5,20-30/5,55 * * * *", at(55)) shouldBe true
    }
  }

  "CronExpression — defensive against malformed input" should {
    "reject the wrong number of fields" in {
      CronExpression.matches("* * * *", at(0))      shouldBe false
      CronExpression.matches("* * * * * *", at(0))  shouldBe false
      CronExpression.matches("", at(0))             shouldBe false
    }
    "reject non-numeric terms" in {
      CronExpression.matches("not-a-cron * * * *", at(0)) shouldBe false
    }
    "reject step values of zero or negative" in {
      CronExpression.matches("*/0 * * * *", at(0))  shouldBe false
      CronExpression.matches("*/-1 * * * *", at(0)) shouldBe false
    }
    "reject inverted ranges" in {
      CronExpression.matches("10-5 * * * *", at(7)) shouldBe false
    }
    "reject out-of-range values" in {
      CronExpression.matches("60 * * * *", at(0))  shouldBe false  // minute 60 doesn't exist
      CronExpression.matches("* 24 * * *", at(0))  shouldBe false  // hour 24 doesn't exist
    }
  }
}
