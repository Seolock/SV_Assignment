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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 상태 코드와 요청 검증만 확인. 서비스는 가짜 객체
@WebMvcTest(BackendController.class)
class BackendControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean BackendService service;


    @Test
    @DisplayName("신규 등록은 201, 재등록은 200")
    void registerStatus() throws Exception {
        when(service.register(any())).thenReturn(true, false);
        String body = "{\"device_id\":\"dev-1\",\"fw\":\"1.0\"}";

        postJson("/api/v1/devices", body).andExpect(status().isCreated());
        postJson("/api/v1/devices", body).andExpect(status().isOk());
    }

    @Test
    @DisplayName("device_id 가 비어 있으면 400")
    void registerBlankId() throws Exception {
        postJson("/api/v1/devices", "{\"device_id\":\" \"}").andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("하트비트는 204")
    void heartbeat() throws Exception {
        mvc.perform(post("/api/v1/devices/dev-1/heartbeat")).andExpect(status().isNoContent());
        verify(service).heartbeat("dev-1");
    }

    @Test
    @DisplayName("텔레메트리 필수 필드 누락 시 400")
    void telemetryMissingField() throws Exception {
        postJson("/api/v1/devices/dev-1/telemetry", "{\"seq\":1,\"ts\":\"2026-09-28T00:00:00Z\",\"temperature\":20.0}")
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("텔레메트리 조회 limit < 1 이면 400")
    void telemetryLimitZero() throws Exception {
        mvc.perform(get("/api/v1/devices/dev-1/telemetry").param("limit", "0")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("텔레메트리 조회 기본값은 last=1h, limit=50")
    void telemetryDefaults() throws Exception {
        mvc.perform(get("/api/v1/devices/dev-1/telemetry")).andExpect(status().isOk());
        verify(service).loadTelemetries("dev-1", "1h", 50);
    }

    @Test
    @DisplayName("명령 생성은 201 과 command_id 반환")
    void createCommand() throws Exception {
        Dto.CommandCreateResponse response = new Dto.CommandCreateResponse();
        response.setCommandId("cmd-1");
        response.setStatus("PENDING");
        when(service.createCommand(eq("dev-1"), any())).thenReturn(response);

        postJson("/api/v1/devices/dev-1/commands", "{\"type\":\"SET_LED\",\"value\":\"on\",\"source\":\"console\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.command_id").value("cmd-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("허용되지 않은 명령 type/value 는 400")
    void createCommandInvalid() throws Exception {
        postJson("/api/v1/devices/dev-1/commands", "{\"type\":\"SET_LED\",\"value\":\"blink\",\"source\":\"console\"}")
                .andExpect(status().isBadRequest());
        postJson("/api/v1/devices/dev-1/commands", "{\"type\":\"REBOOT\",\"value\":\"on\",\"source\":\"console\"}")
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("명령 이력 limit < 1 이면 400")
    void commandsLimitZero() throws Exception {
        mvc.perform(get("/api/v1/devices/dev-1/commands").param("limit", "0")).andExpect(status().isBadRequest());
        verify(service, never()).loadCommands(any(), anyInt());
    }

    @Test
    @DisplayName("대기 명령이 없으면 200 과 빈 바디")
    void pendingEmpty() throws Exception {
        when(service.pending("dev-1")).thenReturn(null);

        mvc.perform(get("/api/v1/devices/dev-1/commands/pending"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("결과 보고 status 가 ACKED/FAILED 가 아니면 400")
    void reportResultInvalid() throws Exception {
        mvc.perform(patch("/api/v1/commands/cmd-1").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DONE\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("규칙 metric 이 temperature 가 아니면 400")
    void setRuleInvalid() throws Exception {
        mvc.perform(put("/api/v1/devices/dev-1/rule").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metric\":\"humidity\",\"threshold\":50}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("서비스의 404/409 는 그대로 응답 코드가 된다")
    void serviceErrorsPropagate() throws Exception {
        when(service.deviceDetail("nope")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT)).when(service).reportResult(eq("cmd-1"), any());

        mvc.perform(get("/api/v1/devices/nope")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/commands/cmd-1").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACKED\"}"))
                .andExpect(status().isConflict());
    }


    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

}
