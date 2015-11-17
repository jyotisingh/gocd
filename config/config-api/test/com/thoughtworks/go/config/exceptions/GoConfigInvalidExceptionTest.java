package com.thoughtworks.go.config.exceptions;

import com.thoughtworks.go.config.BasicCruiseConfig;
import com.thoughtworks.go.domain.ConfigErrors;
import org.junit.Test;

import java.util.ArrayList;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

public class GoConfigInvalidExceptionTest {

    @Test
    public void shouldSetAConsolidatedListOfErrorsAsMessage() {
        ArrayList<ConfigErrors> errors = new ArrayList<>();
        errors.add(error("key1"));
        errors.add(error("key2"));
        errors.add(error("key3"));

        GoConfigInvalidException exception = new GoConfigInvalidException(new BasicCruiseConfig(), errors);
        assertThat(exception.getMessage(), is("error on key1, error on key2, error on key3"));
    }

    private ConfigErrors error(String fieldName) {
        ConfigErrors errors1 = new ConfigErrors();
        errors1.add(fieldName, "error on " + fieldName);
        return errors1;
    }

}