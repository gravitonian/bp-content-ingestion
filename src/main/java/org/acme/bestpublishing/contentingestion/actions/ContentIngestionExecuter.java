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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jmx.export.annotation.ManagedResource;

/**
 * Checks for zip files in a specified folder, extracts them and then ingests the contents
 * of the zip file into Alfresco as book content, such as chapters.
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
}
