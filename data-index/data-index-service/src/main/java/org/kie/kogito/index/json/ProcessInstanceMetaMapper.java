/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.kogito.index.json;

import java.util.function.Function;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

import org.kie.kogito.index.event.KogitoProcessCloudEvent;
import org.kie.kogito.index.model.ProcessInstance;

import static org.kie.kogito.index.Constants.PROCESS_INSTANCES_DOMAIN_ATTRIBUTE;
import static org.kie.kogito.index.json.JsonUtils.parseJson;

public class ProcessInstanceMetaMapper implements Function<KogitoProcessCloudEvent, JsonObject> {

    @Override
    public JsonObject apply(KogitoProcessCloudEvent event) {
        if (event == null) {
            return null;
        } else {
            ProcessInstance pi = event.getData();

            JsonObjectBuilder builder = Json.createObjectBuilder();
            builder.add("id", event.getRootProcessInstanceId() == null ? event.getProcessInstanceId() : event.getRootProcessInstanceId());
            builder.add("processId", event.getRootProcessId() == null ? event.getProcessId() : event.getRootProcessId());
            builder.add(PROCESS_INSTANCES_DOMAIN_ATTRIBUTE, getProcessJson(event, pi));
            builder.addAll(Json.createObjectBuilder(parseJson(event.getData().getVariables())));
            return builder.build();
        }
    }

    private JsonArray getProcessJson(KogitoProcessCloudEvent event, ProcessInstance pi) {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        builder.add("id", pi.getId());
        builder.add("processId", pi.getProcessId());
        if (pi.getRootProcessInstanceId() != null) {
            builder.add("rootProcessInstanceId", pi.getRootProcessInstanceId());
        }
        if (pi.getParentProcessInstanceId() != null) {
            builder.add("parentProcessInstanceId", pi.getParentProcessInstanceId());
        }
        if (pi.getRootProcessId() != null) {
            builder.add("rootProcessId", pi.getRootProcessId());
        }
        builder.add("state", pi.getState());
        if (event.getSource() != null) {
            builder.add("endpoint", event.getSource().toString());
        }
        if (pi.getStart() != null) {
            builder.add("start", pi.getStart().toInstant().toEpochMilli());
        }
        if (pi.getEnd() != null) {
            builder.add("end", pi.getEnd().toInstant().toEpochMilli());
        }
        return Json.createArrayBuilder().add(builder).build();
    }
}
