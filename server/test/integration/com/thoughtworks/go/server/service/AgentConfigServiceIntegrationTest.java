package com.thoughtworks.go.server.service;

import com.thoughtworks.go.config.*;
import com.thoughtworks.go.domain.AgentInstance;
import com.thoughtworks.go.util.ReflectionUtil;
import com.thoughtworks.go.util.SystemEnvironment;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.List;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.*;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = {
        "classpath:WEB-INF/applicationContext-global.xml",
        "classpath:WEB-INF/applicationContext-dataLocalAccess.xml",
        "classpath:WEB-INF/applicationContext-acegi-security.xml"
})
public class AgentConfigServiceIntegrationTest {
    @Autowired private AgentConfigService agentConfigService;
    @Autowired private GoConfigDao goConfigDao;

    @Test
    public void shouldAddAgentToConfigFile() throws Exception {
        Resources resources = new Resources("java");
        AgentConfig approvedAgentConfig = new AgentConfig("uuid", "test1", "192.168.0.1", resources);
        AgentConfig deniedAgentConfig = new AgentConfig("", "test2", "192.168.0.2", resources);
        deniedAgentConfig.disable();
        agentConfigService.addAgent(approvedAgentConfig);
        agentConfigService.addAgent(deniedAgentConfig);
        CruiseConfig cruiseConfig = goConfigDao.load();
        assertThat(cruiseConfig.agents().size(), is(2));
        assertThat(cruiseConfig.agents().get(0), is(approvedAgentConfig));
        assertThat(cruiseConfig.agents().get(0).getResources(), is(resources));
        assertThat(cruiseConfig.agents().get(1), is(deniedAgentConfig));
        assertThat(cruiseConfig.agents().get(1).isDisabled(), is(Boolean.TRUE));
        assertThat(cruiseConfig.agents().get(1).getResources(), is(resources));
    }

    @Test
    public void shouldDeleteMultipleAgents() {
        AgentConfig agentConfig1 = new AgentConfig("UUID1", "remote-host1", "50.40.30.21");
        AgentConfig agentConfig2 = new AgentConfig("UUID2", "remote-host2", "50.40.30.22");
        agentConfig1.disable();
        agentConfig2.disable();
        AgentInstance fromConfigFile1 = AgentInstance.createFromConfig(agentConfig1, new SystemEnvironment());
        AgentInstance fromConfigFile2 = AgentInstance.createFromConfig(agentConfig2, new SystemEnvironment());

        GoConfigDao.CompositeConfigCommand command = agentConfigService.commandForDeletingAgents(fromConfigFile1, fromConfigFile2);

        List<UpdateConfigCommand> commands = command.getCommands();
        assertThat(commands.size(), is(2));
        String uuid1 = (String) ReflectionUtil.getField(commands.get(0), "uuid");
        String uuid2 = (String) ReflectionUtil.getField(commands.get(1), "uuid");
        assertThat(uuid1, is("UUID1"));
        assertThat(uuid2, is("UUID2"));
    }

    @Test
    public void shouldDeleteAgentFromConfigFileGivenUUID() throws Exception {
        AgentConfig agentConfig1 = new AgentConfig("uuid1", "test1", "192.168.0.1");
        AgentConfig agentConfig2 = new AgentConfig("uuid2", "test2", "192.168.0.2");
        AgentInstance fromConfigFile1 = AgentInstance.createFromConfig(agentConfig1, new SystemEnvironment());
        AgentInstance fromConfigFile2 = AgentInstance.createFromConfig(agentConfig2, new SystemEnvironment());

        agentConfigService.addAgent(agentConfig1);
        agentConfigService.addAgent(agentConfig2);

        CruiseConfig cruiseConfig = goConfigDao.load();
        assertThat(cruiseConfig.agents().size(), is(2));

        agentConfigService.deleteAgents(fromConfigFile1);

        cruiseConfig = goConfigDao.load();
        assertThat(cruiseConfig.agents().size(), is(1));
        assertThat(cruiseConfig.agents().get(0), is(agentConfig2));
    }

    @Test
    public void shouldRemoveAgentFromEnvironmentBeforeDeletingAgent() throws Exception {
        AgentConfig agentConfig1 = new AgentConfig("uuid1", "hostname", "127.0.0.1");
        AgentInstance fromConfigFile1 = AgentInstance.createFromConfig(agentConfig1, new SystemEnvironment());

        agentConfigService.addAgent(agentConfig1);
        agentConfigService.addAgent(new AgentConfig("uuid2", "hostname", "127.0.0.1"));

        BasicEnvironmentConfig env = new BasicEnvironmentConfig(new CaseInsensitiveString("foo-environment"));
        env.addAgent("uuid1");
        env.addAgent("uuid2");
        goConfigDao.addEnvironment(env);
        CruiseConfig cruiseConfig = goConfigDao.load();

        assertThat(cruiseConfig.getEnvironments().get(0).getAgents().size(), is(2));

        agentConfigService.deleteAgents(fromConfigFile1);

        cruiseConfig = goConfigDao.load();

        assertThat(cruiseConfig.getEnvironments().get(0).getAgents().size(), is(1));
        assertThat(cruiseConfig.getEnvironments().get(0).getAgents().get(0).getUuid(), is("uuid2"));
    }

    @Test
    public void shouldUpdateAgentResourcesToConfigFile() throws Exception {
        AgentConfig agentConfig = new AgentConfig("uuid", "test", "127.0.0.1", new Resources("java"));
        agentConfigService.addAgent(agentConfig);
        Resources newResources = new Resources("firefox");
        agentConfigService.updateAgentResources(agentConfig.getUuid(), newResources);
        CruiseConfig cruiseConfig = goConfigDao.load();
        assertThat(cruiseConfig.agents().get(0).getResources(), is(newResources));
    }

    @Test
    public void shouldUpdateAgentApprovalStatusByUuidToConfigFile() throws Exception {
        AgentConfig agentConfig = new AgentConfig("uuid", "test", "127.0.0.1", new Resources("java"));
        agentConfigService.addAgent(agentConfig);
        agentConfigService.updateAgentApprovalStatus(agentConfig.getUuid(), Boolean.TRUE);

        CruiseConfig cruiseConfig = goConfigDao.load();
        assertThat(cruiseConfig.agents().get(0).isDisabled(), is(true));
    }

    @Test
    public void shouldRemoveAgentResourcesInConfigFile() throws Exception {
        AgentConfig agentConfig = new AgentConfig("uuid", "test", "127.0.0.1", new Resources("java, resource1, resource2"));
        agentConfigService.addAgent(agentConfig);
        CruiseConfig cruiseConfig = goConfigDao.load();
        assertThat(cruiseConfig.agents().get(0).getResources().size(), is(3));
        agentConfigService.updateAgentResources(agentConfig.getUuid(), new Resources("java"));
        cruiseConfig = goConfigDao.load();
        assertThat(cruiseConfig.agents().get(0).getResources().size(), is(1));
    }
}