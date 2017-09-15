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

package com.thoughtworks.go.server.websocket;

import com.thoughtworks.go.domain.JobIdentifier;
import com.thoughtworks.go.domain.JobInstance;
import com.thoughtworks.go.remote.AgentInstruction;
import com.thoughtworks.go.remote.BuildRepositoryRemote;
import com.thoughtworks.go.server.service.AgentRuntimeInfo;
import com.thoughtworks.go.server.service.AgentService;
import com.thoughtworks.go.server.service.ConsoleService;
import com.thoughtworks.go.server.service.JobInstanceService;
import com.thoughtworks.go.websocket.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentRemoteHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRemoteHandler.class);
    private Map<Agent, String> sessionIds = new ConcurrentHashMap<>();
    private Map<Agent, String> agentCookie = new ConcurrentHashMap<>();
    private Map<String, Agent> agentSessions = new ConcurrentHashMap<>();

    @Qualifier("buildRepositoryMessageProducer")
    @Autowired
    private BuildRepositoryRemote buildRepositoryRemote;
    @Autowired
    private AgentService agentService;
    @Autowired
    private JobInstanceService jobInstanceService;
    private ConsoleService consoleService;

    @Autowired
    public AgentRemoteHandler(@Qualifier("buildRepositoryMessageProducer") BuildRepositoryRemote buildRepositoryRemote, AgentService agentService, JobInstanceService jobInstanceService, ConsoleService consoleService) {
        this.buildRepositoryRemote = buildRepositoryRemote;
        this.agentService = agentService;
        this.jobInstanceService = jobInstanceService;
        this.consoleService = consoleService;
    }

    public void process(Agent agent, Message msg) throws Exception {
        try {
            processWithoutAcknowledgement(agent, msg);
        } finally {
            //TODO: Add retry logic for these as acknowledgements are important to agents. If sending an acknowledgement
            //fails, the agent would fail the build causing the job to be rescheduled. - Jyoti/Ganesh

            //Do not send Acknowledgement for updateAgentRuntimeInfo messages
            if (msg.getAction().equals(Action.updateAgentRuntimeInfo)) {
                return;
            }
            agent.send(new Message(Action.acknowledge, MessageEncoding.encodeData(msg.getAcknowledgementId())));
        }
    }

    public void processWithoutAcknowledgement(Agent agent, Message msg) throws Exception {
        switch (msg.getAction()) {
            case updateAgentRuntimeInfo:
                System.out.println("Called updateAgentRuntimeInfo.." + msg.getAcknowledgementId());
                AgentRuntimeInfo info = MessageEncoding.decodeData(msg.getData(), AgentRuntimeInfo.class);
                if (!sessionIds.containsKey(agent)) {
                    LOGGER.info("{} is connected with websocket {}", info.getIdentifier(), agent);
                    sessionIds.put(agent, info.getUUId());
                    this.agentSessions.put(info.getUUId(), agent);
                }

                if (info.getCookie() == null) {
                    String cookie = agentCookie.get(agent);
                    if (cookie == null) {
                        cookie = buildRepositoryRemote.getCookie(info.getIdentifier(), info.getLocation());
                        agentCookie.put(agent, cookie);
                    }
                    info.setCookie(cookie);
                    agent.send(new Message(Action.setCookie, MessageEncoding.encodeData(cookie)));
                }
                AgentInstruction instruction = this.buildRepositoryRemote.ping(info);
                if (instruction.isShouldCancelJob()) {
                    agent.send(new Message(Action.cancelBuild));
                }
                System.out.println("Done updateAgentRuntimeInfo.." + msg.getAcknowledgementId());
                break;
            case reportCurrentStatus:
                System.out.println("Called reportCurrentStatus.." + msg.getAcknowledgementId());
                Report report = MessageEncoding.decodeData(msg.getData(), Report.class);
                buildRepositoryRemote.reportCurrentStatus(report.getAgentRuntimeInfo(), findJobIdentifier(report), report.getJobState());
                System.out.println("Done reportCurrentStatus.." + msg.getAcknowledgementId());
                break;
            case reportCompleting:
                System.out.println("Called reportCompleting.." + msg.getAcknowledgementId());
                report = MessageEncoding.decodeData(msg.getData(), Report.class);
                buildRepositoryRemote.reportCompleting(report.getAgentRuntimeInfo(), findJobIdentifier(report), report.getResult());
                System.out.println("done reportCompleting.." + msg.getAcknowledgementId());
                break;
            case reportCompleted:
                System.out.println("Called reportCompleted.." + msg.getAcknowledgementId());
                report = MessageEncoding.decodeData(msg.getData(), Report.class);
                buildRepositoryRemote.reportCompleted(report.getAgentRuntimeInfo(), findJobIdentifier(report), report.getResult());
                System.out.println("done reportCompleted.." + msg.getAcknowledgementId());
                break;
            case consoleOut:
                String agentUuid = sessionIds.get(agent);
                ConsoleTransmission consoleTransmission = MessageEncoding.decodeData(msg.getData(), ConsoleTransmission.class);
                JobInstance jobInstance = findJobInstance(consoleTransmission);
                if (!jobInstance.getAgentUuid().equals(agentUuid)) {
                    throw new RuntimeException("Two agents reporting console log for the same job. ganesh did this!");
                }
                File consoleLogFile = consoleService.consoleLogFile(jobInstance.getIdentifier());
                consoleService.updateConsoleLog(consoleLogFile, consoleTransmission.getLineAsStream());
                break;
            default:
                throw new RuntimeException("Unknown action: " + msg.getAction());
        }
    }

    private JobIdentifier findJobIdentifier(Transmission transmission) {
        if (transmission.getJobIdentifier() != null) {
            return transmission.getJobIdentifier();
        }

        JobInstance instance = jobInstanceService.buildById(Long.valueOf(transmission.getBuildId()));
        return instance.getIdentifier();
    }

    private JobInstance findJobInstance(Transmission transmission) {
        return jobInstanceService.buildById(Long.valueOf(transmission.getBuildId()));
    }

    public void remove(Agent agent) {
        agentCookie.remove(agent);
        String uuid = sessionIds.remove(agent);
        if (uuid == null) {
            return;
        }
        agentSessions.remove(uuid);
    }

    public Map<String, Agent> connectedAgents() {
        return agentSessions;
    }

    public void sendCancelMessage(String uuid) {
        if (uuid == null) {
            return;
        }
        Agent agent = agentSessions.get(uuid);
        if (agent != null) {
            agent.send(new Message(Action.cancelBuild));
        }
    }
}
