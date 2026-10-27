package com.production.incidents.repository;

import com.production.incidents.domain.LogEntry;
import com.production.incidents.domain.LogLevel;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface LogEntryRepository extends JpaRepository<LogEntry, Long>, JpaSpecificationExecutor<LogEntry> {

    long countByTimestampBetween(LocalDateTime from, LocalDateTime to);

    long countByLevelInAndTimestampBetween(Collection<LogLevel> levels, LocalDateTime from, LocalDateTime to);

    List<LogEntry> findByErrorGroupIdOrderByTimestampDesc(Long groupId, Pageable pageable);

    /** Per-service error impact: [service, errorCount, firstError, lastError, distinctGroups]. */
    @Query("""
            select e.service, count(e), min(e.timestamp), max(e.timestamp), count(distinct e.errorGroup.id)
            from LogEntry e
            where e.level in :levels and e.timestamp between :from and :to
            group by e.service
            order by min(e.timestamp)
            """)
    List<Object[]> serviceImpact(@Param("levels") Collection<LogLevel> levels,
                                 @Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to);

    /** Hourly error counts for one group: [year, month, day, hour, count]. */
    @Query("""
            select year(e.timestamp), month(e.timestamp), day(e.timestamp), hour(e.timestamp), count(e)
            from LogEntry e
            where e.errorGroup.id = :groupId
            group by year(e.timestamp), month(e.timestamp), day(e.timestamp), hour(e.timestamp)
            order by year(e.timestamp), month(e.timestamp), day(e.timestamp), hour(e.timestamp)
            """)
    List<Object[]> hourlyCountsForGroup(@Param("groupId") Long groupId);

    /** Distinct (group, hour) buckets, used to decide which groups keep recurring: [groupId, y, m, d, h]. */
    @Query("""
            select e.errorGroup.id, year(e.timestamp), month(e.timestamp), day(e.timestamp), hour(e.timestamp)
            from LogEntry e
            where e.errorGroup is not null
            group by e.errorGroup.id, year(e.timestamp), month(e.timestamp), day(e.timestamp), hour(e.timestamp)
            """)
    List<Object[]> groupHourBuckets();

    @Query("select distinct e.service from LogEntry e order by e.service")
    List<String> distinctServices();

    @Query("select min(e.timestamp), max(e.timestamp) from LogEntry e")
    List<Object[]> timeRange();
}
