package com.thoughtworks.go.config;

import org.junit.Test;

import java.util.HashSet;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.*;

public class EnvironmentAgentConfigTest {
    @Test
    public void shouldFailValidationIfUUIDDoesNotMapToAnAgent() {
        EnvironmentAgentConfig config = new EnvironmentAgentConfig("uuid1");
        HashSet<String> uuids = new HashSet<String>();
        uuids.add("uuid2");
        uuids.add("uuid3");
        boolean isValid = config.validateUuidPresent(new CaseInsensitiveString("foo"), uuids);
        assertThat(isValid, is(false));
        assertThat(config.errors().on(EnvironmentAgentConfig.UUID), is("Environment 'foo' has an invalid agent uuid 'uuid1'"));
    }

    @Test
    public void shouldPassValidationIfUUIDMapsToAnAgent() {
        EnvironmentAgentConfig config = new EnvironmentAgentConfig("uuid1");
        HashSet<String> uuids = new HashSet<String>();
        uuids.add("uuid1");
        uuids.add("uuid2");
        boolean isValid = config.validateUuidPresent(new CaseInsensitiveString("foo"), uuids);
        assertThat(isValid, is(true));
        assertThat(config.errors().isEmpty(), is(true));
    }
}