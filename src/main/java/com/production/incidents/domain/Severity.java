package com.production.incidents.domain;

public enum Severity {
    LOW, MEDIUM, HIGH, CRITICAL;

    public Severity max(Severity other) {
        return other != null && other.ordinal() > ordinal() ? other : this;
    }
}
