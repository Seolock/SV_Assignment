package com.sv.backend.repository;

import com.sv.backend.domain.Command;
import com.sv.backend.domain.Device;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CommandRepository extends JpaRepository<Command, Long> {

    Optional<Command> findByCommandId(String commandId);

    List<Command> findByDeviceOrderByCreatedAtDesc(Device device, Limit limit);

    List<Command> findByDeviceAndStatusOrderByCreatedAtAsc(Device device, String status);

}
