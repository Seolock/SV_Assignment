package com.sv.backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;

@Getter
@Setter
@Entity
public class Device extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String deviceId;
    private String fw;
    private Instant lastHeartbeatAt;

    public String status() {
        return lastHeartbeatAt != null && lastHeartbeatAt.isAfter(Instant.now().minus(Duration.ofSeconds(35))) ? "ONLINE" : "OFFLINE";
    }

}
