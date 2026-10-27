package com.production.incidents.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "log_entry", indexes = {
        @Index(name = "idx_log_timestamp", columnList = "timestamp"),
        @Index(name = "idx_log_service", columnList = "service"),
        @Index(name = "idx_log_level", columnList = "level")
})
public class LogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private LogLevel level;

    @Column(nullable = false, length = 120)
    private String service;

    @Column(length = 255)
    private String logger;

    @Column(nullable = false, length = 4000)
    private String message;

    @Column(length = 255)
    private String exceptionType;

    @Lob
    private String stackTrace;

    @Column(length = 255)
    private String sourceFile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "error_group_id")
    private ErrorGroup errorGroup;

    protected LogEntry() {
    }

    public LogEntry(LocalDateTime timestamp, LogLevel level, String service, String logger,
                    String message, String exceptionType, String stackTrace, String sourceFile) {
        this.timestamp = timestamp;
        this.level = level;
        this.service = service;
        this.logger = logger;
        this.message = message;
        this.exceptionType = exceptionType;
        this.stackTrace = stackTrace;
        this.sourceFile = sourceFile;
    }

    public Long getId() { return id; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public LogLevel getLevel() { return level; }
    public String getService() { return service; }
    public String getLogger() { return logger; }
    public String getMessage() { return message; }
    public String getExceptionType() { return exceptionType; }
    public String getStackTrace() { return stackTrace; }
    public String getSourceFile() { return sourceFile; }
    public ErrorGroup getErrorGroup() { return errorGroup; }

    public void setErrorGroup(ErrorGroup errorGroup) { this.errorGroup = errorGroup; }
}
