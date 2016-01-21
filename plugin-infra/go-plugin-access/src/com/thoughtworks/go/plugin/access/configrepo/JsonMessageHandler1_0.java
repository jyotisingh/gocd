package com.thoughtworks.go.plugin.access.configrepo;


import com.google.gson.GsonBuilder;
import com.thoughtworks.go.plugin.access.configrepo.codec.GsonCodec;
import com.thoughtworks.go.plugin.access.configrepo.contract.*;
import com.thoughtworks.go.plugin.access.configrepo.messages.*;
import org.apache.log4j.Logger;

import java.util.Collection;
import java.util.Map;

public class JsonMessageHandler1_0 implements JsonMessageHandler {

    private static final Logger LOGGER = Logger.getLogger(JsonMessageHandler1_0.class);
    private static final int CURRENT_CONTRACT_VERSION = 1;

    private final GsonCodec codec;

    public JsonMessageHandler1_0(){
        codec = new GsonCodec();
    }

    @Override
    public String requestMessageForParseDirectory(String destinationFolder, Collection<CRConfigurationProperty> configurations) {
        ParseDirectoryMessage requestMessage = prepareMessage_1(destinationFolder, configurations);
        return codec.getGson().toJson(requestMessage);
    }

    private ParseDirectoryMessage prepareMessage_1(String destinationFolder, Collection<CRConfigurationProperty> configurations) {
        ParseDirectoryMessage requestMessage = new ParseDirectoryMessage(destinationFolder);
        for(CRConfigurationProperty conf : configurations)
        {
            requestMessage.addConfiguration(conf.getKey(),conf.getValue(),conf.getEncryptedValue());
        }
        return requestMessage;
    }
    class ResponseScratch {
        public Integer target_version;
    }
    private ResponseScratch parseResponseForMigration(String responseBody) {
        return new GsonBuilder().create().fromJson(responseBody, ResponseScratch.class);
    }

    @Override
    public CRParseResult responseMessageForParseDirectory(String responseBody) {
        try {
            ResponseScratch responseMap = parseResponseForMigration(responseBody);
            ErrorCollection errors = new ErrorCollection();
            ParseDirectoryResponseMessage parseDirectoryResponseMessage;

            if(responseMap.target_version == null)
            {
                errors.addError("Plugin response message","missing 'target_version' field");
                String errorsText = errors.getErrorsAsText();
                return new CRParseResult(errorsText);
            }
            else {
                int version = responseMap.target_version;

                while (version < CURRENT_CONTRACT_VERSION) {
                    migrate(responseBody, version);
                    version++;
                }
                // after migration, json should match contract
                parseDirectoryResponseMessage = codec.getGson().fromJson(responseBody, ParseDirectoryResponseMessage.class);
                parseDirectoryResponseMessage.validateResponse(errors);

                String errorsText = errors.getErrorsAsText();
                return new CRParseResult(parseDirectoryResponseMessage.getEnvironments(), parseDirectoryResponseMessage.getPipelines(), errorsText);
            }
        }
        catch (Exception ex)
        {
            StringBuilder builder = new StringBuilder();
            builder.append("Unexpected error when handling plugin response").append('\n');
            builder.append(ex);
            return new CRParseResult(builder.toString());
        }
    }

    private void migrate(String responseBody, int version) {

    }
}
