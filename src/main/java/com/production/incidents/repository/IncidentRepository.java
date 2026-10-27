package com.production.incidents.repository;

import com.production.incidents.domain.Incident;
import com.production.incidents.domain.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IncidentRepository extends JpaRepository<Incident, Long>, JpaSpecificationExecutor<Incident> {

    Optional<Incident> findFirstByErrorGroupIdOrderByDetectedAtDescIdDesc(Long errorGroupId);

    List<Incident> findByStartedAtBetween(LocalDateTime from, LocalDateTime to);

    @Query("select i.status, count(i) from Incident i where i.startedAt between :from and :to group by i.status")
    List<Object[]> countByStatusBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select i.severity, count(i) from Incident i where i.startedAt between :from and :to group by i.severity")
    List<Object[]> countBySeverityBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    List<Incident> findByStatusIn(List<IncidentStatus> statuses);
}
