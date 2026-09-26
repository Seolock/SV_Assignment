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
import org.junit.jupiter.api.Nested;
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
        device.setDeviceId("dev-001");
    }


    @Nested
    @DisplayName("규칙 판정과 반복 생성 방지 (텔레메트리 적재 시)")
    class RuleEvaluation {

        Rule rule;

        @BeforeEach
        void setUp() {
            givenDevice();
            rule = new Rule();
            rule.setDevice(device);
            rule.setMetric("temperature");
            rule.setThreshold(30.0);
            when(ruleRepository.findByDevice(device)).thenReturn(Optional.of(rule));
        }

        @Test
        @DisplayName("임계치를 처음 넘으면 source=rule 인 LED ON 명령을 만들고 last_action 을 on 으로")
        void firstCrossingCreatesOn() {
            service.saveTelemetry("dev-001", sample(1, 35.0));

            Command command = savedCommands(1).get(0);
            assertThat(command.getType()).isEqualTo("SET_LED");
            assertThat(command.getValue()).isEqualTo("on");
            assertThat(command.getSource()).isEqualTo("rule");
            assertThat(command.getStatus()).isEqualTo("PENDING");
            assertThat(command.getCommandId()).startsWith("cmd-");
            assertThat(command.getDevice()).isSameAs(device);
            assertThat(rule.getLastAction()).isEqualTo("on");
        }

        @Test
        @DisplayName("시나리오 3: 온도를 35℃로 고정해 계속 보고해도 명령은 한 번만 생성")
        void sustainedHighTemperatureCreatesOnlyOneCommand() {
            for (long seq = 1; seq <= 5; seq++) {
                service.saveTelemetry("dev-001", sample(seq, 35.0));
            }

            assertThat(savedCommands(1)).extracting(Command::getValue).containsExactly("on");
            verify(telemetryRepository, times(5)).save(any(Telemetry.class));
        }

        @Test
        @DisplayName("ON 이후 임계치 이하로 내려오면 LED OFF 명령 생성")
        void droppingBelowThresholdCreatesOff() {
            rule.setLastAction("on");

            service.saveTelemetry("dev-001", sample(1, 28.0));

            assertThat(savedCommands(1).get(0).getValue()).isEqualTo("off");
            assertThat(rule.getLastAction()).isEqualTo("off");
        }

        @Test
        @DisplayName("임계치와 같은 온도는 '이하'로 판정해 OFF")
        void equalToThresholdIsOff() {
            rule.setLastAction("on");

            service.saveTelemetry("dev-001", sample(1, 30.0));

            assertThat(savedCommands(1).get(0).getValue()).isEqualTo("off");
        }

        @Test
        @DisplayName("OFF 이후 계속 정상 온도면 명령을 만들지 않음")
        void stayingBelowAfterOffCreatesNothing() {
            rule.setLastAction("off");

            service.saveTelemetry("dev-001", sample(1, 25.0));
            service.saveTelemetry("dev-001", sample(2, 26.0));

            verify(commandRepository, never()).save(any());
            assertThat(rule.getLastAction()).isEqualTo("off");
        }

        @Test
        @DisplayName("온도가 임계치를 오르내리면 넘나들 때마다 ON/OFF 가 번갈아 한 번씩")
        void oscillationCreatesCommandPerCrossing() {
            double[] temps = {35.0, 36.0, 28.0, 27.0, 33.0};
            for (int i = 0; i < temps.length; i++) {
                service.saveTelemetry("dev-001", sample(i + 1, temps[i]));
            }

            assertThat(savedCommands(3)).extracting(Command::getValue).containsExactly("on", "off", "on");
        }

        @Test
        @DisplayName("현재 동작: last_action 이 null 인데 정상 온도면 OFF 를 한 번 생성 (명세서 판정표는 '아무것도 안 함')")
        void firstNormalTemperatureCreatesOffOnce() {
            service.saveTelemetry("dev-001", sample(1, 25.0));
            service.saveTelemetry("dev-001", sample(2, 25.0));

            assertThat(savedCommands(1)).extracting(Command::getValue).containsExactly("off");
        }

        @Test
        @DisplayName("임계치를 다시 설정하면 last_action 이 초기화되어 같은 조건에서도 다시 ON")
        void resettingRuleAllowsCommandAgain() {
            service.saveTelemetry("dev-001", sample(1, 35.0));
            service.saveTelemetry("dev-001", sample(2, 35.0));

            Dto.RuleRequest request = new Dto.RuleRequest();
            request.setMetric("temperature");
            request.setThreshold(30.0);
            service.putRule("dev-001", request);
            assertThat(rule.getLastAction()).isNull();

            service.saveTelemetry("dev-001", sample(3, 35.0));

            assertThat(savedCommands(2)).extracting(Command::getValue).containsExactly("on", "on");
        }

        private List<Command> savedCommands(int expectedCount) {
            ArgumentCaptor<Command> captor = ArgumentCaptor.forClass(Command.class);
            verify(commandRepository, times(expectedCount)).save(captor.capture());
            return captor.getAllValues();
        }
    }


    @Nested
    @DisplayName("텔레메트리 적재")
    class SaveTelemetry {

        @Test
        @DisplayName("규칙이 없는 장치는 측정값만 저장하고 명령은 만들지 않음")
        void noRuleNoCommand() {
            givenDevice();
            when(ruleRepository.findByDevice(device)).thenReturn(Optional.empty());

            service.saveTelemetry("dev-001", sample(1, 99.0));

            ArgumentCaptor<Telemetry> captor = ArgumentCaptor.forClass(Telemetry.class);
            verify(telemetryRepository).save(captor.capture());
            assertThat(captor.getValue().getDevice()).isSameAs(device);
            assertThat(captor.getValue().getSeq()).isEqualTo(1L);
            assertThat(captor.getValue().getTemperature()).isEqualTo(99.0);
            verify(commandRepository, never()).save(any());
        }

        @Test
        @DisplayName("등록되지 않은 장치의 측정값은 404, 아무것도 저장하지 않음")
        void unknownDevice() {
            when(deviceRepository.findByDeviceId("dev-999")).thenReturn(Optional.empty());

            assertStatus(() -> service.saveTelemetry("dev-999", sample(1, 35.0)), HttpStatus.NOT_FOUND);
            verifyNoInteractions(telemetryRepository, commandRepository);
        }
    }


    @Nested
    @DisplayName("명령 상태 전이 (결과 보고)")
    class CommandTransition {

        Command command;

        @BeforeEach
        void setUp() {
            command = new Command();
            command.setCommandId("cmd-7f3a");
            command.setStatus("PENDING");
        }

        @Test
        @DisplayName("PENDING -> ACKED")
        void pendingToAcked() {
            givenCommand();

            service.reportResult("cmd-7f3a", result("ACKED", null));

            assertThat(command.getStatus()).isEqualTo("ACKED");
            assertThat(command.getReason()).isNull();
        }

        @Test
        @DisplayName("PENDING -> FAILED, 에이전트 타임아웃 사유 저장")
        void pendingToFailedWithReason() {
            givenCommand();

            service.reportResult("cmd-7f3a", result("FAILED", "device_timeout"));

            assertThat(command.getStatus()).isEqualTo("FAILED");
            assertThat(command.getReason()).isEqualTo("device_timeout");
        }

        @Test
        @DisplayName("에이전트가 같은 ACK 를 재전송하면 두 번째는 409")
        void duplicateReportIsConflict() {
            givenCommand();
            service.reportResult("cmd-7f3a", result("ACKED", null));

            assertStatus(() -> service.reportResult("cmd-7f3a", result("ACKED", null)), HttpStatus.CONFLICT);
            assertThat(command.getStatus()).isEqualTo("ACKED");
        }

        @Test
        @DisplayName("ACKED 명령을 FAILED 로 바꾸려 하면 409, 상태 유지")
        void ackedCannotBecomeFailed() {
            command.setStatus("ACKED");
            givenCommand();

            assertStatus(() -> service.reportResult("cmd-7f3a", result("FAILED", "device_timeout")), HttpStatus.CONFLICT);
            assertThat(command.getStatus()).isEqualTo("ACKED");
            assertThat(command.getReason()).isNull();
        }

        @Test
        @DisplayName("FAILED 명령에 늦게 도착한 ACK 는 409, 상태 유지")
        void failedCannotBecomeAcked() {
            command.setStatus("FAILED");
            command.setReason("device_timeout");
            givenCommand();

            assertStatus(() -> service.reportResult("cmd-7f3a", result("ACKED", null)), HttpStatus.CONFLICT);
            assertThat(command.getStatus()).isEqualTo("FAILED");
            assertThat(command.getReason()).isEqualTo("device_timeout");
        }

        @Test
        @DisplayName("없는 명령에 결과 보고하면 404")
        void unknownCommand() {
            when(commandRepository.findByCommandId("cmd-none")).thenReturn(Optional.empty());

            assertStatus(() -> service.reportResult("cmd-none", result("ACKED", null)), HttpStatus.NOT_FOUND);
        }

        private void givenCommand() {
            when(commandRepository.findByCommandId("cmd-7f3a")).thenReturn(Optional.of(command));
        }

        private Dto.CommandResult result(String status, String reason) {
            Dto.CommandResult result = new Dto.CommandResult();
            result.setStatus(status);
            result.setReason(reason);
            return result;
        }
    }


    @Nested
    @DisplayName("명령 생성과 폴링")
    class CommandCreateAndPoll {

        @Test
        @DisplayName("콘솔 명령은 PENDING 으로 생성되고 cmd- 로 시작하는 ID 를 받음")
        void createFromConsole() {
            givenDevice();
            when(commandRepository.save(any(Command.class))).thenAnswer(inv -> inv.getArgument(0));

            Dto.CommandCreateResponse response = service.createCommand("dev-001", commandRequest("on"));

            assertThat(response.getCommandId()).startsWith("cmd-");
            assertThat(response.getStatus()).isEqualTo("PENDING");
            ArgumentCaptor<Command> captor = ArgumentCaptor.forClass(Command.class);
            verify(commandRepository).save(captor.capture());
            assertThat(captor.getValue().getSource()).isEqualTo("cli");
            assertThat(captor.getValue().getValue()).isEqualTo("on");
        }

        @Test
        @DisplayName("연달아 만든 명령은 서로 다른 ID")
        void commandIdsAreDistinct() {
            givenDevice();
            when(commandRepository.save(any(Command.class))).thenAnswer(inv -> inv.getArgument(0));

            String first = service.createCommand("dev-001", commandRequest("on")).getCommandId();
            String second = service.createCommand("dev-001", commandRequest("off")).getCommandId();

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("에이전트 폴링은 PENDING 상태만 조회")
        void pollingOnlyPending() {
            givenDevice();
            Command pending = new Command();
            pending.setCommandId("cmd-1");
            pending.setType("SET_LED");
            pending.setValue("on");
            when(commandRepository.findByDeviceAndStatusOrderByCreatedAtAsc(device, "PENDING")).thenReturn(List.of(pending));

            List<Dto.PendingCommandResponse> result = service.pending("dev-001");

            assertThat(result).extracting(Dto.PendingCommandResponse::getCommandId).containsExactly("cmd-1");
        }

        private Dto.CommandRequest commandRequest(String value) {
            Dto.CommandRequest request = new Dto.CommandRequest();
            request.setType("SET_LED");
            request.setValue(value);
            request.setSource("cli");
            return request;
        }
    }


    @Nested
    @DisplayName("장치 등록, 하트비트, 온라인 판정")
    class DeviceLifecycle {

        @Test
        @DisplayName("처음 보는 장치는 새로 저장하고 true (201)")
        void registerNew() {
            when(deviceRepository.findByDeviceId("dev-002")).thenReturn(Optional.empty());

            boolean created = service.register(registerRequest("dev-002", "socket://127.0.0.1:5555", "sim-1.0"));

            assertThat(created).isTrue();
            ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
            verify(deviceRepository).save(captor.capture());
            assertThat(captor.getValue().getDeviceId()).isEqualTo("dev-002");
            assertThat(captor.getValue().getDeviceUrl()).isEqualTo("socket://127.0.0.1:5555");
            assertThat(captor.getValue().getFw()).isEqualTo("sim-1.0");
        }

        @Test
        @DisplayName("에이전트 재기동으로 재등록하면 URL/펌웨어만 갱신하고 false (200)")
        void reRegister() {
            device.setDeviceUrl("/dev/ttyUSB0");
            givenDevice();

            boolean created = service.register(registerRequest("dev-001", "socket://127.0.0.1:5555", "sim-1.1"));

            assertThat(created).isFalse();
            assertThat(device.getDeviceUrl()).isEqualTo("socket://127.0.0.1:5555");
            assertThat(device.getFw()).isEqualTo("sim-1.1");
            verify(deviceRepository, never()).save(any());
        }

        @Test
        @DisplayName("하트비트를 받으면 마지막 하트비트 시각이 갱신되고 ONLINE")
        void heartbeatMakesOnline() {
            givenDevice();
            Instant before = Instant.now();

            service.heartbeat("dev-001");

            assertThat(device.getLastHeartbeatAt()).isAfterOrEqualTo(before);
            assertThat(device.status()).isEqualTo("ONLINE");
        }

        @Test
        @DisplayName("등록되지 않은 장치의 하트비트는 404")
        void heartbeatUnknownDevice() {
            when(deviceRepository.findByDeviceId("dev-999")).thenReturn(Optional.empty());

            assertStatus(() -> service.heartbeat("dev-999"), HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("하트비트가 한 번도 없으면 OFFLINE")
        void neverHeartbeatIsOffline() {
            assertThat(device.status()).isEqualTo("OFFLINE");
        }

        @Test
        @DisplayName("마지막 하트비트가 30초 이내면 ONLINE, 지나면 OFFLINE (케이블 분리 등)")
        void heartbeatWindow() {
            device.setLastHeartbeatAt(Instant.now().minusSeconds(25));
            assertThat(device.status()).isEqualTo("ONLINE");

            device.setLastHeartbeatAt(Instant.now().minusSeconds(31));
            assertThat(device.status()).isEqualTo("OFFLINE");
        }

        private Dto.RegisterRequest registerRequest(String id, String url, String fw) {
            Dto.RegisterRequest request = new Dto.RegisterRequest();
            request.setDeviceId(id);
            request.setDeviceUrl(url);
            request.setFw(fw);
            return request;
        }
    }


    @Nested
    @DisplayName("측정 이력 조회 (--last)")
    class LoadTelemetries {

        @Test
        @DisplayName("last=1h30m 이면 지금부터 90분 전 이후를 조회")
        void lastDuration() {
            givenDevice();
            Instant before = Instant.now();

            service.loadTelemetries("dev-001", "1h30m", 50);

            ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
            verify(telemetryRepository).findByDeviceAndTsGreaterThanEqualOrderByTsDesc(eq(device), from.capture(), eq(Limit.of(50)));
            assertThat(from.getValue())
                    .isAfterOrEqualTo(before.minus(Duration.ofMinutes(90)))
                    .isBeforeOrEqualTo(Instant.now().minus(Duration.ofMinutes(90)));
        }

        @Test
        @DisplayName("최신 N건을 가져와 오래된 순으로 반환")
        void returnsOldestFirst() {
            givenDevice();
            when(telemetryRepository.findByDeviceAndTsGreaterThanEqualOrderByTsDesc(eq(device), any(), any()))
                    .thenReturn(List.of(telemetry(3), telemetry(2), telemetry(1)));

            List<Dto.TelemetrySample> result = service.loadTelemetries("dev-001", "1h", 3);

            assertThat(result).extracting(Dto.TelemetrySample::getSeq).containsExactly(1L, 2L, 3L);
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "1d", "0m", "-1h", "", "h"})
        @DisplayName("형식이 틀리거나 0 이하인 last 는 400")
        void invalidLast(String last) {
            givenDevice();

            assertStatus(() -> service.loadTelemetries("dev-001", last, 50), HttpStatus.BAD_REQUEST);
            verifyNoInteractions(telemetryRepository);
        }

        private Telemetry telemetry(long seq) {
            Telemetry telemetry = new Telemetry();
            telemetry.setSeq(seq);
            return telemetry;
        }
    }


    @Nested
    @DisplayName("규칙 조회와 설정")
    class RuleSettings {

        @Test
        @DisplayName("규칙이 없는 장치는 null (404 아님)")
        void noRuleIsNull() {
            givenDevice();
            when(ruleRepository.findByDevice(device)).thenReturn(Optional.empty());

            assertThat(service.getRule("dev-001")).isNull();
        }

        @Test
        @DisplayName("규칙이 없으면 새로 만들어 저장 (upsert)")
        void putCreatesRule() {
            givenDevice();
            when(ruleRepository.findByDevice(device)).thenReturn(Optional.empty());

            service.putRule("dev-001", ruleRequest(30.0));

            ArgumentCaptor<Rule> captor = ArgumentCaptor.forClass(Rule.class);
            verify(ruleRepository).save(captor.capture());
            assertThat(captor.getValue().getDevice()).isSameAs(device);
            assertThat(captor.getValue().getThreshold()).isEqualTo(30.0);
            assertThat(captor.getValue().getLastAction()).isNull();
        }

        @Test
        @DisplayName("기존 규칙의 임계치를 바꾸면 교체하고 last_action 초기화")
        void putReplacesRuleAndResetsLastAction() {
            givenDevice();
            Rule rule = new Rule();
            rule.setThreshold(30.0);
            rule.setLastAction("on");
            when(ruleRepository.findByDevice(device)).thenReturn(Optional.of(rule));

            service.putRule("dev-001", ruleRequest(40.0));

            assertThat(rule.getThreshold()).isEqualTo(40.0);
            assertThat(rule.getLastAction()).isNull();
            verify(ruleRepository).save(rule);
        }

        private Dto.RuleRequest ruleRequest(double threshold) {
            Dto.RuleRequest request = new Dto.RuleRequest();
            request.setMetric("temperature");
            request.setThreshold(threshold);
            return request;
        }
    }


    private void givenDevice() {
        when(deviceRepository.findByDeviceId("dev-001")).thenReturn(Optional.of(device));
    }


    private Dto.TelemetrySample sample(long seq, double temperature) {
        Dto.TelemetrySample sample = new Dto.TelemetrySample();
        sample.setSeq(seq);
        sample.setTs(Instant.now());
        sample.setTemperature(temperature);
        sample.setHumidity(60.0);
        return sample;
    }


    private void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(status);
    }

}
