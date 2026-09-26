package com.sv.backend.repository;

import com.sv.backend.domain.Device;
import com.sv.backend.domain.Telemetry;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface TelemetryRepository extends JpaRepository<Telemetry, Long> {

    Optional<Telemetry> findFirstByDeviceOrderByTsDesc(Device device);

    List<Telemetry> findByDeviceAndTsGreaterThanEqualOrderByTsDesc(Device device, Instant from, Limit limit);

}
