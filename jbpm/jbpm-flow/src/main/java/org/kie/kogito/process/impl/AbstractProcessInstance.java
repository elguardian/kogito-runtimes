/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.kogito.process.impl;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jbpm.process.instance.InternalProcessRuntime;
import org.jbpm.workflow.instance.NodeInstance;
import org.jbpm.workflow.instance.WorkflowProcessInstance;
import org.jbpm.workflow.instance.impl.NodeInstanceImpl;
import org.jbpm.workflow.instance.impl.WorkflowProcessInstanceImpl;
import org.jbpm.workflow.instance.node.WorkItemNodeInstance;
import org.kie.api.runtime.process.EventListener;
import org.kie.api.runtime.process.ProcessRuntime;
import org.kie.api.runtime.process.WorkItemNotFoundException;
import org.kie.kogito.Model;
import org.kie.kogito.process.MutableProcessInstances;
import org.kie.kogito.process.Process;
import org.kie.kogito.process.ProcessError;
import org.kie.kogito.process.ProcessInstance;
import org.kie.kogito.process.ProcessInstanceNotFoundException;
import org.kie.kogito.process.Signal;
import org.kie.kogito.process.WorkItem;
import org.kie.kogito.process.workitem.Policy;
import org.kie.kogito.process.workitem.Transition;
import org.kie.kogito.services.uow.ProcessInstanceWorkUnit;

public abstract class AbstractProcessInstance<T extends Model> implements ProcessInstance<T> {

    private final T variables;
    private final AbstractProcess<T> process;
    private final ProcessRuntime rt;
    private org.kie.api.runtime.process.ProcessInstance legacyProcessInstance;
    
    private Integer status;
    private String id;
    
    private ProcessError processError;
    
    private Supplier<org.kie.api.runtime.process.ProcessInstance> reloadSupplier;
    
    private CompletionEventListener completionEventListener = new CompletionEventListener();

    public AbstractProcessInstance(AbstractProcess<T> process, T variables, ProcessRuntime rt) {
        this.process = process;
        this.rt = rt;
        this.variables = variables;
        
        Map<String, Object> map = bind(variables);
        String processId = process.legacyProcess().getId();
        this.legacyProcessInstance = rt.createProcessInstance(processId, map);
        this.id = legacyProcessInstance.getId();
        this.status = ProcessInstance.STATE_PENDING;
    }
    
    // for marshaller/persistence only
    public void internalSetProcessInstance(org.kie.api.runtime.process.ProcessInstance legacyProcessInstance) {
        if (this.legacyProcessInstance != null && this.status != ProcessInstance.STATE_PENDING) {
            throw new IllegalStateException("Impossible to override process instance that already exists");
        }
        this.legacyProcessInstance = legacyProcessInstance;
        this.status = legacyProcessInstance.getState();
        this.id = legacyProcessInstance.getId();
        ((WorkflowProcessInstanceImpl) this.legacyProcessInstance).setKnowledgeRuntime( ((InternalProcessRuntime)rt).getInternalKieRuntime() );
        ((WorkflowProcessInstanceImpl) this.legacyProcessInstance).reconnect();
        
        ((WorkflowProcessInstanceImpl) this.legacyProcessInstance).setMetaData("KogitoProcessInstance", this);
        ((WorkflowProcessInstance)legacyProcessInstance).addEventListener("processInstanceCompleted:"+this.id, completionEventListener, false);
        
        for (org.kie.api.runtime.process.NodeInstance nodeInstance : ((WorkflowProcessInstance)legacyProcessInstance).getNodeInstances()) {
            if (nodeInstance instanceof WorkItemNodeInstance) {
                ((WorkItemNodeInstance) nodeInstance).internalRegisterWorkItem();
            }
        }
        
        
        unbind(variables, legacyProcessInstance.getVariables());
    }
    
    public org.kie.api.runtime.process.ProcessInstance internalGetProcessInstance() {
        return legacyProcessInstance;
    }
    
    public void internalRemoveProcessInstance(Supplier<org.kie.api.runtime.process.ProcessInstance> reloadSupplier) {
        this.reloadSupplier = reloadSupplier;
        this.status = legacyProcessInstance.getState();
        if (this.status == STATE_ERROR) {
            this.processError = buildProcessError();
        }
        this.legacyProcessInstance = null;
    }

    public void start() {
        if (this.status != ProcessInstance.STATE_PENDING) {
            throw new IllegalStateException("Impossible to start process instance that already was started");
        }
        this.status = ProcessInstance.STATE_ACTIVE;
        ((WorkflowProcessInstance)legacyProcessInstance).addEventListener("processInstanceCompleted:"+this.id, completionEventListener, false);
        
        
        org.kie.api.runtime.process.ProcessInstance processInstance = this.rt.startProcessInstance(this.id);
        addToUnitOfWork(pi -> ((MutableProcessInstances<T>)process.instances()).update(pi.id(), pi));
        unbind(variables, processInstance.getVariables());
        if (legacyProcessInstance != null) {
            this.status = legacyProcessInstance.getState();
        }
    }
    
    protected void addToUnitOfWork(Consumer<ProcessInstance<T>> action) {
        ((InternalProcessRuntime) rt).getUnitOfWorkManager().currentUnitOfWork().intercept(new ProcessInstanceWorkUnit(this, action));
    }

    public void abort() {
        legacyProcessInstance();
        String pid = legacyProcessInstance().getId();
        unbind(variables, legacyProcessInstance().getVariables());        
        this.rt.abortProcessInstance(pid);
        this.status = legacyProcessInstance.getState();
        addToUnitOfWork(pi -> ((MutableProcessInstances<T>)process.instances()).remove(pi.id()));
    }

    @Override
    public <S> void send(Signal<S> signal) {
        legacyProcessInstance().signalEvent(signal.channel(), signal.payload());
        removeOnFinish();
    }

    @Override
    public Process<T> process() {
        return process;
    }

    @Override
    public T variables() {
        return variables;
    }

    @Override
    public int status() {
        return this.status;
    }

    @Override
    public String id() {
        return this.id;
    }
    
    @Override
    public void updateVariables(T updates) {
        Map<String, Object> map = bind(updates);
        
        for (Entry<String, Object> entry : map.entrySet()) {
            ((WorkflowProcessInstance)legacyProcessInstance()).setVariable(entry.getKey(), entry.getValue());
        }
        addToUnitOfWork(pi -> ((MutableProcessInstances<T>)process.instances()).update(pi.id(), pi));
    }

    @Override
    public Optional<ProcessError> error() {
        if (this.status == STATE_ERROR) {
            return Optional.of(this.processError != null ? this.processError : buildProcessError());
        }
        
        return Optional.empty();
    }

    
    private org.kie.api.runtime.process.ProcessInstance legacyProcessInstance() {
        if (this.legacyProcessInstance == null) {
            this.legacyProcessInstance = reloadSupplier.get();
            if (this.legacyProcessInstance == null) {
                throw new ProcessInstanceNotFoundException(id);
            }
        }
        
        return this.legacyProcessInstance;
    }

    @Override
    public WorkItem workItem(String workItemId, Policy<?>... policies) {
        WorkItemNodeInstance workItemInstance = (WorkItemNodeInstance) ((WorkflowProcessInstance)legacyProcessInstance()).getNodeInstances()
                .stream()
                .filter(ni -> ni instanceof WorkItemNodeInstance && ((WorkItemNodeInstance) ni).getWorkItemId().equals(workItemId) && ((WorkItemNodeInstance)ni).getWorkItem().enforce(policies))
                .findFirst()
                .orElseThrow(() -> new WorkItemNotFoundException("Work item with id " + workItemId + " was not found in process instance " + id(), workItemId));
        return new BaseWorkItem(workItemInstance.getWorkItem().getId(), 
                                (String)workItemInstance.getWorkItem().getParameters().getOrDefault("TaskName", workItemInstance.getNodeName()), 
                                workItemInstance.getWorkItem().getState(),
                                workItemInstance.getWorkItem().getPhaseId(),
                                workItemInstance.getWorkItem().getPhaseStatus(),
                                workItemInstance.getWorkItem().getParameters(),
                                workItemInstance.getWorkItem().getResults());
    }

    @Override
    public List<WorkItem> workItems(Policy<?>... policies) {
        return ((WorkflowProcessInstance)legacyProcessInstance()).getNodeInstances()
                .stream()
                .filter(ni -> ni instanceof WorkItemNodeInstance && ((WorkItemNodeInstance)ni).getWorkItem().enforce(policies))
                .map(ni -> new BaseWorkItem(((WorkItemNodeInstance)ni).getWorkItemId(), 
                                            (String)((WorkItemNodeInstance)ni).getWorkItem().getParameters().getOrDefault("TaskName", ni.getNodeName()), 
                                            ((WorkItemNodeInstance)ni).getWorkItem().getState(),
                                            ((WorkItemNodeInstance)ni).getWorkItem().getPhaseId(),
                                            ((WorkItemNodeInstance)ni).getWorkItem().getPhaseStatus(),
                                            ((WorkItemNodeInstance)ni).getWorkItem().getParameters(),
                                            ((WorkItemNodeInstance)ni).getWorkItem().getResults()))
                .collect(Collectors.toList());
        
    }

    @Override
    public void completeWorkItem(String id, Map<String, Object> variables, Policy<?>... policies) {
        this.rt.getWorkItemManager().completeWorkItem(id, variables, policies);
        removeOnFinish();
    }
    
    @Override
    public void abortWorkItem(String id, Policy<?>... policies) {
        this.rt.getWorkItemManager().abortWorkItem(id, policies);
        removeOnFinish();
    }
    
    @Override
    public void transitionWorkItem(String id, Transition<?> transition) {
        this.rt.getWorkItemManager().transitionWorkItem(id, transition);
        removeOnFinish();
    }

    protected void removeOnFinish() {

        if (legacyProcessInstance.getState() != ProcessInstance.STATE_ACTIVE && legacyProcessInstance.getState() != ProcessInstance.STATE_ERROR) {            
            ((WorkflowProcessInstance)legacyProcessInstance).removeEventListener("processInstanceCompleted:"+legacyProcessInstance.getId(), completionEventListener, false);

            this.status = legacyProcessInstance.getState();
            this.id = legacyProcessInstance.getId();
            
            addToUnitOfWork(pi -> ((MutableProcessInstances<T>)process.instances()).remove(pi.id()));
            
        } else {
            addToUnitOfWork(pi -> ((MutableProcessInstances<T>)process.instances()).update(pi.id(), pi));
        }
        unbind(this.variables, legacyProcessInstance().getVariables());
        this.status = legacyProcessInstance.getState();
    }

    // this must be overridden at compile time
    protected Map<String, Object> bind(T variables) {
        HashMap<String, Object> vmap = new HashMap<>();
        if (variables == null) {
            return vmap;
        }
        try {
            for (Field f : variables.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = null;
                v = f.get(variables);
                vmap.put(f.getName(), v);
            }
        } catch (IllegalAccessException e) {
            throw new Error(e);
        }
        vmap.put("$v", variables);
        return vmap;
    }

    protected void unbind(T variables, Map<String, Object> vmap) {
        try {
            for (Field f : variables.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                f.set(variables, vmap.get(f.getName()));
            }
        } catch (IllegalAccessException e) {
            throw new Error(e);
        }
        vmap.put("$v", variables);        
    }    
    
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((id == null) ? 0 : id.hashCode());
        result = prime * result + ((status == null) ? 0 : status.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        AbstractProcessInstance other = (AbstractProcessInstance) obj;
        if (id == null) {
            if (other.id != null)
                return false;
        } else if (!id.equals(other.id))
            return false;
        if (status == null) {
            if (other.status != null)
                return false;
        } else if (!status.equals(other.status))
            return false;
        return true;
    }

    private class CompletionEventListener implements EventListener {
        
        @Override
        public void signalEvent(String type, Object event) {
            removeOnFinish();
        }
        
        @Override
        public String[] getEventTypes() {
            return new String[] {"processInstanceCompleted:"+legacyProcessInstance.getId()};
        }
    }
    
    protected ProcessError buildProcessError() {
        WorkflowProcessInstanceImpl pi = (WorkflowProcessInstanceImpl) legacyProcessInstance();
        
        final String errorMessage = pi.getErrorMessage();
        final String nodeInError = pi.getNodeIdInError();
        return new ProcessError() {
            
            @Override
            public String failedNodeId() {
                return nodeInError;
            }
            
            @Override
            public String errorMessage() {
                return errorMessage;
            }
            @Override
            public void retrigger() {
                WorkflowProcessInstanceImpl pInstance = (WorkflowProcessInstanceImpl) legacyProcessInstance();
                NodeInstance ni = pInstance.getNodeInstanceByNodeDefinitionId(nodeInError, pInstance.getNodeContainer());
                pInstance.setState(STATE_ACTIVE);
                pInstance.internalSetErrorNodeId(null);
                pInstance.internalSetErrorMessage(null);
                ni.trigger(null, org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE);
                removeOnFinish();
            }

            @Override
            public void skip() {
                WorkflowProcessInstanceImpl pInstance = (WorkflowProcessInstanceImpl) legacyProcessInstance();
                NodeInstance ni = pInstance.getNodeInstanceByNodeDefinitionId(nodeInError, pInstance.getNodeContainer());
                pInstance.setState(STATE_ACTIVE);
                pInstance.internalSetErrorNodeId(null);
                pInstance.internalSetErrorMessage(null);
                ((NodeInstanceImpl) ni).triggerCompleted(org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE, true);
                removeOnFinish();
            }
        };
    }
}
