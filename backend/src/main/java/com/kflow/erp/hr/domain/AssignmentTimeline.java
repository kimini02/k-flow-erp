package com.kflow.erp.hr.domain;

import com.kflow.erp.hr.api.HrFailure;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import static com.kflow.erp.hr.api.HrFailure.Code.HR_ASSIGNMENT_INTEGRITY_VIOLATION;

/** Full timeline invariant, separate from a date lookup's local integrity check. No transfer command. */
public final class AssignmentTimeline {
    private AssignmentTimeline() {}
    public record Period(LocalDate from, LocalDate to) {}
    public static void validate(LocalDate joined, LocalDate terminated, List<Period> periods) {
        if (joined == null || periods == null || periods.isEmpty()
                || terminated != null && !joined.isBefore(terminated)) fail();
        for (var p : periods) if (p == null || p.from()==null || p.to()!=null && !p.from().isBefore(p.to())) fail();
        var sorted = periods.stream().sorted(Comparator.comparing(Period::from)).toList();
        LocalDate next=joined;
        for (var p : sorted) {
            if (!Objects.equals(next,p.from())) fail();
            next=p.to();
        }
        if (!Objects.equals(next,terminated)) fail();
    }
    private static void fail() { throw new HrFailure(HR_ASSIGNMENT_INTEGRITY_VIOLATION); }
}
