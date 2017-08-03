/*
 * Copyright 2017 Adobe.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.adobe.acs.commons.mcp.impl.processes;

import com.adobe.acs.commons.mcp.ProcessDefinition;
import com.adobe.acs.commons.mcp.ProcessInstance;
import java.util.List;
import javax.jcr.RepositoryException;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * Set a missing property on target nodes
 */
public class SetProperty implements ProcessDefinition {
    private static enum SetRule{ALWAYS_SET, SET_IF_MISSING, ALWAYS_APPEND, APPEND_IF_MISSING}
    
    private static enum Plurality{SINGLE_VALUE, LIST}
    
    private static enum PropertyType{STRING, LONG, DOUBLE, BOOLEAN, DATE}
    
    private String basePath;
    
    private String traverseTypes;
    private List<String> traverseTypeList;
    
    private String targetNodeTypes;
    private List<String> targetNodeTypeList;
    
    private String relativePropertyPath;
    
    private PropertyType propertyType;
    
    private Plurality propertyPlurality;
    
    private String propertyValue;
    
    private SetRule setRule;

    @Override
    public String getName() {
        return "Set Property";
    }
    
    @Override
    public void init() throws RepositoryException {
        if (propertyPlurality == Plurality.SINGLE_VALUE && (
                setRule == SetRule.ALWAYS_APPEND || setRule == SetRule.APPEND_IF_MISSING)) {
            throw new RepositoryException("Selected set rule " + setRule.name() + " only applies for list properties");
        }
    }

    @Override
    public void buildProcess(ProcessInstance instance, ResourceResolver rr) throws LoginException, RepositoryException {
    }

    @Override
    public void storeReport(ProcessInstance instance, ResourceResolver rr) throws RepositoryException, PersistenceException {
    }    
}
