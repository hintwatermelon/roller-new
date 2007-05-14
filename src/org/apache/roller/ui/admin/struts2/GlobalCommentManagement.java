/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package org.apache.roller.ui.admin.struts2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.collections.ArrayStack;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.RollerException;
import org.apache.roller.business.RollerFactory;
import org.apache.roller.business.WeblogManager;
import org.apache.roller.pojos.CommentData;
import org.apache.roller.ui.core.util.struts2.KeyValueObject;
import org.apache.roller.util.cache.CacheManager;
import org.apache.roller.ui.core.util.struts2.UIAction;
import org.apache.roller.util.Utilities;


/**
 * Action for managing global set of comments.
 */
public class GlobalCommentManagement extends UIAction {
    
    private static Log log = LogFactory.getLog(GlobalCommentManagement.class);
    
    // bean for managing submitted data
    private GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
    
    // list of comments to display
    private List comments = Collections.EMPTY_LIST;
    
    // first comment in the list
    private CommentData firstComment = null;
    
    // last comment in the list
    private CommentData lastComment = null;
    
    // are there more results for the query?
    private boolean moreResults = false;
    
    // link to previous page of results
    private String prevLink = null;
    
    // linke to next page of results
    private String nextLink = null;
    
    // indicates number of comments that would be deleted by bulk removal
    // a non-zero value here indicates bulk removal is a valid option
    private int bulkDeleteCount = 0;
    
    
    public GlobalCommentManagement() {
        this.actionName = "globalCommentManagement";
        this.desiredMenu = "admin";
        this.pageTitle = "commentManagement.title";
    }
    
    
    // admin role required
    public String requiredUserRole() {
        return "admin";
    }
    
    // no weblog required
    public boolean isWeblogRequired() {
        return false;
    }
    
    
    public void loadComments() {
        
        try {
            WeblogManager wmgr = RollerFactory.getRoller().getWeblogManager();
            List comments = wmgr.getComments(
                    null,
                    null,
                    getBean().getSearchString(),
                    getBean().getStartDate(),
                    getBean().getEndDate(),
                    getBean().getStatus(),
                    true, // reverse  chrono order
                    getBean().getOffset(),
                    getBean().getCount() + 1);
            
            if(comments != null) {
                if(comments.size() > getBean().getCount()) {
                    comments.remove(comments.size()-1);
                    setMoreResults(true);
                }
                
                setComments(comments);
                setFirstComment((CommentData)comments.get(0));
                setLastComment((CommentData)comments.get(comments.size()-1));
                loadNextPrevLinks(isMoreResults());
            }
        } catch (RollerException ex) {
            log.error("Error looking up comments", ex);
            // TODO: i18n
            addError("Error looking up comments");
        }
    }

    
    // show comment management page
    public String execute() {
        
        // load list of comments from query
        loadComments();
        
        // load bean data using comments list
        getBean().loadCheckboxes(getComments());
        
        return LIST;
    }
    
    
    /**
     * Query for a specific subset of comments based on various criteria.
     */
    public String query() {
        
        // load list of comments from query
        loadComments();
        
        // load bean data using comments list
        getBean().loadCheckboxes(getComments());
        
        try {
            WeblogManager wmgr = RollerFactory.getRoller().getWeblogManager();
            List allMatchingComments = wmgr.getComments(
                    null,
                    null,
                    getBean().getSearchString(),
                    getBean().getStartDate(),
                    getBean().getEndDate(),
                    getBean().getStatus(),
                    true, // reverse  chrono order
                    0,
                    -1);
            
            if(allMatchingComments.size() > getBean().getCount()) {
                setBulkDeleteCount(allMatchingComments.size());
            }
            
        } catch (RollerException ex) {
            log.error("Error looking up comments", ex);
            // TODO: i18n
            addError("Error looking up comments");
        }
        
        return LIST;
    }
    
    
    /**
     * Bulk delete all comments matching query criteria.
     */
    public String delete() {
        
        try {
            WeblogManager wmgr = RollerFactory.getRoller().getWeblogManager();
            int deleted = wmgr.removeMatchingComments(
                    null,
                    null,
                    getBean().getSearchString(),
                    getBean().getStartDate(),
                    getBean().getEndDate(),
                    getBean().getStatus());
            
            // TODO: i18n
            addMessage("Successfully deleted "+deleted+" comments");
            
            // reset form and load fresh comments list
            setBean(new GlobalCommentManagementBean());
            
            return execute();
            
        } catch (RollerException ex) {
            log.error("Error doing bulk delete", ex);
            // TODO: i18n
            addError("Bulk delete failed due to unexpected error");
        }
        
        return LIST;
    }
    
    
    /**
     * Update a list of comments.
     */
    public String update() {
        
        try {
            WeblogManager wmgr = RollerFactory.getRoller().getWeblogManager();
            
            List flushList = new ArrayList();
            
            // delete all comments with delete box checked
            List<String> deletes = Arrays.asList(getBean().getDeleteComments());
            if(deletes != null && deletes.size() > 0) {
                log.debug("Processing deletes - "+deletes.size());
                
                CommentData deleteComment = null;
                for(String deleteId : deletes) {
                    deleteComment = wmgr.getComment(deleteId);
                    wmgr.removeComment(deleteComment);
                    flushList.add(deleteComment);
                }
            }
            
            // loop through IDs of all comments displayed on page
            List spamIds = Arrays.asList(getBean().getSpamComments());
            log.debug(spamIds.size()+" comments marked as spam");
            
            String[] ids = Utilities.stringToStringArray(getBean().getIds(),",");
            for (int i=0; i < ids.length; i++) {
                log.debug("processing id - "+ ids[i]);
                
                // if we already deleted it then skip forward
                if(deletes.contains(ids[i])) {
                    log.debug("Already deleted, skipping - "+ids[i]);
                    continue;
                }
                
                CommentData comment = wmgr.getComment(ids[i]);
                
                // mark/unmark spam
                if (spamIds.contains(ids[i]) && 
                        !CommentData.SPAM.equals(comment.getStatus())) {
                    log.debug("Marking as spam - "+comment.getId());
                    comment.setStatus(CommentData.SPAM);
                    wmgr.saveComment(comment);
                    
                    flushList.add(comment);
                } else if(CommentData.SPAM.equals(comment.getStatus())) {
                    log.debug("Marking as approved - "+comment.getId());
                    comment.setStatus(CommentData.APPROVED);
                    wmgr.saveComment(comment);
                    
                    flushList.add(comment);
                }
            }
            
            RollerFactory.getRoller().flush();
            
            // notify caches of changes
            for (Iterator comments=flushList.iterator(); comments.hasNext();) {
                CacheManager.invalidate((CommentData)comments.next());
            }
            
            addMessage("commentManagement.updateSuccess");
            
            // reset form and load fresh comments list
            setBean(new GlobalCommentManagementBean());
            
            return execute();
            
        } catch (Exception ex) {
            log.error("ERROR updating comments", ex);
            addError("commentManagement.updateError", ex.toString());
        }
        
        return LIST;
    }
    
    
    private void loadNextPrevLinks(boolean moreResults) {
        
    }
    
    
    public List getCommentStatusOptions() {
        
        List opts = new ArrayList();
        
        opts.add(new KeyValueObject("ALL", getText("commentManagement.all")));
        opts.add(new KeyValueObject("ONLY_PENDING", getText("commentManagement.onlyPending")));
        opts.add(new KeyValueObject("ONLY_APPROVED", getText("commentManagement.onlyApproved")));
        opts.add(new KeyValueObject("ONLY_DISAPPROVED", getText("commentManagement.onlyDisapproved")));
        
        return opts;
    }
    
    public List getSpamStatusOptions() {
        
        List opts = new ArrayList();
        
        opts.add(new KeyValueObject("ALL", getText("commentManagement.all")));
        opts.add(new KeyValueObject("NO_SPAM", getText("commentManagement.noSpam")));
        opts.add(new KeyValueObject("ONLY_SPAM", getText("commentManagement.onlySpam")));
        
        return opts;
    }
    
    
    public GlobalCommentManagementBean getBean() {
        return bean;
    }

    public void setBean(GlobalCommentManagementBean bean) {
        this.bean = bean;
    }

    public List getComments() {
        return comments;
    }

    public void setComments(List comments) {
        this.comments = comments;
    }

    public int getBulkDeleteCount() {
        return bulkDeleteCount;
    }

    public void setBulkDeleteCount(int bulkDeleteCount) {
        this.bulkDeleteCount = bulkDeleteCount;
    }

    public CommentData getFirstComment() {
        return firstComment;
    }

    public void setFirstComment(CommentData firstComment) {
        this.firstComment = firstComment;
    }

    public CommentData getLastComment() {
        return lastComment;
    }

    public void setLastComment(CommentData lastComment) {
        this.lastComment = lastComment;
    }

    public String getPrevLink() {
        return prevLink;
    }

    public void setPrevLink(String prevLink) {
        this.prevLink = prevLink;
    }

    public String getNextLink() {
        return nextLink;
    }

    public void setNextLink(String nextLink) {
        this.nextLink = nextLink;
    }

    public boolean isMoreResults() {
        return moreResults;
    }

    public void setMoreResults(boolean moreResults) {
        this.moreResults = moreResults;
    }
    
}