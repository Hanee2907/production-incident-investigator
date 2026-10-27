package com.production.incidents.repository;

import com.production.incidents.domain.ErrorGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ErrorGroupRepository extends JpaRepository<ErrorGroup, Long> {

    Optional<ErrorGroup> findByFingerprint(String fingerprint);

    List<ErrorGroup> findAllByOrderByOccurrencesDesc();
}
