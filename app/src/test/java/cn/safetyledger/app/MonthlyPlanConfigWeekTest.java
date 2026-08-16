package cn.safetyledger.app;

import org.junit.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MonthlyPlanConfigWeekTest {
    @Test public void august2026StartsOnFirstOwnedMonday() {
        assertEquals(LocalDate.of(2026, 8, 3),
                MonthlyPlanConfig.firstOwnedMonday(YearMonth.of(2026, 8)));
    }

    @Test public void aug1And2RemainInJulysFinalWeek() {
        List<MonthlyPlanConfig.WeekGap> gaps = MonthlyPlanConfig.findMissedOwnedWeeks(
                YearMonth.of(2026, 8), LocalDate.of(2026, 8, 16), List.of());
        assertEquals(1, gaps.size());
        assertEquals(LocalDate.of(2026, 8, 3), gaps.get(0).monday);
        assertEquals(LocalDate.of(2026, 8, 9), gaps.get(0).sunday);
    }

    @Test public void currentUnfinishedWeekIsNotMissed() {
        List<MonthlyPlanConfig.WeekGap> gaps = MonthlyPlanConfig.findMissedOwnedWeeks(
                YearMonth.of(2026, 8), LocalDate.of(2026, 8, 16),
                List.of(LocalDate.of(2026, 8, 6)));
        assertTrue(gaps.isEmpty());
    }
}
