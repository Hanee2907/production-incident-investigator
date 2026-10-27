package com.production.incidents.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** One entry in an incident's history: status changes, notes, recurrences. */
@Entity
@Table(name = "incident_event")
public class IncidentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id")
    private Incident incident;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventType type;

    @Column(nullable = false, length = 4000)
    private String message;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    protected IncidentEvent() {
    }

    IncidentEvent(Incident incident, EventType type, String message, LocalDateTime timestamp) {
        this.incident = incident;
        this.type = type;
        this.message = message;
        this.timestamp = timestamp;
    }

    public Long getId() { return id; }
    public Incident getIncident() { return incident; }
    public EventType getType() { return type; }
    public String getMessage() { return message; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
