/*
 * Copyright 2015 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.thoughtworks.go.server.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.caja.util.Sets;
import com.thoughtworks.go.config.*;
import com.thoughtworks.go.domain.AgentInstance;
import com.thoughtworks.go.listener.AgentChangeListener;
import com.thoughtworks.go.presentation.TriStateSelection;
import com.thoughtworks.go.server.domain.AgentInstances;
import com.thoughtworks.go.util.TriState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.thoughtworks.go.util.ExceptionUtils.bomb;
import static com.thoughtworks.go.util.ExceptionUtils.bombIfNull;

/**
 * @understands how to convert persistant Agent configuration to useful objects and back
 */
@Service
public class AgentConfigService {
    private GoConfigService goConfigService;
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentConfigService.class.getName());

    @Autowired
    public AgentConfigService(GoConfigService goConfigService) {
        this.goConfigService = goConfigService;
    }

    public Agents agents() {
        return goConfigService.agents();
    }

    public void register(AgentChangeListener agentChangeListener) {
        goConfigService.register(agentChangeListener);
    }

    public void disableAgents(AgentInstance... agentInstance) {
        disableAgents(true, agentInstance);
    }

    public void enableAgents(AgentInstance... agentInstance) {
        disableAgents(false, agentInstance);
    }

    private void disableAgents(boolean disabled, AgentInstance... instances) {
        GoConfigDao.CompositeConfigCommand command = new GoConfigDao.CompositeConfigCommand();
        for (AgentInstance agentInstance : instances) {
            String uuid = agentInstance.getUuid();

            if (goConfigService.hasAgent(uuid)) {
                command.addCommand(updateApprovalStatus(uuid, disabled));
            } else {
                AgentConfig agentConfig = agentInstance.agentConfig();
                agentConfig.disable(disabled);
                command.addCommand(createAddAgentCommand(agentConfig));
            }
        }
        goConfigService.updateConfig(command);
    }

    protected static UpdateConfigCommand updateApprovalStatus(final String uuid, final Boolean isDenied) {
        return new UpdateAgentApprovalStatus(uuid, isDenied);
    }

    public void deleteAgents(AgentInstance... agentInstances) {
        goConfigService.updateConfig(commandForDeletingAgents(agentInstances));
    }

    protected GoConfigDao.CompositeConfigCommand commandForDeletingAgents(AgentInstance... agentInstances) {
        GoConfigDao.CompositeConfigCommand command = new GoConfigDao.CompositeConfigCommand();
        for (AgentInstance agentInstance : agentInstances) {
            command.addCommand(deleteAgentCommand(agentInstance.getUuid()));
        }
        return command;
    }

    public static DeleteAgent deleteAgentCommand(String uuid) {
        return new DeleteAgent(uuid);
    }

    /**
     * @understands how to delete agent
     */
    private static class DeleteAgent implements UpdateConfigCommand {
        private final String uuid;

        public DeleteAgent(String uuid) {
            this.uuid = uuid;
        }

        public CruiseConfig update(CruiseConfig cruiseConfig) {
            AgentConfig agentConfig = cruiseConfig.agents().getAgentByUuid(uuid);
            if (agentConfig.isNull()) {
                bomb("Unable to delete agent; Agent [" + uuid + "] not found.");
            }
            cruiseConfig.getEnvironments().removeAgentFromAllEnvironments(uuid);
            cruiseConfig.agents().remove(agentConfig);
            return cruiseConfig;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            DeleteAgent that = (DeleteAgent) o;

            if (uuid != null ? !uuid.equals(that.uuid) : that.uuid != null) {
                return false;
            }

            return true;
        }

        @Override
        public int hashCode() {
            return uuid != null ? uuid.hashCode() : 0;
        }

        @Override
        public String toString() {
            return "DeleteAgent{" +
                    "uuid='" + uuid + '\'' +
                    '}';
        }
    }
    public void updateAgentIpByUuid(String uuid, String ipAddress, String userName) {
        goConfigService.updateConfig(new UpdateAgentIp(uuid, ipAddress, userName));
    }

    private static class UpdateAgentIp implements UpdateConfigCommand, UserAware {
        private final String uuid;
        private final String ipAddress;
        private final String userName;

        private UpdateAgentIp(String uuid, String ipAddress, String userName) {
            this.uuid = uuid;
            this.ipAddress = ipAddress;
            this.userName = userName;
        }

        public CruiseConfig update(CruiseConfig cruiseConfig) throws Exception {
            AgentConfig agentConfig = cruiseConfig.agents().getAgentByUuid(uuid);
            bombIfNull(agentConfig, "Unable to set agent ipAddress; Agent [" + uuid + "] not found.");
            agentConfig.setIpAddress(ipAddress);
            return cruiseConfig;
        }

        public ConfigModifyingUser user() {
            return new ConfigModifyingUser(userName);
        }
    }


    public void updateAgentAttributes(String uuid, String userName, String hostname, String resources, String environments, TriState enable, AgentInstances agentInstances) {
        GoConfigDao.CompositeConfigCommand command = new GoConfigDao.CompositeConfigCommand();

        if (!goConfigService.hasAgent(uuid) && enable.isTrue()) {
            AgentInstance agentInstance = agentInstances.findAgent(uuid);
            AgentConfig agentConfig = agentInstance.agentConfig();
            command.addCommand(createAddAgentCommand(agentConfig));
        }

        if (enable.isTrue()) {
            command.addCommand(updateApprovalStatus(uuid, false));
        }

        if (enable.isFalse()) {
            command.addCommand(updateApprovalStatus(uuid, true));
        }

        if (hostname != null) {
            command.addCommand(new UpdateAgentHostname(uuid, hostname, userName));
        }

        if (resources != null) {
            command.addCommand(new UpdateResourcesCommand(uuid, new Resources(resources)));
        }

        if (environments != null) {
            Set<String> existingEnvironments = goConfigService.getCurrentConfig().getEnvironments().environmentsForAgent(uuid);
            Set<String> newEnvironments = new HashSet<>(Arrays.asList(environments.split(",")));

            Set<String> environmentsToRemove = Sets.difference(existingEnvironments, newEnvironments);
            Set<String> environmentsToAdd = Sets.difference(newEnvironments, existingEnvironments);

            for (String environmentToRemove : environmentsToRemove) {
                command.addCommand(new GoConfigDao.ModifyEnvironmentCommand(uuid, environmentToRemove, TriStateSelection.Action.remove));
            }

            for (String environmentToAdd : environmentsToAdd) {
                command.addCommand(new GoConfigDao.ModifyEnvironmentCommand(uuid, environmentToAdd, TriStateSelection.Action.add));
            }
        }

        goConfigService.updateConfig(command);
    }


    public void saveOrUpdateAgent(AgentInstance agentInstance) {
        AgentConfig agentConfig = agentInstance.agentConfig();
        if (goConfigService.hasAgent(agentConfig.getUuid())) {
            this.updateAgentApprovalStatus(agentConfig.getUuid(), agentConfig.isDisabled());
        } else {
            this.addAgent(agentConfig);
        }
    }

    public void approvePendingAgent(AgentInstance agentInstance) {
        agentInstance.enable();
        if (goConfigService.hasAgent(agentInstance.getUuid())) {
            LOGGER.warn("Registered agent with the same uuid [" + agentInstance + "] already approved.");
        } else {
            goConfigService.updateConfig(createAddAgentCommand(agentInstance.agentConfig()));
        }
    }

    protected static UpdateConfigCommand createAddAgentCommand(final AgentConfig agentConfig) {
        return new AddAgentCommand(agentConfig);
    }

    /**
     * @understands how to add an agent to the config file
     */
    private static class AddAgentCommand implements UpdateConfigCommand {
        private final AgentConfig agentConfig;

        public AddAgentCommand(AgentConfig agentConfig) {
            this.agentConfig = agentConfig;
        }

        public CruiseConfig update(CruiseConfig cruiseConfig) throws Exception {
            cruiseConfig.agents().add(agentConfig);
            return cruiseConfig;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            AddAgentCommand that = (AddAgentCommand) o;

            if (agentConfig != null ? !agentConfig.equals(that.agentConfig) : that.agentConfig != null) {
                return false;
            }

            return true;
        }

        @Override
        public int hashCode() {
            return agentConfig != null ? agentConfig.hashCode() : 0;
        }

        @Override
        public String toString() {
            return "AddAgentcommand{" +
                    "agentConfig=" + agentConfig +
                    '}';
        }

    }

    public void updateAgentResources(final String uuid, final Resources resources) {
        goConfigService.updateConfig(new UpdateResourcesCommand(uuid, resources));
    }

    public void updateAgentApprovalStatus(final String uuid, final Boolean isDenied) {
        goConfigService.updateConfig(updateApprovalStatus(uuid, isDenied));
    }

    public void addAgent(AgentConfig agentConfig) {
        goConfigService.updateConfig(createAddAgentCommand(agentConfig));
    }

    public void modifyResources(AgentInstance[] agentInstances, List<TriStateSelection> selections) {
        GoConfigDao.CompositeConfigCommand command = new GoConfigDao.CompositeConfigCommand();
        for (AgentInstance agentInstance : agentInstances) {
            String uuid = agentInstance.getUuid();
            if (goConfigService.hasAgent(uuid)) {
                for (TriStateSelection selection : selections) {
                    command.addCommand(new ModifyResourcesCommand(uuid, new Resource(selection.getValue()), selection.getAction()));
                }
            }
        }
        goConfigService.updateConfig(command);
    }

    public Agents findAgents(List<String> uuids) {
        return agents().filter(uuids);
    }

    /**
     * @understands how to update the agent approval status
     */
    private static class UpdateAgentApprovalStatus implements UpdateConfigCommand {
        private final String uuid;
        private final Boolean denied;

        public UpdateAgentApprovalStatus(String uuid, Boolean denied) {
            this.uuid = uuid;
            this.denied = denied;
        }

        public CruiseConfig update(CruiseConfig cruiseConfig) {
            AgentConfig agentConfig = cruiseConfig.agents().getAgentByUuid(uuid);
            bombIfNull(agentConfig, "Unable to set agent approval status; Agent [" + uuid + "] not found.");
            agentConfig.setDisabled(denied);
            return cruiseConfig;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            UpdateAgentApprovalStatus that = (UpdateAgentApprovalStatus) o;

            if (denied != null ? !denied.equals(that.denied) : that.denied != null) {
                return false;
            }
            if (uuid != null ? !uuid.equals(that.uuid) : that.uuid != null) {
                return false;
            }

            return true;
        }

        @Override
        public int hashCode() {
            int result = uuid != null ? uuid.hashCode() : 0;
            result = 31 * result + (denied != null ? denied.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "UpdateAgentApprovalStatus{" +
                    "uuid='" + uuid + '\'' +
                    ", denied=" + denied +
                    '}';
        }
    }

    public static class UpdateResourcesCommand implements UpdateConfigCommand {
        private final String uuid;
        private final Resources resources;

        public UpdateResourcesCommand(String uuid, Resources resources) {
            this.uuid = uuid;
            this.resources = resources;
        }

        public CruiseConfig update(CruiseConfig cruiseConfig) {
            AgentConfig agentConfig = cruiseConfig.agents().getAgentByUuid(uuid);
            bombIfNull(agentConfig, "Unable to set agent resources; Agent [" + uuid + "] not found.");
            agentConfig.setResources(resources);
            return cruiseConfig;
        }
    }
    public static class ModifyResourcesCommand implements UpdateConfigCommand {
        private final String uuid;
        private final Resource resource;
        private final TriStateSelection.Action action;

        public ModifyResourcesCommand(String uuid, Resource resource, TriStateSelection.Action action) {
            this.uuid = uuid;
            this.resource = resource;
            this.action = action;
        }

        public CruiseConfig update(CruiseConfig cruiseConfig) {
            AgentConfig agentConfig = cruiseConfig.agents().getAgentByUuid(uuid);
            bombIfNull(agentConfig, "Unable to set agent resources; Agent [" + uuid + "] not found.");
            if (action.equals(TriStateSelection.Action.add)) {
                agentConfig.addResource(resource);
            } else if (action.equals(TriStateSelection.Action.remove)) {
                agentConfig.removeResource(resource);
            } else if (action.equals(TriStateSelection.Action.nochange)) {
                //do nothing
            } else {
                bomb(String.format("unsupported action '%s'", action));
            }
            return cruiseConfig;
        }
    }
    public static class UpdateAgentHostname implements UpdateConfigCommand, UserAware {
        private final String uuid;
        private final String hostname;
        private final String userName;

        public UpdateAgentHostname(String uuid, String hostname, String userName) {
            this.uuid = uuid;
            this.hostname = hostname;
            this.userName = userName;
        }

        public CruiseConfig update(CruiseConfig cruiseConfig) throws Exception {
            AgentConfig agentConfig = cruiseConfig.agents().getAgentByUuid(uuid);
            bombIfNull(agentConfig, "Unable to set agent hostname; Agent [" + uuid + "] not found.");
            agentConfig.setHostName(hostname);
            return cruiseConfig;
        }

        public ConfigModifyingUser user() {
            return new ConfigModifyingUser(userName);
        }
    }


}
