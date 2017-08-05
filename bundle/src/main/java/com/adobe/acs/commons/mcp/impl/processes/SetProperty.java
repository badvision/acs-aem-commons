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
import com.adobe.acs.commons.mcp.form.FormField;
import com.adobe.acs.commons.mcp.form.PathfieldComponent;
import com.adobe.acs.commons.mcp.form.RadioComponent;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.jcr.RepositoryException;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * Set a missing property on target nodes
 */
public class SetProperty implements ProcessDefinition {
    public static final String DEFAULT_TREE_NODE_TYPES = "sling:folder,sling:orderedfolder,nt:hierarchynode,nt:unstructured,cq:page";

    public static final String DEFAULT_NODE_TYPES = "nt:unstructured,cq:pagecontent,dam:asset";

    private static enum SetRule {
        SET_IF_MISSING, ALWAYS_SET, ALWAYS_APPEND, APPEND_IF_MISSING
    }

    private static enum Plurality {
        SINGLE, LIST
    }

    private static enum PropertyType {
        STRING, LONG, DOUBLE, BOOLEAN, DATE
    }

    @FormField(
            name = "Base Path",
            description = "Starting point of process",
            hint = "/content/dam/my-folder, /content/my-site/my-page, etc.",
            component = PathfieldComponent.class
    )
    private String basePath;

    @FormField(
            name = "Tree types",
            description = "Case-insensitive node types that are interpreted as tree nodes comma-separated, * = all",
            options = {"default="+DEFAULT_TREE_NODE_TYPES}
            
    )
    private String traverseTypes=DEFAULT_TREE_NODE_TYPES;
    private List<String> traverseTypeList;

    @FormField(
            name = "Node types",
            description = "Only consider properties on these node types, * = all",
            options = {"default="+DEFAULT_NODE_TYPES}
            
    )
    private String targetNodeTypes = DEFAULT_NODE_TYPES;
    private List<String> targetNodeTypeList;

    @FormField(
            name = "Property path",
            description = "Relative to target node, can be a path",
            hint = "myProperty, jcr:content/metadata/someProperty"
    )
    private String relativePropertyPath;

    @FormField(
            name = "Property type",
            description = "Property type (usually string)",
            component = RadioComponent.EnumerationSelector.class,
            options = "default=STRING"
    )
    private PropertyType propertyType;

    @FormField(
            name = "Property multi",
            description = "Single or multiple values in a list?",
            component = RadioComponent.EnumerationSelector.class,
            options = "default=SINGLE"
    )
    private Plurality propertyPlurality;

    @FormField(
            name = "Property value",
            description = "Property value set according to the specified rule, value must match selected type",
            hint = "12345, myTag:someTag, Dates can use NOW, NOW-1 (yesterday), etc."
    )
    private String propertyValue;

    @FormField(
            name = "Set rule",
            description = "Rule to decide when property should be set and how to set it",
            component = RadioComponent.EnumerationSelector.class,
            options = "default=SET_IF_MISSING"
    )
    private SetRule setRule;

    @Override
    public String getName() {
        return "Set Property";
    }

    @Override
    public void init() throws RepositoryException {
        if (propertyPlurality == Plurality.SINGLE && (setRule == SetRule.ALWAYS_APPEND || setRule == SetRule.APPEND_IF_MISSING)) {
            throw new RepositoryException("Selected set rule " + setRule.name() + " only applies for list properties");
        }
        traverseTypeList = parseList(traverseTypes);
        targetNodeTypeList = parseList(targetNodeTypes);
        
    }

    @Override
    public void buildProcess(ProcessInstance instance, ResourceResolver rr) throws LoginException, RepositoryException {
    }

    @Override
    public void storeReport(ProcessInstance instance, ResourceResolver rr) throws RepositoryException, PersistenceException {
    }
    
    private List<String> parseList(String values) {
        if (values == null || values.isEmpty() || values.contains("*")) {
            return Collections.EMPTY_LIST;
        } else {
            return Stream.of(values.split(",")).map(String::trim).filter(s->!s.isEmpty()).collect(Collectors.toList());
        }
    }
}