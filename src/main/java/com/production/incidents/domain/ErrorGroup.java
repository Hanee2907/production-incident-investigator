package com.production.incidents.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.TreeSet;

/** A cluster of log errors that share the same normalized signature. */
@Entity
@Table(name = "error_group")
public class ErrorGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String fingerprint;

    @Column(length = 255)
    private String exceptionType;

    @Column(nullable = false, length = 4000)
    private String normalizedMessage;

    @Column(nullable = false, length = 4000)
    private String sampleMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private LogLevel highestLevel;

    @Column(nullable = false)
    private LocalDateTime firstSeen;

    @Column(nullable = false)
    private LocalDateTime lastSeen;

    @Column(nullable = false)
    private long occurrences;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "error_group_service", joinColumns = @JoinColumn(name = "error_group_id"))
    @Column(name = "service", length = 120)
    private Set<String> services = new TreeSet<>();

    protected ErrorGroup() {
    }

    public ErrorGroup(String fingerprint, String exceptionType, String normalizedMessage,
                      String sampleMessage, LogLevel level, LocalDateTime seenAt) {
        this.fingerprint = fingerprint;
        this.exceptionType = exceptionType;
        this.normalizedMessage = normalizedMessage;
        this.sampleMessage = sampleMessage;
        this.highestLevel = level;
        this.firstSeen = seenAt;
        this.lastSeen = seenAt;
    }

    /** Records one more occurrence; logs may arrive out of order, so both ends of the window can move. */
    public void recordOccurrence(LocalDateTime at, String service, LogLevel level) {
        occurrences++;
        if (at.isBefore(firstSeen)) {
            firstSeen = at;
        }
        if (at.isAfter(lastSeen)) {
            lastSeen = at;
        }
        services.add(service);
        if (level.ordinal() > highestLevel.ordinal()) {
            highestLevel = level;
        }
    }

    public Long getId() { return id; }
    public String getFingerprint() { return fingerprint; }
    public String getExceptionType() { return exceptionType; }
    public String getNormalizedMessage() { return normalizedMessage; }
    public String getSampleMessage() { return sampleMessage; }
    public LogLevel getHighestLevel() { return highestLevel; }
    public LocalDateTime getFirstSeen() { return firstSeen; }
    public LocalDateTime getLastSeen() { return lastSeen; }
    public long getOccurrences() { return occurrences; }
    public Set<String> getServices() { return services; }
}
