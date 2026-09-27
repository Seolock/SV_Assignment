package com.sv.backend;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.Instant;
import java.util.List;

public class Dto {

    @Data
    public static class RegisterRequest {
        @NotBlank
        private String deviceId;
        private String fw;
    }

    @Data
    public static class DeviceSummary {
        private String deviceId;
        private String status;
        private Instant lastHeartbeatAt;
        private TelemetrySample latest;
    }

    @Data
    public static class DeviceDetail {
        private String deviceId;
        private String fw;
        private String status;
        private Instant lastHeartbeatAt;
        private TelemetrySample latest;
        private RuleResponse rule;
        private List<String> recentCommands;
    }


    @Data
    public static class TelemetrySample {
        @NotNull
        private Long seq;
        @NotNull
        private Instant ts;
        @NotNull
        private Double temperature;
        @NotNull
        private Double humidity;
    }

    @Data
    public static class CommandRequest {
        @NotNull @Pattern(regexp = "SET_LED")
        private String type;
        @NotNull @Pattern(regexp = "on|off")
        private String value;
        @NotNull
        private String source;
    }

    @Data
    public static class CommandCreateResponse {
        private String commandId;
        private String status;
        private Instant createdAt;
    }

    @Data
    public static class CommandResponse {
        private String commandId;
        private String type;
        private String value;
        private String status;
        private String reason;
        private String source;
        private Instant createdAt;
    }

    @Data
    public static class PendingCommandResponse {
        private String commandId;
        private String type;
        private String value;
    }

    @Data
    public static class CommandResult {
        @NotNull @Pattern(regexp = "ACKED|FAILED")
        private String status;
        private String reason;
    }

    @Data
    public static class RuleRequest {
        @NotNull @Pattern(regexp = "temperature")
        private String metric;
        @NotNull
        private Double threshold;
    }

    @Data
    public static class RuleResponse {
        private String metric;
        private Double threshold;
        private String lastAction;
    }

}
