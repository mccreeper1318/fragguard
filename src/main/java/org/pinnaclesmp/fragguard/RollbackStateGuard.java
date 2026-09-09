package org.pinnaclesmp.fragguard;

import java.util.Objects;

final class RollbackStateGuard {
    enum Decision {
        ALREADY_TARGET,
        APPLY,
        CONFLICT
    }

    private RollbackStateGuard() {
    }

    static Decision decide(String actualData, String expectedData, String targetData, boolean force) {
        if (Objects.equals(actualData, targetData)) {
            return Decision.ALREADY_TARGET;
        }
        if (force || Objects.equals(actualData, expectedData)) {
            return Decision.APPLY;
        }
        return Decision.CONFLICT;
    }

    static boolean stillMatchesAudit(String actualData, String auditedBeforeData) {
        return Objects.equals(actualData, auditedBeforeData);
    }
}
