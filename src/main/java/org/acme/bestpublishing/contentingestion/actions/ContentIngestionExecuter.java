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
package org.acme.bestpublishing.contentingestion.actions;

import org.acme.bestpublishing.actions.AbstractIngestionExecuter;
import org.acme.bestpublishing.model.BestPubContentModel;
import org.alfresco.service.cmr.repository.NodeRef;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jmx.export.annotation.ManagedResource;

import java.io.File;
import java.io.IOException;

/**
 * The Content Ingestion component is called from the scheduled job.
 * The work that should be done by the content ingestion
 * is delegated to the Content Ingestion Service component.
 * Example Log:
 *  Checking for Content ZIPs...
 *  File path to check = [/Users/martin/Documents/content]
 *  Found [1] content files
 *  Processing zip file [9780486282146.zip]
 *  Found new ISBN 9780486282146 that has not been published before, uploading to /Data Dictionary/BestPub/Incoming/Content
 *  Creating ISBN folder for 9780486282146
 *  Processed [1] content ZIP files
 *
 * @author martin.bergljung@marversolutions.org
 * @version 1.0
 */
@ManagedResource(
        objectName = "org.acme:application=BestPublishing,type=Ingestion,name=ContentIngestion",
        description = "BestPub Content Ingestion scanning for Book Content ZIPs")
public class ContentIngestionExecuter extends AbstractIngestionExecuter {
    private static final Logger LOG = LoggerFactory.getLogger(ContentIngestionExecuter.class);

    /**
     * Executer implementation just calls super class
     */
    public void execute() {
       super.execute("Content");
    }

    @Override
    public Logger getLog() {
        return LOG;
    }

    /**
     * Process one ZIP file and upload its content to Alfresco
     *
     * @param zipFile              the ZIP file that should be processed and uploaded
     * @param extractedISBN the ISBN number that was extracted from ZIP file name
     * @param alfrescoUploadFolderNodeRef the target folder for new ISBN content
     * @return true if processed file ok, false if there was an error
     */
    @Override
    public boolean processZipFile(File zipFile, String extractedISBN, NodeRef alfrescoUploadFolderNodeRef) {
        getLog().debug("Processing content zip file [{}]", zipFile.getName());

        // Check if ISBN already exists under /Company Home/Data Dictionary/BestPub/Incoming/Content
        NodeRef targetAlfrescoFolderNodeRef = null;
        NodeRef isbnFolderNodeRef = alfrescoRepoUtilsService.getChildByName(alfrescoUploadFolderNodeRef, extractedISBN);
        if (isbnFolderNodeRef == null) {
            // We got a new ISBN that has not been published before
            // And this means uploading to /Data Dictionary/BestPub/Incoming/Content
            targetAlfrescoFolderNodeRef = alfrescoUploadFolderNodeRef;

            getLog().debug("Found new content ISBN {} that has not been published before, " +
                    "uploading to /Data Dictionary/BestPub/Incoming/Content", extractedISBN);
        } else {
            // We got an ISBN that has already been published, so we need to republish it...

            // However, first verify that content has been previously completely ingested into the
            // /Data Dictionary/BestPub/Incoming/Content/{ISBN} folder and has
            // property bestpub:ingestionStatus set to Complete.
            if (serviceRegistry.getNodeService().getProperty(isbnFolderNodeRef,
                    BestPubContentModel.BookFolderType.Prop.INGESTION_STATUS).
                    equals(BestPubContentModel.IngestionStatus.COMPLETE.toString())) {
                getLog().debug("Found updated ISBN {} that has been published before...", extractedISBN);

                // Re-publish content, TODO
            } else {
                getLog().debug("Found new ISBN {} that has had interrupted ingestion...", extractedISBN);

                // We got a new ISBN that has had interrupted ingestion, TODO
            }

            return false;
        }

        try {
            ingestionService.importZipFileContent(zipFile, targetAlfrescoFolderNodeRef, extractedISBN);
            return true;
        } catch (Exception e) {
            getLog().error("Error processing content zip file " + zipFile.getName(), e);
        }

        return false;
    }

}
