package com.thoughtworks.go.validation;

import com.thoughtworks.go.config.AgentConfig;
import com.thoughtworks.go.config.ConfigSaveValidationContext;
import com.thoughtworks.go.config.CruiseConfig;

import java.util.ArrayList;
import java.util.List;

public class AgentConfigsUpdateValidator implements ConfigUpdateValidator {
    private final List<String> agentsUuids;
    private final List<AgentConfig> validatedAgents = new ArrayList<>();

    public AgentConfigsUpdateValidator(List<String> agentsUuids) {
        this.agentsUuids = agentsUuids;
    }

    @Override
    public boolean isValid(CruiseConfig preprocessedConfig) {
        boolean isValid = true;
        for (String uuid : agentsUuids) {
            AgentConfig agentConfig = preprocessedConfig.agents().getAgentByUuid(uuid);
            isValid = agentConfig.validateTree(ConfigSaveValidationContext.forChain(preprocessedConfig)) && isValid;
            validatedAgents.add(agentConfig);
        }
        return isValid;
    }

    public List<AgentConfig> getUpdatedAgents() {
        return validatedAgents;
    }
}

