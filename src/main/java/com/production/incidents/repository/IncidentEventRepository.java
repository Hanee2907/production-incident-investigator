package com.production.incidents.repository;

import com.production.incidents.domain.IncidentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentEventRepository extends JpaRepository<IncidentEvent, Long> {
}
