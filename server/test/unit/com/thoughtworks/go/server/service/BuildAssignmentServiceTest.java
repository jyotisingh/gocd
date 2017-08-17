/*
 * Copyright 2017 ThoughtWorks, Inc.
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

package com.thoughtworks.go.server.service;

import com.thoughtworks.go.config.*;
import com.thoughtworks.go.config.elastic.ElasticProfile;
import com.thoughtworks.go.domain.*;
import com.thoughtworks.go.helper.AgentInstanceMother;
import com.thoughtworks.go.helper.AgentMother;
import com.thoughtworks.go.helper.JobConfigMother;
import com.thoughtworks.go.helper.PipelineConfigMother;
import com.thoughtworks.go.remote.work.BuildAssignment;
import com.thoughtworks.go.remote.work.BuildWork;
import com.thoughtworks.go.server.domain.ElasticAgentMetadata;
import com.thoughtworks.go.server.service.builders.BuilderFactory;
import com.thoughtworks.go.server.transaction.TransactionTemplate;
import com.thoughtworks.go.server.websocket.Agent;
import com.thoughtworks.go.server.websocket.AgentRemoteHandler;
import com.thoughtworks.go.server.websocket.AgentRemoteSocket;
import com.thoughtworks.go.util.SystemEnvironment;
import com.thoughtworks.go.util.TimeProvider;
import com.thoughtworks.go.websocket.Action;
import com.thoughtworks.go.websocket.Message;
import com.thoughtworks.go.websocket.MessageEncoding;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class BuildAssignmentServiceTest {

    @Mock
    private GoConfigService goConfigService;
    @Mock
    private JobInstanceService jobInstanceService;
    @Mock
    private ScheduleService scheduleService;
    @Mock
    private ElasticAgentPluginService elasticAgentPluginService;
    @Mock
    private TimeProvider timeProvider;
    @Mock
    private AgentRemoteHandler agentRemoteHandler;
    @Mock
    private BuilderFactory builderFactory;
    @Mock
    private PipelineService pipelineService;
    @Mock
    private ScheduledPipelineLoader scheduledPipelineLoader;
    @Mock
    private EnvironmentConfigService environmentConfigService;
    @Mock
    private AgentService agentService;
    private BuildAssignmentService buildAssignmentService;
    @Mock
    private TransactionTemplate transactionTemplate;
    private SchedulingContext schedulingContext;
    private ArrayList<JobPlan> jobPlans;
    private AgentConfig elasticAgent;
    private AgentInstance elasticAgentInstance;
    private ElasticProfile elasticProfile1;
    private ElasticProfile elasticProfile2;
    private String elasticProfileId1;
    private String elasticProfileId2;
    private AgentInstance regularAgentInstance;

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        buildAssignmentService = new BuildAssignmentService(goConfigService, jobInstanceService, scheduleService, agentService, environmentConfigService, transactionTemplate, scheduledPipelineLoader, pipelineService, builderFactory, agentRemoteHandler, elasticAgentPluginService, timeProvider);
        elasticProfileId1 = "elastic.profile.id.1";
        elasticProfileId2 = "elastic.profile.id.2";
        elasticAgent = AgentMother.elasticAgent();
        elasticAgentInstance = AgentInstance.createFromConfig(elasticAgent, new SystemEnvironment());
        regularAgentInstance = AgentInstance.createFromConfig(AgentMother.approvedAgent(), new SystemEnvironment());
        elasticProfile1 = new ElasticProfile(elasticProfileId1, elasticAgent.getElasticPluginId());
        elasticProfile2 = new ElasticProfile(elasticProfileId2, elasticAgent.getElasticPluginId());
        jobPlans = new ArrayList<>();
        HashMap<String, ElasticProfile> profiles = new HashMap<>();
        profiles.put(elasticProfile1.getId(), elasticProfile1);
        profiles.put(elasticProfile2.getId(), elasticProfile2);
        schedulingContext = new DefaultSchedulingContext("me", new Agents(elasticAgent), profiles);
        when(jobInstanceService.orderedScheduledBuilds()).thenReturn(jobPlans);
        when(environmentConfigService.filterJobsByAgent(Matchers.eq(jobPlans), Matchers.any(String.class))).thenReturn(jobPlans);
        when(environmentConfigService.envForPipeline(Matchers.any(String.class))).thenReturn("");
    }

    @Test
    public void shouldMatchAnElasticJobToAnElasticAgentOnlyIfThePluginAgreesToTheAssignment() {
        PipelineConfig pipelineWithElasticJob = PipelineConfigMother.pipelineWithElasticJob(elasticProfileId1);
        JobPlan jobPlan = new InstanceFactory().createJobPlan(pipelineWithElasticJob.first().getJobs().first(), schedulingContext);
        jobPlans.add(jobPlan);
        when(elasticAgentPluginService.shouldAssignWork(elasticAgentInstance.elasticAgentMetadata(), "", jobPlan.getElasticProfile())).thenReturn(true);
        buildAssignmentService.onTimer();


        JobPlan matchingJob = buildAssignmentService.findMatchingJob(elasticAgentInstance);
        assertThat(matchingJob, is(jobPlan));
        assertThat(buildAssignmentService.jobPlans().size(), is(0));
    }

    @Test
    public void shouldNotMatchAnElasticJobToAnElasticAgentOnlyIfThePluginIdMatches() {
        PipelineConfig pipelineWithElasticJob = PipelineConfigMother.pipelineWithElasticJob(elasticProfileId1);
        JobPlan jobPlan1 = new InstanceFactory().createJobPlan(pipelineWithElasticJob.first().getJobs().first(), schedulingContext);
        jobPlans.add(jobPlan1);
        when(elasticAgentPluginService.shouldAssignWork(elasticAgentInstance.elasticAgentMetadata(), "", jobPlan1.getElasticProfile())).thenReturn(false);
        buildAssignmentService.onTimer();

        JobPlan matchingJob = buildAssignmentService.findMatchingJob(elasticAgentInstance);
        assertThat(matchingJob, is(nullValue()));
        assertThat(buildAssignmentService.jobPlans().size(), is(1));
    }

    @Test
    public void shouldMatchAnElasticJobToAnElasticAgentOnlyIfThePluginAgreesToTheAssignmentWhenMultipleElasticJobsRequiringTheSamePluginAreScheduled() {
        PipelineConfig pipelineWith2ElasticJobs = PipelineConfigMother.pipelineWithElasticJob(elasticProfileId1, elasticProfileId2);
        JobPlan jobPlan1 = new InstanceFactory().createJobPlan(pipelineWith2ElasticJobs.first().getJobs().first(), schedulingContext);
        JobPlan jobPlan2 = new InstanceFactory().createJobPlan(pipelineWith2ElasticJobs.first().getJobs().last(), schedulingContext);
        jobPlans.add(jobPlan1);
        jobPlans.add(jobPlan2);
        when(elasticAgentPluginService.shouldAssignWork(elasticAgentInstance.elasticAgentMetadata(), "", jobPlan1.getElasticProfile())).thenReturn(false);
        when(elasticAgentPluginService.shouldAssignWork(elasticAgentInstance.elasticAgentMetadata(), "", jobPlan2.getElasticProfile())).thenReturn(true);
        buildAssignmentService.onTimer();


        JobPlan matchingJob = buildAssignmentService.findMatchingJob(elasticAgentInstance);
        assertThat(matchingJob, is(jobPlan2));
        assertThat(buildAssignmentService.jobPlans().size(), is(1));
    }

    @Test
    public void shouldMatchNonElasticJobToNonElasticAgentIfResourcesMatch() {
        PipelineConfig pipeline = PipelineConfigMother.pipelineConfig(UUID.randomUUID().toString());
        pipeline.first().getJobs().add(JobConfigMother.jobWithNoResourceRequirement());
        pipeline.first().getJobs().add(JobConfigMother.elasticJob(elasticProfileId1));
        JobPlan elasticJobPlan = new InstanceFactory().createJobPlan(pipeline.first().getJobs().last(), schedulingContext);
        JobPlan regularJobPlan = new InstanceFactory().createJobPlan(pipeline.first().getJobs().first(), schedulingContext);
        jobPlans.add(elasticJobPlan);
        jobPlans.add(regularJobPlan);
        buildAssignmentService.onTimer();

        JobPlan matchingJob = buildAssignmentService.findMatchingJob(regularAgentInstance);
        assertThat(matchingJob, is(regularJobPlan));
        assertThat(buildAssignmentService.jobPlans().size(), is(1));
        verify(elasticAgentPluginService, never()).shouldAssignWork(Matchers.any(ElasticAgentMetadata.class), Matchers.any(String.class), Matchers.any(ElasticProfile.class));
    }

    @Test
    public void shouldContinueWithAssignmentForOtherAgentsIfAssignmentForOneOfThemFails() {
        HashMap<String, Agent> connectedAgents = new LinkedHashMap<>();
        AgentRemoteSocket agent1Socket = mock(AgentRemoteSocket.class);
        AgentRemoteSocket agent2Socket = mock(AgentRemoteSocket.class);
        AgentInstance agent1 = agent(UUID.randomUUID().toString());
        AgentInstance agent2 = agent(UUID.randomUUID().toString());

        connectedAgents.put(agent1.getUuid(), agent1Socket);
        connectedAgents.put(agent2.getUuid(), agent2Socket);
        when(agentRemoteHandler.connectedAgents()).thenReturn(connectedAgents);
        jobPlans.add(jobPlan("p1", "j1"));
        jobPlans.add(jobPlan("p2", "j2"));
        doThrow(new RuntimeException("failed to send over websockets")).when(agent1Socket).send(any());
        when(agentService.findAgentAndRefreshStatus(agent1.getUuid())).thenReturn(agent1);
        when(agentService.findAgentAndRefreshStatus(agent2.getUuid())).thenReturn(agent2);
        BuildWork buildWork = new BuildWork(null);
        when(transactionTemplate.transactionSurrounding(any())).then(new Answer<BuildWork>() {
            @Override
            public BuildWork answer(InvocationOnMock invocation) throws Throwable {
                return buildWork;
            }
        });

        buildAssignmentService.onTimer();

        assertThat(agent1.getRuntimeStatus(), is(AgentRuntimeStatus.Idle));
        ArgumentCaptor<Message> messageArgCaptorForAgent1 = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<Message> messageArgCaptorForAgent2 = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<AgentBuildingInfo> argumentCaptor = ArgumentCaptor.forClass(AgentBuildingInfo.class);

        verify(agent1Socket).send(messageArgCaptorForAgent1.capture());
        verify(agent2Socket).send(messageArgCaptorForAgent2.capture());
        verify(agentService).building(eq(agent2.getUuid()), argumentCaptor.capture());
        AgentBuildingInfo agentBuildingInfo = argumentCaptor.getValue();
        assertThat(agentBuildingInfo.getJobName(), is("j2"));
        assertThat(agentBuildingInfo.getPipelineName(), is("p2"));
        assertThat(messageArgCaptorForAgent1.getValue().getAction(),  is(Action.assignWork));
        assertThat(messageArgCaptorForAgent1.getValue().getData(),  is(MessageEncoding.encodeWork(buildWork)));
        assertThat(messageArgCaptorForAgent2.getValue().getAction(),  is(Action.assignWork));
        assertThat(messageArgCaptorForAgent2.getValue().getData(),  is(MessageEncoding.encodeWork(buildWork)));
    }

    private DefaultJobPlan jobPlan(String pipelineName, String jobName) {
        JobIdentifier jobIdentifier = new JobIdentifier(pipelineName, 1, "1", "stage1", "1", jobName, 1L);
        DefaultJobPlan plan = new DefaultJobPlan(new Resources(), new ArtifactPlans(), null, 100, jobIdentifier, null, new EnvironmentVariablesConfig(), new EnvironmentVariablesConfig(), null);
        return plan;
    }

    private AgentInstance agent(String uuid) {
        AgentInstance agentInstance = AgentInstanceMother.idle();
        AgentInstanceMother.updateUuid(agentInstance, uuid);
        return agentInstance;
    }
}