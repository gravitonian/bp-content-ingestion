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

import org.alfresco.model.ContentModel;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.namespace.QName;
import org.marversolutions.bestpublishing.contentchecker.exceptions.ContentIngestionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/*
 * Implementation of the Content Folder Import Service, extracts ZIP to temporary location in local filesystem
 * and imports into Alfresco from there.
 *
 * @author martin.bergljung@marversolutions.org
 * @version 1.0
 */
@Transactional(readOnly = true)
public class ContentFolderImporterServiceImpl implements ContentFolderImporterService {
    private static final Logger LOG = LoggerFactory.getLogger(ContentFolderImporterServiceImpl.class);

    /**
     * Part of filename for book content that distinguishes the file as a chapter content XHTML file
     */
    public final static String CHAPTER_FILENAME_PART = "chapter";

    /**
     * Alfresco Services
     */
    private NodeService nodeService;

    /**
     * Best Publishing Services
     */
    private AlfrescoRepoUtilsService alfrescoRepoUtilsService;

    /**
     * Spring DI
     */

    public void setNodeService(NodeService nodeService) {
        this.nodeService = nodeService;
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
                    nodeService.getPath(parentContentFolderNodeRef).toString();
            throw new ContentIngestionException(extraDetails);
        }

        // Process and ingest all content in the Content ZIP
        processZipFile(isbnFolderNodeRef, isbn, file);

        // Validate that we got the full content XML for ISBN
        validateContentXMLFile(isbn, isbnFolderNodeRef);

        // Validate that we got all the PDFs and XMLs for the chapters
        NodeRef chapterFolderNodeRef = alfrescoRepoUtilsService.getOrCreateFolder(isbnFolderNodeRef,
                ADOBE_CHAPTERS_FOLDER_NAME);
        validateChapterPdfAndXmlFiles(chapterFolderNodeRef);

        // Everything went OK, setup ISBN as ready to be fetched by workflow, if it has been started
        nodeService.setProperty(isbnFolderNodeRef, ISBNFolderType.Prop.INGESTION_STATUS,
                IngestionStatus.COMPLETE.toString());
    }

    /**
     * Create the ISBN folder in the /Company Home/Data Dictionary/BESTPUB/Incoming/Content
     * or in /Incoming/Content/Republish folder.
     *
     * @param parentContentFolderNodeRef the node ref for /Company Home/Data Dictionary/BOPP/Incoming/Content
     * @param isbn the book ISBN 13 number
     * @return the folder node reference for this new ISBN folder, pointing to either
     * /Company Home/Data Dictionary/BESTPUB/Incoming/Content/{ISBN}
     */
    private NodeRef createIsbnFolder(final NodeRef parentContentFolderNodeRef, final String isbn) {
        LOG.debug("Creating ISBN folder for {}", isbn);

        Map<QName, Serializable> properties = new HashMap<QName, Serializable>();
        properties.put(ContentModel.PROP_NAME, isbn);
        properties.put(BoppContentModel.BookMetadataAspect.Prop.ISBN, isbn);
        properties.put(BoppContentModel.ISBNFolderType.Prop.INGESTION_STATUS,
                BoppContentModel.IngestionStatus.IN_PROGRESS.toString());

        return nodeService.createNode(parentContentFolderNodeRef,
                ContentModel.ASSOC_CONTAINS, QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, isbn),
                BoppContentModel.ISBNFolderType.QNAME, properties).getChildRef();
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
                    // which would be something like /Adobe Chapters, /artwork, etc
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
                    new ProcessingError(ProcessingErrorCode.CONTENT_CHECKER_EXTRACT_ZIP, msg, ioe));
        }
    }

    /**
     * Extracts a zip entry (file entry) and stores in Alfresco in matching ISBN sub-folder, such as /Adobe Chapters
     *
     * @param fileEntry           the ZIP information about the file
     * @param is                  input stream for the ZIP file entry
     * @param currentZipDirectory the current ZIP directory such as "9780080569437/Adobe Chapters/",
     *                            which tells us where to ingest this content file in Alfresco
     * @param isbn                the related ISBN number
     * @param isbnFolderNodeRef   the Alfresco node reference for the ISBN folder in Data Dictionary
     *                            where the content file should be stored
     */
    private void processZipFileEntry(ZipEntry fileEntry, final InputStream is, final String currentZipDirectory,
                                     final String isbn, final NodeRef isbnFolderNodeRef) {
        // Get from 9780080569437/Adobe Chapters/9780080569437_Chapter_1.pdf
        // to 9780080569437_Chapter_1.pdf
        String filename = FilenameUtils.getName(fileEntry.getName());

        // Get from 9780080569437/Adobe Chapters/
        // to Adobe Chapters
        String[] pathSegments = currentZipDirectory.split("/");
        String folderName = pathSegments[pathSegments.length - 1];

        if (StringUtils.equalsIgnoreCase(folderName, ADOBE_CHAPTERS_FOLDER_NAME)) {
            if (filename.toLowerCase().contains(CHAPTER_FILENAME_PART)) {
                // We got a chapter PDF, store it under the ISBN
                NodeRef chapterFolderNodeRef = alfrescoRepoUtilsService.getOrCreateFolder(isbnFolderNodeRef,
                        ADOBE_CHAPTERS_FOLDER_NAME);
                alfrescoRepoUtilsService.createFile(chapterFolderNodeRef, renameChapterFile(filename), is);
            } else {
                // We got a Supplementary file like ToC or Cover Image, store it under the ISBN
                NodeRef supplementaryFolderNodeRef = alfrescoRepoUtilsService.getOrCreateFolder(isbnFolderNodeRef,
                        SUPPLEMENTARY_FOLDER_NAME);
                alfrescoRepoUtilsService.createFile(supplementaryFolderNodeRef, filename, is);
            }
        } else if (StringUtils.equalsIgnoreCase(folderName, ARTWORK_FOLDER_NAME)) {
            // We got an artwork file like a diagram or image, store it under the ISBN
            NodeRef artworkFolderNodeRef =
                    alfrescoRepoUtilsService.getOrCreateFolder(isbnFolderNodeRef, ARTWORK_FOLDER_NAME);
            alfrescoRepoUtilsService.createFile(artworkFolderNodeRef, filename, is);
        } else if (StringUtils.equalsIgnoreCase(folderName, TFB_XML_FOLDER_NAME)) {
            // We got the TFB XML folder
            if (StringUtils.endsWith(filename.toLowerCase(), "xml")) {
                // We got the content available as XML, store it under the ISBN
                NodeRef tfbXmlFolderNodeRef =
                        alfrescoRepoUtilsService.getOrCreateFolder(isbnFolderNodeRef, TFB_XML_FOLDER_NAME);
                NodeRef xmlFileNodeRef = alfrescoRepoUtilsService.createFile(tfbXmlFolderNodeRef, filename, is);
                NodeRef chapterFolderNodeRef = alfrescoRepoUtilsService.getOrCreateFolder(isbnFolderNodeRef,
                        ADOBE_CHAPTERS_FOLDER_NAME);

                // Then extract the XML content for each chapter and store in Adobe Chapters folder
                xmlExtractor.extractXml(xmlFileNodeRef, chapterFolderNodeRef, isbn);
            } else {
                LOG.warn("Found {} in the {} directory, will not ingest", filename, currentZipDirectory);
            }
        }
    }

    /**
     * The chapter filenames need to be in the format [ISBN]-chapter[chapter number].[pdf|xml].
     * As we use the Alfresco repo utils service to create these files, the file name needs to
     * be correct before we get to that stage...
     *
     * @param filename current filename (from ZIP)
     * @return The renamed chapter filename
     */
    private String renameChapterFile(String filename) {
        String newFilename = filename.replace("_", "-");
        return newFilename.replace("Chapter-", "chapter");
    }

    /**
     * Validates that the {isbn}.xml has been extracted form ZIP and stored in .../{isbn}/TFB XML/
     *
     * @param isbn              the ISBN number
     * @param isbnFolderNodeRef the ISBN folder where it should be stored under /TBF XML
     */
    private void validateContentXMLFile(final String isbn, final NodeRef isbnFolderNodeRef) {
        String contentXMLFilename = isbn + ".xml";
        NodeRef tfbXmlFolderNodeRef =
                alfrescoRepoUtilsService.getOrCreateFolder(isbnFolderNodeRef, TFB_XML_FOLDER_NAME);
        NodeRef tfbContentXMLNodeRef = alfrescoRepoUtilsService.getChildByName(tfbXmlFolderNodeRef, contentXMLFilename);
        if (tfbContentXMLNodeRef == null) {
            String errorMsg = "Missing complete content XML file [" + contentXMLFilename +
                    "] in " + TFB_XML_FOLDER_NAME + "";
            throw new ContentIngestionException(new ProcessingError(
                    ProcessingErrorCode.CONTENT_CHECKER_HANDLE_XML_FILE, errorMsg, null));
        }
    }

    /**
     * Checks that the number of chapter PDF files matches the number of chapter
     * XML files extracted from the master XML file.
     *
     * @param chaptersFolderNodeRef the node ref of the folder containing the chapter PDF and XML files
     */
    private void validateChapterPdfAndXmlFiles(final NodeRef chaptersFolderNodeRef) {
        int numberOfPdfFiles = 0;
        int numberOfXmlFiles = 0;
        List<ChildAssociationRef> files = nodeService.getChildAssocs(chaptersFolderNodeRef);
        for (ChildAssociationRef file : files) {
            NodeRef nodeRef = file.getChildRef();
            ContentData contentData = (ContentData) nodeService.getProperty(nodeRef, ContentModel.PROP_CONTENT);
            String mimeType = contentData.getMimetype();
            if (mimeType.equals(MimetypeMap.MIMETYPE_PDF)) {
                numberOfPdfFiles++;
            } else if (mimeType.equals(MimetypeMap.MIMETYPE_XML)) {
                numberOfXmlFiles++;
            }
        }

        if (numberOfPdfFiles == 0 && numberOfXmlFiles == 0) {
            throw new ContentIngestionException(
                    new ProcessingError(ProcessingErrorCode.CONTENT_CHECKER_CHAPTER_FILES_MISMATCH,
                            "There are no PDF or XML files available in " + ADOBE_CHAPTERS_FOLDER_NAME, null));
        } else if (numberOfPdfFiles != numberOfXmlFiles) {
            throw new ContentIngestionException(
                    new ProcessingError(ProcessingErrorCode.CONTENT_CHECKER_CHAPTER_FILES_MISMATCH,
                            "There are [" + numberOfPdfFiles + "] PDF files but [" + numberOfXmlFiles + "] XML files", null));
        }
    }
}
