package com.thoughtworks.go.config.update;

import com.thoughtworks.go.config.Agents;
import com.thoughtworks.go.config.CruiseConfig;
import com.thoughtworks.go.config.UpdateConfigCommand;
import com.thoughtworks.go.config.commands.EntityConfigUpdateCommand;
import com.thoughtworks.go.server.service.GoConfigService;
import com.thoughtworks.go.validation.ConfigUpdateValidator;

public class AgentsUpdateCommand implements EntityConfigUpdateCommand<Agents> {
    private final GoConfigService goConfigService;
    private final UpdateConfigCommand command;
    private final ConfigUpdateValidator validator;

    public AgentsUpdateCommand(GoConfigService goConfigService, UpdateConfigCommand command, ConfigUpdateValidator validator) {
        this.goConfigService = goConfigService;
        this.command = command;
        this.validator = validator;
    }

    @Override
    public boolean canContinue(CruiseConfig cruiseConfig) {
        return true;
    }

    @Override
    public void update(CruiseConfig preprocessedConfig) throws Exception {
        command.update(preprocessedConfig);
    }

    @Override
    public boolean isValid(CruiseConfig preprocessedConfig) {
        return validator.isValid(preprocessedConfig);
    }

    @Override
    public Agents getEntityConfig() {
        return goConfigService.agents();
    }
}

