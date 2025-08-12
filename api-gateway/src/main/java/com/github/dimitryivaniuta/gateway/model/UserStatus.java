package com.github.dimitryivaniuta.gateway.model;

import lombok.Getter;

/**
 * User lifecycle / access status backed by a SMALLINT in the database.
 *
 * <p>Ordinal values are explicit to keep DB representation stable over time.</p>
 */
@Getter
public enum UserStatus {
    /** Active account allowed to authenticate and call APIs. */
    ACTIVE((short) 0),

    /** Temporarily suspended (e.g., by admin); cannot authenticate. */
    SUSPENDED((short) 1),

    /** Permanently deactivated; retained for audit, no login. */
    DEACTIVATED((short) 2),

    /** Locked due to policy (e.g., too many failed logins). */
    LOCKED((short) 3),

    /** Created but not yet activated/verified. */
    PENDING((short) 4);

    /** Backing SMALLINT value stored in the database. */
    private final short dbValue;

    UserStatus(final short dbValue) {
        this.dbValue = dbValue;
    }

    /**
     * Resolves a {@link UserStatus} from its SMALLINT DB value.
     *
     * @param value database smallint
     * @return matching status or {@link #PENDING} as a conservative default
     */
    public static UserStatus fromDbValue(final Short value) {
        if (value == null) return PENDING;
        for (UserStatus s : values()) {
            if (s.dbValue == value) return s;
        }
        return PENDING;
    }
}
