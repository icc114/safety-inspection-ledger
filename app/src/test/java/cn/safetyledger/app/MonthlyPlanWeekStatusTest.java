package cn.safetyledger.app;

import org.junit.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MonthlyPlanWeekStatusTest {
    @Test public void august2026FirstOwnedWeekStartsOnThird() {
        assertEquals(LocalDate.of(2026, 8, 3),
                MonthlyPlanConfig.firstOwnedMonday(YearMonth.of(2026, 8)));
    }

    @Test public void crossMonthWeekBelongsToItsMondayMonth() {
        LocalDate monday = LocalDate.of(2026, 7, 27);
        assertTrue(MonthlyPlanConfig.weekOwnedBy(YearMonth.of(2026, 7), monday));
        assertFalse(MonthlyPlanConfig.weekOwnedBy(YearMonth.of(2026, 8), monday));
    }

    @Test public void reachedBadgeRequiresCurrentWeekInspection() {
        MonthlyPlanConfig.Item item = new MonthlyPlanConfig.Item("1", "测试", "测试", 4);
        MonthlyPlanConfig.Result noCurrent = new MonthlyPlanConfig.Result(item, 6, false, false);
        MonthlyPlanConfig.Result currentDone = new MonthlyPlanConfig.Result(item, 6, false, true);
        assertFalse(noCurrent.shouldShowReachedBadge());
        assertTrue(currentDone.shouldShowReachedBadge());
    }

    @Test public void missedPreviousWeekOverridesReachedBadge() {
        MonthlyPlanConfig.Item item = new MonthlyPlanConfig.Item("1", "测试", "测试", 4);
        MonthlyPlanConfig.Result missed = new MonthlyPlanConfig.Result(item, 6, true, true);
        assertTrue(missed.lastCompletedWeekMissed);
        assertFalse(missed.shouldShowReachedBadge());
    }
}
