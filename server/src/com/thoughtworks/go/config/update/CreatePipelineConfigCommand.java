package com.thoughtworks.go.config.update;

import com.thoughtworks.go.config.BasicCruiseConfig;
import com.thoughtworks.go.config.CruiseConfig;
import com.thoughtworks.go.config.PipelineConfig;
import com.thoughtworks.go.config.PipelineConfigSaveValidationContext;
import com.thoughtworks.go.config.commands.EntityConfigUpdateCommand;
import com.thoughtworks.go.i18n.LocalizedMessage;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.service.GoConfigService;
import com.thoughtworks.go.server.service.result.LocalizedOperationResult;
import com.thoughtworks.go.serverhealth.HealthStateType;

public class CreatePipelineConfigCommand implements EntityConfigUpdateCommand<PipelineConfig> {
    private final GoConfigService goConfigService;
    private final PipelineConfig pipelineConfig;
    private final Username currentUser;
    private final LocalizedOperationResult result;
    private final String groupName;
    public String group;

    public CreatePipelineConfigCommand(GoConfigService goConfigService, PipelineConfig pipelineConfig, Username currentUser, LocalizedOperationResult result, final String groupName) {
        this.goConfigService = goConfigService;
        this.pipelineConfig = pipelineConfig;
        this.currentUser = currentUser;
        this.result = result;
        this.groupName = groupName;
    }

    @Override
    public void update(CruiseConfig cruiseConfig) throws Exception {
        cruiseConfig.addPipelineWithoutValidation(groupName, pipelineConfig);
    }

    @Override
    public boolean isValid(CruiseConfig preprocessedConfig) {
        PipelineConfig preprocessedPipelineConfig = preprocessedConfig.getPipelineConfigByName(pipelineConfig.name());
        boolean isValid = preprocessedPipelineConfig.validateTree(PipelineConfigSaveValidationContext.forChain(true, groupName, preprocessedConfig, preprocessedPipelineConfig));
        if (!isValid) BasicCruiseConfig.copyErrors(preprocessedPipelineConfig, pipelineConfig);
        return isValid;
    }

    @Override
    public PipelineConfig getEntityConfig() {
        return pipelineConfig;
    }

    @Override
    public boolean canContinue(CruiseConfig cruiseConfig) {
        if (goConfigService.groups().hasGroup(groupName) && !goConfigService.isUserAdminOfGroup(currentUser.getUsername(), groupName)) {
            result.unauthorized(LocalizedMessage.string("UNAUTHORIZED_TO_EDIT_GROUP", groupName), HealthStateType.unauthorised());
            return false;
        }
        return true;
    }
}
