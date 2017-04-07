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
package com.adobe.acs.commons.util;

import com.adobe.acs.commons.fam.Failure;
import com.adobe.acs.commons.fam.ActionManager;
import com.adobe.acs.commons.fam.ActionManagerFactory;
import com.adobe.acs.commons.fam.DeferredActions;
import com.adobe.acs.commons.fam.ManagedProcess;
import com.adobe.acs.commons.util.visitors.TreeFilteringItemVisitor;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.jcr.ItemVisitor;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * This utility takes an alternate approach to moving folders using a four-step
 * process.
 * <ul>
 * <li>Step 1: Evaluate the requirements, check for possible authorization
 * issues; Abort sequence halts other work</li>
 * <li>Step 2: Prepare destination folder structure; Abort sequence is to remove
 * any folders created already</li>
 * <li>Step 3: Relocate the contents of the folders</li>
 * <li>Step 4: Remove the old folder structures</li>
 * </ul>
 */
public class FolderRelocator extends ManagedProcess {

    public static enum Mode {
        RENAME, MOVE
    };
    private final String sourcePath;
    private final String destinationPath;
    private final Mode mode;

    public FolderRelocator(ActionManagerFactory amf, String sourcePath, String destinationPath, String processName, Mode processMode) {
        super(amf, processName);
        this.sourcePath = sourcePath;
        this.mode = processMode;

        if (mode == Mode.MOVE) {
            String nodeName = sourcePath.substring(sourcePath.indexOf('/'));
            this.destinationPath = destinationPath + nodeName;
        } else {
            this.destinationPath = destinationPath;
        }
    }

    String[] requiredFolderPrivilegeNames = {
        Privilege.JCR_READ,
        Privilege.JCR_WRITE,
        Privilege.JCR_REMOVE_CHILD_NODES,
        Privilege.JCR_REMOVE_NODE
    };

    String[] requiredNodePrivilegeNames = {
        Privilege.JCR_ALL
    };

    Privilege[] requiredFolderPrivileges;
    Privilege[] requiredNodePrivileges;

    @Override
    public void buildProcess(ResourceResolver rr) throws LoginException, RepositoryException {
        validateInputs(rr);
        defineCriticalAction("Validate ACLs", rr, this::validateAllAcls);
        defineCriticalAction("Build target folders", rr, this::buildTargetFolders);
        defineCriticalAction("Move nodes", rr, this::moveNodes);
        defineCriticalAction("Remove old folders", rr, this::removeSourceFolders);
    }

    private void validateInputs(ResourceResolver res) throws RepositoryException {
        if (sourcePath == null) {
            throw new RepositoryException("Source path should not be null");
        }
        if (destinationPath == null) {
            throw new RepositoryException("Destination path should not be null");
        }
        if (destinationPath.contains(sourcePath + "/")) {
            throw new RepositoryException("Destination must be outside of source folder");
        }
        if (!resourceExists(res, sourcePath)) {
            throw new RepositoryException("Unable to find source " + sourcePath);
        }
        if (!resourceExists(res, destinationPath.substring(0, destinationPath.lastIndexOf('/')))) {
            throw new RepositoryException("Unable to find destination " + destinationPath);
        }
    }

    private boolean resourceExists(ResourceResolver rr, String path) {
        Resource res = rr.resolve(path);
        return !Resource.RESOURCE_TYPE_NON_EXISTING.equals(res.getResourceType());
    }

    private void validateAllAcls(ActionManager step1) {
        TreeFilteringItemVisitor folderVisitor = new TreeFilteringItemVisitor();
        folderVisitor.setBreadthFirst(true);
        folderVisitor.onEnterNode((node, level) -> step1.deferredWithResolver(rr -> checkNodeAcls(rr, node.getPath(), requiredFolderPrivileges)));
        folderVisitor.onVisitChild((node, level) -> step1.deferredWithResolver(rr -> checkNodeAcls(rr, node.getPath(), requiredNodePrivileges)));
        beginStep(step1, sourcePath, folderVisitor);
    }

    private Privilege[] getPrivilegesFromNames(Session session, String[] names) throws RepositoryException {
        AccessControlManager acm = session.getAccessControlManager();
        Privilege[] prvlgs = new Privilege[names.length];
        for (int i = 0; i < names.length; i++) {
            prvlgs[i] = acm.privilegeFromName(names[i]);
        }
        return prvlgs;
    }

    private void checkNodeAcls(ResourceResolver res, String path, Privilege[] prvlgs) throws RepositoryException {
        Session session = res.adaptTo(Session.class);
        if (!session.getAccessControlManager().hasPrivileges(path, prvlgs)) {
            throw new RepositoryException("Insufficient permissions to permit move operation");
        }
    }

    private void buildTargetFolders(ActionManager step2) {
        TreeFilteringItemVisitor folderVisitor = new TreeFilteringItemVisitor();
        folderVisitor.setBreadthFirst(true);
        folderVisitor.onEnterNode((node, level) -> step2.deferredWithResolver(
                DeferredActions.retry(5, 100, rr -> buildDestinationFolder(rr, node.getPath()))
        ));
        folderVisitor.onVisitChild(null);
        beginStep(step2, sourcePath, folderVisitor);
    }

    private void abortStep2(List<Failure> errors, ResourceResolver res) {
        try {
            TreeFilteringItemVisitor folderVisitor = new TreeFilteringItemVisitor();
            folderVisitor.setBreadthFirst(true);
            folderVisitor.onEnterNode((node, level) -> res.delete(res.resolve(node.getPath())));
            folderVisitor.onVisitChild(null);
            Node source = res.getResource(convertSourceToDestination(sourcePath)).adaptTo(Node.class);
            source.accept(folderVisitor);
        } catch (RepositoryException ex) {
            Logger.getLogger(FolderRelocator.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private void buildDestinationFolder(ResourceResolver rr, String sourceFolder) throws PersistenceException, RepositoryException {
        Resource source = rr.getResource(sourceFolder);
        String targetPath = convertSourceToDestination(sourceFolder);
        ActionManager.setCurrentItem(sourceFolder + "->" + targetPath);
        String targetParentPath = targetPath.substring(0, targetPath.lastIndexOf('/'));
        String targetName = targetPath.substring(targetPath.lastIndexOf('/') + 1);
        Resource destParent = rr.getResource(targetParentPath);
        if (destParent.isResourceType(Resource.RESOURCE_TYPE_NON_EXISTING)) {
            throw new RepositoryException("Unable to find target folder " + targetParentPath);
        }
        rr.create(destParent, targetName, source.getValueMap());
    }

    private String convertSourceToDestination(String source) {
        return source.replaceAll(Pattern.quote(sourcePath), destinationPath);
    }

    private void moveNodes(ActionManager step3) {
        TreeFilteringItemVisitor folderVisitor = new TreeFilteringItemVisitor();
        folderVisitor.setBreadthFirst(true);
        folderVisitor.onEnterNode(null);
        folderVisitor.onVisitChild((node, level) -> step3.deferredWithResolver(
                DeferredActions.retry(15, 250, rr -> moveItem(rr, node.getPath()))
        ));
        beginStep(step3, sourcePath, folderVisitor);
    }

    private void moveItem(ResourceResolver rr, String path) throws RepositoryException {
        ActionManager.setCurrentItem(path);
        Session session = rr.adaptTo(Session.class);
        // Inhibits some workflows
        session.getWorkspace().getObservationManager().setUserData("changedByWorkflowProcess");
        if (path.endsWith("jcr:content")) {
            session.removeItem(convertSourceToDestination(path));
        }
        session.move(path, convertSourceToDestination(path));
        if (path.endsWith("jcr:content")) {
            session.refresh(true);
            session.save();
        }
    }

    private void removeSourceFolders(ActionManager step4) {
        step4.deferredWithResolver(rr -> rr.delete(rr.getResource(sourcePath)));
    }

    private void beginStep(ActionManager step, String startingNode, ItemVisitor visitor) {
        step.deferredWithResolver(rr -> {
            Node source = rr.getResource(startingNode).adaptTo(Node.class);
            source.accept(visitor);
        });
    }
}