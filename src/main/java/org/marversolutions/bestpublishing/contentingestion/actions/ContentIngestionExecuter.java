/*
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package org.marversolutions.bestpublishing.contentingestion.actions;

import org.alfresco.service.cmr.repository.NodeRef;
import org.apache.commons.io.FilenameUtils;
import org.marversolutions.bestpublishing.contentingestion.exceptions.ContentIngestionException;
import org.marversolutions.bestpublishing.contentingestion.services.ContentIngestionService;
import org.marversolutions.bestpublishing.services.AlfrescoRepoUtilsService;
import org.marversolutions.bestpublishing.services.BestPubUtilsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jmx.export.annotation.ManagedAttribute;
//import org.springframework.jmx.export.annotation.ManagedMetric;
import org.springframework.jmx.export.annotation.ManagedResource;
//import org.springframework.jmx.support.MetricType;

import java.io.File;
import java.io.IOException;
import java.util.Date;

/**
 /*
 * Checks for zip files in a specified folder, extracts them and then ingests the contents
 * of the zip file into Alfresco as book content, such as chapters.
 *
 * @author martin.bergljung@marversolutions.org
 * @version 1.0
 */
@ManagedResource(
        objectName = "org.marversolutions:application=BestPublishing,type=Ingestion,name=ContentIngestion",
        description = "BestPub Content Ingestion scanning for Book Content ZIPs")
public class ContentIngestionExecuter {
    private static final Logger LOG = LoggerFactory.getLogger(ContentIngestionExecuter.class);

    /**
     * Best Pub Specific services
     */
    private BestPubUtilsService bestPubUtilsService;
    private AlfrescoRepoUtilsService repoUtils;
    private ContentIngestionService contentIngestionService;

    /**
     * Content Ingestion config
     */
    private String filePathToCheck;
    private String contentFolderPath;
    private String cronExpression;
    private int cronStartDelay;

    /**
     * Content Ingestion stats
     */
    private Date lastRunTime;
    private long numberOfRuns;
    private int zipQueueSize;

    /**
     * Spring Dependency Injection
     */
    public void setFilePathToCheck(String filePathToCheck) { this.filePathToCheck = filePathToCheck; }
    public void setContentFolderPath(String contentFolderPath) { this.contentFolderPath = contentFolderPath; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public void setCronStartDelay(int cronStartDelay) { this.cronStartDelay = cronStartDelay; }
    public void setRepoUtils(AlfrescoRepoUtilsService repoUtils) {
        this.repoUtils = repoUtils;
    }
    public void setContentIngestionService(ContentIngestionService contentIngestionService) {
        this.contentIngestionService = contentIngestionService;}
    public void setBestPubUtilsService(BestPubUtilsService bestPubUtilsService) {
        this.bestPubUtilsService = bestPubUtilsService;
    }

    /**
     * Managed Properties (JMX)
     */
    @ManagedAttribute(description = "Path to content ZIP files" )
    public String getFilePathToCheck() { return this.filePathToCheck;}
    @ManagedAttribute(description = "Cron expression controlling execution" )
    public String getCronExpression() { return this.cronExpression;}
    @ManagedAttribute(description = "Ingestion start delay after bootstrap (ms)" )
    public int getCronStartDelay() { return this.cronStartDelay;}
    @ManagedAttribute(description = "Last time it was called" )
    public Date getLastRunTime() { return this.lastRunTime; }
    @ManagedAttribute(description = "Number of times it has run" )
    public long getNumberOfRuns() { return this.numberOfRuns; }
//    @ManagedMetric(category="utilization", displayName="ZIP Queue Size",
  //          description="The size of the ZIP File Queue",
    //        metricType = MetricType.COUNTER, unit="zips")
    public long getZipQueueSize() { return this.zipQueueSize; }

    /**
     * Executer implementation
     */
    public void execute() {
        LOG.debug("Running the content ingestion");

        // Running stats
        lastRunTime = new Date();
        numberOfRuns++;

        // Get the node references for the /Company Home/Data Dictionary/BestPub/Incoming/Content
        // folder where this content ingestion action will upload the content
        NodeRef contentFolderNodeRef = repoUtils.getNodeByXPath(contentFolderPath);

        try {
            LOG.debug("File path to check = [{}]", filePathToCheck);

            File folder = new File(filePathToCheck);
            if (!folder.exists()) {
                throw new ContentIngestionException("Folder to check does not exist.");
            }
            if (!folder.isDirectory()) {
                throw new ContentIngestionException("The file path must be to a directory.");
            }

            File[] zipFiles = bestPubUtilsService.findFilesUsingExtension(folder, "zip");
            zipQueueSize = zipFiles.length;
            LOG.debug("Found [{}] content files", zipFiles.length);

            for (File zipFile : zipFiles) {
                if (processZipFile(zipFile, contentFolderNodeRef)) {
                    // All done, delete the ZIP
                    zipFile.delete();

                    //checkerLog.addEvent(new LogEventProcessedSuccessfully(zipFile.getName()), new Date());
                } else {
                    // Something went wrong when processing the zip file,
                    // move it to a directory for ZIPs that failed processing
                    bestPubUtilsService.moveZipToDirForFailedProcessing(zipFile, filePathToCheck);
                }

                zipQueueSize--;
            }

            LOG.debug("Processed [{}] content ZIP files", zipFiles.length);
        } catch (Exception e) {
            LOG.error("Encountered an error when ingesting content - exiting", e);
        }
    }

    /**
     * Process one Content ZIP file and upload its content to Alfresco
     *
     * @param zipFile                       the ZIP file that should be processed and uploaded
     * @param contentFolderNodeRef          the target folder for new ISBN content packages
     * @return true if processed file ok, false if there was an error
     */
    private boolean processZipFile(File zipFile, NodeRef contentFolderNodeRef)
            throws IOException {
        LOG.debug("Processing zip file [{}]", zipFile.getName());

        String isbn = FilenameUtils.removeExtension(zipFile.getName());
        if (!bestPubUtilsService.isISBN(isbn)) {
            LOG.error("Error processing zip file [{}], filename is not an ISBN number", zipFile.getName());

            return false;
        }

        // Check if ISBN already exists under /Company Home/Data Dictionary/BestPub/Incoming/Content
        NodeRef targetContentFolderNodeRef = null;
        NodeRef isbnFolderNodeRef = repoUtils.getChildByName(contentFolderNodeRef, isbn);
        if (isbnFolderNodeRef == null) {
            // We got a new ISBN that has not been published before
            // And this means uploading the content package to /Data Dictionary/BestPub/Incoming/Content
            targetContentFolderNodeRef = contentFolderNodeRef;

            LOG.debug("Found new ISBN {} that has not been published before, " +
                    "uploading to /Data Dictionary/BestPub/Incoming/Content", isbn);
        } else {
            // We got an ISBN that has already been published, so we need to republish it
            // And this means uploading the content package to /Data Dictionary/BESTPUB/Incoming/Content/Republish

            // However, first verify that content has been previously completely ingested into the
            // /Data Dictionary/BOPP/Incoming/Content/{ISBN} folder and has
            // property boppc:ingestionStatus set to Complete.
            /*
            if (nodeService.getProperty(isbnFolderNodeRef, BoppContentModel.ISBNFolderType.Prop.INGESTION_STATUS).
                    equals(BoppContentModel.IngestionStatus.COMPLETE.toString())) {
                targetContentFolderNodeRef = republishContentFolderNodeRef;

                LOG.debug("Found updated ISBN {} that has been published before, " +
                        "uploading to /Data Dictionary/BOPP/Incoming/Content/Republish", isbn);

                // Delete old republished one if it exists (Content can be re-published multiple times)
                isbnFolderNodeRef = repoUtils.getChildByName(republishContentFolderNodeRef, isbn);
                if (isbnFolderNodeRef != null && nodeService.exists(isbnFolderNodeRef)) {
                    nodeService.deleteNode(isbnFolderNodeRef);
                }
            } else {
                // We got a new ISBN that has had interrupted ingestion, upload again to
                // /Data Dictionary/BOPP/Incoming/Content
                targetContentFolderNodeRef = contentFolderNodeRef;

                // Delete the interrupted ingestion folder
                nodeService.deleteNode(isbnFolderNodeRef);

                LOG.debug("Found new ISBN {} that has had interrupted ingestion, " +
                        "only ISBN folder exist with Ingestion Status = In Progress, " +
                        "uploading again to /Data Dictionary/BOPP/Incoming/Content", isbn);
            }*/
        }

        try {
            contentIngestionService.importZipFileContent(zipFile, targetContentFolderNodeRef, isbn);
            return true;
        } catch (Exception e) {
            LOG.error("Error processing zip file " +  zipFile.getName(), e);
        }

        return false;
    }
}
