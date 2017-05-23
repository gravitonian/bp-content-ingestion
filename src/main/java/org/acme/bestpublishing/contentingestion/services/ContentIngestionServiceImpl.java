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
package org.acme.bestpublishing.contentingestion.services;

import org.alfresco.model.ContentModel;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.acme.bestpublishing.contentingestion.exceptions.ContentIngestionException;
import org.acme.bestpublishing.error.ProcessingError;
import org.acme.bestpublishing.error.ProcessingErrorCode;
import org.acme.bestpublishing.model.BestPubContentModel;
import org.acme.bestpublishing.services.AlfrescoRepoUtilsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.acme.bestpublishing.constants.BestPubConstants.*;

/*
 * Implementation of the Content Ingestion Service, extracts ZIP to temporary location in local filesystem
 * and imports into Alfresco Repository from there.
 *
 * @author martin.bergljung@marversolutions.org
 * @version 1.0
 */
@Transactional(readOnly = true)
public class ContentIngestionServiceImpl implements ContentIngestionService {
    private static final Logger LOG = LoggerFactory.getLogger(ContentIngestionServiceImpl.class);

    /**
     * Part of filename for book content that distinguishes the file as a chapter content XHTML file
     */
    public final static String ZIP_CHAPTER_FILENAME_PART = "chapter";

    /**
     * The ZIP directory that contains chapter content and supplementary content
     */
    public final static String ZIP_CONTENT_DIR_NAME = "content";

    /**
     * The ZIP directory that contains artwork files
     */
    public final static String ZIP_ARTWORK_DIR_NAME = "images";

    /**
     * The ZIP directory that contains style files (css)
     */
    public final static String ZIP_STYLES_DIR_NAME = "styles";

    /**
     * Alfresco Services
     */
    private ServiceRegistry serviceRegistry;

    /**
     * Best Publishing Services
     */
    private AlfrescoRepoUtilsService alfrescoRepoUtilsService;

    /**
     * Spring DI
     */

    public void setServiceRegistry(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }
    public void setAlfrescoRepoUtilsService(AlfrescoRepoUtilsService alfrescoRepoUtilsService) {
        this.alfrescoRepoUtilsService = alfrescoRepoUtilsService;
    }

    /**
     * Interface Implementation
     */

    @Override
    @Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW)
    public void importZipFileContent(final File file, final NodeRef parentContentFolderNodeRef, final String isbn) {
        // Create the main ISBN folder where all the content should be ingested
        NodeRef isbnFolderNodeRef = createIsbnFolder(parentContentFolderNodeRef, isbn);
        if (isbnFolderNodeRef == null) {
            String extraDetails = "Could not create new ISBN folder for " + isbn + " under " +
                    serviceRegistry.getNodeService().getPath(parentContentFolderNodeRef).toString();
            throw new ContentIngestionException(extraDetails);
        }

        // Process and ingest all content in the Content ZIP
        processZipFile(isbnFolderNodeRef, isbn, file);

        // Everything went OK, setup ISBN as ready to be fetched by workflow, if it has been started
        serviceRegistry.getNodeService().setProperty(
                isbnFolderNodeRef, BestPubContentModel.BookFolderType.Prop.INGESTION_STATUS,
                BestPubContentModel.IngestionStatus.COMPLETE.toString());
    }

    /**
     * Create the ISBN folder in the /Company Home/Data Dictionary/BestPub/Incoming/Content folder.
     *
     * @param parentContentFolderNodeRef the node ref for /Company Home/Data Dictionary/BestPub/Incoming/Content
     * @param isbn the book ISBN 13 number
     * @return the folder node reference for this new ISBN folder, pointing to
     * /Company Home/Data Dictionary/BestPub/Incoming/Content/{ISBN}
     */
    private NodeRef createIsbnFolder(final NodeRef parentContentFolderNodeRef, final String isbn) {
        LOG.debug("Creating ISBN folder for {}", isbn);

        Map<QName, Serializable> properties = new HashMap<QName, Serializable>();
        properties.put(ContentModel.PROP_NAME, isbn);
        properties.put(BestPubContentModel.BookInfoAspect.Prop.ISBN, isbn);
        properties.put(BestPubContentModel.BookFolderType.Prop.INGESTION_STATUS,
                BestPubContentModel.IngestionStatus.IN_PROGRESS.toString());

        return serviceRegistry.getNodeService().createNode(parentContentFolderNodeRef,
                ContentModel.ASSOC_CONTAINS, QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, isbn),
                BestPubContentModel.BookFolderType.QNAME, properties).getChildRef();
    }

    /**
     * Unzip passed in file to a temporary location in the local filesystem.
     *
     * @param isbnFolderNodeRef ISBN folder node reference where all content are stored
     * @param isbn              the related ISBN number
     * @param file              the file to unzip
     * @return the new folder with unzipped content
     */
    private void processZipFile(final NodeRef isbnFolderNodeRef, final String isbn, final File file) {
        String currentZipDirectory = null;
        ZipFile zipFile = null;

        try {
            zipFile = new ZipFile(file);
            Enumeration enumeration = zipFile.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry zipEntry = (ZipEntry) enumeration.nextElement();
                if (zipEntry.isDirectory()) {
                    // If the entry is a directory, save it as new current directory,
                    // which would be something like /Chapters, /artwork, etc
                    currentZipDirectory = zipEntry.getName();
                } else {
                    // If the entry is a file, ingest into Alfresco in current folder
                    // (current folder will be what matches current ZIP directory)
                    // Note. the input stream for the entry is closed by Alfresco ContentWriter,
                    // and also when you close ZipFile
                    BufferedInputStream bis = new BufferedInputStream(zipFile.getInputStream(zipEntry));
                    processZipFileEntry(zipEntry, bis, currentZipDirectory, isbn, isbnFolderNodeRef);
                }
            }

            zipFile.close();
        } catch (IOException ioe) {
            String msg = "Error extracting content ZIP " + zipFile.getName() + " [error=" + ioe.getMessage() + "]";
            throw new ContentIngestionException(
                    new ProcessingError(ProcessingErrorCode.CONTENT_INGESTION_EXTRACT_ZIP, msg, ioe));
        }
    }

    /**
     * Extracts a zip entry (file entry) and stores in Alfresco in matching ISBN sub-folder, such as /Adobe Chapters
     *
     * @param fileEntry           the ZIP information about the file
     * @param is                  input stream for the ZIP file entry
     * @param currentZipDirectory the current ZIP directory such as "9780486282145/content/"
     * @param isbn                the related ISBN number
     * @param isbnFolderNodeRef   the Alfresco node reference for the ISBN folder in Data Dictionary
     *                            where the content file should be stored
     */
    private void processZipFileEntry(ZipEntry fileEntry, final InputStream is, final String currentZipDirectory,
                                     final String isbn, final NodeRef isbnFolderNodeRef) {
        // Get from 9780486282145/content/9780486282145-Chapter-1.pdf
        // to 9780486282145-Chapter-1.pdf
        String filename = FilenameUtils.getName(fileEntry.getName());

        // Get from 9780486282145/content/
        // to content
        String[] pathSegments = currentZipDirectory.split("/");
        String zipDirName = pathSegments[pathSegments.length - 1];

        if (StringUtils.equalsIgnoreCase(zipDirName, ZIP_CONTENT_DIR_NAME)) {
            if (filename.toLowerCase().contains(ZIP_CHAPTER_FILENAME_PART)) {
                // We got a chapter XHTML file, store it under the {ISBN}/Chapters
                NodeRef chapterFolderNodeRef = alfrescoRepoUtilsService.getOrCreateFolder(isbnFolderNodeRef,
                        CHAPTERS_FOLDER_NAME);
                alfrescoRepoUtilsService.createFile(chapterFolderNodeRef, filename, is);
            } else {
                // We got a Supplementary file like ToC or Cover Image, store it under the {ISBN}/Supplementary
                NodeRef supplementaryFolderNodeRef = alfrescoRepoUtilsService.getOrCreateFolder(isbnFolderNodeRef,
                        SUPPLEMENTARY_FOLDER_NAME);
                alfrescoRepoUtilsService.createFile(supplementaryFolderNodeRef, filename, is);
            }
        } else if (StringUtils.equalsIgnoreCase(zipDirName, ZIP_ARTWORK_DIR_NAME)) {
            // We got an artwork file like a diagram or image, store it under the {ISBN}/Artwork
            NodeRef artworkFolderNodeRef =
                    alfrescoRepoUtilsService.getOrCreateFolder(isbnFolderNodeRef, ARTWORK_FOLDER_NAME);
            alfrescoRepoUtilsService.createFile(artworkFolderNodeRef, filename, is);
        } else if (StringUtils.equalsIgnoreCase(zipDirName, ZIP_STYLES_DIR_NAME)) {
            // We got a style (css) file, store it under the {ISBN}/Styles
            NodeRef artworkFolderNodeRef =
                    alfrescoRepoUtilsService.getOrCreateFolder(isbnFolderNodeRef, STYLES_FOLDER_NAME);
            alfrescoRepoUtilsService.createFile(artworkFolderNodeRef, filename, is);
        } else {
            LOG.warn("Found {} in the {} directory, will not ingest", filename, currentZipDirectory);
        }
    }
}
