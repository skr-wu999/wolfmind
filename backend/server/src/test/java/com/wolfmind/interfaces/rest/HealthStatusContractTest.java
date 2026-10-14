package com.wolfmind.interfaces.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HealthStatusContractTest {
    private MockMvc endpoint(HealthIndicator... indicators) {
        var beans = new DefaultListableBeanFactory();
        for (int i = 0; i < indicators.length; i++) beans.registerSingleton("check-" + i, indicators[i]);
        return MockMvcBuilders.standaloneSetup(new HealthController(beans.getBeanProvider(HealthIndicator.class))).build();
    }

    @Test void requiredDependencyFailureReturns503AndKeepsTheDependencyDetails() throws Exception {
        endpoint(HealthChecks.unhealthy("redis", true, () -> "unavailable"),
                HealthChecks.healthy("sqlite", true, () -> "ready"))
                .perform(get("/api/v1/health")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.version").value("0.1.0"))
                .andExpect(jsonPath("$.dependencies[0].name").value("redis"))
                .andExpect(jsonPath("$.dependencies[0].required").value(true))
                .andExpect(jsonPath("$.dependencies[0].detail").value("unavailable"))
                .andExpect(jsonPath("$.dependencies[1].status").value("UP"));
    }

    @Test void optionalModelFailureDoesNotMakeTheServiceUnavailable() throws Exception {
        endpoint(HealthChecks.healthy("event-store", true, () -> "ready"),
                HealthChecks.unhealthy("llm", false, () -> "rule fallback"))
                .perform(get("/api/v1/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.dependencies[1].status").value("DOWN"))
                .andExpect(jsonPath("$.dependencies[1].required").value(false));
    }

    @Test void healthyRequiredDependenciesReturn200() throws Exception {
        endpoint(HealthChecks.healthy("redis", true, () -> "PONG"),
                HealthChecks.healthy("sqlite", true, () -> "ready"))
                .perform(get("/api/v1/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.dependencies.length()").value(2));
    }
}
