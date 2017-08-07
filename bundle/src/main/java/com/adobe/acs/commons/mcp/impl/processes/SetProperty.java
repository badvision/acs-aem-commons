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

import com.adobe.acs.commons.fam.ActionManager;
import com.adobe.acs.commons.mcp.ProcessDefinition;
import com.adobe.acs.commons.mcp.ProcessInstance;
import com.adobe.acs.commons.mcp.form.FormField;
import com.adobe.acs.commons.mcp.form.PathfieldComponent;
import com.adobe.acs.commons.mcp.form.RadioComponent;
import com.adobe.acs.commons.util.visitors.SimpleFilteringResourceVisitor;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.jcr.RepositoryException;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
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
        STRING(String.class, Function.identity(), Array::get), 
        LONG(Long.TYPE, Long::parseLong, Array::getLong), 
        DOUBLE(Double.TYPE, Double::parseDouble, Array::getDouble), 
        BOOLEAN(Boolean.TYPE, Boolean::parseBoolean, Array::getBoolean), 
        DATE(Date.class, SetProperty::parseDate, Array::get);
        Class clazz;
        Function<String, ? extends Object> parser;
        BiFunction<Object, Integer, ?> arrayGetter;

        PropertyType(Class c, Function<String, ? extends Object> p, BiFunction<Object, Integer, Object> a) {
            clazz = c;
            parser = p;
            arrayGetter = a;
        }
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
            options = {"default=" + DEFAULT_TREE_NODE_TYPES}
    )
    private String traverseTypes = DEFAULT_TREE_NODE_TYPES;
    private List<String> traverseTypeList;

    @FormField(
            name = "Node types",
            description = "Only consider properties on these node types, * = all",
            options = {"default=" + DEFAULT_NODE_TYPES}
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
    private Class propertyClass;

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
    private Object propertyObjectValue;
    
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
        if (propertyPlurality == Plurality.SINGLE) {
            propertyClass = propertyType.clazz;
        } else {
            propertyClass = Array.newInstance(propertyType.clazz, 0).getClass();
        }

        traverseTypeList = parseList(traverseTypes);
        targetNodeTypeList = parseList(targetNodeTypes);
        
        propertyObjectValue = propertyType.parser.apply(propertyValue);
    }

    @Override
    public void buildProcess(ProcessInstance instance, ResourceResolver rr) throws LoginException, RepositoryException {
        instance.getInfo().setDescription(basePath + " " + setRule.name().toLowerCase() + " " + relativePropertyPath + " to " + propertyValue);
        instance.defineCriticalAction("Set properties", rr, this::performPropertiesChange);
    }

    @Override
    public void storeReport(ProcessInstance instance, ResourceResolver rr) throws RepositoryException, PersistenceException {
    }

    private void performPropertiesChange(ActionManager manager) {
        SimpleFilteringResourceVisitor visitor = new SimpleFilteringResourceVisitor();
        if (!traverseTypeList.isEmpty()) {
            visitor.setTraversalFilter(r -> traverseTypeList.contains(r.getResourceType().toLowerCase()));
        }

        visitor.setResourcesAndLeafVisitor((r, level) -> {
            if (targetNodeTypeList.isEmpty() || targetNodeTypeList.contains(r.getResourceType().toLowerCase())) {
                manager.deferredWithResolver(rr -> {
                    setPropertyIfRequired(rr, r.getPath());
                });
            }
        });

        manager.deferredWithResolver(rr -> visitor.accept(rr.getResource(basePath)));
    }

    private void setPropertyIfRequired(ResourceResolver r, String path) {
        Resource res = r.getResource(path);
        String propName = relativePropertyPath;
        if (relativePropertyPath.contains("/")) {
            res = res.getChild(relativePropertyPath.substring(0, relativePropertyPath.lastIndexOf('/')));
            propName = relativePropertyPath.substring(relativePropertyPath.lastIndexOf('/') + 1);
        }
        ModifiableValueMap props = res.adaptTo(ModifiableValueMap.class);
        switch (setRule) {
            case SET_IF_MISSING:
                if (props.containsKey(propName)) {
                    if (propertyType == PropertyType.STRING && propertyPlurality == Plurality.SINGLE) {
                        String val = props.get(propName, String.class);
                        if (val != null && !val.trim().isEmpty()) {
                            break;
                        }
                    } else {
                        break;
                    }
                }
            case ALWAYS_SET:
                setProperty(props, propName);
                break;
            case APPEND_IF_MISSING:
                if (props.containsKey(propName) && hasPropertyInList(props, propName)) {
                    break;
                }
            case ALWAYS_APPEND:
                appendProperty(props, propName);
        }
    }

    private boolean hasPropertyInList(Map<String, Object> props, String key) {
        Object prop = props.get(key);
        if (prop != null) {
            for (int i=0; i < Array.getLength(prop); i++)  {
                Object v = propertyType.arrayGetter.apply(prop, i);
                if (v.equals(propertyObjectValue)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void setProperty(Map<String, Object> props, String key) {
        if (propertyPlurality == Plurality.SINGLE) {
            props.put(key, propertyObjectValue);
        } else {
            Object newArray = Array.newInstance(propertyType.clazz, 1);
            Array.set(newArray, 0, propertyObjectValue);
            props.put(key, newArray);
        }
    }

    private void appendProperty(Map<String, Object> props, String key) {
        Object origArray = props.get(key);
        int len = Array.getLength(origArray);
        Object newArray = Array.newInstance(propertyType.clazz, len + 1);
        for (int i=0; i < len; i++) {
            Array.set(newArray, i, Array.get(origArray, i));
        }
        Array.set(newArray, len, propertyObjectValue);
        props.put(key, newArray);
    }

    private List<String> parseList(String values) {
        if (values == null || values.isEmpty() || values.contains("*")) {
            return Collections.EMPTY_LIST;
        } else {
            return Stream.of(values.split(","))
                    .map(String::trim).map(String::toLowerCase)
                    .filter(s -> !s.isEmpty()).collect(Collectors.toList());
        }
    }
    
    private static Date parseDate(String dateStr) {
        return null;
    }
}
