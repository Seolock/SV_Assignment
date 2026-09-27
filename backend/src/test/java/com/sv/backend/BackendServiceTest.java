package com.sv.backend;

import com.sv.backend.domain.Command;
import com.sv.backend.domain.Device;
import com.sv.backend.domain.Rule;
import com.sv.backend.domain.Telemetry;
import com.sv.backend.repository.CommandRepository;
import com.sv.backend.repository.DeviceRepository;
import com.sv.backend.repository.RuleRepository;
import com.sv.backend.repository.TelemetryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BackendServiceTest {

    @Mock DeviceRepository deviceRepository;
    @Mock TelemetryRepository telemetryRepository;
    @Mock CommandRepository commandRepository;
    @Mock RuleRepository ruleRepository;
    @InjectMocks BackendService service;

    Device device;

    @BeforeEach
    void setUp() {
        device = new Device();
        device.setDeviceId("dev-1");
    }


    // 장치 등록

    @Test
    @DisplayName("처음 보는 장치는 저장하고 true")
    void registerNew() {
        when(deviceRepository.findByDeviceId("dev-1")).thenReturn(Optional.empty());

        assertThat(service.register(registerRequest("dev-1", "1.0"))).isTrue();
        verify(deviceRepository).save(any(Device.class));
    }

    @Test
    @DisplayName("이미 있는 장치는 fw만 갱신하고 false")
    void registerExisting() {
        device.setFw("1.0");
        when(deviceRepository.findByDeviceId("dev-1")).thenReturn(Optional.of(device));

        assertThat(service.register(registerRequest("dev-1", "2.0"))).isFalse();
        assertThat(device.getFw()).isEqualTo("2.0");
        verify(deviceRepository, never()).save(any());
    }


    // 텔레메트리 저장 + 규칙 적용

    @Test
    @DisplayName("규칙이 없으면 명령을 만들지 않는다")
    void telemetryWithoutRule() {
        givenDevice();
        when(ruleRepository.findByDevice(device)).thenReturn(Optional.empty());

        service.saveTelemetry("dev-1", sample(30.0));

        verify(telemetryRepository).save(any(Telemetry.class));
        verify(commandRepository, never()).save(any());
    }

    @Test
    @DisplayName("임계값 초과 시 rule 출처의 SET_LED on 명령 생성")
    void telemetryAboveThreshold() {
        givenDevice();
        Rule rule = givenRule(25.0, null);

        service.saveTelemetry("dev-1", sample(25.1));

        Command command = savedCommand();
        assertThat(command.getType()).isEqualTo("SET_LED");
        assertThat(command.getValue()).isEqualTo("on");
        assertThat(command.getSource()).isEqualTo("rule");
        assertThat(command.getStatus()).isEqualTo("PENDING");
        assertThat(rule.getLastAction()).isEqualTo("on");
    }

    @Test
    @DisplayName("임계값과 같으면 off")
    void telemetryEqualThreshold() {
        givenDevice();
        givenRule(25.0, "on");

        service.saveTelemetry("dev-1", sample(25.0));

        assertThat(savedCommand().getValue()).isEqualTo("off");
    }

    @Test
    @DisplayName("직전 동작과 같으면 명령을 반복 생성하지 않는다")
    void telemetrySameAction() {
        givenDevice();
        givenRule(25.0, "on");

        service.saveTelemetry("dev-1", sample(30.0));

        verify(commandRepository, never()).save(any());
    }

    @Test
    @DisplayName("없는 장치는 404")
    void telemetryUnknownDevice() {
        when(deviceRepository.findByDeviceId("nope")).thenReturn(Optional.empty());

        assertStatus(() -> service.saveTelemetry("nope", sample(1.0)), HttpStatus.NOT_FOUND);
    }


    // 텔레메트리 이력 조회

    @Test
    @DisplayName("last 만큼의 기간으로 조회하고, 결과를 시간 오름차순으로 뒤집는다")
    void loadTelemetries() {
        givenDevice();
        Telemetry newer = telemetry(2L);
        Telemetry older = telemetry(1L);
        when(telemetryRepository.findByDeviceAndTsGreaterThanEqualOrderByTsDesc(eq(device), any(), eq(Limit.of(10))))
                .thenReturn(List.of(newer, older));

        Instant before = Instant.now();
        List<Dto.TelemetrySample> result = service.loadTelemetries("dev-1", "30m", 10);
        Instant after = Instant.now();

        assertThat(result).extracting(Dto.TelemetrySample::getSeq).containsExactly(1L, 2L);
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(telemetryRepository).findByDeviceAndTsGreaterThanEqualOrderByTsDesc(eq(device), from.capture(), any());
        assertThat(from.getValue()).isBetween(before.minus(Duration.ofMinutes(30)), after.minus(Duration.ofMinutes(30)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0m", "-1h", "10"})
    @DisplayName("잘못된 last 는 400")
    void loadTelemetriesBadLast(String last) {
        givenDevice();

        assertStatus(() -> service.loadTelemetries("dev-1", last, 10), HttpStatus.BAD_REQUEST);
    }


    // 명령 생성 / 대기 명령 / 결과 보고 / 상세 조회

    @Test
    @DisplayName("명령 생성 시 cmd- 접두사 ID와 PENDING 상태")
    void createCommand() {
        givenDevice();
        when(commandRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        Dto.CommandRequest request = new Dto.CommandRequest();
        request.setType("SET_LED");
        request.setValue("on");
        request.setSource("console");

        Dto.CommandCreateResponse response = service.createCommand("dev-1", request);

        assertThat(response.getCommandId()).startsWith("cmd-");
        assertThat(response.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("대기 명령이 있으면 SENT 로 바꾸고 반환")
    void pendingFound() {
        givenDevice();
        Command command = command("PENDING", Instant.now());
        when(commandRepository.findFirstByDeviceAndStatusInOrderByCreatedAtAsc(device, List.of("PENDING", "TIMEOUT")))
                .thenReturn(Optional.of(command));

        Dto.PendingCommandResponse response = service.pending("dev-1");

        assertThat(response.getCommandId()).isEqualTo("cmd-1");
        assertThat(response.getValue()).isEqualTo("on");
        assertThat(command.getStatus()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("대기 명령이 없으면 null")
    void pendingEmpty() {
        givenDevice();
        when(commandRepository.findFirstByDeviceAndStatusInOrderByCreatedAtAsc(any(), any())).thenReturn(Optional.empty());

        assertThat(service.pending("dev-1")).isNull();
    }

    @Test
    @DisplayName("SENT 명령에 결과를 반영")
    void reportResult() {
        Command command = command("SENT", Instant.now());
        when(commandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(command));

        service.reportResult("cmd-1", result("FAILED", "gpio error"));

        assertThat(command.getStatus()).isEqualTo("FAILED");
        assertThat(command.getReason()).isEqualTo("gpio error");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "ACKED", "FAILED", "TIMEOUT"})
    @DisplayName("SENT 가 아닌 명령에 결과 보고하면 409, 상태 유지")
    void reportResultConflict(String status) {
        Command command = command(status, Instant.now());
        when(commandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(command));

        assertStatus(() -> service.reportResult("cmd-1", result("ACKED", null)), HttpStatus.CONFLICT);
        assertThat(command.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("없는 명령에 결과 보고하면 404")
    void reportResultUnknown() {
        when(commandRepository.findByCommandId("nope")).thenReturn(Optional.empty());

        assertStatus(() -> service.reportResult("nope", result("ACKED", null)), HttpStatus.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SENT"})
    @DisplayName("미완료 명령이 14초 지나면 조회 시 TIMEOUT")
    void commandDetailTimeout(String status) {
        Command command = command(status, Instant.now().minusSeconds(15));
        when(commandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(command));

        assertThat(service.commandDetail("cmd-1").getStatus()).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("14초 이내 미완료 명령은 그대로")
    void commandDetailFresh() {
        Command command = command("SENT", Instant.now().minusSeconds(5));
        when(commandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(command));

        assertThat(service.commandDetail("cmd-1").getStatus()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("완료된 명령은 오래돼도 TIMEOUT 으로 바뀌지 않는다")
    void commandDetailFinished() {
        Command command = command("ACKED", Instant.now().minusSeconds(60));
        when(commandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(command));

        assertThat(service.commandDetail("cmd-1").getStatus()).isEqualTo("ACKED");
    }


    // 규칙 저장

    @Test
    @DisplayName("규칙을 덮어쓰면 last_action 초기화")
    void setRuleResetsLastAction() {
        givenDevice();
        givenRule(25.0, "on");
        when(ruleRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        Dto.RuleRequest request = new Dto.RuleRequest();
        request.setMetric("temperature");
        request.setThreshold(30.0);

        Dto.RuleResponse response = service.setRule("dev-1", request);

        assertThat(response.getThreshold()).isEqualTo(30.0);
        assertThat(response.getLastAction()).isNull();
    }


    private void givenDevice() {
        when(deviceRepository.findByDeviceId("dev-1")).thenReturn(Optional.of(device));
    }

    private Rule givenRule(double threshold, String lastAction) {
        Rule rule = new Rule();
        rule.setDevice(device);
        rule.setMetric("temperature");
        rule.setThreshold(threshold);
        rule.setLastAction(lastAction);
        when(ruleRepository.findByDevice(device)).thenReturn(Optional.of(rule));
        return rule;
    }

    private Command savedCommand() {
        ArgumentCaptor<Command> captor = ArgumentCaptor.forClass(Command.class);
        verify(commandRepository).save(captor.capture());
        return captor.getValue();
    }

    private Command command(String status, Instant createdAt) {
        Command command = new Command();
        command.setDevice(device);
        command.setCommandId("cmd-1");
        command.setType("SET_LED");
        command.setValue("on");
        command.setStatus(status);
        ReflectionTestUtils.setField(command, "createdAt", createdAt);
        return command;
    }

    private Telemetry telemetry(long seq) {
        Telemetry telemetry = new Telemetry();
        telemetry.setSeq(seq);
        return telemetry;
    }

    private static Dto.RegisterRequest registerRequest(String deviceId, String fw) {
        Dto.RegisterRequest request = new Dto.RegisterRequest();
        request.setDeviceId(deviceId);
        request.setFw(fw);
        return request;
    }

    private static Dto.TelemetrySample sample(double temperature) {
        Dto.TelemetrySample sample = new Dto.TelemetrySample();
        sample.setSeq(1L);
        sample.setTs(Instant.now());
        sample.setTemperature(temperature);
        sample.setHumidity(50.0);
        return sample;
    }

    private static Dto.CommandResult result(String status, String reason) {
        Dto.CommandResult result = new Dto.CommandResult();
        result.setStatus(status);
        result.setReason(reason);
        return result;
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

}
