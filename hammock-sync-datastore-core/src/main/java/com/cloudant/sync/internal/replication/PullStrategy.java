/*
 * Copyright © 2017 IBM Corp. All rights reserved.
 *
 * Copyright © 2013 Cloudant, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.cloudant.sync.internal.replication;

import com.cloudant.http.HttpConnectionRequestInterceptor;
import com.cloudant.http.HttpConnectionResponseInterceptor;
import com.cloudant.sync.documentstore.Attachment;
import com.cloudant.sync.documentstore.Database;
import com.cloudant.sync.documentstore.DocumentException;
import com.cloudant.sync.documentstore.DocumentStoreException;
import com.cloudant.sync.event.EventBus;
import com.cloudant.sync.internal.documentstore.DatabaseImpl;
import com.cloudant.sync.internal.documentstore.DocumentRevsList;
import com.cloudant.sync.internal.documentstore.PreparedAttachment;
import com.cloudant.sync.internal.mazha.ChangesResult;
import com.cloudant.sync.internal.mazha.CouchClient;
import com.cloudant.sync.internal.mazha.DocumentRevs;
import com.cloudant.sync.internal.util.CollectionUtils;
import com.cloudant.sync.internal.util.JSONUtils;
import com.cloudant.sync.internal.util.Misc;
import com.cloudant.sync.replication.DatabaseNotFoundException;
import com.cloudant.sync.replication.PullFilter;

import org.apache.commons.codec.binary.Hex;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PullStrategy implements ReplicationStrategy {

    // internal state which gets reset each time run() is called
    private static class State {
        // Flag to stop the replication thread.
        // Volatile as might be set from another thread.
        private volatile boolean cancel = false;

        /**
         * Flag is set when the replication process is complete. The thread
         * may live on because the listener's callback is executed on the thread.
         */
        private volatile boolean replicationTerminated = false;

        int documentCounter = 0;

        int batchCounter = 0;
    }

    private State state;

    private static final Logger logger = Logger.getLogger(PullStrategy.class
            .getCanonicalName());

    private static final String LOG_TAG = "PullStrategy";

    CouchDB sourceDb;

    PullFilter filter;
    String selector;
    List<String> docIds;

    DatastoreWrapper targetDb;

    private final String name;

    private final EventBus eventBus = new EventBus();

    // Is _bulk_get endpoint supported?
    private boolean useBulkGet = false;

    public int changeLimitPerBatch = 1000;

    public int insertBatchSize = 100;

    public boolean pullAttachmentsInline = false;

    public PullStrategy(URI source,
                        Database target,
                        PullFilter filter,
                        String selector,
                        List<String> docIds,
                        List<HttpConnectionRequestInterceptor> requestInterceptors,
                        List<HttpConnectionResponseInterceptor> responseInterceptors) {
        this.filter = filter;
        this.selector = selector;
        this.docIds = docIds;
        if (docIds != null && !docIds.isEmpty()) {
            Collections.sort(docIds);
        }
        this.sourceDb = new CouchClientWrapper(new CouchClient(source, requestInterceptors,
                responseInterceptors));
        this.targetDb = new DatastoreWrapper((DatabaseImpl) target);
        String replicatorName;
        if (filter != null) {
            replicatorName = String.format("%s <-- %s (%s)", target.getPath(), source,
                    filter.getName());
        } else if (selector != null) {
            replicatorName = String.format("%s <-- %s (%s)", target.getPath(), source, selector);
        } else if (docIds != null && !docIds.isEmpty()) {
            String concatenatedIds = Misc.join(",",docIds);
            replicatorName = String.format("%s <-- %s (%s)", target.getPath(), source,
                    concatenatedIds);
        } else {
            replicatorName = String.format("%s <-- %s ", target.getPath(), source);
        }
        this.name = String.format("%s [%s]", LOG_TAG, replicatorName);
    }

    @Override
    public boolean isReplicationTerminated() {
        if (this.state != null) {
            return state.replicationTerminated;
        } else {
            return false;
        }
    }

    @Override
    public void setCancel() {
        // if we have been cancelled before run(), we have to create the internal state
        if (this.state == null) {
            this.state = new State();
        }
        this.state.cancel = true;
    }

    @Override
    public int getDocumentCounter() {
        if (this.state != null) {
            return this.state.documentCounter;
        } else {
            return 0;
        }
    }

    @Override
    public int getBatchCounter() {
        if (this.state != null) {
            return this.state.batchCounter;
        } else {
            return 0;
        }
    }

    /**
     * Handle exceptions in separate run() method to allow replicate() to
     * just return when cancel is set to true rather than having to keep
     * track of whether we terminated via an error in replicate().
     */
    @Override
    public void run() {

        if (this.state != null && this.state.cancel) {
            // we were already cancelled, don't run, but still post completion
            this.state.documentCounter = 0;
            this.state.batchCounter = 0;
            runComplete(null);
            return;
        }
        // reset internal state
        this.state = new State();

        Throwable errorInfo = null;

        try {
            this.useBulkGet = sourceDb.isBulkSupported();
            replicate();

        } catch (ExecutionException ex) {
            logger.log(Level.SEVERE, String.format("Batch %s ended with error:", this.state
                    .batchCounter), ex);
            errorInfo = ex.getCause();
        } catch (Throwable e) {
            logger.log(Level.SEVERE, String.format("Batch %s ended with error:", this.state
                    .batchCounter), e);
            errorInfo = e;
        }

        runComplete(errorInfo);
    }

    private void runComplete(Throwable errorInfo) {
        state.replicationTerminated = true;

        String msg = "Pull replication terminated via ";
        msg += this.state.cancel ? "cancel." : "completion.";

        // notify complete/errored on eventbus
        logger.info(msg + " Posting on EventBus.");
        if (errorInfo == null) {  // successful replication
            eventBus.post(new ReplicationStrategyCompleted(this));
        } else {
            eventBus.post(new ReplicationStrategyErrored(this, errorInfo));
        }
    }

    private void replicate()
            throws DatabaseNotFoundException, ExecutionException, InterruptedException,
            DocumentException, DocumentStoreException {
        logger.info("Pull replication started");
        long startTime = System.currentTimeMillis();

        // We were cancelled before we started
        if (this.state.cancel) {
            return;
        }

        if (!this.sourceDb.exists()) {
            throw new DatabaseNotFoundException(
                    "Database not found " + this.sourceDb.getIdentifier());
        }

        this.state.documentCounter = 0;

        while (!this.state.cancel) {
            this.state.batchCounter++;
            final Object lastKnownCheckpoint = this.targetDb.getCheckpoint(this.getReplicationId());
            String msg = String.format(
                    "Batch %s started (completed %s changes so far)",
                    this.state.batchCounter,
                    this.state.documentCounter
            );
            logger.info(msg);
            long batchStartTime = System.currentTimeMillis();

            ChangesResultWrapper changeFeeds = this.nextBatch(lastKnownCheckpoint);
            int batchChangesProcessed = 0;

            // So we can check whether all changes were processed during
            // a log analysis.
            msg = String.format(
                    "Batch %s contains %s changes",
                    this.state.batchCounter,
                    changeFeeds.size()
            );
            logger.info(msg);

            if (changeFeeds.size() > 0) {
                batchChangesProcessed = processOneChangesBatch(changeFeeds);
                state.documentCounter += batchChangesProcessed;
            }

            if (!this.state.cancel && (lastKnownCheckpoint == null || !lastKnownCheckpoint.equals(changeFeeds.getLastSeq()))) {
                try {
                    this.targetDb.putCheckpoint(this.getReplicationId(), changeFeeds.getLastSeq());
                } catch (DocumentStoreException e) {
                    logger.log(Level.WARNING, "Failed to put checkpoint doc, next replication " +
                            "will " +
                            "start from previous checkpoint", e);
                }
            }

            long batchEndTime = System.currentTimeMillis();
            msg = String.format(
                    "Batch %s completed in %sms (batch was %s changes)",
                    this.state.batchCounter,
                    batchEndTime - batchStartTime,
                    batchChangesProcessed
            );
            logger.info(msg);

            // This logic depends on the changes in the feed rather than the
            // changes we actually processed.
            if (changeFeeds.size() < this.changeLimitPerBatch) {
                break;
            }
        }

        long endTime = System.currentTimeMillis();
        long deltaTime = endTime - startTime;
        String msg;
        if (this.state.cancel) {
            msg = String.format(Locale.ENGLISH,
                    "Pull canceled after %sms (%s changes processed)",
                    deltaTime,
                    this.state.documentCounter);
        } else {
            msg = String.format(Locale.ENGLISH,
                    "Pull completed in %sms (%s total changes processed)",
                    deltaTime,
                    this.state.documentCounter
            );
        }
        logger.info(msg);

    }

    public static class BatchItem {

        public BatchItem(DocumentRevsList revsList,
                         HashMap<String[], Map<String, PreparedAttachment>> attachments) {
            this.revsList = revsList;
            this.attachments = attachments;
        }

        public HashMap<String[], Map<String, PreparedAttachment>> attachments;
        public DocumentRevsList revsList;
    }

    private int processOneChangesBatch(ChangesResultWrapper changeFeeds)
            throws ExecutionException, InterruptedException, DocumentException,
            DocumentStoreException {
        String feed = String.format(
                "Change feed: { last_seq: %s, change size: %s}",
                changeFeeds.getLastSeq(),
                changeFeeds.getResults().size()
        );
        logger.info(feed);

        Map<String, List<String>> openRevs = changeFeeds.openRevisions(0, changeFeeds.size());
        Map<String, List<String>> missingRevisions = this.targetDb.getDbCore().revsDiff
                (openRevs);

        int changesProcessed = 0;

        // Process the changes in batches
        List<String> ids = new ArrayList<String>(missingRevisions.keySet());
        List<List<String>> batches = CollectionUtils.partition(ids, this.insertBatchSize);


        for (List<String> batch : batches) {

            List<BatchItem> batchesToInsert = new ArrayList<BatchItem>();

            if (this.state.cancel) {
                break;
            }

            try {
                Iterable<DocumentRevsList> result = createTask(batch, missingRevisions);

                for (DocumentRevsList revsList : result) {
                    // We promise not to insert documents after cancel is set
                    if (this.state.cancel) {
                        break;
                    }

                    // attachments, keyed by docId and revId, so that
                    // we can add the attachments to the correct leaf
                    // nodes
                    HashMap<String[], Map<String, PreparedAttachment>> atts = new HashMap<String[],
                            Map<String, PreparedAttachment>>();

                    // now put together a list of attachments we need to download
                    if (!this.pullAttachmentsInline) {
                        try {
                            for (DocumentRevs documentRevs : revsList) {
                                Map<String, Object> attachments = documentRevs.getAttachments();
                                // keep track of attachments we are going to prepare
                                Map<String, PreparedAttachment> preparedAtts = new
                                        HashMap<String, PreparedAttachment>();
                                atts.put(new String[]{documentRevs.getId(), documentRevs.getRev()
                                }, preparedAtts);

                                for (Map.Entry<String, Object> entry : attachments.entrySet()) {
                                    String attachmentName = entry.getKey();
                                    Map attachmentMetadata = (Map) entry.getValue();
                                    int revpos = (Integer) attachmentMetadata.get("revpos");
                                    String contentType = (String) attachmentMetadata.get
                                            ("content_type");
                                    String encoding = (String) attachmentMetadata.get("encoding");
                                    long length = (Integer) attachmentMetadata.get("length");
                                    long encodedLength = 0; // encodedLength can default to 0 if
                                    // it's not encoded
                                    if (Attachment.getEncodingFromString(encoding) != Attachment
                                            .Encoding.Plain) {
                                        encodedLength = (Integer) attachmentMetadata.get
                                                ("encoded_length");
                                    }

                                    // do we already have the attachment @ this revpos?
                                    // look back up the tree for this document and see:
                                    // if we already have it, then we don't need to fetch it
                                    DocumentRevs.Revisions revs = documentRevs.getRevisions();
                                    int offset = revs.getStart() - revpos;
                                    if (offset >= 0 && offset < revs.getIds().size()) {
                                        String revId = String.valueOf(revpos) + "-" + revs.getIds
                                                ().get(offset);

                                        Attachment a = this.targetDb.getDbCore()
                                                .getAttachment(documentRevs.getId(), revId,
                                                        attachmentName);
                                        if (a != null) {
                                            // skip attachment, already got it
                                            continue;
                                        }

                                    }

                                    // by preparing the attachment here, it is downloaded outside
                                    // of the database transaction
                                    preparedAtts.put(attachmentName, this.sourceDb
                                            .pullAttachmentWithRetry
                                                    (documentRevs.getId(), documentRevs.getRev(),
                                                            entry
                                                            .getKey(), new
                                                                    AttachmentPullProcessor(this
                                                            .targetDb, entry.getKey(), contentType,
                                                            encoding, length, encodedLength)));
                                }
                            }
                        } catch (Exception e) {
                            logger.log(Level.SEVERE,
                                    "There was a problem downloading an attachment to the" +
                                            " datastore, terminating replication",
                                    e);
                            this.state.cancel = true;
                        }
                    }

                    if (this.state.cancel) {
                        break;
                    }

                    batchesToInsert.add(new BatchItem(revsList, atts));
                    changesProcessed++;
                }
                this.targetDb.bulkInsert(batchesToInsert, this.pullAttachmentsInline);
            } catch (Exception e) {
                throw new ExecutionException(e);
            }
        }

        return changesProcessed;
    }

    public String getReplicationId() throws DocumentStoreException {
        HashMap<String, String> dict = new HashMap<String, String>();
        dict.put("source", this.sourceDb.getIdentifier());
        dict.put("target", this.targetDb.getIdentifier());
        if (filter != null) {
            dict.put("filter", this.filter.toQueryString());
        } else if (selector != null) {
            dict.put("selector", this.selector);
        } else if (docIds != null && !docIds.isEmpty()) {
            dict.put("docIds", Misc.join(",",docIds));
        }
        // get raw SHA-1 of dictionary
        try {
            byte[] sha1Bytes = Misc.getSha1(new ByteArrayInputStream(JSONUtils.serializeAsBytes
                    (dict)));
            // return SHA-1 as a hex string
            byte[] sha1Hex = new Hex().encode(sha1Bytes);
            return new String(sha1Hex, Charset.forName("UTF-8"));
        } catch (IOException ioe) {
            throw new DocumentStoreException(ioe);
        }
    }

    private ChangesResultWrapper nextBatch(final Object lastCheckpoint) {
        logger.fine("last checkpoint " + lastCheckpoint);

        ChangesResult changeFeeds = null;
        if (this.selector != null) {
            changeFeeds = this.sourceDb.changes(
                    this.selector,
                    lastCheckpoint,
                    this.changeLimitPerBatch);
        } else if (this.docIds != null && !this.docIds.isEmpty()) {
            changeFeeds = this.sourceDb.changes(
                    this.docIds,
                    lastCheckpoint,
                    this.changeLimitPerBatch);
        } else {
            changeFeeds = this.sourceDb.changes(
                    this.filter,
                    lastCheckpoint,
                    this.changeLimitPerBatch);
        }
        logger.finer("changes feed: " + JSONUtils.toPrettyJson(changeFeeds));
        return new ChangesResultWrapper(changeFeeds);
    }

    public Iterable<DocumentRevsList> createTask(List<String> ids,
                                                 Map<String, List<String>> revisions) throws
            DocumentStoreException {

        List<BulkGetRequest> requests = new ArrayList<BulkGetRequest>();

        for (String id : ids) {
            //skip any document with an empty ID
            if (id.isEmpty()) {
                logger.info("Found document with empty ID in change feed, skipping");
                continue;
            }
            // get list for atts_since (these are possible ancestors we have, it's ok to be eager

            // and get all revision IDs higher up in the tree even if they're not our
            // ancestors and
            // belong to a different subtree)
            HashSet<String> possibleAncestors = new HashSet<String>();
            for (String revId : revisions.get(id)) {
                List<String> thesePossibleAncestors = targetDb.getDbCore()
                        .getPossibleAncestorRevisionIDs(id, revId, 50);
                if (thesePossibleAncestors != null) {
                    possibleAncestors.addAll(thesePossibleAncestors);
                }
            }
            requests.add(new BulkGetRequest(
                    id,
                    new ArrayList<String>(revisions.get(id)),
                    new ArrayList<String>(possibleAncestors)));
        }

        if (useBulkGet) {
            return new GetRevisionTaskBulk(this.sourceDb, requests, this.pullAttachmentsInline);
        } else {
            return new GetRevisionTaskThreaded(this.sourceDb, requests, this.pullAttachmentsInline);
        }
    }

    @Override
    public EventBus getEventBus() {
        return eventBus;
    }

    @Override
    public String getRemote() {
        return this.sourceDb.getIdentifier();
    }
}
