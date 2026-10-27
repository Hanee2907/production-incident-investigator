package com.production.incidents.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Entity
@Table(name = "incident", indexes = {
        @Index(name = "idx_incident_status", columnList = "status"),
        @Index(name = "idx_incident_severity", columnList = "severity")
})
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private IncidentStatus status = IncidentStatus.OPEN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "error_group_id")
    private ErrorGroup errorGroup;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "incident_service", joinColumns = @JoinColumn(name = "incident_id"))
    @Column(name = "service", length = 120)
    private Set<String> affectedServices = new TreeSet<>();

    @Column(length = 120)
    private String assignee;

    /** When the underlying failure began (first matching error in the logs). */
    @Column(nullable = false)
    private LocalDateTime startedAt;

    /** When this tool opened the incident. */
    @Column(nullable = false)
    private LocalDateTime detectedAt;

    private LocalDateTime resolvedAt;

    @Column(length = 4000)
    private String resolutionNotes;

    @Column(nullable = false)
    private int recurrenceCount;

    @OneToMany(mappedBy = "incident", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("timestamp ASC, id ASC")
    private List<IncidentEvent> events = new ArrayList<>();

    protected Incident() {
    }

    public Incident(String title, String description, Severity severity, LocalDateTime startedAt, LocalDateTime detectedAt) {
        this.title = title;
        this.description = description;
        this.severity = severity;
        this.startedAt = startedAt;
        this.detectedAt = detectedAt;
    }

    public IncidentEvent addEvent(EventType type, String message, LocalDateTime at) {
        IncidentEvent event = new IncidentEvent(this, type, message, at);
        events.add(event);
        return event;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Severity getSeverity() { return severity; }
    public IncidentStatus getStatus() { return status; }
    public ErrorGroup getErrorGroup() { return errorGroup; }
    public Set<String> getAffectedServices() { return affectedServices; }
    public String getAssignee() { return assignee; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getDetectedAt() { return detectedAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public String getResolutionNotes() { return resolutionNotes; }
    public int getRecurrenceCount() { return recurrenceCount; }
    public List<IncidentEvent> getEvents() { return events; }

    public void setTitle(String title) { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setSeverity(Severity severity) { this.severity = severity; }
    public void setStatus(IncidentStatus status) { this.status = status; }
    public void setErrorGroup(ErrorGroup errorGroup) { this.errorGroup = errorGroup; }
    public void setAssignee(String assignee) { this.assignee = assignee; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public void setResolutionNotes(String resolutionNotes) { this.resolutionNotes = resolutionNotes; }
    public void incrementRecurrence() { this.recurrenceCount++; }
}
