package com.openjiuwen.service.app.controller.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.controller.query.QueryIngressSupport;
import com.openjiuwen.service.spec.dto.ResetConversationRequest;
import com.openjiuwen.service.spec.dto.ResetConversationResponse;
import com.openjiuwen.service.spec.lifecycle.AgentReadiness;
import com.openjiuwen.service.spec.paths.AgentServicePaths;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

/**
 * MVC stack reset conversation controller ({@code POST /v1/reset_conversation} and legacy path).
 */
@RestController
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ResetConversationMvcController {

    private final ObjectProvider<ServeOrchestrator> orchestratorProvider;
    private final ObjectProvider<AgentReadiness> readinessProvider;
    private final ObjectMapper objectMapper;

    public ResetConversationMvcController(ObjectProvider<ServeOrchestrator> orchestratorProvider,
                                          ObjectProvider<AgentReadiness> readinessProvider,
                                          ObjectMapper objectMapper) {
        this.orchestratorProvider = orchestratorProvider;
        this.readinessProvider = readinessProvider;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = AgentServicePaths.RESET_CONVERSATION_V1, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResetConversationResponse resetV1(@RequestBody ResetConversationRequest request,
                                           HttpServletResponse response) throws IOException {
        return handleReset(request, response);
    }

    @PostMapping(value = AgentServicePaths.RESET_CONVERSATION_LEGACY, produces = MediaType.APPLICATION_JSON_VALUE)
    @ConditionalOnProperty(prefix = "openjiuwen.service.query", name = "legacy-path-enabled",
            havingValue = "true", matchIfMissing = true)
    public ResetConversationResponse resetLegacy(@RequestBody ResetConversationRequest request,
                                                 HttpServletResponse response) throws IOException {
        return handleReset(request, response);
    }

    private ResetConversationResponse handleReset(ResetConversationRequest request,
                                                  HttpServletResponse response) throws IOException {
        ResetIngressSupport.ValidationResult validation = ResetIngressSupport.validate(request);
        if (!validation.valid()) {
            writeJson(response, validation.errorStatus(), validation.errorBody());
            return null;
        }
        if (!isAgentReady()) {
            writeJson(response, HttpStatus.SERVICE_UNAVAILABLE.value(), QueryIngressSupport.agentNotReady());
            return null;
        }
        ServeOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
        if (orchestrator == null) {
            writeJson(response, HttpStatus.SERVICE_UNAVAILABLE.value(), QueryIngressSupport.serviceUnavailable());
            return null;
        }
        orchestrator.resetConversation(validation.conversationId());
        return ResetConversationResponse.ok(validation.conversationId());
    }

    private boolean isAgentReady() {
        AgentReadiness readiness = readinessProvider.getIfAvailable();
        return readiness == null || readiness.isAgentLoaded();
    }

    private void writeJson(HttpServletResponse response, int status, Object value) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), value);
    }
}
