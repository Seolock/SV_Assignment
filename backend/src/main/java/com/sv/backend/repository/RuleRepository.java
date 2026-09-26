package com.sv.backend.repository;

import com.sv.backend.domain.Device;
import com.sv.backend.domain.Rule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RuleRepository extends JpaRepository<Rule, Long> {

    Optional<Rule> findByDevice(Device device);

}
