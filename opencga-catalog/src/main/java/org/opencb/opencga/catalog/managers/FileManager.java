/*
 * Copyright 2015-2017 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencb.opencga.catalog.managers;

import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.opencb.biodata.models.variant.VariantFileMetadata;
import org.opencb.biodata.models.variant.stats.VariantSetStats;
import org.opencb.commons.datastore.core.*;
import org.opencb.commons.datastore.core.result.Error;
import org.opencb.commons.utils.CollectionUtils;
import org.opencb.commons.utils.FileUtils;
import org.opencb.commons.utils.ListUtils;
import org.opencb.opencga.catalog.audit.AuditManager;
import org.opencb.opencga.catalog.audit.AuditRecord;
import org.opencb.opencga.catalog.auth.authorization.AuthorizationManager;
import org.opencb.opencga.catalog.db.DBAdaptorFactory;
import org.opencb.opencga.catalog.db.api.*;
import org.opencb.opencga.catalog.db.mongodb.MongoDBAdaptorFactory;
import org.opencb.opencga.catalog.exceptions.*;
import org.opencb.opencga.catalog.io.CatalogIOManager;
import org.opencb.opencga.catalog.io.CatalogIOManagerFactory;
import org.opencb.opencga.catalog.models.InternalGetDataResult;
import org.opencb.opencga.catalog.models.update.FileUpdateParams;
import org.opencb.opencga.catalog.stats.solr.CatalogSolrManager;
import org.opencb.opencga.catalog.utils.*;
import org.opencb.opencga.core.common.TimeUtils;
import org.opencb.opencga.core.common.UriUtils;
import org.opencb.opencga.core.config.Configuration;
import org.opencb.opencga.core.config.HookConfiguration;
import org.opencb.opencga.core.models.*;
import org.opencb.opencga.core.models.acls.permissions.FileAclEntry;
import org.opencb.opencga.core.models.acls.permissions.StudyAclEntry;
import org.opencb.opencga.core.models.common.Enums;
import org.opencb.opencga.core.results.OpenCGAResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.opencb.opencga.catalog.auth.authorization.CatalogAuthorizationManager.checkPermissions;
import static org.opencb.opencga.catalog.utils.FileMetadataReader.VARIANT_FILE_STATS;
import static org.opencb.opencga.core.common.JacksonUtils.getDefaultObjectMapper;

/**
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class FileManager extends AnnotationSetManager<File> {

    private static final QueryOptions INCLUDE_STUDY_URI;
    public static final QueryOptions INCLUDE_FILE_IDS;
    public static final QueryOptions INCLUDE_FILE_URI;
    public static final QueryOptions INCLUDE_FILE_URI_PATH;
    public  static final QueryOptions EXCLUDE_FILE_ATTRIBUTES;
    private static final Comparator<File> ROOT_FIRST_COMPARATOR;
    private static final Comparator<File> ROOT_LAST_COMPARATOR;

    protected static Logger logger;
    private FileMetadataReader fileMetadataReader;
    private UserManager userManager;
    private StudyManager studyManager;

    public static final String SKIP_TRASH = "SKIP_TRASH";
    public static final String DELETE_EXTERNAL_FILES = "DELETE_EXTERNAL_FILES";
    public static final String FORCE_DELETE = "FORCE_DELETE";

    public static final String GET_NON_DELETED_FILES = Status.READY + "," + File.FileStatus.TRASHED + "," + File.FileStatus.STAGE + ","
            + File.FileStatus.MISSING;
    public static final String GET_NON_TRASHED_FILES = Status.READY + "," + File.FileStatus.STAGE + "," + File.FileStatus.MISSING;

    private final String defaultFacet = "creationYear>>creationMonth;format;bioformat;format>>bioformat;status;"
            + "size[0..214748364800]:10737418240;numSamples[0..10]:1";

    static {
        INCLUDE_FILE_IDS = new QueryOptions(QueryOptions.INCLUDE, Arrays.asList(FileDBAdaptor.QueryParams.ID.key(),
                FileDBAdaptor.QueryParams.NAME.key(), FileDBAdaptor.QueryParams.UID.key(), FileDBAdaptor.QueryParams.UUID.key(),
                FileDBAdaptor.QueryParams.STUDY_UID.key(), FileDBAdaptor.QueryParams.TYPE.key()));
        INCLUDE_FILE_URI = new QueryOptions(QueryOptions.INCLUDE, Arrays.asList(FileDBAdaptor.QueryParams.ID.key(),
                FileDBAdaptor.QueryParams.NAME.key(), FileDBAdaptor.QueryParams.UID.key(), FileDBAdaptor.QueryParams.UUID.key(),
                FileDBAdaptor.QueryParams.URI.key(), FileDBAdaptor.QueryParams.STUDY_UID.key(), FileDBAdaptor.QueryParams.TYPE.key()));
        INCLUDE_FILE_URI_PATH = new QueryOptions(QueryOptions.INCLUDE, Arrays.asList(FileDBAdaptor.QueryParams.ID.key(),
                FileDBAdaptor.QueryParams.NAME.key(), FileDBAdaptor.QueryParams.UID.key(), FileDBAdaptor.QueryParams.UUID.key(),
                FileDBAdaptor.QueryParams.STATUS.key(),
                FileDBAdaptor.QueryParams.URI.key(), FileDBAdaptor.QueryParams.PATH.key(), FileDBAdaptor.QueryParams.EXTERNAL.key(),
                FileDBAdaptor.QueryParams.STUDY_UID.key(), FileDBAdaptor.QueryParams.TYPE.key()));
        EXCLUDE_FILE_ATTRIBUTES = new QueryOptions(QueryOptions.EXCLUDE, Arrays.asList(FileDBAdaptor.QueryParams.ATTRIBUTES.key(),
                FileDBAdaptor.QueryParams.ANNOTATION_SETS.key(), FileDBAdaptor.QueryParams.STATS.key()));
        INCLUDE_STUDY_URI = new QueryOptions(QueryOptions.INCLUDE, StudyDBAdaptor.QueryParams.URI.key());

        ROOT_FIRST_COMPARATOR = (f1, f2) -> (f1.getPath() == null ? 0 : f1.getPath().length())
                - (f2.getPath() == null ? 0 : f2.getPath().length());
        ROOT_LAST_COMPARATOR = (f1, f2) -> (f2.getPath() == null ? 0 : f2.getPath().length())
                - (f1.getPath() == null ? 0 : f1.getPath().length());

        logger = LoggerFactory.getLogger(FileManager.class);
    }

    FileManager(AuthorizationManager authorizationManager, AuditManager auditManager, CatalogManager catalogManager,
                DBAdaptorFactory catalogDBAdaptorFactory, CatalogIOManagerFactory ioManagerFactory, Configuration configuration) {
        super(authorizationManager, auditManager, catalogManager, catalogDBAdaptorFactory, ioManagerFactory, configuration);
        fileMetadataReader = new FileMetadataReader(this.catalogManager);
        this.userManager = catalogManager.getUserManager();
        this.studyManager = catalogManager.getStudyManager();
    }

    @Override
    Enums.Resource getEntity() {
        return Enums.Resource.FILE;
    }

    @Override
    OpenCGAResult<File> internalGet(long studyUid, String fileName, @Nullable Query query, QueryOptions options, String user)
            throws CatalogException {
        ParamUtils.checkIsSingleID(fileName);

        QueryOptions queryOptions = options != null ? new QueryOptions(options) : new QueryOptions();

        FileDBAdaptor.QueryParams queryParam = FileDBAdaptor.QueryParams.PATH;
        if (UUIDUtils.isOpenCGAUUID(fileName)) {
            queryParam = FileDBAdaptor.QueryParams.UUID;
        } else {
            fileName = fileName.replace(":", "/");
            if (fileName.startsWith("/")) {
                fileName = fileName.substring(1);
            }
        }

        // We search the file
        Query queryCopy = query == null ? new Query() : new Query(query);
        queryCopy.append(FileDBAdaptor.QueryParams.STUDY_UID.key(), studyUid)
                .append(queryParam.key(), fileName);
        OpenCGAResult<File> pathDataResult = fileDBAdaptor.get(studyUid, queryCopy, queryOptions, user);
        if (pathDataResult.getNumResults() > 1) {
            throw new CatalogException("Error: More than one file id found based on " + fileName);
        } else if (pathDataResult.getNumResults() == 1) {
            return pathDataResult;
        }

        if (queryParam == FileDBAdaptor.QueryParams.PATH && !fileName.contains("/")) {
            queryParam = FileDBAdaptor.QueryParams.NAME;

            // We search as a fileName as well
            queryCopy = query == null ? new Query() : new Query(query);
            queryCopy.append(FileDBAdaptor.QueryParams.STUDY_UID.key(), studyUid)
                    .append(FileDBAdaptor.QueryParams.NAME.key(), fileName);
            OpenCGAResult<File> nameDataResult = fileDBAdaptor.get(studyUid, queryCopy, queryOptions, user);
            if (nameDataResult.getNumResults() > 1) {
                throw new CatalogException("Error: More than one file id found based on " + fileName);
            } else if (nameDataResult.getNumResults() == 1) {
                return nameDataResult;
            }
        }

        // The file could not be found or the user does not have permissions to see it
        // Check if the file can be found without adding the user restriction
        OpenCGAResult<File> resultsNoCheck = fileDBAdaptor.get(queryCopy, queryOptions);
        if (resultsNoCheck.getNumResults() == 1) {
            throw new CatalogAuthorizationException("Permission denied. " + user + " is not allowed to see the file " + fileName);
        }
        if (queryParam == FileDBAdaptor.QueryParams.NAME) {
            // The last search was performed by name but we can also search by path just in case
            queryCopy.put(FileDBAdaptor.QueryParams.PATH.key(), fileName);
            resultsNoCheck = fileDBAdaptor.get(queryCopy, queryOptions);
            if (resultsNoCheck.getNumResults() == 1) {
                throw new CatalogAuthorizationException("Permission denied. " + user + " is not allowed to see the file " + fileName);
            }
        }

        throw new CatalogException("File " + fileName + " not found");
    }

    @Override
    InternalGetDataResult<File> internalGet(long studyUid, List<String> entryList, @Nullable Query query, QueryOptions options,
                                            String user, boolean ignoreException) throws CatalogException {
        if (ListUtils.isEmpty(entryList)) {
            throw new CatalogException("Missing file entries.");
        }
        List<String> uniqueList = ListUtils.unique(entryList);

        QueryOptions queryOptions = options != null ? new QueryOptions(options) : new QueryOptions();
        Query queryCopy = query == null ? new Query() : new Query(query);
        queryCopy.append(FileDBAdaptor.QueryParams.STUDY_UID.key(), studyUid);

        Function<File, String> fileStringFunction = File::getPath;
        boolean canBeSearchedAsName = true;
        List<String> correctedFileList = new ArrayList<>(uniqueList.size());
        FileDBAdaptor.QueryParams idQueryParam = null;
        for (String entry : uniqueList) {
            FileDBAdaptor.QueryParams param = FileDBAdaptor.QueryParams.PATH;
            if (UUIDUtils.isOpenCGAUUID(entry)) {
                correctedFileList.add(entry);
                param = FileDBAdaptor.QueryParams.UUID;
                fileStringFunction = File::getUuid;
            } else {
                String fileName = entry.replace(":", "/");
                if (fileName.startsWith("/")) {
                    // Remove the starting /. Absolute paths are not supported.
                    fileName = fileName.substring(1);
                }
                correctedFileList.add(fileName);

                if (fileName.contains("/")) {
                    canBeSearchedAsName = false;
                }
            }
            if (idQueryParam == null) {
                idQueryParam = param;
            }
            if (idQueryParam != param) {
                throw new CatalogException("Found uuids and paths in the same query. Please, choose one or do two different queries.");
            }
        }
        queryCopy.put(idQueryParam.key(), correctedFileList);

        // Ensure the field by which we are querying for will be kept in the results
        queryOptions = keepFieldInQueryOptions(queryOptions, idQueryParam.key());

        OpenCGAResult<File> fileDataResult = fileDBAdaptor.get(studyUid, queryCopy, queryOptions, user);
        if (fileDataResult.getNumResults() != correctedFileList.size() && idQueryParam == FileDBAdaptor.QueryParams.PATH
                && canBeSearchedAsName) {
            // We also search by name
            queryCopy = query == null ? new Query() : new Query(query);
            queryCopy.append(FileDBAdaptor.QueryParams.STUDY_UID.key(), studyUid)
                    .append(FileDBAdaptor.QueryParams.NAME.key(), correctedFileList);

            // Ensure the field by which we are querying for will be kept in the results
            queryOptions = keepFieldInQueryOptions(queryOptions, FileDBAdaptor.QueryParams.NAME.key());

            OpenCGAResult<File> nameDataResult = fileDBAdaptor.get(studyUid, queryCopy, queryOptions, user);
            if (nameDataResult.getNumResults() > fileDataResult.getNumResults()) {
                fileDataResult = nameDataResult;
                fileStringFunction = File::getName;
            }
        }

        if (fileDataResult.getNumResults() > correctedFileList.size()) {
            throw new CatalogException("Error: More than one file found for at least one of the files introduced");
        } else if (ignoreException || fileDataResult.getNumResults() == correctedFileList.size()) {
            return keepOriginalOrder(correctedFileList, fileStringFunction, fileDataResult, ignoreException, false);
        } else {
            // The file could not be found or the user does not have permissions to see it
            // Check if the file can be found without adding the user restriction
            OpenCGAResult<File> resultsNoCheck = fileDBAdaptor.get(queryCopy, queryOptions);
            if (resultsNoCheck.getNumResults() == correctedFileList.size()) {
                throw new CatalogAuthorizationException("Permission denied. " + user + " is not allowed to see some or none of the files.");
            }
            if (canBeSearchedAsName) {
                // The last query was performed by name, so we now search by path
                queryCopy = query == null ? new Query() : new Query(query);
                queryCopy.append(FileDBAdaptor.QueryParams.STUDY_UID.key(), studyUid)
                        .append(FileDBAdaptor.QueryParams.PATH.key(), correctedFileList);
                resultsNoCheck = fileDBAdaptor.get(queryCopy, queryOptions);
                if (resultsNoCheck.getNumResults() == correctedFileList.size()) {
                    throw new CatalogAuthorizationException("Permission denied. " + user + " is not allowed to see some or none of the "
                            + "files.");
                }
            }

            throw CatalogException.notFound("files", getMissingFields(uniqueList, fileDataResult.getResults(), fileStringFunction));
        }
    }

    private OpenCGAResult<File> getFile(long studyUid, String fileUuid, QueryOptions options) throws CatalogDBException {
        Query query = new Query()
                .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), studyUid)
                .append(FileDBAdaptor.QueryParams.UUID.key(), fileUuid);
        return fileDBAdaptor.get(query, options);
    }

    public URI getUri(File file) throws CatalogException {
        ParamUtils.checkObj(file, "File");
        if (file.getUri() != null) {
            return file.getUri();
        } else {
            OpenCGAResult<File> fileDataResult = fileDBAdaptor.get(file.getUid(), INCLUDE_STUDY_URI);
            if (fileDataResult.getNumResults() == 0) {
                throw new CatalogException("File " + file.getUid() + " not found");
            }
            return fileDataResult.first().getUri();
        }
    }

    @Deprecated
    public URI getUri(long studyId, String filePath) throws CatalogException {
        if (StringUtils.isEmpty(filePath)) {
            URI studyUri = getStudyUri(studyId);
            return filePath.isEmpty()
                    ? studyUri
                    : catalogIOManagerFactory.get(studyUri).getFileUri(studyUri, filePath);
        }

        List<File> parents = getParents(studyId, filePath, false, INCLUDE_FILE_URI_PATH).getResults();

        for (File parent : parents) {
            if (parent.getUri() != null) {
                if (parent.isExternal()) {
                    throw new CatalogException("Cannot upload files to an external folder");
                }
                String relativePath = filePath.replaceFirst(parent.getPath(), "");
                return Paths.get(parent.getUri()).resolve(relativePath).toUri();
            }
        }
        URI studyUri = getStudyUri(studyId);
        return filePath.isEmpty()
                ? studyUri
                : catalogIOManagerFactory.get(studyUri).getFileUri(studyUri, filePath);
    }

    public Study getStudy(File file, String sessionId) throws CatalogException {
        ParamUtils.checkObj(file, "file");
        ParamUtils.checkObj(sessionId, "session id");

        if (file.getStudyUid() <= 0) {
            throw new CatalogException("Missing study uid field in file");
        }

        String user = userManager.getUserId(sessionId);

        Query query = new Query(StudyDBAdaptor.QueryParams.UID.key(), file.getStudyUid());
        OpenCGAResult<Study> studyDataResult = studyDBAdaptor.get(query, QueryOptions.empty(), user);
        if (studyDataResult.getNumResults() == 1) {
            return studyDataResult.first();
        } else {
            authorizationManager.checkCanViewStudy(file.getStudyUid(), user);
            throw new CatalogException("Incorrect study uid");
        }
    }

    public void matchUpVariantFiles(String studyStr, List<File> transformedFiles, String sessionId) throws CatalogException {
        String userId = userManager.getUserId(sessionId);
        Study study = studyManager.resolveId(studyStr, userId);

        for (File transformedFile : transformedFiles) {
            authorizationManager.checkFilePermission(study.getUid(), transformedFile.getUid(), userId, FileAclEntry.FilePermissions.WRITE);
            String variantPathName = getMainVariantFile(transformedFile.getPath());
            if (variantPathName == null) {
                // Skip the file.
                logger.debug("The file {} is not a variant transformed file", transformedFile.getName());
                continue;
            }

            // Search in the same path
            logger.info("Looking for vcf file in path {}", variantPathName);
            Query query = new Query()
                    .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid())
                    .append(FileDBAdaptor.QueryParams.PATH.key(), variantPathName)
                    .append(FileDBAdaptor.QueryParams.BIOFORMAT.key(), File.Bioformat.VARIANT);

            List<File> fileList = fileDBAdaptor.get(query, new QueryOptions()).getResults();

            if (fileList.isEmpty()) {
                // Search by name in the whole study
                String variantFileName = getMainVariantFile(transformedFile.getName());
                logger.info("Looking for vcf file by name {}", variantFileName);
                query = new Query()
                        .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid())
                        .append(FileDBAdaptor.QueryParams.NAME.key(), variantFileName)
                        .append(FileDBAdaptor.QueryParams.BIOFORMAT.key(), File.Bioformat.VARIANT);
                fileList = new ArrayList<>(fileDBAdaptor.get(query, new QueryOptions()).getResults());

                // In case of finding more than one file, try to find the proper one.
                if (fileList.size() > 1) {
                    // Discard files already with a transformed file.
                    fileList.removeIf(file -> file.getIndex() != null
                            && file.getIndex().getTransformedFile() != null
                            && file.getIndex().getTransformedFile().getId() != transformedFile.getUid());
                }
                if (fileList.size() > 1) {
                    // Discard files not transformed or indexed.
                    fileList.removeIf(file -> file.getIndex() == null
                            || file.getIndex().getStatus() == null
                            || file.getIndex().getStatus().getName() == null
                            || file.getIndex().getStatus().getName().equals(FileIndex.IndexStatus.NONE));
                }
            }


            if (fileList.size() != 1) {
                // VCF file not found
                logger.warn("The vcf file corresponding to the file " + transformedFile.getName() + " could not be found");
                continue;
            }
            File vcf = fileList.get(0);

            // Look for the json file. It should be in the same directory where the transformed file is.
            String jsonPathName = getVariantMetadataFile(transformedFile.getPath());
            query = new Query()
                    .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid())
                    .append(FileDBAdaptor.QueryParams.PATH.key(), jsonPathName)
                    .append(FileDBAdaptor.QueryParams.FORMAT.key(), File.Format.JSON);
            fileList = fileDBAdaptor.get(query, new QueryOptions()).getResults();
            if (fileList.size() != 1) {
                // Skip. This should not ever happen
                logger.warn("The json file corresponding to the file " + transformedFile.getName() + " could not be found");
                continue;
            }
            File json = fileList.get(0);

            /* Update relations */
            File.RelatedFile producedFromRelation = new File.RelatedFile(vcf, File.RelatedFile.Relation.PRODUCED_FROM);

            // Update json file
            logger.debug("Updating json relation");
            List<File.RelatedFile> relatedFiles = ParamUtils.defaultObject(json.getRelatedFiles(), ArrayList::new);
            // Do not add twice the same relation
            if (!relatedFiles.contains(producedFromRelation)) {
                relatedFiles.add(producedFromRelation);
                ObjectMap params = new ObjectMap(FileDBAdaptor.QueryParams.RELATED_FILES.key(), relatedFiles);
                fileDBAdaptor.update(json.getUid(), params, QueryOptions.empty());
            }

            // Update transformed file
            logger.debug("Updating transformed relation");
            relatedFiles = ParamUtils.defaultObject(transformedFile.getRelatedFiles(), ArrayList::new);
            // Do not add twice the same relation
            if (!relatedFiles.contains(producedFromRelation)) {
                relatedFiles.add(producedFromRelation);
                transformedFile.setRelatedFiles(relatedFiles);
                ObjectMap params = new ObjectMap(FileDBAdaptor.QueryParams.RELATED_FILES.key(), relatedFiles);
                fileDBAdaptor.update(transformedFile.getUid(), params, QueryOptions.empty());
            }

            // Update vcf file
            logger.debug("Updating vcf relation");
            FileIndex index = vcf.getIndex();
            if (index.getTransformedFile() == null) {
                index.setTransformedFile(new FileIndex.TransformedFile(transformedFile.getUid(), json.getUid()));
            }
            String status = FileIndex.IndexStatus.NONE;
            if (vcf.getIndex() != null && vcf.getIndex().getStatus() != null && vcf.getIndex().getStatus().getName() != null) {
                status = vcf.getIndex().getStatus().getName();
            }
            if (FileIndex.IndexStatus.NONE.equals(status)) {
                // If TRANSFORMED, TRANSFORMING, etc, do not modify the index status
                index.setStatus(new FileIndex.IndexStatus(FileIndex.IndexStatus.TRANSFORMED, "Found transformed file"));
            }
            ObjectMap params = new ObjectMap(FileDBAdaptor.QueryParams.INDEX.key(), index);
            fileDBAdaptor.update(vcf.getUid(), params, QueryOptions.empty());

            // Update variant stats
            Path statsFile = Paths.get(json.getUri().getRawPath());
            try (InputStream is = FileUtils.newInputStream(statsFile)) {
                VariantFileMetadata fileMetadata = getDefaultObjectMapper().readValue(is, VariantFileMetadata.class);
                VariantSetStats stats = fileMetadata.getStats();
                update(studyStr, vcf.getPath(),
                        new FileUpdateParams().setStats(new ObjectMap(VARIANT_FILE_STATS, stats)), new QueryOptions(), sessionId);
            } catch (IOException e) {
                throw new CatalogException("Error reading file \"" + statsFile + "\"", e);
            }
        }
    }

    public OpenCGAResult<FileIndex> updateFileIndexStatus(File file, String newStatus, String message, String sessionId)
            throws CatalogException {
        return updateFileIndexStatus(file, newStatus, message, null, sessionId);
    }

    public OpenCGAResult<FileIndex> updateFileIndexStatus(File file, String newStatus, String message, Integer release, String token)
            throws CatalogException {
        String userId = userManager.getUserId(token);
        Study study = studyDBAdaptor.get(file.getStudyUid(), StudyManager.INCLUDE_STUDY_ID).first();

        ObjectMap auditParams = new ObjectMap()
                .append("file", file)
                .append("newStatus", newStatus)
                .append("message", message)
                .append("release", release)
                .append("token", token);

        authorizationManager.checkFilePermission(study.getUid(), file.getUid(), userId, FileAclEntry.FilePermissions.WRITE);

        FileIndex index = file.getIndex();
        if (index != null) {
            if (!FileIndex.IndexStatus.isValid(newStatus)) {
                throw new CatalogException("The status " + newStatus + " is not a valid status.");
            } else {
                index.setStatus(new FileIndex.IndexStatus(newStatus, message));
            }
        } else {
            index = new FileIndex(userId, TimeUtils.getTime(), new FileIndex.IndexStatus(newStatus), -1, new ObjectMap());
        }
        if (release != null) {
            if (newStatus.equals(FileIndex.IndexStatus.READY)) {
                index.setRelease(release);
            }
        }
        ObjectMap params = new ObjectMap(FileDBAdaptor.QueryParams.INDEX.key(), index);
        OpenCGAResult update = fileDBAdaptor.update(file.getUid(), params, QueryOptions.empty());
        auditManager.auditUpdate(userId, Enums.Resource.FILE, file.getId(), file.getUuid(), study.getId(), study.getUuid(),
                auditParams, new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));

        return new OpenCGAResult<>(update.getTime(), update.getEvents(), 1, Collections.singletonList(index), 1);
    }

    @Deprecated
    public OpenCGAResult<File> getParents(long fileId, QueryOptions options, String sessionId) throws CatalogException {
        OpenCGAResult<File> fileDataResult = fileDBAdaptor.get(fileId, new QueryOptions(QueryOptions.INCLUDE,
                Arrays.asList(FileDBAdaptor.QueryParams.PATH.key(), FileDBAdaptor.QueryParams.STUDY_UID.key())));

        if (fileDataResult.getNumResults() == 0) {
            return fileDataResult;
        }

        String userId = userManager.getUserId(sessionId);
        authorizationManager.checkFilePermission(fileDataResult.first().getStudyUid(), fileId, userId, FileAclEntry.FilePermissions.VIEW);

        return getParents(fileDataResult.first().getStudyUid(), fileDataResult.first().getPath(), true, options);
    }

    public OpenCGAResult<File> createFolder(String studyStr, String path, File.FileStatus status, boolean parents, String description,
                                         QueryOptions options, String sessionId) throws CatalogException {
        ParamUtils.checkPath(path, "folderPath");
        options = ParamUtils.defaultObject(options, QueryOptions::new);

        String userId = userManager.getUserId(sessionId);
        Study study = studyManager.resolveId(studyStr, userId);

        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (!path.endsWith("/")) {
            path = path + "/";
        }

        OpenCGAResult<File> fileDataResult;
        switch (checkPathExists(path, study.getUid())) {
            case FREE_PATH:
                fileDataResult = create(studyStr, File.Type.DIRECTORY, File.Format.NONE, File.Bioformat.NONE, path,
                        description, status, 0, null, -1, null, null, parents, null, options, sessionId);
                break;
            case DIRECTORY_EXISTS:
                Query query = new Query()
                        .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid())
                        .append(FileDBAdaptor.QueryParams.PATH.key(), path);
                fileDataResult = fileDBAdaptor.get(study.getUid(), query, options, userId);
                fileDataResult.getEvents().add(new Event(Event.Type.WARNING, path, "Folder already existed"));
                break;
            case FILE_EXISTS:
            default:
                throw new CatalogException("A file with the same name of the folder already exists in Catalog");
        }

        return fileDataResult;
    }

    public OpenCGAResult<File> createFile(String studyStr, String path, String description, boolean parents, String content,
                                       String sessionId) throws CatalogException {
        ParamUtils.checkPath(path, "filePath");

        String userId = userManager.getUserId(sessionId);
        Study study = studyManager.resolveId(studyStr, userId);

        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        switch (checkPathExists(path, study.getUid())) {
            case FREE_PATH:
                return create(studyStr, File.Type.FILE, File.Format.PLAIN, File.Bioformat.UNKNOWN, path, description,
                        new File.FileStatus(File.FileStatus.READY), 0, null, -1, null, null, parents, content, new QueryOptions(),
                        sessionId);
            case FILE_EXISTS:
            case DIRECTORY_EXISTS:
            default:
                throw new CatalogException("A file or folder with the same name already exists in the path of Catalog");
        }
    }

    public OpenCGAResult<File> create(String studyStr, File.Type type, File.Format format, File.Bioformat bioformat, String path,
                                   String description, File.FileStatus status, long size, List<Sample> samples, long jobId,
                                   Map<String, Object> stats, Map<String, Object> attributes, boolean parents, String content,
                                   QueryOptions options, String sessionId) throws CatalogException {
        File file = new File(type, format, bioformat, path, description, status, size, samples, jobId, null, stats, attributes);
        return create(studyStr, file, parents, content, options, sessionId);
    }

    @Override
    public OpenCGAResult<File> create(String studyStr, File entry, QueryOptions options, String token) throws CatalogException {
        throw new NotImplementedException("Call to create passing parents and content variables");
    }

    public OpenCGAResult<File> create(String studyStr, File file, boolean parents, String content, QueryOptions options, String token)
            throws CatalogException {
        String userId = userManager.getUserId(token);
        Study study = studyManager.resolveId(studyStr, userId, StudyManager.INCLUDE_VARIABLE_SET);

        ObjectMap auditParams = new ObjectMap()
                .append("study", studyStr)
                .append("file", file)
                .append("parents", parents)
                .append("content", content)
                .append("options", options)
                .append("token", token);
        try {
            File parentFile = getParents(study.getUid(), file.getPath(), false, INCLUDE_FILE_URI_PATH).first();
            authorizationManager.checkFilePermission(study.getUid(), parentFile.getUid(), userId, FileAclEntry.FilePermissions.WRITE);

            OpenCGAResult<File> result = create(study, file, parents, content, options, token);
            auditManager.auditCreate(userId, Enums.Resource.FILE, file.getId(), file.getUuid(), study.getId(), study.getUuid(),
                    auditParams, new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));
            return result;
        } catch (CatalogException e) {
            auditManager.auditCreate(userId, Enums.Resource.FILE, file.getId(), "", study.getId(), study.getUuid(), auditParams,
                    new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()));
            throw e;
        }
    }

    void validateNewFile(Study study, File file, String sessionId, boolean overwrite) throws CatalogException {
        /** Check and set all the params and create a File object **/
        ParamUtils.checkObj(file, "File");
        ParamUtils.checkPath(file.getPath(), "path");
        file.setType(ParamUtils.defaultObject(file.getType(), File.Type.FILE));
        file.setFormat(ParamUtils.defaultObject(file.getFormat(), File.Format.PLAIN));
        file.setBioformat(ParamUtils.defaultObject(file.getBioformat(), File.Bioformat.NONE));
        file.setDescription(ParamUtils.defaultString(file.getDescription(), ""));
        file.setRelatedFiles(ParamUtils.defaultObject(file.getRelatedFiles(), ArrayList::new));
        file.setSamples(ParamUtils.defaultObject(file.getSamples(), ArrayList::new));
        file.setCreationDate(TimeUtils.getTime());
        file.setModificationDate(file.getCreationDate());
        file.setStatus(ParamUtils.defaultObject(file.getStatus(), new File.FileStatus(File.FileStatus.READY)));
        file.setStats(ParamUtils.defaultObject(file.getStats(), HashMap::new));
        file.setAttributes(ParamUtils.defaultObject(file.getAttributes(), HashMap::new));

        validateNewSamples(study, file, sessionId);

        if (file.getSize() < 0) {
            throw new CatalogException("Error: DiskUsage can't be negative!");
        }
        // TODO: Check why we suppose we have job uids
        if (file.getJob() != null && file.getJob().getUid() > 0 && !jobDBAdaptor.exists(file.getJob().getUid())) {
            throw new CatalogException("Job { id: " + file.getJob().getUid() + "} does not exist.");
        }

        // Fix path
        if (file.getType() == File.Type.DIRECTORY && !file.getPath().endsWith("/")) {
            file.setPath(file.getPath() + "/");
        }
        if (file.getType() == File.Type.FILE && file.getPath().endsWith("/")) {
            file.setPath(file.getPath().substring(0, file.getPath().length() - 1));
        }
        file.setName(Paths.get(file.getPath()).getFileName().toString());
        file.setId(file.getPath().replace("/", ":"));

        URI uri;
        try {
            if (file.getType() == File.Type.DIRECTORY) {
                uri = getFileUri(study.getUid(), file.getPath(), true);
            } else {
                uri = getFileUri(study.getUid(), file.getPath(), false);
            }
        } catch (URISyntaxException e) {
            throw new CatalogException(e);
        }
        file.setUri(uri);

        if (!overwrite) {
            // Check if it already exists
            Query query = new Query()
                    .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid())
                    .append(FileDBAdaptor.QueryParams.PATH.key(), file.getPath())
                    .append(FileDBAdaptor.QueryParams.STATUS_NAME.key(), "!=" + File.FileStatus.TRASHED + ";!=" + File.FileStatus.DELETED
                            + ";!=" + File.FileStatus.DELETING + ";!=" + File.FileStatus.PENDING_DELETE + ";!=" + File.FileStatus.REMOVED);
            if (fileDBAdaptor.count(query).getNumMatches() > 0) {
                logger.warn("The file '{}' already exists in catalog", file.getPath());
                throw new CatalogException("The file '" + file.getPath() + "' already exists in catalog");
            }
            query = new Query()
                    .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid())
                    .append(FileDBAdaptor.QueryParams.URI.key(), uri)
                    .append(FileDBAdaptor.QueryParams.STATUS_NAME.key(), "!=" + File.FileStatus.TRASHED + ";!=" + File.FileStatus.DELETED
                            + ";!=" + File.FileStatus.DELETING + ";!=" + File.FileStatus.PENDING_DELETE + ";!=" + File.FileStatus.REMOVED);
            OpenCGAResult<File> fileResult = fileDBAdaptor.get(query,
                    new QueryOptions(QueryOptions.INCLUDE, FileDBAdaptor.QueryParams.PATH.key()));
            if (fileResult.getNumResults() > 0) {
                logger.warn("The uri '{}' of the file is already in catalog but in path '{}'.", uri, fileResult.first().getPath());
                throw new CatalogException("The uri '" + uri + "' of the file is already in catalog but in path '"
                        + fileResult.first().getPath() + "'");
            }
        }

        boolean external = isExternal(study, file.getPath(), uri);
        file.setExternal(external);
        file.setRelease(studyManager.getCurrentRelease(study));

        validateNewAnnotationSets(study.getVariableSets(), file.getAnnotationSets());

        file.setUuid(UUIDUtils.generateOpenCGAUUID(UUIDUtils.Entity.FILE));
        checkHooks(file, study.getFqn(), HookConfiguration.Stage.CREATE);
    }

    private OpenCGAResult<File> register(Study study, File file, boolean parents, QueryOptions options, String sessionId)
            throws CatalogException {
        String userId = userManager.getUserId(sessionId);
        long studyId = study.getUid();

        //Find parent. If parents == true, create folders.
        String parentPath = getParentPath(file.getPath());

        long parentFileId = fileDBAdaptor.getId(studyId, parentPath);
        boolean newParent = false;
        if (parentFileId < 0 && StringUtils.isNotEmpty(parentPath)) {
            if (parents) {
                newParent = true;
                File parentFile = new File(File.Type.DIRECTORY, File.Format.NONE, File.Bioformat.NONE, parentPath, "",
                        new File.FileStatus(File.FileStatus.READY), 0, file.getSamples(), -1, null, Collections.emptyMap(),
                        Collections.emptyMap());
                validateNewFile(study, parentFile, sessionId, false);
                parentFileId = register(study, parentFile, parents, options, sessionId).first().getUid();
            } else {
                throw new CatalogDBException("Directory not found " + parentPath);
            }
        }

        //Check permissions
        if (parentFileId < 0) {
            throw new CatalogException("Unable to create file without a parent file");
        } else {
            if (!newParent) {
                //If parent has been created, for sure we have permissions to create the new file.
                authorizationManager.checkFilePermission(studyId, parentFileId, userId, FileAclEntry.FilePermissions.WRITE);
            }
        }

        fileDBAdaptor.insert(studyId, file, study.getVariableSets(), options);
        OpenCGAResult<File> queryResult = getFile(studyId, file.getUuid(), options);
        // We obtain the permissions set in the parent folder and set them to the file or folder being created
        OpenCGAResult<Map<String, List<String>>> allFileAcls = authorizationManager.getAllFileAcls(studyId, parentFileId, userId, false);
        // Propagate ACLs
        if (allFileAcls.getNumResults() > 0) {
            authorizationManager.replicateAcls(studyId, Arrays.asList(queryResult.first().getUid()), allFileAcls.getResults().get(0),
                    Enums.Resource.FILE);
        }

        matchUpVariantFiles(study.getFqn(), queryResult.getResults(), sessionId);

        return queryResult;
    }

    private OpenCGAResult<File> create(Study study, File file, boolean parents, String content, QueryOptions options, String sessionId)
            throws CatalogException {
        validateNewFile(study, file, sessionId, false);

        if (file.getType() == File.Type.FILE && StringUtils.isNotEmpty(content)) {
            CatalogIOManager ioManager = catalogIOManagerFactory.getDefault();
            // We set parents to true because the file has been successfully registered, which means the directories are already registered
            // in catalog
            ioManager.createDirectory(Paths.get(file.getUri()).getParent().toUri(), true);
            InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            ioManager.createFile(file.getUri(), inputStream);

            new FileMetadataReader(catalogManager).addMetadataInformation(study, file, sessionId);
            validateNewSamples(study, file, sessionId);
        }

        OpenCGAResult<File> result;
        try {
            result = register(study, file, parents, options, sessionId);
        } catch (CatalogException e) {
            if (file.getType() == File.Type.FILE && StringUtils.isNotEmpty(content)) {
                CatalogIOManager ioManager = catalogIOManagerFactory.getDefault();
                ioManager.deleteFile(file.getUri());
            }
            throw CatalogException.appendMessage(e, "Error registering file: ");
        }

        return result;
    }

    private void validateNewSamples(Study study, File file, String sessionId) throws CatalogException {
        if (file.getSamples() == null || file.getSamples().isEmpty()) {
            return;
        }

        String userId = catalogManager.getUserManager().getUserId(sessionId);

        List<String> sampleIdList = file.getSamples().stream().map(Sample::getId).collect(Collectors.toList());
        InternalGetDataResult<Sample> sampleResult = catalogManager.getSampleManager().internalGet(study.getUid(), sampleIdList,
                SampleManager.INCLUDE_SAMPLE_IDS, userId, true);

        List<Sample> sampleList = new ArrayList<>(file.getSamples().size());
        sampleList.addAll(sampleResult.getResults());
        for (InternalGetDataResult<Sample>.Missing missing : sampleResult.getMissing()) {
            Sample sample = new Sample().setId(missing.getId());
            catalogManager.getSampleManager().validateNewSample(study, sample, userId);
            sampleList.add(sample);
        }

        file.setSamples(sampleList);
    }

    /**
     * Upload a file in Catalog.
     *
     * @param studyStr        study where the file will be uploaded.
     * @param fileInputStream Input stream of the file to be uploaded.
     * @param file            File object containing at least the basic metadata necessary for a successful upload: path
     * @param overwrite       Overwrite the current file if any.
     * @param parents         boolean indicating whether unexisting parent folders should also be created automatically.
     * @param calculateChecksum boolean indicating whether to calculate the checksum of the uploaded file.
     * @param token       session id of the user performing the upload.
     * @return a OpenCGAResult with the file uploaded.
     * @throws CatalogException if the user does not have permissions or any other unexpected issue happens.
     */
    public OpenCGAResult<File> upload(String studyStr, InputStream fileInputStream, File file, boolean overwrite, boolean parents,
                                   boolean calculateChecksum, String token) throws CatalogException {
        // Check basic parameters
        ParamUtils.checkObj(fileInputStream, "fileInputStream");

        String userId = userManager.getUserId(token);
        Study study = studyManager.resolveId(studyStr, userId, StudyManager.INCLUDE_VARIABLE_SET);

        ObjectMap auditParams = new ObjectMap()
                .append("studyStr", studyStr)
                .append("file", file)
                .append("overwrite", overwrite)
                .append("parents", parents)
                .append("calculateChecksum", calculateChecksum)
                .append("token", token);
        try {
            validateNewFile(study, file, token, overwrite);

            File overwrittenFile = null;
            Query query = new Query(FileDBAdaptor.QueryParams.PATH.key(), file.getPath());
            OpenCGAResult<File> fileDataResult = fileDBAdaptor.get(query, QueryOptions.empty());
            if (fileDataResult.getNumResults() > 0) {
                if (overwrite) {
                    overwrittenFile = fileDataResult.first();
                } else {
                    throw new CatalogException("Path " + file.getPath() + " already in use");
                }
            }

            OpenCGAResult<File> parentFolders = getParents(study.getUid(), file.getPath(), false, QueryOptions.empty());
            if (parentFolders.getNumResults() == 0) {
                // There always must be at least the root folder
                throw new CatalogException("Unexpected error happened.");
            }

            // Check permissions over the most internal path
            authorizationManager.checkFilePermission(study.getUid(), parentFolders.first().getUid(), userId,
                    FileAclEntry.FilePermissions.UPLOAD);
            authorizationManager.checkFilePermission(study.getUid(), parentFolders.first().getUid(), userId,
                    FileAclEntry.FilePermissions.WRITE);

            // We obtain the basic studyPath where we will upload the file temporarily
            java.nio.file.Path studyPath = Paths.get(study.getUri());

            CatalogIOManager ioManager = catalogManager.getCatalogIOManagerFactory().getDefault();
            // We attempt to create it first because it may be that the parent directories were not created because they don't contain any
            // files yet

            if (parentFolders.first().getType() == File.Type.FILE && !overwrite) {
                throw new CatalogException("Cannot upload file in '" + file.getPath() + "'. " + parentFolders.first().getPath()
                        + "' is already an existing file path.");
            } else if (parentFolders.first().getType() == File.Type.DIRECTORY) {
                ioManager.createDirectory(parentFolders.first().getUri(), true);
            }
            ioManager.checkWritableUri(parentFolders.first().getUri());

            java.nio.file.Path tempFilePath = studyPath.resolve("tmp_" + file.getName()).resolve(file.getName());
            URI tempDirectory = tempFilePath.getParent().toUri();
            logger.info("Uploading file... Temporal file path: {}", tempFilePath.toString());

            // Create the temporal directory and upload the file
            try {
                if (!Files.exists(tempFilePath.getParent())) {
                    logger.debug("Creating temporal folder: {}", tempFilePath.getParent());
                    ioManager.createDirectory(tempDirectory, true);
                }

                // Start uploading the file to the temporal directory
                // Upload the file to a temporary folder
                Files.copy(fileInputStream, tempFilePath);
            } catch (Exception e) {
                logger.error("Error uploading file {}", file.getName(), e);

                // Clean temporal directory
                ioManager.deleteDirectory(tempDirectory);

                throw new CatalogException("Error uploading file " + file.getName(), e);
            }
            URI sourceUri = tempFilePath.toUri();

            // Move the file from the temporal directory
            try {
                // Create the directories where the file will be placed (if they weren't created before)
                ioManager.createDirectory(Paths.get(file.getUri()).getParent().toUri(), true);

                // Move the file to the final directory
                String checksum = new org.opencb.opencga.catalog.managers.FileUtils(catalogManager).move(sourceUri, file.getUri(),
                        overwrite, calculateChecksum);
                file.setChecksum(checksum);

                // Improve metadata information and extract samples if any
                new FileMetadataReader(catalogManager).addMetadataInformation(study, file, token);
                validateNewSamples(study, file, token);
            } catch (CatalogException e) {
                ioManager.deleteDirectory(tempDirectory);
                logger.error("Upload file: {}", e.getMessage(), e);
                throw new CatalogException("Upload file failed. Could not move the content to " + file.getUri() + ": " + e.getMessage());
            }

            // Register the file in catalog
            try {
                if (overwrittenFile != null) {
                    // We need to update the existing file document
                    ObjectMap params = new ObjectMap();
                    QueryOptions queryOptions = new QueryOptions();

                    params.put(FileDBAdaptor.QueryParams.SIZE.key(), file.getSize());
                    params.put(FileDBAdaptor.QueryParams.URI.key(), file.getUri());
                    params.put(FileDBAdaptor.QueryParams.EXTERNAL.key(), file.isExternal());
                    params.put(FileDBAdaptor.QueryParams.STATUS_NAME.key(), File.FileStatus.READY);
                    params.put(FileDBAdaptor.QueryParams.CHECKSUM.key(), file.getChecksum());

                    if (file.getSamples() != null && !file.getSamples().isEmpty()) {
                        params.put(FileDBAdaptor.QueryParams.SAMPLES.key(), file.getSamples());

                        // Set new samples
                        Map<String, Object> actionMap = new HashMap<>();
                        actionMap.put(FileDBAdaptor.QueryParams.SAMPLES.key(), ParamUtils.UpdateAction.SET.name());
                        queryOptions.put(Constants.ACTIONS, actionMap);
                    }
                    if (!file.getAttributes().isEmpty()) {
                        Map<String, Object> attributes = overwrittenFile.getAttributes();
                        attributes.putAll(file.getAttributes());
                        params.put(FileDBAdaptor.QueryParams.ATTRIBUTES.key(), attributes);
                    }
                    if (!file.getStats().isEmpty()) {
                        Map<String, Object> stats = overwrittenFile.getStats();
                        stats.putAll(file.getStats());
                        params.put(FileDBAdaptor.QueryParams.STATS.key(), stats);
                    }

                    fileDBAdaptor.update(overwrittenFile.getUid(), params, null, queryOptions);
                } else {
                    // We need to register a new file
                    register(study, file, parents, QueryOptions.empty(), token);
                }
            } catch (CatalogException e) {
                ioManager.deleteFile(file.getUri());
                logger.error("Upload file: {}", e.getMessage(), e);
                throw new CatalogException("Upload file failed. Could not register the file in the DB: " + e.getMessage());
            }

            auditManager.auditCreate(userId, Enums.Action.UPLOAD, Enums.Resource.FILE, file.getId(), file.getUuid(),
                    study.getId(), study.getUuid(), auditParams, new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));

            return fileDBAdaptor.get(query, QueryOptions.empty());
        } catch (CatalogException e) {
            auditManager.auditCreate(userId, Enums.Action.UPLOAD, Enums.Resource.FILE, file.getId(), "", study.getId(),
                    study.getUuid(), auditParams, new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()));
            throw e;
        }
    }

    @Deprecated
    public OpenCGAResult<File> get(Long fileId, QueryOptions options, String sessionId) throws CatalogException {
        return get(null, String.valueOf(fileId), options, sessionId);
    }

    public OpenCGAResult<FileTree> getTree(@Nullable String studyId, String fileId, Query query, QueryOptions options, int maxDepth,
                                        String token) throws CatalogException {
        long startTime = System.currentTimeMillis();

        options = ParamUtils.defaultObject(options, QueryOptions::new);
        query = ParamUtils.defaultObject(query, Query::new);

        String userId = userManager.getUserId(token);
        Study study = studyManager.resolveId(studyId, userId);

        ObjectMap auditParams = new ObjectMap()
                .append("studyId", studyId)
                .append("fileId", fileId)
                .append("query", query)
                .append("options", options)
                .append("maxDepth", maxDepth)
                .append("token", token);
        try {
            if (options.containsKey(QueryOptions.INCLUDE)) {
                // Add type to the queryOptions
                List<String> asStringListOld = options.getAsStringList(QueryOptions.INCLUDE);
                List<String> newList = new ArrayList<>(asStringListOld.size());
                for (String include : asStringListOld) {
                    newList.add(include);
                }
                newList.add(FileDBAdaptor.QueryParams.TYPE.key());
                options.put(QueryOptions.INCLUDE, newList);
            } else {
                // Avoid excluding type
                if (options.containsKey(QueryOptions.EXCLUDE)) {
                    List<String> asStringListOld = options.getAsStringList(QueryOptions.EXCLUDE);
                    if (asStringListOld.contains(FileDBAdaptor.QueryParams.TYPE.key())) {
                        // Remove type from exclude options
                        if (asStringListOld.size() > 1) {
                            List<String> toExclude = new ArrayList<>(asStringListOld.size() - 1);
                            for (String s : asStringListOld) {
                                if (!s.equalsIgnoreCase(FileDBAdaptor.QueryParams.TYPE.key())) {
                                    toExclude.add(s);
                                }
                            }
                            options.put(QueryOptions.EXCLUDE, StringUtils.join(toExclude.toArray(), ","));
                        } else {
                            options.remove(QueryOptions.EXCLUDE);
                        }
                    }
                }
            }

            File file = internalGet(study.getUid(), fileId, INCLUDE_FILE_IDS, userId).first();

            query.put(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid());

            // Check if we can obtain the file from the dbAdaptor properly.
            QueryOptions qOptions = new QueryOptions()
                    .append(QueryOptions.INCLUDE, Arrays.asList(FileDBAdaptor.QueryParams.PATH.key(), FileDBAdaptor.QueryParams.NAME.key(),
                            FileDBAdaptor.QueryParams.UID.key(), FileDBAdaptor.QueryParams.TYPE.key()));
            OpenCGAResult<File> fileDataResult = fileDBAdaptor.get(file.getUid(), qOptions);
            if (fileDataResult == null || fileDataResult.getNumResults() != 1) {
                throw new CatalogException("An error occurred with the database.");
            }

            // Check if the id does not correspond to a directory
            if (!fileDataResult.first().getType().equals(File.Type.DIRECTORY)) {
                throw new CatalogException("The file introduced is not a directory.");
            }

            // Call recursive method
            FileTree fileTree = getTree(fileDataResult.first(), query, options, maxDepth, study.getUid(), userId);

            int dbTime = (int) (System.currentTimeMillis() - startTime);
            int numResults = countFilesInTree(fileTree);

            auditManager.audit(userId, Enums.Action.TREE, Enums.Resource.FILE, file.getId(), file.getUuid(), study.getId(),
                    study.getUuid(), auditParams, new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));

            return new OpenCGAResult<>(dbTime, Collections.emptyList(), numResults, Collections.singletonList(fileTree), numResults);
        } catch (CatalogException e) {
            auditManager.audit(userId, Enums.Action.TREE, Enums.Resource.FILE, fileId, "", study.getId(), study.getUuid(),
                    auditParams, new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()));
            throw e;
        }
    }

    public OpenCGAResult<File> getFilesFromFolder(String folderStr, String studyStr, QueryOptions options, String sessionId)
            throws CatalogException {
        ParamUtils.checkObj(folderStr, "folder");
        String userId = userManager.getUserId(sessionId);
        Study study = studyManager.resolveId(studyStr, userId);

        File file = internalGet(study.getUid(), folderStr, new QueryOptions(QueryOptions.INCLUDE,
                Arrays.asList(FileDBAdaptor.QueryParams.PATH.key(), FileDBAdaptor.QueryParams.TYPE.key())), userId).first();

        options = ParamUtils.defaultObject(options, QueryOptions::new);

        if (!file.getType().equals(File.Type.DIRECTORY)) {
            throw new CatalogDBException("File {path:'" + file.getPath() + "'} is not a folder.");
        }
        Query query = new Query(FileDBAdaptor.QueryParams.DIRECTORY.key(), file.getPath());
        return search(studyStr, query, options, sessionId);
    }

    @Override
    public DBIterator<File> iterator(String studyStr, Query query, QueryOptions options, String sessionId) throws CatalogException {
        query = ParamUtils.defaultObject(query, Query::new);
        options = ParamUtils.defaultObject(options, QueryOptions::new);
        String userId = userManager.getUserId(sessionId);
        Study study = studyManager.resolveId(studyStr, userId);

        fixQueryObject(study, query, userId);
        query.put(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid());

        return fileDBAdaptor.iterator(study.getUid(), query, options, userId);
    }

    @Override
    public OpenCGAResult<File> search(String studyId, Query query, QueryOptions options, String token) throws CatalogException {
        query = ParamUtils.defaultObject(query, Query::new);
        Query finalQuery = new Query(query);
        options = ParamUtils.defaultObject(options, QueryOptions::new);

        String userId = userManager.getUserId(token);
        Study study = studyManager.resolveId(studyId, userId, new QueryOptions(QueryOptions.INCLUDE,
                StudyDBAdaptor.QueryParams.VARIABLE_SET.key()));

        ObjectMap auditParams = new ObjectMap()
                .append("studyId", studyId)
                .append("query", new Query(query))
                .append("options", options)
                .append("token", token);
        try {
            // Fix query if it contains any annotation
            AnnotationUtils.fixQueryAnnotationSearch(study, finalQuery);
            AnnotationUtils.fixQueryOptionAnnotation(options);
            fixQueryObject(study, finalQuery, userId);
            finalQuery.append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid());

            OpenCGAResult<File> queryResult = fileDBAdaptor.get(study.getUid(), finalQuery, options, userId);
            auditManager.auditSearch(userId, Enums.Resource.FILE, study.getId(), study.getUuid(), auditParams,
                    new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));

            return queryResult;
        } catch (CatalogException e) {
            auditManager.auditSearch(userId, Enums.Resource.FILE, study.getId(), study.getUuid(), auditParams,
                    new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()));
            throw e;
        }
    }

    void fixQueryObject(Study study, Query query, String user) throws CatalogException {
        if (StringUtils.isNotEmpty(query.getString(FileDBAdaptor.QueryParams.ID.key()))) {
            OpenCGAResult<File> queryResult = internalGet(study.getUid(), query.getAsStringList(FileDBAdaptor.QueryParams.ID.key()),
                    INCLUDE_FILE_IDS, user, true);
            query.remove(FileDBAdaptor.QueryParams.ID.key());
            query.put(FileDBAdaptor.QueryParams.UID.key(), queryResult.getResults().stream().map(File::getUid)
                    .collect(Collectors.toList()));
        }

        // The samples introduced could be either ids or names. As so, we should use the smart resolutor to do this.
        if (StringUtils.isNotEmpty(query.getString(FileDBAdaptor.QueryParams.SAMPLES.key()))) {
            OpenCGAResult<Sample> sampleDataResult = catalogManager.getSampleManager().internalGet(study.getUid(),
                    query.getAsStringList(FileDBAdaptor.QueryParams.SAMPLES.key()), SampleManager.INCLUDE_SAMPLE_IDS, user, true);
            query.put(FileDBAdaptor.QueryParams.SAMPLE_UIDS.key(), sampleDataResult.getResults().stream().map(Sample::getUid)
                    .collect(Collectors.toList()));
            query.remove(FileDBAdaptor.QueryParams.SAMPLES.key());
        }
    }

    @Override
    public OpenCGAResult<File> count(String studyId, Query query, String token) throws CatalogException {
        query = ParamUtils.defaultObject(query, Query::new);

        String userId = userManager.getUserId(token);
        Study study = studyManager.resolveId(studyId, userId, new QueryOptions(QueryOptions.INCLUDE,
                StudyDBAdaptor.QueryParams.VARIABLE_SET.key()));

        ObjectMap auditParams = new ObjectMap()
                .append("studyId", studyId)
                .append("query", new Query(query))
                .append("token", token);
        try {
            // Fix query if it contains any annotation
            AnnotationUtils.fixQueryAnnotationSearch(study, query);
            // The samples introduced could be either ids or names. As so, we should use the smart resolutor to do this.
            fixQueryObject(study, query, userId);

            query.append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid());
            OpenCGAResult<Long> queryResultAux = fileDBAdaptor.count(study.getUid(), query, userId,
                    StudyAclEntry.StudyPermissions.VIEW_FILES);

            auditManager.auditCount(userId, Enums.Resource.FILE, study.getId(), study.getUuid(), auditParams,
                    new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));

            return new OpenCGAResult<>(queryResultAux.getTime(), queryResultAux.getEvents(), 0, Collections.emptyList(),
                    queryResultAux.getNumMatches());
        } catch (CatalogException e) {
            auditManager.auditCount(userId, Enums.Resource.FILE, study.getId(), study.getUuid(), auditParams,
                    new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()));
            throw e;
        }
    }

    @Override
    public OpenCGAResult delete(String studyStr, List<String> fileIds, ObjectMap params, String token) throws CatalogException {
        return delete(studyStr, fileIds, params, false, token);
    }

    public OpenCGAResult delete(String studyStr, List<String> fileIds, ObjectMap params, boolean ignoreException, String token)
            throws CatalogException {
        String userId = catalogManager.getUserManager().getUserId(token);
        Study study = studyManager.resolveId(studyStr, userId, new QueryOptions(QueryOptions.INCLUDE,
                StudyDBAdaptor.QueryParams.VARIABLE_SET.key()));

        String operationUuid = UUIDUtils.generateOpenCGAUUID(UUIDUtils.Entity.AUDIT);

        ObjectMap auditParams = new ObjectMap()
                .append("study", studyStr)
                .append("fileIds", fileIds)
                .append("params", params)
                .append("ignoreException", ignoreException)
                .append("token", token);

        boolean checkPermissions;
        try {
            // If the user is the owner or the admin, we won't check if he has permissions for every single entry
            checkPermissions = !authorizationManager.checkIsOwnerOrAdmin(study.getUid(), userId);
        } catch (CatalogException e) {
            auditManager.auditDelete(operationUuid, userId, Enums.Resource.FILE, "", "", study.getId(), study.getUuid(),
                    auditParams, new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()));
            throw e;
        }

        // We need to avoid processing subfolders or subfiles of an already processed folder independently
        Set<String> processedPaths = new HashSet<>();
        boolean physicalDelete = params.getBoolean(SKIP_TRASH, false) || params.getBoolean(DELETE_EXTERNAL_FILES, false);

        OpenCGAResult<File> result = OpenCGAResult.empty();
        for (String id : fileIds) {
            String fileId = id;
            String fileUuid = "";

            try {
                OpenCGAResult<File> internalResult = internalGet(study.getUid(), id, INCLUDE_FILE_URI_PATH, userId);
                if (internalResult.getNumResults() == 0) {
                    throw new CatalogException("File '" + id + "' not found");
                }
                File file = internalResult.first();
                // We set the proper values for the audit
                fileId = file.getId();
                fileUuid = file.getUuid();

                if (subpathInPath(file.getPath(), processedPaths)) {
                    // We skip this folder because it is a subfolder or subfile within an already processed folder
                    continue;
                }

                OpenCGAResult updateResult = delete(study, file, params, checkPermissions, physicalDelete, userId);
                result.append(updateResult);

                // We store the processed path as is
                if (file.getType() == File.Type.DIRECTORY) {
                    processedPaths.add(file.getPath());
                }

                auditManager.auditDelete(operationUuid, userId, Enums.Resource.FILE, file.getId(), file.getUuid(), study.getId(),
                        study.getUuid(), auditParams, new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));
            } catch (CatalogException e) {
                Event event = new Event(Event.Type.ERROR, fileId, e.getMessage());
                result.getEvents().add(event);

                logger.error("Could not delete file {}: {}", fileId, e.getMessage(), e);
                auditManager.auditDelete(operationUuid, userId, Enums.Resource.FILE, fileId, fileUuid, study.getId(),
                        study.getUuid(), auditParams, new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()));
            }
        }

        return endResult(result, ignoreException);
    }

    @Override
    public OpenCGAResult delete(String studyStr, Query query, ObjectMap params, String token) throws CatalogException {
        return delete(studyStr, query, params, false, token);
    }

    public OpenCGAResult delete(String studyStr, Query query, ObjectMap params, boolean ignoreException, String token)
            throws CatalogException {
        Query finalQuery = new Query(ParamUtils.defaultObject(query, Query::new));
        params = ParamUtils.defaultObject(params, ObjectMap::new);

        OpenCGAResult dataResult = OpenCGAResult.empty();

        String userId = userManager.getUserId(token);
        Study study = studyManager.resolveId(studyStr, userId, new QueryOptions(QueryOptions.INCLUDE,
                StudyDBAdaptor.QueryParams.VARIABLE_SET.key()));

        StopWatch watch = StopWatch.createStarted();
        String operationUuid = UUIDUtils.generateOpenCGAUUID(UUIDUtils.Entity.AUDIT);

        ObjectMap auditParams = new ObjectMap()
                .append("study", studyStr)
                .append("query", new Query(query))
                .append("params", params)
                .append("ignoreException", ignoreException)
                .append("token", token);

        // If the user is the owner or the admin, we won't check if he has permissions for every single entry
        boolean checkPermissions;

        // We try to get an iterator containing all the files to be deleted
        DBIterator<File> fileIterator;
        try {
            // Fix query if it contains any annotation
            AnnotationUtils.fixQueryAnnotationSearch(study, finalQuery);
            fixQueryObject(study, finalQuery, userId);
            finalQuery.append(SampleDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid());

            // If the user is the owner or the admin, we won't check if he has permissions for every single entry
            checkPermissions = !authorizationManager.checkIsOwnerOrAdmin(study.getUid(), userId);

            fileIterator = fileDBAdaptor.iterator(study.getUid(), finalQuery, INCLUDE_FILE_URI_PATH, userId);
        } catch (CatalogException e) {
            auditManager.auditDelete(operationUuid, userId, Enums.Resource.FILE, "", "", study.getId(), study.getUuid(), auditParams,
                    new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));
            throw e;
        }

        // We need to avoid processing subfolders or subfiles of an already processed folder independently
        Set<String> processedPaths = new HashSet<>();
        boolean physicalDelete = params.getBoolean(SKIP_TRASH, false) || params.getBoolean(DELETE_EXTERNAL_FILES, false);

        long numMatches = 0;

        while (fileIterator.hasNext()) {
            File file = fileIterator.next();

            if (subpathInPath(file.getPath(), processedPaths)) {
                // We skip this folder because it is a subfolder or subfile within an already processed folder
                continue;
            }

            try {
                OpenCGAResult result = delete(study, file, params, checkPermissions, physicalDelete, userId);
                dataResult.append(result);

                // We store the processed path as is
                if (file.getType() == File.Type.DIRECTORY) {
                    processedPaths.add(file.getPath());
                }

                auditManager.auditDelete(operationUuid, userId, Enums.Resource.FILE, file.getId(), file.getUuid(), study.getId(),
                        study.getUuid(), auditParams, new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));
            } catch (CatalogException e) {
                String errorMsg;

                if (file.getType() == File.Type.FILE) {
                    errorMsg = "Cannot delete file " + file.getPath() + ": " + e.getMessage();
                } else {
                    errorMsg = "Cannot delete folder " + file.getPath() + ": " + e.getMessage();
                }
                dataResult.getEvents().add(new Event(Event.Type.ERROR, file.getPath(), e.getMessage()));

                logger.error(errorMsg, e);
                auditManager.auditDelete(operationUuid, userId, Enums.Resource.FILE, file.getId(), file.getUuid(), study.getId(),
                        study.getUuid(), auditParams, new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()));
            }
        }

        dataResult.setTime((int) watch.getTime(TimeUnit.MILLISECONDS));
        dataResult.setNumMatches(dataResult.getNumMatches() + numMatches);

        return endResult(dataResult, ignoreException);
    }

    private OpenCGAResult delete(Study study, File file, ObjectMap params, boolean checkPermissions, boolean physicalDelete, String userId)
            throws CatalogException {
        if (checkPermissions) {
            authorizationManager.checkFilePermission(study.getUid(), file.getUid(), userId, FileAclEntry.FilePermissions.DELETE);
        }

        // Check if the file or the folder plus any nested files/folders can be deleted
        List<File> fileList = checkCanDeleteFile(study.getFqn(), file, physicalDelete, userId);

        // Remove job references
//                try {
//                    removeJobReferences(study.getUid(), fileList);
//                } catch (CatalogException e) {
//                    logger.error("Could not remove job references: {}", e.getMessage(), e);
//                    throw new CatalogException("Could not remove job references: " + e.getMessage(), e);
//                }


        // Remove the index references in case it is a transformed file or folder
//                try {
//                    updateIndexStatusAfterDeletionOfTransformedFile(study.getUid(), file);
//                } catch (CatalogException e) {
//                    logger.error("Could not remove relation references: {}", e.getMessage(), e);
//                    throw new CatalogException("Could not remove relation references: " + e.getMessage(), e);
//                }

        if (file.isExternal()) {
            // unlink
            return unlink(study.getUid(), file);
        } else {
            // local
            if (physicalDelete) {
                return physicalDelete(study.getUid(), file, params.getBoolean(FORCE_DELETE, false));
            } else {
                return sendToTrash(study.getUid(), file);
            }
        }
    }

    /**
     * Given a registered folder in OpenCGA, it will scan its contents to register any nested file/folder that might not be registered.
     *
     * @param studyId  Study id.
     * @param folderId Folder id, path or uuid.
     * @param token    Token of the user. The user will need to have read and write access to the folderId.
     * @return An OpenCGAResult containing the number of files that have been added and the full list of files registered (old and new).
     * @throws CatalogException If there is any of the following errors:
     *                          Study not found, folderId does not exist or user does not have permissions.
     */
    public OpenCGAResult<File> syncUntrackedFiles(String studyId, String folderId, String token) throws CatalogException {
        return syncUntrackedFiles(studyId, folderId, uri -> true, token);
    }

    public OpenCGAResult<File> syncUntrackedFiles(String studyId, String folderId, Predicate<URI> filter, String token)
            throws CatalogException {
        String userId = userManager.getUserId(token);
        Study study = studyManager.resolveId(studyId, userId);

        File folder = internalGet(study.getUid(), folderId, INCLUDE_FILE_URI_PATH, userId).first();

        if (folder.getType() == File.Type.FILE) {
            throw new CatalogException("Provided folder '" + folderId + "' is actually a file");
        }

        authorizationManager.checkFilePermission(study.getUid(), folder.getUid(), userId, FileAclEntry.FilePermissions.WRITE);

        CatalogIOManager ioManager = catalogIOManagerFactory.get(folder.getUri());
        Iterator<URI> iterator = ioManager.listFilesStream(folder.getUri()).iterator();

        if (filter == null) {
            filter = uri -> true;
        }

        long numMatches = 0;

        OpenCGAResult<File> result = OpenCGAResult.empty();
        List<File> fileList = new ArrayList<>();
        List<Event> eventList = new ArrayList<>();
        while (iterator.hasNext()) {
            URI fileUri = iterator.next().normalize();

            numMatches++;

            if (!filter.test(fileUri)) {
                continue;
            }

            String relativeFilePath = folder.getUri().relativize(fileUri).getPath();
            String finalCatalogPath = Paths.get(folder.getPath()).resolve(relativeFilePath).toString();
            if (relativeFilePath.endsWith("/") && !finalCatalogPath.endsWith("/")) {
                finalCatalogPath += "/";
            }

            try {
                File registeredFile = internalGet(study.getUid(), finalCatalogPath, INCLUDE_FILE_URI_PATH, userId).first();
                if (!registeredFile.getUri().equals(fileUri)) {
                    eventList.add(new Event(Event.Type.WARNING, registeredFile.getPath(), "The uri registered in Catalog '"
                            + registeredFile.getUri().getPath() + "' for the path does not match the uri that would have been synced '"
                            + fileUri.getPath() + "'"));
                }
                fileList.add(registeredFile);
            } catch (CatalogException e) {
                File file = registerFile(study, finalCatalogPath, fileUri, token).first();

                result.setNumInserted(result.getNumInserted() + 1);
                fileList.add(file);
            }
        }
        result.setNumMatches(numMatches);
        result.setEvents(eventList);
        result.setResults(fileList);
        result.setNumResults(fileList.size());

        return result;
    }

    public OpenCGAResult<File> unlink(@Nullable String studyId, String fileId, String token) throws CatalogException {
        String userId = userManager.getUserId(token);
        Study study = studyManager.resolveId(studyId, userId);

        ObjectMap auditParams = new ObjectMap()
                .append("", studyId)
                .append("", fileId)
                .append("", token);

        try {
            ParamUtils.checkParameter(fileId, "File");

            File file = internalGet(study.getUid(), fileId, QueryOptions.empty(), userId).first();

            // Check 2. User has the proper permissions to delete the file.
            authorizationManager.checkFilePermission(study.getUid(), file.getUid(), userId, FileAclEntry.FilePermissions.DELETE);

            // Check 3.
            if (!file.isExternal()) {
                throw new CatalogException("Only previously linked files can be unlinked. Please, use delete instead.");
            }

            // Check if the file can be deleted
            List<File> fileList = checkCanDeleteFile(studyId, file, false, userId);

            // Remove job references
            try {
                removeJobReferences(study.getUid(), fileList);
            } catch (CatalogException e) {
                logger.error("Could not remove job references: {}", e.getMessage(), e);
                throw new CatalogException("Could not remove job references: " + e.getMessage(), e);
            }

            // Remove the index references in case it is a transformed file or folder
            try {
                updateIndexStatusAfterDeletionOfTransformedFile(study.getUid(), file);
            } catch (CatalogException e) {
                logger.error("Could not remove relation references: {}", e.getMessage(), e);
                throw new CatalogException("Could not remove relation references: " + e.getMessage(), e);
            }

            unlink(study.getUid(), file);

            Query query = new Query()
                    .append(FileDBAdaptor.QueryParams.UID.key(), file.getUid())
                    .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid());
            OpenCGAResult<File> result = fileDBAdaptor.get(study.getUid(), query, new QueryOptions(), userId);
            auditManager.audit(userId, Enums.Action.UNLINK, Enums.Resource.FILE, file.getId(), file.getUuid(), study.getId(),
                    study.getUuid(), auditParams, new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));

            return result;
        } catch (CatalogException e) {
            auditManager.audit(userId, Enums.Action.UNLINK, Enums.Resource.FILE, fileId, "", study.getId(), study.getUuid(),
                    auditParams, new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()));
            throw e;
        }
    }

    /**
     * This method sets the status of the file to PENDING_DELETE in all cases and does never remove a file from the file system (performed
     * by the daemon).
     * However, it applies the following changes:
     * - Status -> PENDING_DELETE
     * - Path -> Renamed to {path}__DELETED_{time}
     * - URI -> File or folder name from the file system renamed to {name}__DELETED_{time}
     * URI in the database updated accordingly
     *
     * @param studyId study id.
     * @param file    file or folder.
     * @return a OpenCGAResult object.
     */
    private OpenCGAResult physicalDelete(long studyId, File file, boolean forceDelete) throws CatalogException {
//        StopWatch watch = StopWatch.createStarted();

        String currentStatus = file.getStatus().getName();
        if (File.FileStatus.DELETED.equals(currentStatus)) {
            throw new CatalogException("The file was already deleted");
        }
//        if (File.FileStatus.PENDING_DELETE.equals(currentStatus) && !forceDelete) {
        if (File.FileStatus.PENDING_DELETE.equals(currentStatus)) {
            throw new CatalogException("The file was already pending for deletion");
        }
        if (File.FileStatus.DELETING.equals(currentStatus)) {
            throw new CatalogException("The file is already being deleted");
        }

        return fileDBAdaptor.delete(file, File.FileStatus.PENDING_DELETE);


//        URI fileUri = getUri(file);
//        CatalogIOManager ioManager = catalogIOManagerFactory.get(fileUri);
//
//        // Set the path suffix to DELETED
//        String suffixName = INTERNAL_DELIMITER + File.FileStatus.DELETED + "_" + TimeUtils.getTime();
//
//        long numMatched = 0;
//        long numModified = 0;
//        List<OpenCGAResult.Fail> failedList = new ArrayList<>();
//
//        if (file.getType() == File.Type.FILE) {
//            logger.debug("Deleting physical file {}" + file.getPath());
//
//            numMatched += 1;
//
//            try {
//                // 1. Set the file status to deleting
//                ObjectMap update = new ObjectMap()
//                        .append(FileDBAdaptor.QueryParams.STATUS_NAME.key(), File.FileStatus.DELETING)
//                        .append(FileDBAdaptor.QueryParams.PATH.key(), file.getPath() + suffixName);
//
//                fileDBAdaptor.update(file.getUid(), update, QueryOptions.empty());
//
//                // 2. Delete the file from disk
//                try {
//                    Files.delete(Paths.get(fileUri));
//                } catch (IOException e) {
//                    logger.error("{}", e.getMessage(), e);
//
//                    // We rollback and leave the file/folder in PENDING_DELETE status
//                    update = new ObjectMap(FileDBAdaptor.QueryParams.STATUS_NAME.key(), File.FileStatus.PENDING_DELETE);
//                    fileDBAdaptor.update(file.getUid(), update, QueryOptions.empty());
//
//                    throw new CatalogException("Could not delete physical file/folder: " + e.getMessage(), e);
//                }
//
//                // 3. Update the file status in the database. Set to delete
//                update = new ObjectMap(FileDBAdaptor.QueryParams.STATUS_NAME.key(), File.FileStatus.DELETED);
//                fileDBAdaptor.update(file.getUid(), update, QueryOptions.empty());
//
//                numModified += 1;
//            } catch (CatalogException e) {
//                failedList.add(new OpenCGAResult.Fail(file.getId(), e.getMessage()));
//            }
//        } else {
//            logger.debug("Starting physical deletion of folder {}", file.getId());
//
//            // Rename the directory in the filesystem.
//            URI newURI;
//            String basePath = Paths.get(file.getPath()).toString();
//            String suffixedPath;
//
//            if (!File.FileStatus.PENDING_DELETE.equals(currentStatus)) {
//                try {
//                    newURI = UriUtils.createDirectoryUri(Paths.get(fileUri).toString() + suffixName);
//                } catch (URISyntaxException e) {
//                    logger.error("URI exception: {}", e.getMessage(), e);
//                    throw new CatalogException("URI exception: " + e.getMessage(), e);
//                }
//
//                logger.debug("Renaming {} to {}", fileUri.toString(), newURI.toString());
//                ioManager.rename(fileUri, newURI);
//
//                suffixedPath = basePath + suffixName;
//            } else {
//                // newURI is actually = to fileURI
//                newURI = fileUri;
//
//                // suffixedPath = basePath
//                suffixedPath = basePath;
//            }
//
//            // Obtain all files and folders within the folder
//            Query query = new Query()
//                    .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), studyId)
//                    .append(FileDBAdaptor.QueryParams.PATH.key(), "~^" + file.getPath() + "*")
//                    .append(FileDBAdaptor.QueryParams.STATUS_NAME.key(), GET_NON_DELETED_FILES);
//            logger.debug("Looking for files and folders inside {} to mark as {}", file.getPath(), forceDelete
//                    ? File.FileStatus.DELETED : File.FileStatus.PENDING_DELETE);
//
//            QueryOptions options = new QueryOptions();
//            if (forceDelete) {
//                options.append(QueryOptions.SORT, FileDBAdaptor.QueryParams.PATH.key())
//                        .append(QueryOptions.ORDER, QueryOptions.DESCENDING);
//            }
//            DBIterator<File> iterator = fileDBAdaptor.iterator(query, options);
//
//            while (iterator.hasNext()) {
//                File auxFile = iterator.next();
//                numMatched += 1;
//
//                String newPath;
//                String newUri;
//
//                if (!File.FileStatus.PENDING_DELETE.equals(currentStatus)) {
//                    // Edit the PATH
//                    newPath = auxFile.getPath().replaceFirst(basePath, suffixedPath);
//                    newUri = auxFile.getUri().toString().replaceFirst(fileUri.toString(), newURI.toString());
//                } else {
//                    newPath = auxFile.getPath();
//                    newUri = auxFile.getUri().toString();
//                }
//
//                try {
//                    if (!forceDelete) {
//                        // Deferred deletion
//                        logger.debug("Replacing old uri {} for {}, old path {} for {}, and setting the status to {}",
//                                auxFile.getUri().toString(), newUri, auxFile.getPath(), newPath, File.FileStatus.PENDING_DELETE);
//
//                        ObjectMap updateParams = new ObjectMap()
//                                .append(FileDBAdaptor.QueryParams.STATUS_NAME.key(), File.FileStatus.PENDING_DELETE)
//                                .append(FileDBAdaptor.QueryParams.URI.key(), newUri)
//                                .append(FileDBAdaptor.QueryParams.PATH.key(), newPath);
//                        fileDBAdaptor.update(auxFile.getUid(), updateParams, QueryOptions.empty());
//                    } else {
//                        // We delete the files and folders now
//
//                        // 1. Set the file status to deleting
//                        ObjectMap update = new ObjectMap()
//                                .append(FileDBAdaptor.QueryParams.STATUS_NAME.key(), File.FileStatus.DELETING)
//                                .append(FileDBAdaptor.QueryParams.URI.key(), newUri)
//                                .append(FileDBAdaptor.QueryParams.PATH.key(), newPath);
//                        fileDBAdaptor.update(auxFile.getUid(), update, QueryOptions.empty());
//
//                        // 2. Delete the file from disk
//                        try {
//                            Files.delete(Paths.get(newUri.replaceFirst("file://", "")));
//                        } catch (IOException e) {
//                            logger.error("{}", e.getMessage(), e);
//
//                            // We rollback and leave the file/folder in PENDING_DELETE status
//                            update = new ObjectMap(FileDBAdaptor.QueryParams.STATUS_NAME.key(), File.FileStatus.PENDING_DELETE);
//                            fileDBAdaptor.update(auxFile.getUid(), update, QueryOptions.empty());
//
//                            throw new CatalogException("Could not delete physical file/folder: " + e.getMessage(), e);
//                        }
//
//                        // 3. Update the file status in the database. Set to delete
//                        update = new ObjectMap(FileDBAdaptor.QueryParams.STATUS_NAME.key(), File.FileStatus.DELETED);
//                        fileDBAdaptor.update(auxFile.getUid(), update, QueryOptions.empty());
//                    }
//                    numModified += 1;
//                } catch (CatalogException e) {
//                    failedList.add(new OpenCGAResult.Fail(auxFile.getId(), e.getMessage()));
//                }
//            }
//        }
//
//        return new OpenCGAResult("delete", (int) watch.getTime(TimeUnit.MILLISECONDS), numMatched, numModified, failedList, null, null);
    }

    private OpenCGAResult sendToTrash(long studyId, File file) throws CatalogDBException {
        // It doesn't really matter if file is a file or a directory. I can directly set the status of the file or the directory +
        // subfiles and subdirectories doing a single query as I don't need to rename anything
        // Obtain all files within the folder
//        Query query = new Query()
//                .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), studyId)
//                .append(FileDBAdaptor.QueryParams.PATH.key(), "~^" + file.getPath() + "*")
//                .append(FileDBAdaptor.QueryParams.STATUS_NAME.key(), GET_NON_DELETED_FILES);
//        ObjectMap params = new ObjectMap()
//                .append(FileDBAdaptor.QueryParams.STATUS_NAME.key(), File.FileStatus.TRASHED);
//
//        return fileDBAdaptor.update(query, params, QueryOptions.empty()).setId("trash");
        return fileDBAdaptor.delete(file, File.FileStatus.TRASHED);
    }

    private OpenCGAResult unlink(long studyId, File file) throws CatalogDBException {
//        StopWatch watch = StopWatch.createStarted();

        OpenCGAResult unlink = fileDBAdaptor.delete(file, File.FileStatus.REMOVED);
        return unlink;

//        String suffixName = INTERNAL_DELIMITER + File.FileStatus.REMOVED + "_" + TimeUtils.getTime();
//
//        // Set the new path
//        String basePath = Paths.get(file.getPath()).toString();
//        String suffixedPath = basePath + suffixName;
//
//        long numMatched = 0;
//        long numModified = 0;
//
//        if (file.getType() == File.Type.FILE) {
//            numMatched += 1;
//
//            ObjectMap params = new ObjectMap()
//                    .append(FileDBAdaptor.QueryParams.PATH.key(), file.getPath().replaceFirst(basePath, suffixedPath))
//                    .append(FileDBAdaptor.QueryParams.STATUS_NAME.key(), File.FileStatus.REMOVED);
//
//            logger.debug("Unlinking file {}", file.getPath());
//            fileDBAdaptor.update(file.getUid(), params, QueryOptions.empty());
//
//            numModified += 1;
//        } else {
//            // Obtain all files within the folder
//            Query query = new Query()
//                    .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), studyId)
//                    .append(FileDBAdaptor.QueryParams.PATH.key(), "~^" + file.getPath() + "*")
//                    .append(FileDBAdaptor.QueryParams.STATUS_NAME.key(), GET_NON_DELETED_FILES);
//
//            logger.debug("Looking for files and folders inside {} to unlink", file.getPath());
//            DBIterator<File> iterator = fileDBAdaptor.iterator(query, new QueryOptions());
//
//            while (iterator.hasNext()) {
//                File auxFile = iterator.next();
//                numMatched += 1;
//
//                ObjectMap updateParams = new ObjectMap()
//                        .append(FileDBAdaptor.QueryParams.PATH.key(), auxFile.getPath().replaceFirst(basePath, suffixedPath))
//                        .append(FileDBAdaptor.QueryParams.STATUS_NAME.key(), File.FileStatus.REMOVED);
//
//                fileDBAdaptor.update(auxFile.getUid(), updateParams, QueryOptions.empty());
//
//                numModified += 1;
//            }
//        }
//
//        return new OpenCGAResult("unlink", (int) watch.getTime(TimeUnit.MILLISECONDS), numMatched, numModified, null, null, null);
    }

    private boolean subpathInPath(String subpath, Set<String> pathSet) {
        String[] split = StringUtils.split(subpath, "/");
        String auxPath = "";
        for (String s : split) {
            auxPath += s + "/";
            if (pathSet.contains(auxPath)) {
                return true;
            }
        }
        return false;
    }

    public OpenCGAResult<File> updateAnnotations(String studyStr, String fileStr, String annotationSetId,
                                              Map<String, Object> annotations, ParamUtils.CompleteUpdateAction action,
                                              QueryOptions options, String token) throws CatalogException {
        if (annotations == null || annotations.isEmpty()) {
            throw new CatalogException("Missing array of annotations.");
        }
        FileUpdateParams updateParams = new FileUpdateParams()
                .setAnnotationSets(Collections.singletonList(new AnnotationSet(annotationSetId, "", annotations)));
        options = ParamUtils.defaultObject(options, QueryOptions::new);
        options.put(Constants.ACTIONS, new ObjectMap(AnnotationSetManager.ANNOTATIONS, action));

        return update(studyStr, fileStr, updateParams, options, token);
    }

    public OpenCGAResult<File> removeAnnotations(String studyStr, String fileStr, String annotationSetId,
                                              List<String> annotations, QueryOptions options, String token) throws CatalogException {
        return updateAnnotations(studyStr, fileStr, annotationSetId, new ObjectMap("remove", StringUtils.join(annotations, ",")),
                ParamUtils.CompleteUpdateAction.REMOVE, options, token);
    }

    public OpenCGAResult<File> resetAnnotations(String studyStr, String fileStr, String annotationSetId, List<String> annotations,
                                             QueryOptions options, String token) throws CatalogException {
        return updateAnnotations(studyStr, fileStr, annotationSetId, new ObjectMap("reset", StringUtils.join(annotations, ",")),
                ParamUtils.CompleteUpdateAction.RESET, options, token);
    }

    public OpenCGAResult<File> update(String studyStr, Query query, FileUpdateParams updateParams, QueryOptions options, String token)
            throws CatalogException {
        return update(studyStr, query, updateParams, false, options, token);
    }

    public OpenCGAResult<File> update(String studyStr, Query query, FileUpdateParams updateParams, boolean ignoreException,
                                      QueryOptions options, String token) throws CatalogException {
        String userId = userManager.getUserId(token);
        Study study = studyManager.resolveId(studyStr, userId, StudyManager.INCLUDE_VARIABLE_SET);

        String operationId = UUIDUtils.generateOpenCGAUUID(UUIDUtils.Entity.AUDIT);

        ObjectMap auditParams = new ObjectMap()
                .append("study", studyStr)
                .append("query", query)
                .append("updateParams", updateParams != null ? updateParams.getUpdateMap() : null)
                .append("ignoreException", ignoreException)
                .append("options", options)
                .append("token", token);

        Query finalQuery = new Query(ParamUtils.defaultObject(query, Query::new));

        DBIterator<File> iterator;
        try {
            // Fix query if it contains any annotation
            AnnotationUtils.fixQueryAnnotationSearch(study, finalQuery);
            fixQueryObject(study, finalQuery, userId);
            finalQuery.append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid());

            iterator = fileDBAdaptor.iterator(study.getUid(), finalQuery, EXCLUDE_FILE_ATTRIBUTES, userId);
        } catch (CatalogException e) {
            auditManager.auditUpdate(operationId, userId, Enums.Resource.FILE, "", "", study.getId(), study.getUuid(),
                    auditParams, new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()));
            throw e;
        }

        OpenCGAResult<File> result = OpenCGAResult.empty();
        while (iterator.hasNext()) {
            File file = iterator.next();
            try {
                OpenCGAResult<File> updateResult = update(study, file, updateParams, options, userId, token);
                result.append(updateResult);

                auditManager.auditUpdate(userId, Enums.Resource.FILE, file.getId(), file.getUuid(), study.getId(), study.getUuid(),
                        auditParams, new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));
            } catch (CatalogException e) {
                Event event = new Event(Event.Type.ERROR, file.getId(), e.getMessage());
                result.getEvents().add(event);

                logger.error("Cannot update file {}: {}", file.getId(), e.getMessage());
                auditManager.auditUpdate(operationId, userId, Enums.Resource.FILE, file.getId(), file.getUuid(), study.getId(),
                        study.getUuid(), auditParams, new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()));
            }
        }

        String ownerId = studyDBAdaptor.getOwnerId(study.getUid());
        userDBAdaptor.updateUserLastModified(ownerId);

        return endResult(result, ignoreException);
    }

    public OpenCGAResult<File> update(String studyStr, String fileId, FileUpdateParams updateParams, QueryOptions options, String token)
            throws CatalogException {
        String userId = userManager.getUserId(token);
        Study study = studyManager.resolveId(studyStr, userId, StudyManager.INCLUDE_VARIABLE_SET);

        String operationId = UUIDUtils.generateOpenCGAUUID(UUIDUtils.Entity.AUDIT);

        ObjectMap auditParams = new ObjectMap()
                .append("study", studyStr)
                .append("fileId", fileId)
                .append("updateParams", updateParams != null ? updateParams.getUpdateMap() : null)
                .append("options", options)
                .append("token", token);

        OpenCGAResult<File> result = OpenCGAResult.empty();
        String fileUuid = "";
        try {
            OpenCGAResult<File> internalResult = internalGet(study.getUid(), fileId, EXCLUDE_FILE_ATTRIBUTES, userId);
            if (internalResult.getNumResults() == 0) {
                throw new CatalogException("File '" + fileId + "' not found");
            }
            File file = internalResult.first();

            // We set the proper values for the audit
            fileId = file.getId();
            fileUuid = file.getUuid();

            OpenCGAResult<File> updateResult = update(study, file, updateParams, options, userId, token);
            result.append(updateResult);

            auditManager.auditUpdate(userId, Enums.Resource.FILE, file.getId(), file.getUuid(), study.getId(), study.getUuid(),
                    auditParams, new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));
        } catch (CatalogException e) {
            Event event = new Event(Event.Type.ERROR, fileId, e.getMessage());
            result.getEvents().add(event);

            logger.error("Cannot update file {}: {}", fileId, e.getMessage());
            auditManager.auditUpdate(operationId, userId, Enums.Resource.FILE, fileId, fileUuid, study.getId(),
                    study.getUuid(), auditParams, new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()));
            throw e;
        }

        String ownerId = studyDBAdaptor.getOwnerId(study.getUid());
        userDBAdaptor.updateUserLastModified(ownerId);

        return result;
    }

    /**
     * Update a File from catalog.
     *
     * @param studyStr   Study id in string format. Could be one of [id|user@aliasProject:aliasStudy|aliasProject:aliasStudy|aliasStudy].
     * @param fileIds   List of file ids. Could be either the id, path or uuid.
     * @param updateParams Data model filled only with the parameters to be updated.
     * @param options      QueryOptions object.
     * @param token  Session id of the user logged in.
     * @return A OpenCGAResult.
     * @throws CatalogException if there is any internal error, the user does not have proper permissions or a parameter passed does not
     *                          exist or is not allowed to be updated.
     */
    public OpenCGAResult<File> update(String studyStr, List<String> fileIds, FileUpdateParams updateParams, QueryOptions options,
                                   String token) throws CatalogException {
        return update(studyStr, fileIds, updateParams, false, options, token);
    }

    public OpenCGAResult<File> update(String studyStr, List<String> fileIds, FileUpdateParams updateParams, boolean ignoreException,
                                      QueryOptions options, String token) throws CatalogException {
        String userId = userManager.getUserId(token);
        Study study = studyManager.resolveId(studyStr, userId, StudyManager.INCLUDE_VARIABLE_SET);

        String operationId = UUIDUtils.generateOpenCGAUUID(UUIDUtils.Entity.AUDIT);

        ObjectMap auditParams = new ObjectMap()
                .append("study", studyStr)
                .append("fileIds", fileIds)
                .append("updateParams", updateParams != null ? updateParams.getUpdateMap() : null)
                .append("ignoreException", ignoreException)
                .append("options", options)
                .append("token", token);

        OpenCGAResult<File> result = OpenCGAResult.empty();
        for (String id : fileIds) {
            String fileId = id;
            String fileUuid = "";

            try {
                OpenCGAResult<File> internalResult = internalGet(study.getUid(), fileId, EXCLUDE_FILE_ATTRIBUTES, userId);
                if (internalResult.getNumResults() == 0) {
                    throw new CatalogException("File '" + id + "' not found");
                }
                File file = internalResult.first();

                // We set the proper values for the audit
                fileId = file.getId();
                fileUuid = file.getUuid();

                OpenCGAResult<File> updateResult = update(study, file, updateParams, options, userId, token);
                result.append(updateResult);

                auditManager.auditUpdate(userId, Enums.Resource.FILE, file.getId(), file.getUuid(), study.getId(), study.getUuid(),
                        auditParams, new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));
            } catch (CatalogException e) {
                Event event = new Event(Event.Type.ERROR, id, e.getMessage());
                result.getEvents().add(event);

                logger.error("Cannot update file {}: {}", fileId, e.getMessage());
                auditManager.auditUpdate(operationId, userId, Enums.Resource.FILE, fileId, fileUuid, study.getId(),
                        study.getUuid(), auditParams, new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()));
            }
        }

        String ownerId = studyDBAdaptor.getOwnerId(study.getUid());
        userDBAdaptor.updateUserLastModified(ownerId);

        return endResult(result, ignoreException);
    }

    private OpenCGAResult<File> update(Study study, File file, FileUpdateParams updateParams, QueryOptions options, String userId,
                                    String token) throws CatalogException {
        ObjectMap parameters = new ObjectMap();
        if (updateParams != null) {
            parameters = updateParams.getUpdateMap();
        }
        ParamUtils.checkUpdateParametersMap(parameters);

        options = ParamUtils.defaultObject(options, QueryOptions::new);

        if (parameters.containsKey(SampleDBAdaptor.QueryParams.ANNOTATION_SETS.key())) {
            Map<String, Object> actionMap = options.getMap(Constants.ACTIONS, new HashMap<>());
            if (!actionMap.containsKey(AnnotationSetManager.ANNOTATION_SETS)
                    && !actionMap.containsKey(AnnotationSetManager.ANNOTATIONS)) {
                logger.warn("Assuming the user wants to add the list of annotation sets provided");
                actionMap.put(AnnotationSetManager.ANNOTATION_SETS, ParamUtils.UpdateAction.ADD);
                options.put(Constants.ACTIONS, actionMap);
            }
        }

        // Check permissions...
        // Only check write annotation permissions if the user wants to update the annotation sets
        if (updateParams != null && updateParams.getAnnotationSets() != null) {
            authorizationManager.checkFilePermission(study.getUid(), file.getUid(), userId,
                    FileAclEntry.FilePermissions.WRITE_ANNOTATIONS);
        }
        // Only check update permissions if the user wants to update anything apart from the annotation sets
        if ((parameters.size() == 1 && !parameters.containsKey(FileDBAdaptor.QueryParams.ANNOTATION_SETS.key()))
                || parameters.size() > 1) {
            authorizationManager.checkFilePermission(study.getUid(), file.getUid(), userId, FileAclEntry.FilePermissions.WRITE);
        }

        if (isRootFolder(file)) {
            throw new CatalogException("Cannot modify root folder");
        }

        // We obtain the numeric ids of the samples given
        if (updateParams != null && ListUtils.isNotEmpty(updateParams.getSamples())) {
            List<Sample> sampleList = catalogManager.getSampleManager().internalGet(study.getUid(), updateParams.getSamples(),
                    SampleManager.INCLUDE_SAMPLE_IDS, userId, false).getResults();

            parameters.put(FileDBAdaptor.QueryParams.SAMPLES.key(), sampleList);
        }

        //Name must be changed with "rename".
        if (updateParams != null && StringUtils.isNotEmpty(updateParams.getName())) {
            logger.info("Rename file using update method!");
            rename(study.getFqn(), file.getPath(), updateParams.getName(), token);
            parameters.remove(FileDBAdaptor.QueryParams.NAME.key());
        }

        checkUpdateAnnotations(study, file, parameters, options, VariableSet.AnnotableDataModels.FILE, fileDBAdaptor, userId);

        return fileDBAdaptor.update(file.getUid(), parameters, study.getVariableSets(), options);
    }

    @Deprecated
    public OpenCGAResult<File> update(String studyStr, String entryStr, ObjectMap parameters, QueryOptions options, String token)
            throws CatalogException {
        ParamUtils.checkObj(parameters, "Parameters");
        options = ParamUtils.defaultObject(options, QueryOptions::new);

        String userId = userManager.getUserId(token);
        Study study = studyManager.resolveId(studyStr, userId, StudyManager.INCLUDE_VARIABLE_SET);

        File file = internalGet(study.getUid(), entryStr, QueryOptions.empty(), userId).first();

        ObjectMap auditParams = new ObjectMap()
                .append("study", studyStr)
                .append("fileId", entryStr)
                .append("updateParams", parameters)
                .append("options", options)
                .append("token", token);
        try {
            // Check permissions...
            // Only check write annotation permissions if the user wants to update the annotation sets
            if (parameters.containsKey(FileDBAdaptor.QueryParams.ANNOTATION_SETS.key())) {
                authorizationManager.checkFilePermission(study.getUid(), file.getUid(), userId,
                        FileAclEntry.FilePermissions.WRITE_ANNOTATIONS);
            }
            // Only check update permissions if the user wants to update anything apart from the annotation sets
            if ((parameters.size() == 1 && !parameters.containsKey(FileDBAdaptor.QueryParams.ANNOTATION_SETS.key()))
                    || parameters.size() > 1) {
                authorizationManager.checkFilePermission(study.getUid(), file.getUid(), userId, FileAclEntry.FilePermissions.WRITE);
            }

            try {
                ParamUtils.checkAllParametersExist(parameters.keySet().iterator(), (a) -> FileDBAdaptor.UpdateParams.getParam(a) != null);
            } catch (CatalogParameterException e) {
                throw new CatalogException("Could not update: " + e.getMessage(), e);
            }

            // We obtain the numeric ids of the samples given
            if (StringUtils.isNotEmpty(parameters.getString(FileDBAdaptor.QueryParams.SAMPLES.key()))) {
                List<Sample> sampleList = catalogManager.getSampleManager().internalGet(study.getUid(),
                        parameters.getAsStringList(FileDBAdaptor.QueryParams.SAMPLES.key()), SampleManager.INCLUDE_SAMPLE_IDS, userId,
                        false).getResults();

                parameters.put(FileDBAdaptor.QueryParams.SAMPLES.key(), sampleList);
            }

            //Name must be changed with "rename".
            if (parameters.containsKey(FileDBAdaptor.QueryParams.NAME.key())) {
                logger.info("Rename file using update method!");
                rename(studyStr, file.getPath(), parameters.getString(FileDBAdaptor.QueryParams.NAME.key()), token);
            }

            OpenCGAResult<File> queryResult = unsafeUpdate(study, file, parameters, options, userId);
            auditManager.auditUpdate(userId, Enums.Resource.FILE, file.getId(), file.getUuid(), study.getId(), study.getUuid(),
                    auditParams, new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));
            return queryResult;
        } catch (CatalogException e) {
            auditManager.auditUpdate(userId, Enums.Resource.FILE, file.getId(), file.getUuid(), study.getId(), study.getUuid(),
                    auditParams, new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()));
            throw e;
        }
    }

    OpenCGAResult<File> unsafeUpdate(Study study, File file, ObjectMap parameters, QueryOptions options, String userId)
            throws CatalogException {
        if (isRootFolder(file)) {
            throw new CatalogException("Cannot modify root folder");
        }

        try {
            ParamUtils.checkAllParametersExist(parameters.keySet().iterator(), (a) -> FileDBAdaptor.UpdateParams.getParam(a) != null);
        } catch (CatalogParameterException e) {
            throw new CatalogException("Could not update: " + e.getMessage(), e);
        }

        checkUpdateAnnotations(study, file, parameters, options, VariableSet.AnnotableDataModels.FILE, fileDBAdaptor, userId);

        String ownerId = studyDBAdaptor.getOwnerId(study.getUid());
        fileDBAdaptor.update(file.getUid(), parameters, study.getVariableSets(), options);
        OpenCGAResult<File> queryResult = fileDBAdaptor.get(file.getUid(), options);
        userDBAdaptor.updateUserLastModified(ownerId);
        return queryResult;
    }

    private void removeJobReferences(long studyUid, List<File> files) throws CatalogException {
        QueryOptions options = new QueryOptions(QueryOptions.INCLUDE, Arrays.asList(
                JobDBAdaptor.QueryParams.UID.key(), JobDBAdaptor.QueryParams.INPUT.key(), JobDBAdaptor.QueryParams.OUTPUT.key(),
                JobDBAdaptor.QueryParams.OUT_DIR.key(), JobDBAdaptor.QueryParams.ATTRIBUTES.key()));

        List<Long> resourceList = files.stream().map(File::getUid).collect(Collectors.toList());

        // Find all the jobs containing references to any of the files to be deleted
        Query query = new Query()
                .append(JobDBAdaptor.QueryParams.STUDY_UID.key(), studyUid)
                .append(JobDBAdaptor.QueryParams.INPUT_UID.key(), resourceList);
        OpenCGAResult<Job> jobInputFiles = jobDBAdaptor.get(query, options);

        query = new Query()
                .append(JobDBAdaptor.QueryParams.STUDY_UID.key(), studyUid)
                .append(JobDBAdaptor.QueryParams.OUTPUT_UID.key(), resourceList);
        OpenCGAResult<Job> jobOutputFiles = jobDBAdaptor.get(query, options);

        query = new Query()
                .append(JobDBAdaptor.QueryParams.STUDY_UID.key(), studyUid)
                .append(JobDBAdaptor.QueryParams.OUT_DIR_UID.key(), resourceList);
        OpenCGAResult<Job> jobOutDirFolders = jobDBAdaptor.get(query, options);

        // We create a job map that will contain all the changes done so far to avoid performing more queries
        Map<Long, Job> jobMap = new HashMap<>();
        Set<Long> fileIdsReferencedInJobs = new HashSet<>();
        for (Job job : jobInputFiles.getResults()) {
            fileIdsReferencedInJobs.addAll(job.getInput().stream().map(File::getUid).collect(Collectors.toList()));
            jobMap.put(job.getUid(), job);
        }
        for (Job job : jobOutputFiles.getResults()) {
            fileIdsReferencedInJobs.addAll(job.getOutput().stream().map(File::getUid).collect(Collectors.toList()));
            jobMap.put(job.getUid(), job);
        }
        for (Job job : jobOutDirFolders.getResults()) {
            fileIdsReferencedInJobs.add(job.getOutDir().getUid());
            jobMap.put(job.getUid(), job);
        }

        if (fileIdsReferencedInJobs.isEmpty()) {
            logger.info("No associated jobs found for the files to be deleted.");
            return;
        }

        // We create a map with the files that are related to jobs that are going to be deleted
        Map<Long, File> relatedFileMap = new HashMap<>();
        for (Long fileId : resourceList) {
            if (fileIdsReferencedInJobs.contains(fileId)) {
                relatedFileMap.put(fileId, null);
            }
        }

        if (relatedFileMap.isEmpty()) {
            logger.error("Unexpected error: None of the matching jobs seem to be related to any of the files to be deleted");
            throw new CatalogException("Internal error. Please, report to the OpenCGA administrators.");
        }

        // We obtain the current information of those files
        query = new Query()
                .append(FileDBAdaptor.QueryParams.UID.key(), relatedFileMap.keySet())
                .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), studyUid)
                .append(FileDBAdaptor.QueryParams.STATUS_NAME.key(), GET_NON_DELETED_FILES);
        OpenCGAResult<File> fileDataResult = fileDBAdaptor.get(query, QueryOptions.empty());

        if (fileDataResult.getNumResults() < relatedFileMap.size()) {
            logger.error("Unexpected error: The number of files fetched does not match the number of files looked for.");
            throw new CatalogException("Internal error. Please, report to the OpenCGA administrators.");
        }

        relatedFileMap = new HashMap<>();
        for (File file : fileDataResult.getResults()) {
            relatedFileMap.put(file.getUid(), file);
        }

        // We update the input files from the jobs
        for (Job jobAux : jobInputFiles.getResults()) {
            Job job = jobMap.get(jobAux.getUid());

            List<File> inputFiles = new ArrayList<>(job.getInput().size());
            List<File> attributeFiles = new ArrayList<>(job.getInput().size());
            for (File file : job.getInput()) {
                if (relatedFileMap.containsKey(file.getUid())) {
                    attributeFiles.add(relatedFileMap.get(file.getUid()));
                } else {
                    inputFiles.add(file);
                }
            }

            if (attributeFiles.isEmpty()) {
                logger.error("Unexpected error: Deleted file was apparently not found in the map of job associated files");
                throw new CatalogException("Internal error. Please, report to the OpenCGA administrators.");
            }

            Map<String, Object> attributes = job.getAttributes();
            ObjectMap opencgaAttributes;
            if (!attributes.containsKey(Constants.PRIVATE_OPENCGA_ATTRIBUTES)) {
                opencgaAttributes = new ObjectMap();
                attributes.put(Constants.PRIVATE_OPENCGA_ATTRIBUTES, opencgaAttributes);
            } else {
                opencgaAttributes = (ObjectMap) attributes.get(Constants.PRIVATE_OPENCGA_ATTRIBUTES);
            }

            List<Object> fileList = opencgaAttributes.getAsList(Constants.JOB_DELETED_INPUT_FILES);
            if (fileList == null || fileList.isEmpty()) {
                fileList = new ArrayList<>(attributeFiles);
            } else {
                fileList = new ArrayList<>(fileList);
                fileList.addAll(attributeFiles);
            }
            opencgaAttributes.put(Constants.JOB_DELETED_INPUT_FILES, fileList);

            ObjectMap params = new ObjectMap()
                    .append(JobDBAdaptor.QueryParams.ATTRIBUTES.key(), attributes)
                    .append(JobDBAdaptor.QueryParams.INPUT.key(), inputFiles);
            jobDBAdaptor.update(job.getUid(), params, QueryOptions.empty());
        }

        // We update the output files from the jobs
        for (Job jobAux : jobOutputFiles.getResults()) {
            Job job = jobMap.get(jobAux.getUid());

            List<File> outputFiles = new ArrayList<>(job.getOutput().size());
            List<File> attributeFiles = new ArrayList<>(job.getOutput().size());
            for (File file : job.getOutput()) {
                if (relatedFileMap.containsKey(file.getUid())) {
                    attributeFiles.add(relatedFileMap.get(file.getUid()));
                } else {
                    outputFiles.add(file);
                }
            }

            if (attributeFiles.isEmpty()) {
                logger.error("Unexpected error: Deleted file was apparently not found in the map of job associated files");
                throw new CatalogException("Internal error. Please, report to the OpenCGA administrators.");
            }

            Map<String, Object> attributes = job.getAttributes();
            ObjectMap opencgaAttributes;
            if (!attributes.containsKey(Constants.PRIVATE_OPENCGA_ATTRIBUTES)) {
                opencgaAttributes = new ObjectMap();
                attributes.put(Constants.PRIVATE_OPENCGA_ATTRIBUTES, opencgaAttributes);
            } else {
                opencgaAttributes = (ObjectMap) attributes.get(Constants.PRIVATE_OPENCGA_ATTRIBUTES);
            }

            List<Object> fileList = opencgaAttributes.getAsList(Constants.JOB_DELETED_OUTPUT_FILES);
            if (fileList == null || fileList.isEmpty()) {
                fileList = new ArrayList<>(attributeFiles);
            } else {
                fileList = new ArrayList<>(fileList);
                fileList.addAll(attributeFiles);
            }
            opencgaAttributes.put(Constants.JOB_DELETED_OUTPUT_FILES, fileList);

            ObjectMap params = new ObjectMap()
                    .append(JobDBAdaptor.QueryParams.ATTRIBUTES.key(), attributes)
                    .append(JobDBAdaptor.QueryParams.OUTPUT.key(), outputFiles);
            jobDBAdaptor.update(job.getUid(), params, QueryOptions.empty());
        }

        // We update the outdir file from the jobs
        for (Job jobAux : jobOutDirFolders.getResults()) {
            Job job = jobMap.get(jobAux.getUid());

            File outDir = job.getOutDir();
            if (outDir == null || outDir.getUid() <= 0) {
                logger.error("Unexpected error: Output directory from job not found?");
                throw new CatalogException("Internal error. Please, report to the OpenCGA administrators.");
            }
            if (!relatedFileMap.containsKey(job.getOutDir().getUid())) {
                logger.error("Unexpected error: Deleted output directory was apparently not found in the map of job associated files");
                throw new CatalogException("Internal error. Please, report to the OpenCGA administrators.");
            }
            // We get the whole file entry
            outDir = relatedFileMap.get(outDir.getUid());

            Map<String, Object> attributes = job.getAttributes();
            ObjectMap opencgaAttributes;
            if (!attributes.containsKey(Constants.PRIVATE_OPENCGA_ATTRIBUTES)) {
                opencgaAttributes = new ObjectMap();
                attributes.put(Constants.PRIVATE_OPENCGA_ATTRIBUTES, opencgaAttributes);
            } else {
                opencgaAttributes = (ObjectMap) attributes.get(Constants.PRIVATE_OPENCGA_ATTRIBUTES);
            }
            opencgaAttributes.put(Constants.JOB_DELETED_OUTPUT_DIRECTORY, outDir);

            ObjectMap params = new ObjectMap()
                    .append(JobDBAdaptor.QueryParams.ATTRIBUTES.key(), attributes)
                    .append(JobDBAdaptor.QueryParams.OUT_DIR.key(), -1L);
            jobDBAdaptor.update(job.getUid(), params, QueryOptions.empty());
        }
    }

    public OpenCGAResult<File> link(String studyStr, URI uriOrigin, String pathDestiny, ObjectMap params, String token)
            throws CatalogException {
        // We make two attempts to link to ensure the behaviour remains even if it is being called at the same time link from different
        // threads
        String userId = userManager.getUserId(token);
        Study study = studyManager.resolveId(studyStr, userId);

        ObjectMap auditParams = new ObjectMap()
                .append("study", studyStr)
                .append("uriOrigin", uriOrigin)
                .append("pathDestiny", pathDestiny)
                .append("params", params)
                .append("token", token);
        try {
            OpenCGAResult<File> result = privateLink(study, uriOrigin, pathDestiny, params, token);
            auditManager.auditCreate(userId, Enums.Action.LINK, Enums.Resource.FILE, result.first().getId(),
                    result.first().getUuid(), study.getId(), study.getUuid(), auditParams,
                    new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));
            return result;
        } catch (CatalogException | IOException e) {
            try {
                OpenCGAResult<File> result = privateLink(study, uriOrigin, pathDestiny, params, token);
                auditManager.auditCreate(userId, Enums.Action.LINK, Enums.Resource.FILE, result.first().getId(),
                        result.first().getUuid(), study.getId(), study.getUuid(), auditParams,
                        new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));
                return result;
            } catch (CatalogException | IOException e2) {
                auditManager.auditCreate(userId, Enums.Action.LINK, Enums.Resource.FILE, uriOrigin.toString(), "",
                        study.getId(), study.getUuid(), auditParams, new AuditRecord.Status(AuditRecord.Status.Result.ERROR,
                                new Error(0, "", e2.getMessage())));
                throw new CatalogException(e2.getMessage(), e2.getCause());
            }
        }
    }

    @Override
    public OpenCGAResult rank(String studyStr, Query query, String field, int numResults, boolean asc, String sessionId)
            throws CatalogException {
        query = ParamUtils.defaultObject(query, Query::new);
        ParamUtils.checkObj(field, "field");
        ParamUtils.checkObj(sessionId, "sessionId");

        String userId = userManager.getUserId(sessionId);
        Study study = studyManager.resolveId(studyStr, userId);

        authorizationManager.checkStudyPermission(study.getUid(), userId, StudyAclEntry.StudyPermissions.VIEW_FILES);

        // TODO: In next release, we will have to check the count parameter from the queryOptions object.
        boolean count = true;
        query.append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid());
        OpenCGAResult queryResult = null;
        if (count) {
            // We do not need to check for permissions when we show the count of files
            queryResult = fileDBAdaptor.rank(query, field, numResults, asc);
        }

        return ParamUtils.defaultObject(queryResult, OpenCGAResult::new);
    }

    @Override
    public OpenCGAResult groupBy(@Nullable String studyStr, Query query, List<String> fields, QueryOptions options, String sessionId)
            throws CatalogException {
        query = ParamUtils.defaultObject(query, Query::new);
        options = ParamUtils.defaultObject(options, QueryOptions::new);
        if (fields == null || fields.size() == 0) {
            throw new CatalogException("Empty fields parameter.");
        }

        String userId = userManager.getUserId(sessionId);
        Study study = studyManager.resolveId(studyStr, userId);

        fixQueryObject(study, query, userId);

        // Add study id to the query
        query.put(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid());

        // We do not need to check for permissions when we show the count of files
        OpenCGAResult queryResult = fileDBAdaptor.groupBy(study.getUid(), query, fields, options, userId);

        return ParamUtils.defaultObject(queryResult, OpenCGAResult::new);
    }

    OpenCGAResult<File> rename(String studyStr, String fileStr, String newName, String sessionId) throws CatalogException {
        ParamUtils.checkFileName(newName, "name");

        String userId = userManager.getUserId(sessionId);
        Study study = studyManager.resolveId(studyStr, userId);

        File file = internalGet(study.getUid(), fileStr, EXCLUDE_FILE_ATTRIBUTES, userId).first();

        String ownerId = StringUtils.split(study.getFqn(), "@")[0];

        authorizationManager.checkFilePermission(study.getUid(), file.getUid(), userId, FileAclEntry.FilePermissions.WRITE);

        if (file.getName().equals(newName)) {
            OpenCGAResult result = OpenCGAResult.empty();
            result.setEvents(Collections.singletonList(new Event(Event.Type.WARNING, newName, "File already had that name.")));
            return result;
        }

        if (isRootFolder(file)) {
            throw new CatalogException("Can not rename root folder");
        }

        String oldPath = file.getPath();
        Path parent = Paths.get(oldPath).getParent();
        String newPath;
        if (parent == null) {
            newPath = newName;
        } else {
            newPath = parent.resolve(newName).toString();
        }

        userDBAdaptor.updateUserLastModified(ownerId);
        CatalogIOManager catalogIOManager;
        URI oldUri = file.getUri();
        URI newUri = Paths.get(oldUri).getParent().resolve(newName).toUri();
//        URI studyUri = file.getUri();
        boolean isExternal = file.isExternal(); //If the file URI is not null, the file is external located.
        OpenCGAResult<File> result;
        switch (file.getType()) {
            case DIRECTORY:
                if (!isExternal) {  //Only rename non external files
                    catalogIOManager = catalogIOManagerFactory.get(oldUri); // TODO? check if something in the subtree is not READY?
                    if (catalogIOManager.exists(oldUri)) {
                        catalogIOManager.rename(oldUri, newUri);   // io.move() 1
                    }
                }
                fileDBAdaptor.rename(file.getUid(), newPath, newUri.toString(), null);
                break;
            case FILE:
                if (!isExternal) {  //Only rename non external files
                    catalogIOManager = catalogIOManagerFactory.get(oldUri);
                    catalogIOManager.rename(oldUri, newUri);
                }
                fileDBAdaptor.rename(file.getUid(), newPath, newUri.toString(), null);
                break;
            default:
                throw new CatalogException("Unknown file type " + file.getType());
        }

        return fileDBAdaptor.get(file.getUid(), QueryOptions.empty());
    }

    public DataInputStream grep(String studyId, String fileId, String pattern, QueryOptions options, String token) throws CatalogException {
        String userId = userManager.getUserId(token);
        Study study = studyManager.resolveId(studyId, userId);

        ObjectMap auditParams = new ObjectMap()
                .append("studyId", studyId)
                .append("fileId", fileId)
                .append("pattern", pattern)
                .append("options", options)
                .append("token", token);
        try {
            File file = internalGet(study.getUid(), fileId, INCLUDE_FILE_URI, userId).first();
            authorizationManager.checkFilePermission(study.getUid(), file.getUid(), userId, FileAclEntry.FilePermissions.VIEW_CONTENT);

            URI fileUri = getUri(file);
            boolean ignoreCase = options.getBoolean("ignoreCase");
            boolean multi = options.getBoolean("multi");
            DataInputStream inputStream = catalogIOManagerFactory.get(fileUri).getGrepFileObject(fileUri, pattern, ignoreCase, multi);

            auditManager.audit(userId, Enums.Action.GREP, Enums.Resource.FILE, file.getId(), file.getUuid(), study.getId(),
                    study.getUuid(), auditParams, new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));

            return inputStream;
        } catch (CatalogException e) {
            auditManager.audit(userId, Enums.Action.GREP, Enums.Resource.FILE, fileId, "", study.getId(), study.getUuid(),
                    auditParams, new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()));
            throw e;
        }
    }

    public DataInputStream download(String studyStr, String fileId, int start, int limit, String token) throws CatalogException {
        String userId = userManager.getUserId(token);
        Study study = studyManager.resolveId(studyStr, userId);

        ObjectMap auditParams = new ObjectMap()
                .append("study", studyStr)
                .append("fileId", fileId)
                .append("start", start)
                .append("limit", limit)
                .append("token", token);
        File file;
        try {
            file = internalGet(study.getUid(), fileId, INCLUDE_FILE_URI, userId).first();
            authorizationManager.checkFilePermission(study.getUid(), file.getUid(), userId, FileAclEntry.FilePermissions.DOWNLOAD);
            URI fileUri = getUri(file);
            DataInputStream dataInputStream = catalogIOManagerFactory.get(fileUri).getFileObject(fileUri, start, limit);

            auditManager.audit(userId, Enums.Action.DOWNLOAD, Enums.Resource.FILE, file.getId(), file.getUuid(), study.getId(),
                    study.getUuid(), auditParams, new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));
            return dataInputStream;
        } catch (CatalogException e) {
            auditManager.audit(userId, Enums.Action.DOWNLOAD, Enums.Resource.FILE, fileId, "", study.getId(), study.getUuid(),
                    auditParams, new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()));
            throw e;
        }
    }

    public void setFileIndex(String studyStr, String fileId, FileIndex index, String sessionId) throws CatalogException {
        String userId = userManager.getUserId(sessionId);
        Study study = studyManager.resolveId(studyStr, userId);
        long fileUid = internalGet(study.getUid(), fileId, INCLUDE_FILE_IDS, userId).first().getUid();

        authorizationManager.checkFilePermission(study.getUid(), fileUid, userId, FileAclEntry.FilePermissions.WRITE);

        ObjectMap parameters = new ObjectMap(FileDBAdaptor.QueryParams.INDEX.key(), index);
        fileDBAdaptor.update(fileUid, parameters, QueryOptions.empty());
    }

    // **************************   ACLs  ******************************** //
    public OpenCGAResult<Map<String, List<String>>> getAcls(String studyId, List<String> fileList, String member, boolean ignoreException,
                                                            String token) throws CatalogException {
        String user = userManager.getUserId(token);
        Study study = studyManager.resolveId(studyId, user);

        String operationId = UUIDUtils.generateOpenCGAUUID(UUIDUtils.Entity.AUDIT);
        ObjectMap auditParams = new ObjectMap()
                .append("studyId", studyId)
                .append("fileList", fileList)
                .append("member", member)
                .append("ignoreException", ignoreException)
                .append("token", token);
        try {
            OpenCGAResult<Map<String, List<String>>> fileAclList = OpenCGAResult.empty();
            InternalGetDataResult<File> fileDataResult = internalGet(study.getUid(), fileList, INCLUDE_FILE_IDS, user, ignoreException);

            Map<String, InternalGetDataResult.Missing> missingMap = new HashMap<>();
            if (fileDataResult.getMissing() != null) {
                missingMap = fileDataResult.getMissing().stream()
                        .collect(Collectors.toMap(InternalGetDataResult.Missing::getId, Function.identity()));
            }
            int counter = 0;
            for (String fileId : fileList) {
                if (!missingMap.containsKey(fileId)) {
                    File file = fileDataResult.getResults().get(counter);
                    try {
                        OpenCGAResult<Map<String, List<String>>> allFileAcls;
                        if (StringUtils.isNotEmpty(member)) {
                            allFileAcls = authorizationManager.getFileAcl(study.getUid(), file.getUid(), user, member);
                        } else {
                            allFileAcls = authorizationManager.getAllFileAcls(study.getUid(), file.getUid(), user, true);
                        }
                        fileAclList.append(allFileAcls);

                        auditManager.audit(operationId, user, Enums.Action.FETCH_ACLS, Enums.Resource.FILE, file.getId(),
                                file.getUuid(), study.getId(), study.getUuid(), auditParams,
                                new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS), new ObjectMap());
                    } catch (CatalogException e) {
                        auditManager.audit(operationId, user, Enums.Action.FETCH_ACLS, Enums.Resource.FILE, file.getId(),
                                file.getUuid(), study.getId(), study.getUuid(), auditParams,
                                new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()), new ObjectMap());

                        if (!ignoreException) {
                            throw e;
                        } else {
                            Event event = new Event(Event.Type.ERROR, fileId, missingMap.get(fileId).getErrorMsg());
                            fileAclList.append(new OpenCGAResult<>(0, Collections.singletonList(event), 0,
                                    Collections.singletonList(Collections.emptyMap()), 0));
                        }
                    }
                    counter += 1;
                } else {
                    Event event = new Event(Event.Type.ERROR, fileId, missingMap.get(fileId).getErrorMsg());
                    fileAclList.append(new OpenCGAResult<>(0, Collections.singletonList(event), 0,
                            Collections.singletonList(Collections.emptyMap()), 0));

                    auditManager.audit(operationId, user, Enums.Action.FETCH_ACLS, Enums.Resource.FILE, fileId, "",
                            study.getId(), study.getUuid(), auditParams, new AuditRecord.Status(AuditRecord.Status.Result.ERROR,
                                    new Error(0, "", missingMap.get(fileId).getErrorMsg())), new ObjectMap());
                }
            }
            return fileAclList;
        } catch (CatalogException e) {
            for (String fileId : fileList) {
                auditManager.audit(operationId, user, Enums.Action.FETCH_ACLS, Enums.Resource.FILE, fileId, "", study.getId(),
                        study.getUuid(), auditParams, new AuditRecord.Status(AuditRecord.Status.Result.ERROR, e.getError()),
                        new ObjectMap());
            }
            throw e;
        }
    }

    public OpenCGAResult<Map<String, List<String>>> updateAcl(String studyId, List<String> fileStrList, String memberList,
                                                    File.FileAclParams aclParams, String token) throws CatalogException {
        String user = userManager.getUserId(token);
        Study study = studyManager.resolveId(studyId, user);

        ObjectMap auditParams = new ObjectMap()
                .append("studyId", studyId)
                .append("fileStrList", fileStrList)
                .append("memberList", memberList)
                .append("aclParams", aclParams)
                .append("token", token);
        String operationId = UUIDUtils.generateOpenCGAUUID(UUIDUtils.Entity.AUDIT);

        try {
            int count = 0;
            count += fileStrList != null && !fileStrList.isEmpty() ? 1 : 0;
            count += StringUtils.isNotEmpty(aclParams.getSample()) ? 1 : 0;

            if (count > 1) {
                throw new CatalogException("Update ACL: Only one of these parameters are allowed: file or sample per query.");
            } else if (count == 0) {
                throw new CatalogException("Update ACL: At least one of these parameters should be provided: file or sample");
            }

            if (aclParams.getAction() == null) {
                throw new CatalogException("Invalid action found. Please choose a valid action to be performed.");
            }

            List<String> permissions = Collections.emptyList();
            if (StringUtils.isNotEmpty(aclParams.getPermissions())) {
                permissions = Arrays.asList(aclParams.getPermissions().trim().replaceAll("\\s", "").split(","));
                checkPermissions(permissions, FileAclEntry.FilePermissions::valueOf);
            }


            List<File> extendedFileList;
            if (StringUtils.isNotEmpty(aclParams.getSample())) {
                // Obtain the sample ids
                OpenCGAResult<Sample> sampleDataResult = catalogManager.getSampleManager().internalGet(study.getUid(),
                        Arrays.asList(StringUtils.split(aclParams.getSample(), ",")), SampleManager.INCLUDE_SAMPLE_IDS, user, false);
                Query query = new Query(FileDBAdaptor.QueryParams.SAMPLE_UIDS.key(),
                        sampleDataResult.getResults().stream().map(Sample::getUid).collect(Collectors.toList()));

                extendedFileList = catalogManager.getFileManager().search(studyId, query, EXCLUDE_FILE_ATTRIBUTES, token).getResults();
            } else {
                extendedFileList = internalGet(study.getUid(), fileStrList, EXCLUDE_FILE_ATTRIBUTES, user, false).getResults();
            }

            authorizationManager.checkCanAssignOrSeePermissions(study.getUid(), user);

            // Increase the list with the files/folders within the list of ids that correspond with folders
            extendedFileList = getRecursiveFilesAndFolders(study.getUid(), extendedFileList);

            // Validate that the members are actually valid members
            List<String> members;
            if (memberList != null && !memberList.isEmpty()) {
                members = Arrays.asList(memberList.split(","));
            } else {
                members = Collections.emptyList();
            }
            authorizationManager.checkNotAssigningPermissionsToAdminsGroup(members);
            checkMembers(study.getUid(), members);
//        studyManager.membersHavePermissionsInStudy(resourceIds.getStudyId(), members);

            OpenCGAResult<Map<String, List<String>>> queryResultList;
            switch (aclParams.getAction()) {
                case SET:
                    queryResultList = authorizationManager.setAcls(study.getUid(), extendedFileList.stream().map(File::getUid)
                            .collect(Collectors.toList()), members, permissions, Enums.Resource.FILE);
                    break;
                case ADD:
                    queryResultList = authorizationManager.addAcls(study.getUid(), extendedFileList.stream().map(File::getUid)
                            .collect(Collectors.toList()), members, permissions, Enums.Resource.FILE);
                    break;
                case REMOVE:
                    queryResultList = authorizationManager.removeAcls(extendedFileList.stream().map(File::getUid)
                            .collect(Collectors.toList()), members, permissions, Enums.Resource.FILE);
                    break;
                case RESET:
                    queryResultList = authorizationManager.removeAcls(extendedFileList.stream().map(File::getUid)
                            .collect(Collectors.toList()), members, null, Enums.Resource.FILE);
                    break;
                default:
                    throw new CatalogException("Unexpected error occurred. No valid action found.");
            }
            for (File file : extendedFileList) {
                auditManager.audit(operationId, user, Enums.Action.UPDATE_ACLS, Enums.Resource.FILE, file.getId(),
                        file.getUuid(), study.getId(), study.getUuid(), auditParams,
                        new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS), new ObjectMap());
            }
            return queryResultList;
        } catch (CatalogException e) {
            if (fileStrList != null) {
                for (String fileId : fileStrList) {
                    auditManager.audit(operationId, user, Enums.Action.UPDATE_ACLS, Enums.Resource.FILE, fileId, "",
                            study.getId(), study.getUuid(), auditParams, new AuditRecord.Status(AuditRecord.Status.Result.ERROR,
                                    e.getError()), new ObjectMap());
                }
            }
            throw e;
        }
    }


    // **************************   Private methods   ******************************** //
    private boolean isRootFolder(File file) throws CatalogException {
        ParamUtils.checkObj(file, "File");
        return file.getPath().isEmpty();
    }

    /**
     * Fetch all the recursive files and folders within the list of file ids given.
     *
     * @param studyUid Study uid.
     * @param fileList List of files
     * @return a more complete file list containing all the nested files
     */
    private List<File> getRecursiveFilesAndFolders(long studyUid, List<File> fileList) throws CatalogException {
        List<File> fileListCopy = new LinkedList<>();
        fileListCopy.addAll(fileList);

        Set<Long> uidFileSet = new HashSet<>();
        uidFileSet.addAll(fileList.stream().map(File::getUid).collect(Collectors.toSet()));

        List<String> pathList = new ArrayList<>();
        for (File file : fileList) {
            if (file.getType().equals(File.Type.DIRECTORY)) {
                pathList.add("~^" + file.getPath());
            }
        }

        if (CollectionUtils.isNotEmpty(pathList)) {
            // Search for all the files within the list of paths
            Query query = new Query()
                    .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), studyUid)
                    .append(FileDBAdaptor.QueryParams.PATH.key(), pathList);
            OpenCGAResult<File> fileDataResult1 = fileDBAdaptor.get(query, INCLUDE_FILE_URI_PATH);
            for (File file1 : fileDataResult1.getResults()) {
                if (!uidFileSet.contains(file1.getUid())) {
                    uidFileSet.add(file1.getUid());
                    fileListCopy.add(file1);
                }
            }
        }

        return fileListCopy;
    }

    private List<String> calculateAllPossiblePaths(String filePath) {
        String path = "";
        String[] split = filePath.split("/");
        List<String> paths = new ArrayList<>(split.length + 1);
        paths.add("");  //Add study root folder
        //Add intermediate folders
        //Do not add the last split, could be a file or a folder..
        //Depending on this, it could end with '/' or not.
        for (int i = 0; i < split.length - 1; i++) {
            String f = split[i];
            path = path + f + "/";
            paths.add(path);
        }
        paths.add(filePath); //Add the file path
        return paths;
    }

    //FIXME: This should use org.opencb.opencga.storage.core.variant.io.VariantReaderUtils
    private String getMainVariantFile(String name) {
        if (name.endsWith(".variants.avro.gz") || name.endsWith(".variants.proto.gz") || name.endsWith(".variants.json.gz")) {
            int idx = name.lastIndexOf(".variants.");
            return name.substring(0, idx);
        } else {
            return null;
        }
    }

    private boolean isTransformedFile(String name) {
        return getMainVariantFile(name) != null;
    }

    private String getVariantMetadataFile(String path) {
        String file = getMainVariantFile(path);
        if (file != null) {
            return file + ".file.json.gz";
        } else {
            return null;
        }
    }

    private OpenCGAResult<File> getParents(long studyUid, String filePath, boolean rootFirst, QueryOptions options)
            throws CatalogException {
        List<String> paths = calculateAllPossiblePaths(filePath);

        Query query = new Query(FileDBAdaptor.QueryParams.PATH.key(), paths);
        query.put(FileDBAdaptor.QueryParams.STUDY_UID.key(), studyUid);
        OpenCGAResult<File> result = fileDBAdaptor.get(query, options);
        result.getResults().sort(rootFirst ? ROOT_FIRST_COMPARATOR : ROOT_LAST_COMPARATOR);
        return result;
    }

    private String getParentPath(String path) {
        Path parent = Paths.get(path).getParent();
        String parentPath;
        if (parent == null) {   //If parent == null, the file is in the root of the study
            parentPath = "";
        } else {
            parentPath = parent.toString() + "/";
        }
        return parentPath;
    }

    /**
     * Get the URI where a file should be in Catalog, given a study and a path.
     *
     * @param studyId   Study identifier
     * @param path      Path to locate
     * @param directory Boolean indicating if the file is a directory
     * @return URI where the file should be placed
     * @throws CatalogException CatalogException
     */
    private URI getFileUri(long studyId, String path, boolean directory) throws CatalogException, URISyntaxException {
        // Get the closest existing parent. If parents == true, may happen that the parent is not registered in catalog yet.
        File existingParent = getParents(studyId, path, false, null).first();

        //Relative path to the existing parent
        String relativePath = Paths.get(existingParent.getPath()).relativize(Paths.get(path)).toString();
        if (path.endsWith("/") && !relativePath.endsWith("/")) {
            relativePath += "/";
        }

        String uriStr = Paths.get(existingParent.getUri().getPath()).resolve(relativePath).toString();

        if (directory) {
            return UriUtils.createDirectoryUri(uriStr);
        } else {
            return UriUtils.createUri(uriStr);
        }
    }

    private boolean isExternal(Study study, String catalogFilePath, URI fileUri) throws CatalogException {
        URI studyUri = study.getUri();

        String studyFilePath = studyUri.resolve(catalogFilePath).getPath();
        String originalFilePath = fileUri.getPath();

        logger.debug("Study file path: {}", studyFilePath);
        logger.debug("File path: {}", originalFilePath);
        return !studyFilePath.equals(originalFilePath);
    }

    private FileTree getTree(File folder, Query query, QueryOptions queryOptions, int maxDepth, long studyId, String userId)
            throws CatalogDBException {

        if (maxDepth == 0) {
            return null;
        }

        try {
            authorizationManager.checkFilePermission(studyId, folder.getUid(), userId, FileAclEntry.FilePermissions.VIEW);
        } catch (CatalogException e) {
            return null;
        }

        // Update the new path to be looked for
        query.put(FileDBAdaptor.QueryParams.DIRECTORY.key(), folder.getPath());

        FileTree fileTree = new FileTree(folder);
        List<FileTree> children = new ArrayList<>();

        // Obtain the files and directories inside the directory
        OpenCGAResult<File> fileDataResult = fileDBAdaptor.get(query, queryOptions);

        for (File fileAux : fileDataResult.getResults()) {
            if (fileAux.getType().equals(File.Type.DIRECTORY)) {
                FileTree subTree = getTree(fileAux, query, queryOptions, maxDepth - 1, studyId, userId);
                if (subTree != null) {
                    children.add(subTree);
                }
            } else {
                try {
                    authorizationManager.checkFilePermission(studyId, fileAux.getUid(), userId, FileAclEntry.FilePermissions.VIEW);
                    children.add(new FileTree(fileAux));
                } catch (CatalogException e) {
                    continue;
                }
            }
        }
        fileTree.setChildren(children);

        return fileTree;
    }

    private int countFilesInTree(FileTree fileTree) {
        int count = 1;
        for (FileTree tree : fileTree.getChildren()) {
            count += countFilesInTree(tree);
        }
        return count;
    }

    /**
     * Method to check if a files matching a query can be deleted. It will only be possible to delete files as long as they are not indexed.
     *
     * @param query          Query object.
     * @param physicalDelete boolean indicating whether the files matching the query should be completely deleted from the file system or
     *                       they should be sent to the trash bin.
     * @param studyId        Study where the query will be applied.
     * @param userId         user for which DELETE permissions will be checked.
     * @return the list of files scanned that can be deleted.
     * @throws CatalogException if any of the files cannot be deleted.
     */
    public List<File> checkCanDeleteFiles(Query query, boolean physicalDelete, long studyId, String userId) throws CatalogException {
        String statusQuery = physicalDelete ? GET_NON_DELETED_FILES : GET_NON_TRASHED_FILES;

        QueryOptions options = new QueryOptions(QueryOptions.INCLUDE, Arrays.asList(FileDBAdaptor.QueryParams.UID.key(),
                FileDBAdaptor.QueryParams.NAME.key(), FileDBAdaptor.QueryParams.TYPE.key(), FileDBAdaptor.QueryParams.RELATED_FILES.key(),
                FileDBAdaptor.QueryParams.SIZE.key(), FileDBAdaptor.QueryParams.URI.key(), FileDBAdaptor.QueryParams.PATH.key(),
                FileDBAdaptor.QueryParams.INDEX.key(), FileDBAdaptor.QueryParams.STATUS.key(), FileDBAdaptor.QueryParams.EXTERNAL.key()));
        Query myQuery = new Query(query);
        myQuery.put(FileDBAdaptor.QueryParams.STUDY_UID.key(), studyId);
        myQuery.put(FileDBAdaptor.QueryParams.STATUS_NAME.key(), statusQuery);

        OpenCGAResult<File> fileDataResult = fileDBAdaptor.get(myQuery, options);

        return checkCanDeleteFiles(fileDataResult.getResults().iterator(), physicalDelete, String.valueOf(studyId), userId);
    }

    public List<File> checkCanDeleteFiles(Iterator<File> fileIterator, boolean physicalDelete, String studyStr, String userId)
            throws CatalogException {
        List<File> filesChecked = new LinkedList<>();

        while (fileIterator.hasNext()) {
            filesChecked.addAll(checkCanDeleteFile(studyStr, fileIterator.next(), physicalDelete, userId));
        }

        return filesChecked;
    }

    public List<File> checkCanDeleteFile(String studyStr, File file, boolean physicalDelete, String userId) throws CatalogException {
        String statusQuery = physicalDelete ? GET_NON_DELETED_FILES : GET_NON_TRASHED_FILES;

        Study study = studyManager.resolveId(studyStr, userId);
        QueryOptions options = new QueryOptions(QueryOptions.INCLUDE, Arrays.asList(FileDBAdaptor.QueryParams.UID.key(),
                FileDBAdaptor.QueryParams.NAME.key(), FileDBAdaptor.QueryParams.TYPE.key(), FileDBAdaptor.QueryParams.RELATED_FILES.key(),
                FileDBAdaptor.QueryParams.SIZE.key(), FileDBAdaptor.QueryParams.URI.key(), FileDBAdaptor.QueryParams.PATH.key(),
                FileDBAdaptor.QueryParams.INDEX.key(), FileDBAdaptor.QueryParams.STATUS.key(), FileDBAdaptor.QueryParams.EXTERNAL.key()));

        List<File> filesToAnalyse = new LinkedList<>();

        if (file.getType() == File.Type.FILE) {
            filesToAnalyse.add(file);
        } else {
            // We cannot delete the root folder
            if (isRootFolder(file)) {
                throw new CatalogException("Root directories cannot be deleted");
            }

            // Get all recursive files and folders
            Query newQuery = new Query()
                    .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid())
                    .append(FileDBAdaptor.QueryParams.PATH.key(), "~^" + file.getPath() + "*")
                    .append(FileDBAdaptor.QueryParams.STATUS_NAME.key(), statusQuery);
            OpenCGAResult<File> recursiveFileDataResult = fileDBAdaptor.get(newQuery, options);

            if (file.isExternal()) {
                // Check there aren't local files within the directory
                List<String> wrongFiles = new LinkedList<>();
                for (File nestedFile : recursiveFileDataResult.getResults()) {
                    if (!nestedFile.isExternal()) {
                        wrongFiles.add(nestedFile.getPath());
                    }
                }
                if (!wrongFiles.isEmpty()) {
                    throw new CatalogException("Local files {" + StringUtils.join(wrongFiles, ", ") + "} detected within the external "
                            + "folder " + file.getPath() + ". Please, delete those folders or files manually first");
                }
            } else {
                // Check there aren't external files within the directory
                List<String> wrongFiles = new LinkedList<>();
                for (File nestedFile : recursiveFileDataResult.getResults()) {
                    if (nestedFile.isExternal()) {
                        wrongFiles.add(nestedFile.getPath());
                    }
                }
                if (!wrongFiles.isEmpty()) {
                    throw new CatalogException("External files {" + StringUtils.join(wrongFiles, ", ") + "} detected within the local "
                            + "folder " + file.getPath() + ". Please, unlink those folders or files manually first");
                }
            }

            filesToAnalyse.addAll(recursiveFileDataResult.getResults());
        }

        // If the user is the owner or the admin, we won't check if he has permissions for every single file
        boolean checkPermissions = !authorizationManager.checkIsOwnerOrAdmin(study.getUid(), userId);

        Set<Long> transformedFromFileIds = new HashSet<>();

        for (File fileAux : filesToAnalyse) {
            if (checkPermissions) {
                authorizationManager.checkFilePermission(study.getUid(), fileAux.getUid(), userId, FileAclEntry.FilePermissions.DELETE);
            }

            // Check the file status is not STAGE or MISSING
            if (fileAux.getStatus() == null) {
                throw new CatalogException("Cannot check file status for deletion");
            }
            if (File.FileStatus.STAGE.equals(fileAux.getStatus().getName())
                    || File.FileStatus.MISSING.equals(fileAux.getStatus().getName())) {
                throw new CatalogException("Cannot delete file: " + fileAux.getName() + ". The status is " + fileAux.getStatus().getName());
            }

            // Check the index status
            if (fileAux.getIndex() != null && fileAux.getIndex().getStatus() != null
                    && !FileIndex.IndexStatus.NONE.equals(fileAux.getIndex().getStatus().getName())
                    && !FileIndex.IndexStatus.TRANSFORMED.equals(fileAux.getIndex().getStatus().getName())) {
                throw new CatalogException("Cannot delete file: " + fileAux.getName() + ". The index status is "
                        + fileAux.getIndex().getStatus().getName());
            }

            // Check if the file is produced from other file being indexed and add them to the transformedFromFileIds set
            if (fileAux.getRelatedFiles() != null && !fileAux.getRelatedFiles().isEmpty()) {
                transformedFromFileIds.addAll(
                        fileAux.getRelatedFiles().stream()
                                .filter(myFile -> myFile.getRelation() == File.RelatedFile.Relation.PRODUCED_FROM)
                                .map(File.RelatedFile::getFile)
                                .map(File::getUid)
                                .collect(Collectors.toSet())
                );
            }
        }

        // Check the original files are not being indexed at the moment
        if (!transformedFromFileIds.isEmpty()) {
            Query query = new Query(FileDBAdaptor.QueryParams.UID.key(), new ArrayList<>(transformedFromFileIds));
            try (DBIterator<File> iterator = fileDBAdaptor.iterator(query, new QueryOptions(QueryOptions.INCLUDE, Arrays.asList(
                    FileDBAdaptor.QueryParams.INDEX.key(), FileDBAdaptor.QueryParams.UID.key())))) {
                while (iterator.hasNext()) {
                    File next = iterator.next();
                    String status = next.getIndex().getStatus().getName();
                    switch (status) {
                        case FileIndex.IndexStatus.READY:
                            // If they are already ready, we only need to remove the reference to the transformed files as they will be
                            // removed
                            next.getIndex().setTransformedFile(null);
                            break;
                        case FileIndex.IndexStatus.TRANSFORMED:
                            // We need to remove the reference to the transformed files and change their status from TRANSFORMED to NONE
                            next.getIndex().setTransformedFile(null);
                            next.getIndex().getStatus().setName(FileIndex.IndexStatus.NONE);
                            break;
                        case FileIndex.IndexStatus.NONE:
                        case FileIndex.IndexStatus.DELETED:
                            break;
                        default:
                            throw new CatalogException("Cannot delete files that are in use in storage.");
                    }
                }
            }
        }

        return filesToAnalyse;
    }

    public DataResult<FacetField> facet(String studyId, Query query, QueryOptions options, boolean defaultStats, String token)
            throws CatalogException, IOException {
        ParamUtils.defaultObject(query, Query::new);
        ParamUtils.defaultObject(options, QueryOptions::new);

        String userId = userManager.getUserId(token);
        // We need to add variableSets and groups to avoid additional queries as it will be used in the catalogSolrManager
        Study study = studyManager.resolveId(studyId, userId, new QueryOptions(QueryOptions.INCLUDE,
                Arrays.asList(StudyDBAdaptor.QueryParams.VARIABLE_SET.key(), StudyDBAdaptor.QueryParams.GROUPS.key())));

        ObjectMap auditParams = new ObjectMap()
                .append("studyId", studyId)
                .append("query", new Query(query))
                .append("options", options)
                .append("defaultStats", defaultStats)
                .append("token", token);
        try {
            if (defaultStats || StringUtils.isEmpty(options.getString(QueryOptions.FACET))) {
                String facet = options.getString(QueryOptions.FACET);
                options.put(QueryOptions.FACET, StringUtils.isNotEmpty(facet) ? defaultFacet + ";" + facet : defaultFacet);
            }

            AnnotationUtils.fixQueryAnnotationSearch(study, userId, query, authorizationManager);

            CatalogSolrManager catalogSolrManager = new CatalogSolrManager(catalogManager);
            DataResult<FacetField> result = catalogSolrManager.facetedQuery(study, CatalogSolrManager.FILE_SOLR_COLLECTION, query, options,
                    userId);
            auditManager.auditFacet(userId, Enums.Resource.FILE, study.getId(), study.getUuid(), auditParams,
                    new AuditRecord.Status(AuditRecord.Status.Result.SUCCESS));

            return result;
        } catch (CatalogException e) {
            auditManager.auditFacet(userId, Enums.Resource.FILE, study.getId(), study.getUuid(), auditParams,
                    new AuditRecord.Status(AuditRecord.Status.Result.ERROR, new Error(0, "", e.getMessage())));
            throw e;
        }
    }

    private void updateIndexStatusAfterDeletionOfTransformedFile(long studyId, File file) throws CatalogDBException {
        if (file.getType() == File.Type.FILE && (file.getRelatedFiles() == null || file.getRelatedFiles().isEmpty())) {
            return;
        }

        // We check if any of the files to be removed are transformation files
        Query query = new Query()
                .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), studyId)
                .append(FileDBAdaptor.QueryParams.PATH.key(), "~^" + file.getPath() + "*")
                .append(FileDBAdaptor.QueryParams.RELATED_FILES_RELATION.key(), File.RelatedFile.Relation.PRODUCED_FROM);
        OpenCGAResult<File> fileQR = fileDBAdaptor.get(query, new QueryOptions(QueryOptions.INCLUDE,
                FileDBAdaptor.QueryParams.RELATED_FILES.key()));
        if (fileQR.getNumResults() > 0) {
            // Among the files to be deleted / unlinked, there are transformed files. We need to check that these files are not being used
            // anymore.
            Set<Long> fileIds = new HashSet<>();
            for (File transformedFile : fileQR.getResults()) {
                fileIds.addAll(
                        transformedFile.getRelatedFiles().stream()
                                .filter(myFile -> myFile.getRelation() == File.RelatedFile.Relation.PRODUCED_FROM)
                                .map(File.RelatedFile::getFile)
                                .map(File::getUid)
                                .collect(Collectors.toSet())
                );
            }

            // Update the original files to remove the transformed file
            query = new Query(FileDBAdaptor.QueryParams.UID.key(), new ArrayList<>(fileIds));
            Map<Long, FileIndex> filesToUpdate;
            try (DBIterator<File> iterator = fileDBAdaptor.iterator(query, new QueryOptions(QueryOptions.INCLUDE, Arrays.asList(
                    FileDBAdaptor.QueryParams.INDEX.key(), FileDBAdaptor.QueryParams.UID.key())))) {
                filesToUpdate = new HashMap<>();
                while (iterator.hasNext()) {
                    File next = iterator.next();
                    String status = next.getIndex().getStatus().getName();
                    switch (status) {
                        case FileIndex.IndexStatus.READY:
                            // If they are already ready, we only need to remove the reference to the transformed files as they will be
                            // removed
                            next.getIndex().setTransformedFile(null);
                            filesToUpdate.put(next.getUid(), next.getIndex());
                            break;
                        case FileIndex.IndexStatus.TRANSFORMED:
                            // We need to remove the reference to the transformed files and change their status from TRANSFORMED to NONE
                            next.getIndex().setTransformedFile(null);
                            next.getIndex().getStatus().setName(FileIndex.IndexStatus.NONE);
                            filesToUpdate.put(next.getUid(), next.getIndex());
                            break;
                        default:
                            break;
                    }
                }
            }

            for (Map.Entry<Long, FileIndex> indexEntry : filesToUpdate.entrySet()) {
                fileDBAdaptor.update(indexEntry.getKey(), new ObjectMap(FileDBAdaptor.QueryParams.INDEX.key(), indexEntry.getValue()),
                        QueryOptions.empty());
            }
        }
    }

    /**
     * Create the parent directories that are needed.
     *
     * @param study            study where they will be created.
     * @param userId           user that is creating the parents.
     * @param studyURI         Base URI where the created folders will be pointing to. (base physical location)
     * @param path             Path used in catalog as a virtual location. (whole bunch of directories inside the virtual
     *                         location in catalog)
     * @param checkPermissions Boolean indicating whether to check if the user has permissions to create a folder in the first directory
     *                         that is available in catalog.
     * @throws CatalogDBException
     */
    private void createParents(Study study, String userId, URI studyURI, Path path, boolean checkPermissions) throws CatalogException {
        if (path == null) {
            if (checkPermissions) {
                authorizationManager.checkStudyPermission(study.getUid(), userId, StudyAclEntry.StudyPermissions.WRITE_FILES);
            }
            return;
        }

        String stringPath = path.toString();
        if (("/").equals(stringPath)) {
            return;
        }

        logger.info("Path: {}", stringPath);

        if (stringPath.startsWith("/")) {
            stringPath = stringPath.substring(1);
        }

        if (!stringPath.endsWith("/")) {
            stringPath = stringPath + "/";
        }

        // Check if the folder exists
        Query query = new Query()
                .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid())
                .append(FileDBAdaptor.QueryParams.PATH.key(), stringPath);

        if (fileDBAdaptor.count(query).getNumMatches() == 0) {
            createParents(study, userId, studyURI, path.getParent(), checkPermissions);
        } else {
            if (checkPermissions) {
                long fileId = fileDBAdaptor.getId(study.getUid(), stringPath);
                authorizationManager.checkFilePermission(study.getUid(), fileId, userId, FileAclEntry.FilePermissions.WRITE);
            }
            return;
        }

        String parentPath = getParentPath(stringPath);
        long parentFileId = fileDBAdaptor.getId(study.getUid(), parentPath);
        // We obtain the permissions set in the parent folder and set them to the file or folder being created
        OpenCGAResult<Map<String, List<String>>> allFileAcls = authorizationManager.getAllFileAcls(study.getUid(), parentFileId, userId,
                checkPermissions);

        URI completeURI = Paths.get(studyURI).resolve(path).toUri();

        // Create the folder in catalog
        File folder = new File(path.getFileName().toString(), File.Type.DIRECTORY, File.Format.PLAIN, File.Bioformat.NONE, completeURI,
                stringPath, null, TimeUtils.getTime(), TimeUtils.getTime(), "", new File.FileStatus(File.FileStatus.READY), false, 0, null,
                new Experiment(), Collections.emptyList(), new Job(), Collections.emptyList(), null,
                studyManager.getCurrentRelease(study), Collections.emptyList(), null, null);
        folder.setUuid(UUIDUtils.generateOpenCGAUUID(UUIDUtils.Entity.FILE));
        checkHooks(folder, study.getFqn(), HookConfiguration.Stage.CREATE);
        fileDBAdaptor.insert(study.getUid(), folder, Collections.emptyList(), new QueryOptions());
        OpenCGAResult<File> queryResult = getFile(study.getUid(), folder.getUuid(), QueryOptions.empty());
        // Propagate ACLs
        if (allFileAcls != null && allFileAcls.getNumResults() > 0) {
            authorizationManager.replicateAcls(study.getUid(), Arrays.asList(queryResult.first().getUid()), allFileAcls.getResults().get(0),
                    Enums.Resource.FILE);
        }
    }

    private OpenCGAResult<File> privateLink(Study study, URI uriOrigin, String pathDestiny, ObjectMap params, String sessionId)
            throws CatalogException, IOException {
        params = ParamUtils.defaultObject(params, ObjectMap::new);
        CatalogIOManager ioManager = catalogIOManagerFactory.get(uriOrigin);
        if (!ioManager.exists(uriOrigin)) {
            throw new CatalogIOException("File " + uriOrigin + " does not exist");
        }

        final URI normalizedUri;
        try {
            normalizedUri = UriUtils.createUri(uriOrigin.normalize().getPath());
        } catch (URISyntaxException e) {
            throw new CatalogException(e);
        }

        String userId = userManager.getUserId(sessionId);
        authorizationManager.checkStudyPermission(study.getUid(), userId, StudyAclEntry.StudyPermissions.WRITE_FILES);

        pathDestiny = ParamUtils.defaultString(pathDestiny, "");
        if (pathDestiny.length() == 1 && (pathDestiny.equals(".") || pathDestiny.equals("/"))) {
            pathDestiny = "";
        } else {
            if (pathDestiny.startsWith("/")) {
                pathDestiny = pathDestiny.substring(1);
            }
            if (!pathDestiny.isEmpty() && !pathDestiny.endsWith("/")) {
                pathDestiny = pathDestiny + "/";
            }
        }
        String externalPathDestinyStr;
        if (Paths.get(normalizedUri).toFile().isDirectory()) {
            externalPathDestinyStr = Paths.get(pathDestiny).resolve(Paths.get(normalizedUri).getFileName()).toString() + "/";
        } else {
            externalPathDestinyStr = Paths.get(pathDestiny).resolve(Paths.get(normalizedUri).getFileName()).toString();
        }

        // Check if the path already exists and is not external
        Query query = new Query()
                .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid())
                .append(FileDBAdaptor.QueryParams.STATUS_NAME.key(), "!=" + File.FileStatus.TRASHED + ";!=" + Status.DELETED + ";!="
                        + File.FileStatus.REMOVED)
                .append(FileDBAdaptor.QueryParams.PATH.key(), externalPathDestinyStr)
                .append(FileDBAdaptor.QueryParams.EXTERNAL.key(), false);
        if (fileDBAdaptor.count(query).getNumMatches() > 0) {
            throw new CatalogException("Cannot link to " + externalPathDestinyStr + ". The path already existed and is not external.");
        }

        // Check if the uri was already linked to that same path
        query = new Query()
                .append(FileDBAdaptor.QueryParams.URI.key(), normalizedUri)
                .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid())
                .append(FileDBAdaptor.QueryParams.STATUS_NAME.key(), "!=" + File.FileStatus.TRASHED + ";!=" + Status.DELETED + ";!="
                        + File.FileStatus.REMOVED)
                .append(FileDBAdaptor.QueryParams.PATH.key(), externalPathDestinyStr)
                .append(FileDBAdaptor.QueryParams.EXTERNAL.key(), true);


        if (fileDBAdaptor.count(query).getNumMatches() > 0) {
            // Create a regular expression on URI to return everything linked from that URI
            query.put(FileDBAdaptor.QueryParams.URI.key(), "~^" + normalizedUri);
            query.remove(FileDBAdaptor.QueryParams.PATH.key());

            // Limit the number of results and only some fields
            QueryOptions queryOptions = new QueryOptions()
                    .append(QueryOptions.LIMIT, 100);

            return fileDBAdaptor.get(query, queryOptions);
        }

        // Check if the uri was linked to other path
        query = new Query()
                .append(FileDBAdaptor.QueryParams.URI.key(), normalizedUri)
                .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid())
                .append(FileDBAdaptor.QueryParams.STATUS_NAME.key(), "!=" + File.FileStatus.TRASHED + ";!=" + Status.DELETED + ";!="
                        + File.FileStatus.REMOVED)
                .append(FileDBAdaptor.QueryParams.EXTERNAL.key(), true);
        if (fileDBAdaptor.count(query).getNumMatches() > 0) {
            QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, FileDBAdaptor.QueryParams.PATH.key());
            String path = fileDBAdaptor.get(query, queryOptions).first().getPath();
            throw new CatalogException(normalizedUri + " was already linked to other path: " + path);
        }

        boolean parents = params.getBoolean("parents", false);
        // FIXME: Implement resync
        boolean resync = params.getBoolean("resync", false);
        String description = params.getString("description", "");
        String checksum = params.getString(FileDBAdaptor.QueryParams.CHECKSUM.key(), "");

        List<File.RelatedFile> relatedFiles = params.getAsList("relatedFiles", File.RelatedFile.class);
        if (relatedFiles != null) {
            for (File.RelatedFile relatedFile : relatedFiles) {
                File tmpFile = internalGet(study.getUid(), relatedFile.getFile().getId(), INCLUDE_FILE_IDS, userId).first();
                relatedFile.setFile(tmpFile);
            }
        }

        // Because pathDestiny can be null, we will use catalogPath as the virtual destiny where the files will be located in catalog.
        Path catalogPath = Paths.get(pathDestiny);

        if (pathDestiny.isEmpty()) {
            // If no destiny is given, everything will be linked to the root folder of the study.
            authorizationManager.checkStudyPermission(study.getUid(), userId, StudyAclEntry.StudyPermissions.WRITE_FILES);
        } else {
            // Check if the folder exists
            query = new Query()
                    .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid())
                    .append(FileDBAdaptor.QueryParams.PATH.key(), pathDestiny);
            if (fileDBAdaptor.count(query).getNumMatches() == 0) {
                if (parents) {
                    // Get the base URI where the files are located in the study
                    URI studyURI = study.getUri();
                    createParents(study, userId, studyURI, catalogPath, true);
                    // Create them in the disk
//                    URI directory = Paths.get(studyURI).resolve(catalogPath).toUri();
//                    catalogIOManagerFactory.get(directory).createDirectory(directory, true);
                } else {
                    throw new CatalogException("The path " + catalogPath + " does not exist in catalog.");
                }
            } else {
                // Check if the user has permissions to link files in the directory
                long fileId = fileDBAdaptor.getId(study.getUid(), pathDestiny);
                authorizationManager.checkFilePermission(study.getUid(), fileId, userId, FileAclEntry.FilePermissions.WRITE);
            }
        }

        Path pathOrigin = Paths.get(normalizedUri);
        Path externalPathDestiny = Paths.get(externalPathDestinyStr);
        if (Paths.get(normalizedUri).toFile().isFile()) {

            // Check if there is already a file in the same path
            query = new Query()
                    .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid())
                    .append(FileDBAdaptor.QueryParams.PATH.key(), externalPathDestinyStr);

            // Create the file
            if (fileDBAdaptor.count(query).getNumMatches() == 0) {
                return registerFile(study, externalPathDestinyStr, normalizedUri, sessionId);
            } else {
                throw new CatalogException("Cannot link " + externalPathDestiny.getFileName().toString() + ". A file with the same name "
                        + "was found in the same path.");
            }
        } else {
            // This list will contain the list of transformed files detected during the link
            List<File> transformedFiles = new ArrayList<>();

            // We remove the / at the end for replacement purposes in the walkFileTree
            String finalExternalPathDestinyStr = externalPathDestinyStr.substring(0, externalPathDestinyStr.length() - 1);

            // Link all the files and folders present in the uri
            Files.walkFileTree(pathOrigin, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {

                    try {
                        String destinyPath = dir.toString().replace(Paths.get(normalizedUri).toString(), finalExternalPathDestinyStr);

                        if (!destinyPath.isEmpty() && !destinyPath.endsWith("/")) {
                            destinyPath += "/";
                        }

                        if (destinyPath.startsWith("/")) {
                            destinyPath = destinyPath.substring(1);
                        }

                        Query query = new Query()
                                .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid())
                                .append(FileDBAdaptor.QueryParams.PATH.key(), destinyPath);

                        if (fileDBAdaptor.count(query).getNumMatches() == 0) {
                            // If the folder does not exist, we create it

                            String parentPath = getParentPath(destinyPath);
                            long parentFileId = fileDBAdaptor.getId(study.getUid(), parentPath);
                            // We obtain the permissions set in the parent folder and set them to the file or folder being created
                            OpenCGAResult<Map<String, List<String>>> allFileAcls;
                            try {
                                allFileAcls = authorizationManager.getAllFileAcls(study.getUid(), parentFileId, userId, true);
                            } catch (CatalogException e) {
                                throw new RuntimeException(e);
                            }

                            File folder = new File(dir.getFileName().toString(), File.Type.DIRECTORY, File.Format.PLAIN,
                                    File.Bioformat.NONE, dir.toUri(), destinyPath, null, TimeUtils.getTime(),
                                    TimeUtils.getTime(), description, new File.FileStatus(File.FileStatus.READY), true, 0, null,
                                    new Experiment(), Collections.emptyList(), new Job(), relatedFiles,
                                    null, studyManager.getCurrentRelease(study), Collections.emptyList(),
                                    Collections.emptyMap(), Collections.emptyMap());
                            folder.setUuid(UUIDUtils.generateOpenCGAUUID(UUIDUtils.Entity.FILE));
                            checkHooks(folder, study.getFqn(), HookConfiguration.Stage.CREATE);
                            fileDBAdaptor.insert(study.getUid(), folder, Collections.emptyList(), new QueryOptions());
                            OpenCGAResult<File> queryResult = getFile(study.getUid(), folder.getUuid(), QueryOptions.empty());

                            // Propagate ACLs
                            if (allFileAcls != null && allFileAcls.getNumResults() > 0) {
                                authorizationManager.replicateAcls(study.getUid(), Arrays.asList(queryResult.first().getUid()),
                                        allFileAcls.getResults().get(0), Enums.Resource.FILE);
                            }
                        }

                    } catch (CatalogException e) {
                        logger.error("An error occurred when trying to create folder {}", dir.toString());
//                        e.printStackTrace();
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path filePath, BasicFileAttributes attrs) throws IOException {
                    try {
                        String destinyPath = filePath.toString().replace(Paths.get(normalizedUri).toString(), finalExternalPathDestinyStr);

                        if (destinyPath.startsWith("/")) {
                            destinyPath = destinyPath.substring(1);
                        }

                        Query query = new Query()
                                .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid())
                                .append(FileDBAdaptor.QueryParams.PATH.key(), destinyPath);

                        if (fileDBAdaptor.count(query).getNumMatches() == 0) {
                            long size = Files.size(filePath);
                            // If the file does not exist, we create it
                            String parentPath = getParentPath(destinyPath);
                            long parentFileId = fileDBAdaptor.getId(study.getUid(), parentPath);
                            // We obtain the permissions set in the parent folder and set them to the file or folder being created
                            OpenCGAResult<Map<String, List<String>>> allFileAcls;
                            try {
                                allFileAcls = authorizationManager.getAllFileAcls(study.getUid(), parentFileId, userId, true);
                            } catch (CatalogException e) {
                                throw new RuntimeException(e);
                            }

                            File subfile = new File(filePath.getFileName().toString(), File.Type.FILE, File.Format.UNKNOWN,
                                    File.Bioformat.NONE, filePath.toUri(), destinyPath, null, TimeUtils.getTime(),
                                    TimeUtils.getTime(), description, new File.FileStatus(File.FileStatus.READY), true, size, null,
                                    new Experiment(), Collections.emptyList(), new Job(), relatedFiles,
                                    null, studyManager.getCurrentRelease(study), Collections.emptyList(),
                                    Collections.emptyMap(), Collections.emptyMap());
                            subfile.setUuid(UUIDUtils.generateOpenCGAUUID(UUIDUtils.Entity.FILE));
                            checkHooks(subfile, study.getFqn(), HookConfiguration.Stage.CREATE);
                            fileDBAdaptor.insert(study.getUid(), subfile, Collections.emptyList(), new QueryOptions());
                            OpenCGAResult<File> queryResult = getFile(study.getUid(), subfile.getUuid(), QueryOptions.empty());

                            // Propagate ACLs
                            if (allFileAcls != null && allFileAcls.getNumResults() > 0) {
                                authorizationManager.replicateAcls(study.getUid(), Arrays.asList(queryResult.first().getUid()),
                                        allFileAcls.getResults().get(0), Enums.Resource.FILE);
                            }

                            File file = FileManager.this.fileMetadataReader.setMetadataInformation(queryResult.first(),
                                    queryResult.first().getUri(), new QueryOptions(), sessionId, false);
                            if (isTransformedFile(file.getName())) {
                                logger.info("Detected transformed file {}", file.getPath());
                                transformedFiles.add(file);
                            }
                        } else {
                            throw new CatalogException("Cannot link the file " + filePath.getFileName().toString()
                                    + ". There is already a file in the path " + destinyPath + " with the same name.");
                        }

                    } catch (CatalogException e) {
                        logger.error(e.getMessage());
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });

            // Try to link transformed files with their corresponding original files if any
            try {
                if (transformedFiles.size() > 0) {
                    matchUpVariantFiles(study.getFqn(), transformedFiles, sessionId);
                }
            } catch (CatalogException e) {
                logger.warn("Matching avro to variant file: {}", e.getMessage());
            }

            // Check if the uri was already linked to that same path
            query = new Query()
                    .append(FileDBAdaptor.QueryParams.URI.key(), "~^" + normalizedUri)
                    .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), study.getUid())
                    .append(FileDBAdaptor.QueryParams.STATUS_NAME.key(), "!=" + File.FileStatus.TRASHED + ";!=" + Status.DELETED + ";!="
                            + File.FileStatus.REMOVED)
                    .append(FileDBAdaptor.QueryParams.EXTERNAL.key(), true);

            // Limit the number of results and only some fields
            QueryOptions queryOptions = new QueryOptions()
                    .append(QueryOptions.LIMIT, 100);
            return fileDBAdaptor.get(query, queryOptions);
        }
    }

    OpenCGAResult<File> registerFile(Study study, String filePath, URI fileUri, String token) throws CatalogException {
        String userId = userManager.getUserId(token);
        CatalogIOManager ioManager = catalogIOManagerFactory.get(fileUri);

        // The file is not registered in Catalog, so we will register it
        long size = ioManager.getFileSize(fileUri);

        String parentPath = getParentPath(filePath);
        File parentFile = internalGet(study.getUid(), parentPath, INCLUDE_FILE_URI_PATH, userId).first();
        // We obtain the permissions set in the parent folder and set them to the file or folder being created
        OpenCGAResult<Map<String, List<String>>> allFileAcls = authorizationManager.getAllFileAcls(study.getUid(),
                parentFile.getUid(), userId, true);

        File subfile = new File(Paths.get(filePath).getFileName().toString(), File.Type.FILE, File.Format.UNKNOWN,
                File.Bioformat.NONE, fileUri, filePath, "", TimeUtils.getTime(), TimeUtils.getTime(),
                "", new File.FileStatus(File.FileStatus.READY), parentFile.isExternal(), size, null, new Experiment(),
                Collections.emptyList(), new Job(), Collections.emptyList(), null, studyManager.getCurrentRelease(study),
                Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap());
        subfile.setUuid(UUIDUtils.generateOpenCGAUUID(UUIDUtils.Entity.FILE));
        checkHooks(subfile, study.getFqn(), HookConfiguration.Stage.CREATE);
        fileDBAdaptor.insert(study.getUid(), subfile, Collections.emptyList(), new QueryOptions());
        OpenCGAResult<File> result = getFile(study.getUid(), subfile.getUuid(), QueryOptions.empty());

        // Propagate ACLs
        if (allFileAcls != null && allFileAcls.getNumResults() > 0) {
            authorizationManager.replicateAcls(study.getUid(), Arrays.asList(result.first().getUid()), allFileAcls.getResults().get(0),
                    Enums.Resource.FILE);
        }
        File file = this.fileMetadataReader.setMetadataInformation(result.first(), result.first().getUri(), new QueryOptions(), token,
                false);
        result.setResults(Collections.singletonList(file));

        // If it is a transformed file, we will try to link it with the correspondent original file
        try {
            if (isTransformedFile(file.getName())) {
                matchUpVariantFiles(study.getFqn(), Arrays.asList(file), token);
            }
        } catch (CatalogException e1) {
            logger.warn("Matching avro to variant file: {}", e1.getMessage());
        }

        return result;
    }

    private void checkHooks(File file, String fqn, HookConfiguration.Stage stage) throws CatalogException {

        Map<String, Map<String, List<HookConfiguration>>> hooks = this.configuration.getHooks();
        if (hooks != null && hooks.containsKey(fqn)) {
            Map<String, List<HookConfiguration>> entityHookMap = hooks.get(fqn);
            List<HookConfiguration> hookList = null;
            if (entityHookMap.containsKey(MongoDBAdaptorFactory.FILE_COLLECTION)) {
                hookList = entityHookMap.get(MongoDBAdaptorFactory.FILE_COLLECTION);
            } else if (entityHookMap.containsKey(MongoDBAdaptorFactory.FILE_COLLECTION.toUpperCase())) {
                hookList = entityHookMap.get(MongoDBAdaptorFactory.FILE_COLLECTION.toUpperCase());
            }

            // We check the hook list
            if (hookList != null) {
                for (HookConfiguration hookConfiguration : hookList) {
                    if (hookConfiguration.getStage() != stage) {
                        continue;
                    }

                    String field = hookConfiguration.getField();
                    if (StringUtils.isEmpty(field)) {
                        logger.warn("Missing 'field' field from hook configuration");
                        continue;
                    }
                    field = field.toLowerCase();

                    String filterValue = hookConfiguration.getValue();
                    if (StringUtils.isEmpty(filterValue)) {
                        logger.warn("Missing 'value' field from hook configuration");
                        continue;
                    }

                    String value = null;
                    switch (field) {
                        case "name":
                            value = file.getName();
                            break;
                        case "format":
                            value = file.getFormat().name();
                            break;
                        case "bioformat":
                            value = file.getFormat().name();
                            break;
                        case "path":
                            value = file.getPath();
                            break;
                        case "description":
                            value = file.getDescription();
                            break;
                        // TODO: At some point, we will also have to consider any field that is not a String
//                        case "size":
//                            value = file.getSize();
//                            break;
                        default:
                            break;
                    }
                    if (value == null) {
                        continue;
                    }

                    String filterNewValue = hookConfiguration.getWhat();
                    if (StringUtils.isEmpty(filterNewValue)) {
                        logger.warn("Missing 'what' field from hook configuration");
                        continue;
                    }

                    String filterWhere = hookConfiguration.getWhere();
                    if (StringUtils.isEmpty(filterWhere)) {
                        logger.warn("Missing 'where' field from hook configuration");
                        continue;
                    }
                    filterWhere = filterWhere.toLowerCase();


                    if (filterValue.startsWith("~")) {
                        // Regular expression
                        if (!value.matches(filterValue.substring(1))) {
                            // If it doesn't match, we will check the next hook of the loop
                            continue;
                        }
                    } else {
                        if (!value.equals(filterValue)) {
                            // If it doesn't match, we will check the next hook of the loop
                            continue;
                        }
                    }

                    // The value matched, so we will perform the action desired by the user
                    if (hookConfiguration.getAction() == HookConfiguration.Action.ABORT) {
                        throw new CatalogException("A hook to abort the insertion matched");
                    }

                    // We check the field the user wants to update
                    if (filterWhere.equals(FileDBAdaptor.QueryParams.DESCRIPTION.key())) {
                        switch (hookConfiguration.getAction()) {
                            case ADD:
                            case SET:
                                file.setDescription(hookConfiguration.getWhat());
                                break;
                            case REMOVE:
                                file.setDescription("");
                                break;
                            default:
                                break;
                        }
                    } else if (filterWhere.equals(FileDBAdaptor.QueryParams.TAGS.key())) {
                        switch (hookConfiguration.getAction()) {
                            case ADD:
                                List<String> values;
                                if (hookConfiguration.getWhat().contains(",")) {
                                    values = Arrays.asList(hookConfiguration.getWhat().split(","));
                                } else {
                                    values = Collections.singletonList(hookConfiguration.getWhat());
                                }
                                List<String> tagsCopy = new ArrayList<>();
                                if (file.getTags() != null) {
                                    tagsCopy.addAll(file.getTags());
                                }
                                tagsCopy.addAll(values);
                                file.setTags(tagsCopy);
                                break;
                            case SET:
                                if (hookConfiguration.getWhat().contains(",")) {
                                    values = Arrays.asList(hookConfiguration.getWhat().split(","));
                                } else {
                                    values = Collections.singletonList(hookConfiguration.getWhat());
                                }
                                file.setTags(values);
                                break;
                            case REMOVE:
                                file.setTags(Collections.emptyList());
                                break;
                            default:
                                break;
                        }
                    } else if (filterWhere.startsWith(FileDBAdaptor.QueryParams.STATS.key())) {
                        String[] split = StringUtils.split(filterWhere, ".", 2);
                        String statsField = null;
                        if (split.length == 2) {
                            statsField = split[1];
                        }

                        switch (hookConfiguration.getAction()) {
                            case ADD:
                                if (statsField == null) {
                                    logger.error("Cannot add a value to {} directly. Expected {}.<subfield>",
                                            FileDBAdaptor.QueryParams.STATS.key(), FileDBAdaptor.QueryParams.STATS.key());
                                    continue;
                                }

                                List<String> values;
                                if (hookConfiguration.getWhat().contains(",")) {
                                    values = Arrays.asList(hookConfiguration.getWhat().split(","));
                                } else {
                                    values = Collections.singletonList(hookConfiguration.getWhat());
                                }

                                Object currentStatsValue = file.getStats().get(statsField);
                                if (currentStatsValue == null) {
                                    file.getStats().put(statsField, values);
                                } else if (currentStatsValue instanceof Collection) {
                                    ((List) currentStatsValue).addAll(values);
                                } else {
                                    logger.error("Cannot add a value to {} if it is not an array", filterWhere);
                                    continue;
                                }

                                break;
                            case SET:
                                if (statsField == null) {
                                    logger.error("Cannot set a value to {} directly. Expected {}.<subfield>",
                                            FileDBAdaptor.QueryParams.STATS.key(), FileDBAdaptor.QueryParams.STATS.key());
                                    continue;
                                }

                                if (hookConfiguration.getWhat().contains(",")) {
                                    values = Arrays.asList(hookConfiguration.getWhat().split(","));
                                } else {
                                    values = Collections.singletonList(hookConfiguration.getWhat());
                                }
                                file.getStats().put(statsField, values);
                                break;
                            case REMOVE:
                                if (statsField == null) {
                                    file.setStats(Collections.emptyMap());
                                } else {
                                    file.getStats().remove(statsField);
                                }
                                break;
                            default:
                                break;
                        }
                    } else if (filterWhere.startsWith(FileDBAdaptor.QueryParams.ATTRIBUTES.key())) {
                        String[] split = StringUtils.split(filterWhere, ".", 2);
                        String attributesField = null;
                        if (split.length == 2) {
                            attributesField = split[1];
                        }

                        switch (hookConfiguration.getAction()) {
                            case ADD:
                                if (attributesField == null) {
                                    logger.error("Cannot add a value to {} directly. Expected {}.<subfield>",
                                            FileDBAdaptor.QueryParams.ATTRIBUTES.key(), FileDBAdaptor.QueryParams.ATTRIBUTES.key());
                                    continue;
                                }

                                List<String> values;
                                if (hookConfiguration.getWhat().contains(",")) {
                                    values = Arrays.asList(hookConfiguration.getWhat().split(","));
                                } else {
                                    values = Collections.singletonList(hookConfiguration.getWhat());
                                }

                                Object currentStatsValue = file.getAttributes().get(attributesField);
                                if (currentStatsValue == null) {
                                    file.getAttributes().put(attributesField, values);
                                } else if (currentStatsValue instanceof Collection) {
                                    ((List) currentStatsValue).addAll(values);
                                } else {
                                    logger.error("Cannot add a value to {} if it is not an array", filterWhere);
                                    continue;
                                }
                                break;
                            case SET:
                                if (attributesField == null) {
                                    logger.error("Cannot set a value to {} directly. Expected {}.<subfield>",
                                            FileDBAdaptor.QueryParams.ATTRIBUTES.key(), FileDBAdaptor.QueryParams.ATTRIBUTES.key());
                                    continue;
                                }

                                if (hookConfiguration.getWhat().contains(",")) {
                                    values = Arrays.asList(hookConfiguration.getWhat().split(","));
                                } else {
                                    values = Collections.singletonList(hookConfiguration.getWhat());
                                }
                                file.getAttributes().put(attributesField, values);
                                break;
                            case REMOVE:
                                if (attributesField == null) {
                                    file.setAttributes(Collections.emptyMap());
                                } else {
                                    file.getAttributes().remove(attributesField);
                                }
                                break;
                            default:
                                break;
                        }
                    } else {
                        logger.error("{} field cannot be updated. Please, check the hook configured.", hookConfiguration.getWhere());
                    }
                }
            }
        }
    }

    private URI getStudyUri(long studyId) throws CatalogException {
        return studyDBAdaptor.get(studyId, INCLUDE_STUDY_URI).first().getUri();
    }

    private enum CheckPath {
        FREE_PATH, FILE_EXISTS, DIRECTORY_EXISTS
    }

    private CheckPath checkPathExists(String path, long studyId) throws CatalogDBException {
        String myPath = path;
        if (myPath.endsWith("/")) {
            myPath = myPath.substring(0, myPath.length() - 1);
        }

        // We first look for any file called the same way the directory needs to be called
        Query query = new Query()
                .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), studyId)
                .append(FileDBAdaptor.QueryParams.PATH.key(), myPath);
        OpenCGAResult<Long> fileDataResult = fileDBAdaptor.count(query);
        if (fileDataResult.getNumMatches() > 0) {
            return CheckPath.FILE_EXISTS;
        }

        query = new Query()
                .append(FileDBAdaptor.QueryParams.STUDY_UID.key(), studyId)
                .append(FileDBAdaptor.QueryParams.PATH.key(), myPath + "/");
        fileDataResult = fileDBAdaptor.count(query);

        return fileDataResult.getNumMatches() > 0 ? CheckPath.DIRECTORY_EXISTS : CheckPath.FREE_PATH;
    }
}
