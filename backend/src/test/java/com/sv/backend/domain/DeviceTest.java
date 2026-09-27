package com.sv.backend.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceTest {

    @Test
    @DisplayName("하트비트가 없으면 OFFLINE")
    void noHeartbeat() {
        assertThat(new Device().status()).isEqualTo("OFFLINE");
    }

    @Test
    @DisplayName("마지막 하트비트가 35초 이내면 ONLINE, 넘으면 OFFLINE")
    void heartbeatWindow() {
        Device device = new Device();

        device.setLastHeartbeatAt(Instant.now().minusSeconds(30));
        assertThat(device.status()).isEqualTo("ONLINE");

        device.setLastHeartbeatAt(Instant.now().minusSeconds(36));
        assertThat(device.status()).isEqualTo("OFFLINE");
    }

}
