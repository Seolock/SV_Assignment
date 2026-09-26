package com.sv.backend;

import com.sv.backend.domain.Command;
import com.sv.backend.domain.Device;
import com.sv.backend.domain.Rule;
import com.sv.backend.domain.Telemetry;
import com.sv.backend.repository.CommandRepository;
import com.sv.backend.repository.DeviceRepository;
import com.sv.backend.repository.RuleRepository;
import com.sv.backend.repository.TelemetryRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BackendService {

    private final DeviceRepository deviceRepository;
    private final TelemetryRepository telemetryRepository;
    private final CommandRepository commandRepository;
    private final RuleRepository ruleRepository;


    @Transactional
    public boolean register(Dto.RegisterRequest registerRequest) {
        Device device = deviceRepository.findByDeviceId(registerRequest.getDeviceId()).orElse(null);
        if (device == null) {
            device = new Device(registerRequest);
            deviceRepository.save(device);
            return true;
        }
        device.setDeviceUrl(registerRequest.getDeviceUrl());
        device.setFw(registerRequest.getFw());
        return false;
    }


    public List<Dto.DeviceSummary> listDevices() {
        List<Device> devices = deviceRepository.findAll();
        return devices.stream().map(this::toDeviceSummary).toList();
    }


    public Dto.DeviceDetail deviceDetail(String id) {
        Device device = deviceRepository.findByDeviceId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found: " + id));
        return toDeviceDetail(device);
    }


    @Transactional
    public void heartbeat(String id) {
        Device device = deviceRepository.findByDeviceId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found: " + id));
        device.setLastHeartbeatAt(Instant.now());
    }


    @Transactional
    public void saveTelemetry(String id, Dto.TelemetrySample telemetrySample) {
        Device device = deviceRepository.findByDeviceId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found: " + id));
        telemetryRepository.save(toTelemetry(device, telemetrySample));
        applyRule(device,telemetrySample);
    }


    public List<Dto.TelemetrySample> loadTelemetries(String id, String last, int limit) {
        Device device = deviceRepository.findByDeviceId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found: " + id));
        Instant from = Instant.now().minus(parseLast(last));
        return telemetryRepository.findByDeviceAndTsGreaterThanEqualOrderByTsDesc(device, from, Limit.of(limit))
                .reversed().stream().map(this::toSample).toList();
    }


    public Dto.CommandCreateResponse createCommand(String id, Dto.CommandRequest commandRequest) {
        Device device = deviceRepository.findByDeviceId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found: " + id));
        Command command = commandRepository.save(newCommand(device, commandRequest.getType(), commandRequest.getValue(), commandRequest.getSource()));
        return toCommandCreateResponse(command);
    }


    public List<Dto.CommandResponse> loadCommands(String id, int limit) {
        Device device = deviceRepository.findByDeviceId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found: " + id));
        return commandRepository.findByDeviceOrderByCreatedAtDesc(device, Limit.of(limit))
                .reversed().stream().map(this::toCommandResponse).toList();
    }


    public List<Dto.PendingCommandResponse> pending(String id) {
        Device device = deviceRepository.findByDeviceId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found: " + id));
        return commandRepository.findByDeviceAndStatusOrderByCreatedAtAsc(device, "PENDING")
                .stream().map(this::toPendingCommand).toList();
    }


    @Transactional
    public void reportResult(String cmdId, Dto.CommandResult commandResult) {
        Command command = commandRepository.findByCommandId(cmdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "command not found: " + cmdId));
        if (!"PENDING".equals(command.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "command is not PENDING: " + cmdId);
        }
        command.setStatus(commandResult.getStatus());
        command.setReason(commandResult.getReason());
    }


    public Dto.CommandResponse commandDetail(String cmdId) {
        Command command = commandRepository.findByCommandId(cmdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "command not found: " + cmdId));
        return toCommandResponse(command);
    }


    public Dto.RuleResponse getRule(String id) {
        Device device = deviceRepository.findByDeviceId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found: " + id));
        return ruleRepository.findByDevice(device).map(this::toRuleResponse).orElse(null);
    }


    public Dto.RuleResponse setRule(String id, Dto.RuleRequest ruleRequest) {
        Device device = deviceRepository.findByDeviceId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found: " + id));
        Rule rule = ruleRepository.findByDevice(device).orElse(null);
        if (rule == null) {
            rule = new Rule();
            rule.setDevice(device);
        }
        rule.setMetric(ruleRequest.getMetric());
        rule.setThreshold(ruleRequest.getThreshold());
        rule.setLastAction(null);
        return toRuleResponse(ruleRepository.save(rule));
    }


    private Duration parseLast(String last) {
        try {
            Duration duration = Duration.parse("PT" + last.toUpperCase());
            if (!duration.isNegative() && !duration.isZero()) {
                return duration;
            }
        } catch (DateTimeParseException ignored) {
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "last must be like 1h, 30m, 1h30m: " + last);
    }


    // 규칙 적용
    private void applyRule(Device device, Dto.TelemetrySample telemetrySample) {
        Rule rule = ruleRepository.findByDevice(device).orElse(null);
        if (rule == null) {
            return;
        }
        String action = telemetrySample.getTemperature() > rule.getThreshold() ? "on" : "off";
        if (action.equals(rule.getLastAction())) {
            return;
        }
        commandRepository.save(newCommand(device, "SET_LED", action, "rule"));
        rule.setLastAction(action);
    }


    private Command newCommand(Device device, String type, String value, String source) {
        Command command = new Command();
        command.setDevice(device);
        command.setCommandId("cmd-" + UUID.randomUUID().toString().substring(0, 8));
        command.setType(type);
        command.setValue(value);
        command.setStatus("PENDING");
        command.setSource(source);
        return command;
    }


    private Dto.DeviceSummary toDeviceSummary(Device device) {
        Dto.DeviceSummary summary = new Dto.DeviceSummary();
        summary.setDeviceId(device.getDeviceId());
        summary.setStatus(device.status());
        summary.setLastHeartbeatAt(device.getLastHeartbeatAt());
        summary.setLatest(telemetryRepository.findFirstByDeviceOrderByTsDesc(device).map(this::toSample).orElse(null));
        return summary;
    }


    private Dto.TelemetrySample toSample(Telemetry telemetry) {
        Dto.TelemetrySample telemetrySample = new Dto.TelemetrySample();
        telemetrySample.setSeq(telemetry.getSeq());
        telemetrySample.setTs(telemetry.getTs());
        telemetrySample.setTemperature(telemetry.getTemperature());
        telemetrySample.setHumidity(telemetry.getHumidity());
        return telemetrySample;
    }


    private Dto.DeviceDetail toDeviceDetail(Device device) {
        Dto.DeviceDetail detail = new Dto.DeviceDetail();
        detail.setDeviceId(device.getDeviceId());
        detail.setDeviceUrl(device.getDeviceUrl());
        detail.setFw(device.getFw());
        detail.setStatus(device.status());
        detail.setLastHeartbeatAt(device.getLastHeartbeatAt());
        detail.setLatest(telemetryRepository.findFirstByDeviceOrderByTsDesc(device).map(this::toSample).orElse(null));
        detail.setRule(ruleRepository.findByDevice(device).map(this::toRuleResponse).orElse(null));
        detail.setRecentCommands(commandRepository.findByDeviceOrderByCreatedAtDesc(device, Limit.of(3))
                .stream().map(Command::getCommandId).toList());
        return detail;
    }


    private Dto.RuleResponse toRuleResponse(Rule rule) {
        Dto.RuleResponse response = new Dto.RuleResponse();
        response.setMetric(rule.getMetric());
        response.setThreshold(rule.getThreshold());
        response.setLastAction(rule.getLastAction());
        return response;
    }


    private Dto.CommandResponse toCommandResponse(Command command) {
        Dto.CommandResponse response = new Dto.CommandResponse();
        response.setCommandId(command.getCommandId());
        response.setType(command.getType());
        response.setValue(command.getValue());
        response.setStatus(command.getStatus());
        response.setSource(command.getSource());
        response.setCreatedAt(command.getCreatedAt());
        response.setUpdatedAt(command.getUpdatedAt());
        return response;
    }


    private Telemetry toTelemetry(Device device, Dto.TelemetrySample telemetrySample) {
        Telemetry telemetry = new Telemetry();
        telemetry.setDevice(device);
        telemetry.setSeq(telemetrySample.getSeq());
        telemetry.setTs(telemetrySample.getTs());
        telemetry.setTemperature(telemetrySample.getTemperature());
        telemetry.setHumidity(telemetrySample.getHumidity());
        return telemetry;
    }


    private Dto.CommandCreateResponse toCommandCreateResponse(Command command) {
        Dto.CommandCreateResponse response = new Dto.CommandCreateResponse();
        response.setCommandId(command.getCommandId());
        response.setStatus(command.getStatus());
        response.setCreatedAt(command.getCreatedAt());
        return response;
    }


    private Dto.PendingCommandResponse toPendingCommand(Command command) {
        Dto.PendingCommandResponse response = new Dto.PendingCommandResponse();
        response.setCommandId(command.getCommandId());
        response.setType(command.getType());
        response.setValue(command.getValue());
        return response;
    }

}
