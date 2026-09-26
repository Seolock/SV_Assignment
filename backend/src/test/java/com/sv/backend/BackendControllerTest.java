package com.sv.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 요청 검증(@Valid, @Min), 상태 코드, JSON 키 형식을 확인. 서비스는 가짜 객체
@WebMvcTest(BackendController.class)
class BackendControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean BackendService service;


    @Test
    @DisplayName("신규 등록은 201, 같은 deviceId 재등록은 200")
    void registerStatus() throws Exception {
        when(service.register(any())).thenReturn(true, false);
        String body = "{\"deviceId\":\"dev-001\",\"deviceUrl\":\"socket://127.0.0.1:5555\"}";

        postJson("/api/v1/devices", body).andExpect(status().isCreated());
        postJson("/api/v1/devices", body).andExpect(status().isOk());
    }


    @Test
    @DisplayName("deviceId 없이 등록하면 400, 서비스 호출 안 함")
    void registerWithoutDeviceId() throws Exception {
        postJson("/api/v1/devices", "{\"deviceUrl\":\"socket://127.0.0.1:5555\"}").andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }


    @Test
    @DisplayName("하트비트는 본문 없이 204")
    void heartbeat() throws Exception {
        mvc.perform(post("/api/v1/devices/dev-001/heartbeat")).andExpect(status().isNoContent());
        verify(service).heartbeat("dev-001");
    }


    @Test
    @DisplayName("정상 텔레메트리는 200 으로 적재")
    void telemetryAccepted() throws Exception {
        postJson("/api/v1/devices/dev-001/telemetry",
                "{\"seq\":1024,\"ts\":\"2026-09-10T09:00:00Z\",\"temperature\":24.5,\"humidity\":60.2}")
                .andExpect(status().isOk());
        verify(service).saveTelemetry(eq("dev-001"), any());
    }


    @Test
    @DisplayName("temperature 가 빠진 텔레메트리는 400 (규칙 판정 NPE 방지)")
    void telemetryWithoutTemperature() throws Exception {
        postJson("/api/v1/devices/dev-001/telemetry",
                "{\"seq\":1,\"ts\":\"2026-09-10T09:00:00Z\",\"humidity\":60.2}")
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }


    @Test
    @DisplayName("ts 를 시각으로 해석할 수 없으면 400")
    void telemetryWithBadTimestamp() throws Exception {
        postJson("/api/v1/devices/dev-001/telemetry",
                "{\"seq\":1,\"ts\":\"yesterday\",\"temperature\":24.5,\"humidity\":60.2}")
                .andExpect(status().isBadRequest());
    }


    @Test
    @DisplayName("LED 명령 value 가 on/off 가 아니면 400, type 이 SET_LED 가 아니면 400")
    void invalidCommand() throws Exception {
        postJson("/api/v1/devices/dev-001/commands", "{\"type\":\"SET_LED\",\"value\":\"blink\",\"source\":\"cli\"}")
                .andExpect(status().isBadRequest());
        postJson("/api/v1/devices/dev-001/commands", "{\"type\":\"REBOOT\",\"value\":\"on\",\"source\":\"cli\"}")
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }


    @Test
    @DisplayName("명령 생성은 201 과 commandId")
    void createCommand() throws Exception {
        Dto.CommandCreateResponse response = new Dto.CommandCreateResponse();
        response.setCommandId("cmd-7f3a");
        response.setStatus("PENDING");
        when(service.createCommand(eq("dev-001"), any())).thenReturn(response);

        postJson("/api/v1/devices/dev-001/commands", "{\"type\":\"SET_LED\",\"value\":\"on\",\"source\":\"cli\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commandId").value("cmd-7f3a"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }


    @Test
    @DisplayName("결과 보고 status 가 ACKED/FAILED 가 아니면 400")
    void invalidResultStatus() throws Exception {
        patchJson("/api/v1/commands/cmd-7f3a", "{\"status\":\"DONE\"}").andExpect(status().isBadRequest());
        patchJson("/api/v1/commands/cmd-7f3a", "{\"status\":\"PENDING\"}").andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }


    @Test
    @DisplayName("이미 끝난 명령에 결과 보고하면 서비스의 409 가 그대로 응답")
    void terminalCommandConflict() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "command is not PENDING: cmd-7f3a"))
                .when(service).reportResult(eq("cmd-7f3a"), any());

        patchJson("/api/v1/commands/cmd-7f3a", "{\"status\":\"ACKED\"}").andExpect(status().isConflict());
    }


    @Test
    @DisplayName("없는 장치 조회는 404")
    void unknownDevice() throws Exception {
        when(service.deviceDetail("dev-999")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found: dev-999"));

        mvc.perform(get("/api/v1/devices/dev-999")).andExpect(status().isNotFound());
    }


    @Test
    @DisplayName("규칙 metric 이 temperature 가 아니거나 threshold 가 없으면 400")
    void invalidRule() throws Exception {
        putJson("/api/v1/devices/dev-001/rule", "{\"metric\":\"humidity\",\"threshold\":70}").andExpect(status().isBadRequest());
        putJson("/api/v1/devices/dev-001/rule", "{\"metric\":\"temperature\"}").andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }


    @Test
    @DisplayName("limit 이 1 미만이면 400")
    void limitTooSmall() throws Exception {
        mvc.perform(get("/api/v1/devices/dev-001/commands?limit=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/devices/dev-001/telemetry?limit=-1")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }


    @Test
    @DisplayName("응답 JSON 키는 Java 필드 이름 그대로 camelCase")
    void responseIsCamelCase() throws Exception {
        Dto.DeviceSummary summary = new Dto.DeviceSummary();
        summary.setDeviceId("dev-001");
        summary.setStatus("ONLINE");
        summary.setLastHeartbeatAt(Instant.parse("2026-09-10T09:00:05Z"));
        when(service.listDevices()).thenReturn(List.of(summary));

        mvc.perform(get("/api/v1/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceId").value("dev-001"))
                .andExpect(jsonPath("$[0].lastHeartbeatAt").value("2026-09-10T09:00:05Z"));
    }


    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }


    private ResultActions patchJson(String url, String body) throws Exception {
        return mvc.perform(patch(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }


    private ResultActions putJson(String url, String body) throws Exception {
        return mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

}
