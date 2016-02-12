/*************************
 * GO-LICENSE-START*********************************
 * Copyright 2014 ThoughtWorks, Inc.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ************************GO-LICENSE-END
 ***********************************/
package com.thoughtworks.go.config;

import com.rits.cloning.Cloner;
import com.thoughtworks.go.config.remote.ConfigRepoConfig;
import com.thoughtworks.go.config.remote.ConfigReposConfig;
import com.thoughtworks.go.config.remote.PartialConfig;
import com.thoughtworks.go.listener.AsyncConfigChangedListener;
import com.thoughtworks.go.server.service.GoConfigService;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @understands current state of configuration part.
 * <p/>
 * Provides partial configurations.
 */
@Component
public class GoPartialConfig implements PartialConfigUpdateCompletedListener, ChangedRepoConfigWatchListListener, PartialsProvider, AsyncConfigChangedListener {

    private static final Logger LOGGER = Logger.getLogger(GoPartialConfig.class);

    private GoRepoConfigDataSource repoConfigDataSource;
    private GoConfigWatchList configWatchList;
    private final MergedGoConfig mergedGoConfig;
    private final GoConfigService goConfigService;
    private final CachedGoPartials cachedGoPartials;

    private List<PartialConfigChangedListener> listeners = new ArrayList<PartialConfigChangedListener>();

    @Autowired
    public GoPartialConfig(GoRepoConfigDataSource repoConfigDataSource,
                           GoConfigWatchList configWatchList, MergedGoConfig mergedGoConfig, GoConfigService goConfigService, CachedGoPartials cachedGoPartials) {
        this.repoConfigDataSource = repoConfigDataSource;
        this.configWatchList = configWatchList;
        this.mergedGoConfig = mergedGoConfig;
        this.goConfigService = goConfigService;
        this.cachedGoPartials = cachedGoPartials;

        this.configWatchList.registerListener(this);
        this.repoConfigDataSource.registerListener(this);
        this.mergedGoConfig.registerAsyncListener(this);
    }

    public void registerListener(PartialConfigChangedListener listener) {
        this.listeners.add(listener);
    }

    public boolean hasListener(PartialConfigChangedListener listener) {
        return this.listeners.contains(listener);
    }

//    private void notifyListeners() {
//        try {
//            goConfigService.updateConfig(new UpdateConfigCommand() {
//                @Override
//                public CruiseConfig update(CruiseConfig cruiseConfig) throws Exception {
//                    return cruiseConfig;
//                }
//            });
//        } catch (Exception e) {
//            mergedGoConfig.saveConfigError(e);
//        }
//    }

    public List<PartialConfig> lastPartials() {
        List<PartialConfig> list = new ArrayList<>();
        for (PartialConfig partialConfig : cachedGoPartials.getFingerprintToLatestValidConfigMap().values()) {
            list.add(partialConfig);
        }
        return list;
    }


    @Override
    public void onFailedPartialConfig(ConfigRepoConfig repoConfig, Exception ex) {
        // do nothing here, we keep previous version of part.
        // As an addition we should stop scheduling pipelines defined in that old part.
    }

    @Override
    public synchronized void onSuccessPartialConfig(ConfigRepoConfig repoConfig, PartialConfig newPart) {
        String fingerprint = repoConfig.getMaterialConfig().getFingerprint();
        if (this.configWatchList.hasConfigRepoWithFingerprint(fingerprint)) {
            //TODO maybe validate new part without context of other partials or main config

            // put latest valid
            cachedGoPartials.getFingerprintToLatestKnownConfigMap().put(fingerprint, newPart);
            if (updateConfig()) {
                this.cachedGoPartials.getFingerprintToLatestValidConfigMap().put(fingerprint, newPart);
            }
        }
    }

    private boolean updateConfig() {
        Map<String, PartialConfig> map = new Cloner().deepClone(cachedGoPartials.getFingerprintToLatestKnownConfigMap());
        try {
            final List<PartialConfig> partials = new ArrayList<>();
            for (PartialConfig partialConfig : map.values()) {
                partials.add(partialConfig);
            }
            goConfigService.updateConfig(new UpdateConfigCommand() {
                @Override
                public CruiseConfig update(CruiseConfig cruiseConfig) throws Exception {
                    cruiseConfig.setPartials(partials);
                    return cruiseConfig;
                }

            });
            return true;
        } catch (Exception e) {
            mergedGoConfig.saveConfigError(e);
            return false;
        }
    }

    @Override
    public synchronized void onChangedRepoConfigWatchList(ConfigReposConfig newConfigRepos) {
        List<String> toRemove = new ArrayList<>();
        // remove partial configs from map which are no longer on the list
        for (String fingerprint : cachedGoPartials.getFingerprintToLatestKnownConfigMap().keySet()) {
            if (!newConfigRepos.hasMaterialWithFingerprint(fingerprint)) {
                cachedGoPartials.getFingerprintToLatestKnownConfigMap().remove(fingerprint);
                toRemove.add(fingerprint);
            }
        }
        if (!toRemove.isEmpty()) {
            if (updateConfig()) {
                for (String fingerprint : toRemove) {
                    this.cachedGoPartials.getFingerprintToLatestValidConfigMap().remove(fingerprint);
                }
            }
        }
    }

    @Override
    public void onConfigChange(CruiseConfig newCruiseConfig) {
        if (!cachedGoPartials.areAllKnownPartialsValid()) {
            updateConfig();
        }
    }
}
