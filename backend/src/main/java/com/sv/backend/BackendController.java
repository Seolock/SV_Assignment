package com.sv.backend;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class BackendController {

    private final BackendService backendService;

    // 장치 저장 - 에이전트
    @PostMapping("/devices")
    public ResponseEntity<Void> register(@Valid @RequestBody Dto.RegisterRequest registerRequest) {
        boolean created = backendService.register(registerRequest);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).build();
    }


    // 장치 리스트 조회 - 콘솔
    @GetMapping("/devices")
    public List<Dto.DeviceSummary> listDevices() {
        return backendService.listDevices();
    }


    // 장치 상세 조회 - 콘솔
    @GetMapping("/devices/{id}")
    public Dto.DeviceDetail deviceDetail(@PathVariable String id) {
        return backendService.deviceDetail(id);
    }


    // 하트비트 저장 - 에이전트
    @PostMapping("/devices/{id}/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat(@PathVariable String id) {
        backendService.heartbeat(id);
    }


    // 텔레메트리 저장 - 에이전트
    @PostMapping("/devices/{id}/telemetry")
    public void saveTelemetry(@PathVariable String id, @Valid @RequestBody Dto.TelemetrySample telemetrySample) {
        backendService.saveTelemetry(id, telemetrySample);
    }


    // 텔레메트리 이력 조회 - 콘솔
    @GetMapping("/devices/{id}/telemetry")
    public List<Dto.TelemetrySample> loadTelemetries(@PathVariable String id,
                                                     @RequestParam(defaultValue = "1h") String last,
                                                     @RequestParam(defaultValue = "50") @Min(1) Integer limit) {
        return backendService.loadTelemetries(id, last, limit);
    }


    // 명령 저장 - 콘솔
    @PostMapping("/devices/{id}/commands")
    @ResponseStatus(HttpStatus.CREATED)
    public Dto.CommandCreateResponse createCommand(@PathVariable String id, @Valid @RequestBody Dto.CommandRequest commandRequest) {
        return backendService.createCommand(id, commandRequest);
    }


    // 명령 이력 조회 - 콘솔
    @GetMapping("/devices/{id}/commands")
    public List<Dto.CommandResponse> loadCommands(@PathVariable String id, @RequestParam(defaultValue = "20") @Min(1) Integer limit) {
        return backendService.loadCommands(id, limit);
    }


    // 대기 명령 조회 - 에이전트
    @GetMapping("/devices/{id}/commands/pending")
    public List<Dto.PendingCommandResponse> pending(@PathVariable String id) {
        return backendService.pending(id);
    }


    // 명령 실행 결과 저장 - 에이전트
    @PatchMapping("/commands/{cmdId}")
    public void reportResult(@PathVariable String cmdId, @Valid @RequestBody Dto.CommandResult commandResult) {
        backendService.reportResult(cmdId, commandResult);
    }


    // 명령 상세 조회 - 콘솔
    @GetMapping("/commands/{cmdId}")
    public Dto.CommandResponse commandDetail(@PathVariable String cmdId) {
        return backendService.commandDetail(cmdId);
    }


    // 규칙 조회 - 콘솔
    @GetMapping("/devices/{id}/rule")
    public Dto.RuleResponse getRule(@PathVariable String id) {
        return backendService.getRule(id);
    }


    // 규칙 저장 - 콘솔
    @PutMapping("/devices/{id}/rule")
    public Dto.RuleResponse setRule(@PathVariable String id, @Valid @RequestBody Dto.RuleRequest ruleRequest) {
        return backendService.setRule(id, ruleRequest);
    }

}
