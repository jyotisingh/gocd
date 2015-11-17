package com.thoughtworks.go.validation;

import com.thoughtworks.go.config.CruiseConfig;

public class DoNothingValidator implements ConfigUpdateValidator {
    @Override
    public boolean isValid(CruiseConfig preprocessedConfig) {
        return true;
    }
}
