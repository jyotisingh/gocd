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

package com.thoughtworks.go.domain;

import com.thoughtworks.go.config.*;
import com.thoughtworks.go.helper.GoConfigMother;
import org.junit.Before;
import org.junit.Test;

import static com.thoughtworks.go.util.TestUtils.contains;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertThat;

public class AgentConfigTest {

    private CruiseConfig cruiseConfig;
    private AgentConfig agentConfig;

    @Before
    public void setUp() {
        cruiseConfig = GoConfigMother.configWithPipelines("dev", "qa");
        agentConfig = new AgentConfig("uuid", "hostname", "10.10.10.10");
        cruiseConfig.agents().add(agentConfig);
    }

    @Test
    public void agentWithNoIpAddressShouldBeValid() throws Exception {
        CruiseConfig cruiseConfig = new BasicCruiseConfig();
        AgentConfig agent = new AgentConfig("uuid", null, null);
        cruiseConfig.agents().add(agent);

        assertThat(cruiseConfig.validateAfterPreprocess().isEmpty(), is(true));
    }

    @Test
    public void shouldValidateIpV4AndIpV6() throws Exception {
        shouldBeValid("127.0.0.1");
        shouldBeValid("0.0.0.0");
        shouldBeValid("255.255.0.0");
        shouldBeValid("0:0:0:0:0:0:0:1");
    }

    @Test
    public void shouldDetectInvalidIPAddress() throws Exception {
        shouldBeInvalid("blahinvalid", "'blahinvalid' is an invalid IP address.");
        shouldBeInvalid("blah.invalid", "'blah.invalid' is an invalid IP address.");
        shouldBeInvalid("399.0.0.1", "'399.0.0.1' is an invalid IP address.");
    }

    @Test
    public void shouldInvalidateEmptyAddress() {
        shouldBeInvalid("", "IpAddress cannot be empty if it is present.");
    }

    @Test
    public void shouldValidateTree(){
        Resource resource = new Resource("junk%");
        AgentConfig agentConfig = new AgentConfig("uuid", "junk", "junk", new Resources(resource));
        boolean isValid = agentConfig.validateTree(ConfigSaveValidationContext.forChain(agentConfig));
        assertThat(agentConfig.errors().on(AgentConfig.IP_ADDRESS), is("'junk' is an invalid IP address."));
        assertThat(resource.errors().on(JobConfig.RESOURCES), contains("Resource name 'junk%' is not valid."));
        assertThat(isValid, is(false));
    }

    @Test
    public void shouldPassValidationWhenUUidIsAvailable(){
        AgentConfig agentConfig = new AgentConfig("uuid");
        agentConfig.validate(ConfigSaveValidationContext.forChain(agentConfig));
        assertThat(agentConfig.errors().on(AgentConfig.UUID), is(nullValue()));
    }

    @Test
    public void shouldFailValidationWhenUUidIsBlank(){
        AgentConfig agentConfig = new AgentConfig("");
        agentConfig.validate(ConfigSaveValidationContext.forChain(agentConfig));
        assertThat(agentConfig.errors().on(AgentConfig.UUID), is("UUID cannot be empty"));
        agentConfig = new AgentConfig(null);
        agentConfig.validate(ConfigSaveValidationContext.forChain(agentConfig));
        assertThat(agentConfig.errors().on(AgentConfig.UUID), is("UUID cannot be empty"));
    }

    private void shouldBeInvalid(String address, String errorMsg) {
        AgentConfig agentConfig = new AgentConfig();
        agentConfig.setIpAddress(address);
        agentConfig.validate(ConfigSaveValidationContext.forChain(cruiseConfig));
        assertThat(agentConfig.errors().on("ipAddress"), is(errorMsg));
    }

    private void shouldBeValid(String ipAddress) throws Exception {
        CruiseConfig cruiseConfig = new BasicCruiseConfig();
        AgentConfig agentConfig = new AgentConfig();
        agentConfig.setIpAddress(ipAddress);
        cruiseConfig.agents().add(agentConfig);
        agentConfig.validate(ConfigSaveValidationContext.forChain(cruiseConfig));
        assertThat(agentConfig.errors().on("ipAddress"), is(nullValue()));
    }

}
