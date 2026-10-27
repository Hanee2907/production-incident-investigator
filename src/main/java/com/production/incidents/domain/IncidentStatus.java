package com.production.incidents.domain;

public enum IncidentStatus {
    OPEN, INVESTIGATING, RESOLVED, CLOSED;

    public boolean isActive() {
        return this == OPEN || this == INVESTIGATING;
    }
}
