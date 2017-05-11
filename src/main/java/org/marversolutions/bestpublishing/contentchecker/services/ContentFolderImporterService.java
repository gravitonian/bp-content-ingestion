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
package org.marversolutions.bestpublishing.contentchecker.services;

import org.alfresco.service.cmr.repository.NodeRef;

import java.io.File;

/*
 * Content Helper Service to extract all the different files from a content package (ZIP) from production, such
 * as individual chapter PDFs and images/pictures for the different chapters, and then store them in Alfresco.
 *
 * @author martin.bergljung@marversolutions.org
 * @version 1.0
 */
public interface ContentFolderImporterService {
    /**
     * Extracts and imports all the content in the ZIP such as chapter PDFs, artwork, book PDF etc
     *
     * @param zipFile the content ZIP file to be imported
     * @param parentContentFolderNodeRef the node ref for /Company Home/Data Dictionary/BESTPUB/Incoming/Content
     * @param isbn the book ISBN 13 number
     */
    public void importZipFileContent(final File zipFile, final NodeRef parentContentFolderNodeRef, final String isbn);
}