/*
 * Copyright 2024-2026 Firefly Software Solutions Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fireflyframework.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fireflyframework.workflow.properties.WorkflowProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Test configuration for workflow engine tests.
 */
@TestConfiguration
public class TestConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    public WorkflowProperties workflowProperties() {
        WorkflowProperties properties = new WorkflowProperties();
        properties.setEnabled(true);
        properties.setDefaultTimeout(Duration.ofMinutes(5));
        properties.setDefaultStepTimeout(Duration.ofSeconds(30));
        
        WorkflowProperties.StateConfig stateConfig = new WorkflowProperties.StateConfig();
        stateConfig.setEnabled(true);
        stateConfig.setDefaultTtl(Duration.ofHours(1));
        stateConfig.setKeyPrefix("test-workflow");
        properties.setState(stateConfig);
        
        WorkflowProperties.EventConfig eventConfig = new WorkflowProperties.EventConfig();
        eventConfig.setEnabled(true);
        eventConfig.setPublishStepEvents(true);
        eventConfig.setDefaultDestination("test-workflow-events");
        properties.setEvents(eventConfig);
        
        WorkflowProperties.RetryConfig retryConfig = new WorkflowProperties.RetryConfig();
        retryConfig.setMaxAttempts(3);
        retryConfig.setInitialDelay(Duration.ofMillis(100));
        properties.setRetry(retryConfig);
        
        return properties;
    }
}
