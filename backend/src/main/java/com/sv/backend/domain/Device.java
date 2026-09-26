package com.sv.backend.domain;

import com.sv.backend.Dto;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;

@NoArgsConstructor
@Getter
@Setter
@Entity
public class Device extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String deviceId;

    private String deviceUrl;
    private String fw;
    private Instant lastHeartbeatAt;

    public Device(Dto.RegisterRequest registerRequest){
        deviceId = registerRequest.getDeviceId();
        deviceUrl = registerRequest.getDeviceUrl();
        fw = registerRequest.getFw();
    }

    public String status() {
        return lastHeartbeatAt != null && lastHeartbeatAt.isAfter(Instant.now().minus(Duration.ofSeconds(30))) ? "ONLINE" : "OFFLINE";
    }

}
