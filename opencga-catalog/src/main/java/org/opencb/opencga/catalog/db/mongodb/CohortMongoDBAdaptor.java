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

package org.opencb.opencga.catalog.db.mongodb;

import com.mongodb.MongoClient;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.opencb.biodata.models.variant.StudyEntry;
import org.opencb.commons.datastore.core.DataResult;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.mongodb.MongoDBCollection;
import org.opencb.commons.datastore.mongodb.MongoDBQueryUtils;
import org.opencb.opencga.catalog.db.api.CohortDBAdaptor;
import org.opencb.opencga.catalog.db.api.DBIterator;
import org.opencb.opencga.catalog.db.api.StudyDBAdaptor;
import org.opencb.opencga.catalog.db.mongodb.converters.AnnotableConverter;
import org.opencb.opencga.catalog.db.mongodb.converters.CohortConverter;
import org.opencb.opencga.catalog.db.mongodb.iterators.CohortMongoDBIterator;
import org.opencb.opencga.catalog.exceptions.CatalogAuthorizationException;
import org.opencb.opencga.catalog.exceptions.CatalogDBException;
import org.opencb.opencga.catalog.exceptions.CatalogException;
import org.opencb.opencga.catalog.utils.Constants;
import org.opencb.opencga.catalog.utils.ParamUtils;
import org.opencb.opencga.catalog.utils.UUIDUtils;
import org.opencb.opencga.core.common.Entity;
import org.opencb.opencga.core.common.TimeUtils;
import org.opencb.opencga.core.models.*;
import org.opencb.opencga.core.models.acls.permissions.CohortAclEntry;
import org.opencb.opencga.core.models.acls.permissions.StudyAclEntry;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.opencb.opencga.catalog.db.mongodb.AuthorizationMongoDBUtils.filterAnnotationSets;
import static org.opencb.opencga.catalog.db.mongodb.AuthorizationMongoDBUtils.getQueryForAuthorisedEntries;
import static org.opencb.opencga.catalog.db.mongodb.MongoDBUtils.*;

public class CohortMongoDBAdaptor extends AnnotationMongoDBAdaptor<Cohort> implements CohortDBAdaptor {

    private final MongoDBCollection cohortCollection;
    private CohortConverter cohortConverter;

    public CohortMongoDBAdaptor(MongoDBCollection cohortCollection, MongoDBAdaptorFactory dbAdaptorFactory) {
        super(LoggerFactory.getLogger(CohortMongoDBAdaptor.class));
        this.dbAdaptorFactory = dbAdaptorFactory;
        this.cohortCollection = cohortCollection;
        this.cohortConverter = new CohortConverter();
    }

    @Override
    protected AnnotableConverter<? extends Annotable> getConverter() {
        return cohortConverter;
    }

    @Override
    protected MongoDBCollection getCollection() {
        return cohortCollection;
    }

    @Override
    public DataResult nativeInsert(Map<String, Object> cohort, String userId) throws CatalogDBException {
        Document document = getMongoDBDocument(cohort, "cohort");
        return cohortCollection.insert(document, null);
    }

    @Override
    public DataResult insert(long studyId, Cohort cohort, List<VariableSet> variableSetList, QueryOptions options)
            throws CatalogDBException {
        try {
            return runTransaction(clientSession -> {
                long startTime = startQuery();
                dbAdaptorFactory.getCatalogStudyDBAdaptor().checkId(clientSession, studyId);
                insert(clientSession, studyId, cohort, variableSetList);
                return endWrite(startTime, 1, 1, 0, 0, null);
            });
        } catch (Exception e) {
            logger.error("Could not create cohort '{}': {}", cohort.getId(), e.getMessage(), e);
            throw e;
        }
    }

    long insert(ClientSession clientSession, long studyId, Cohort cohort, List<VariableSet> variableSetList) throws CatalogDBException {
        checkCohortIdExists(clientSession, studyId, cohort.getId());

        long newId = getNewUid(clientSession);
        cohort.setUid(newId);
        cohort.setStudyUid(studyId);
        if (StringUtils.isEmpty(cohort.getUuid())) {
            cohort.setUuid(UUIDUtils.generateOpenCGAUUID(UUIDUtils.Entity.COHORT));
        }
        if (StringUtils.isEmpty(cohort.getCreationDate())) {
            cohort.setCreationDate(TimeUtils.getTime());
        }

        Document cohortObject = cohortConverter.convertToStorageType(cohort, variableSetList);

        cohortObject.put(PRIVATE_CREATION_DATE, TimeUtils.toDate(cohort.getCreationDate()));
        cohortObject.put(PERMISSION_RULES_APPLIED, Collections.emptyList());

        logger.debug("Inserting cohort '{}' ({})...", cohort.getId(), cohort.getUid());
        cohortCollection.insert(clientSession, cohortObject, null);
        logger.debug("Cohort '{}' successfully inserted", cohort.getId());
        return newId;
    }

    @Override
    public DataResult<Cohort> getAllInStudy(long studyId, QueryOptions options) throws CatalogDBException {
        return get(new Query(QueryParams.STUDY_UID.key(), studyId), options);
    }

    @Override
    public DataResult update(long cohortId, ObjectMap parameters, QueryOptions queryOptions) throws CatalogDBException {
        return update(cohortId, parameters, Collections.emptyList(), queryOptions);
    }

    @Override
    public DataResult<AnnotationSet> getAnnotationSet(long id, @Nullable String annotationSetName) throws CatalogDBException {
        QueryOptions queryOptions = new QueryOptions();
        List<String> includeList = new ArrayList<>();

        if (StringUtils.isNotEmpty(annotationSetName)) {
            includeList.add(Constants.ANNOTATION_SET_NAME + "." + annotationSetName);
        } else {
            includeList.add(QueryParams.ANNOTATION_SETS.key());
        }

        queryOptions.put(QueryOptions.INCLUDE, includeList);

        DataResult<Cohort> cohortDataResult = get(id, queryOptions);
        if (cohortDataResult.first().getAnnotationSets().isEmpty()) {
            return new DataResult<>(cohortDataResult.getTime(), cohortDataResult.getWarnings(), 0, Collections.emptyList(), 0);
        } else {
            List<AnnotationSet> annotationSets = cohortDataResult.first().getAnnotationSets();
            int size = annotationSets.size();
            return new DataResult<>(cohortDataResult.getTime(), cohortDataResult.getWarnings(), size, annotationSets, size);
        }
    }

    @Override
    public DataResult update(long cohortId, ObjectMap parameters, List<VariableSet> variableSetList, QueryOptions queryOptions)
            throws CatalogDBException {
        DataResult update = update(new Query(QueryParams.UID.key(), cohortId), parameters, variableSetList, queryOptions);
        if (update.getNumUpdated() != 1) {
            throw new CatalogDBException("Could not update cohort with id " + cohortId);
        }
        return update;
    }

    @Override
    public long getStudyId(long cohortId) throws CatalogDBException {
        checkId(cohortId);
        DataResult queryResult = nativeGet(new Query(QueryParams.UID.key(), cohortId),
                new QueryOptions(MongoDBCollection.INCLUDE, PRIVATE_STUDY_UID));
        if (queryResult.getResults().isEmpty()) {
            throw CatalogDBException.uidNotFound("Cohort", cohortId);
        } else {
            return ((Document) queryResult.first()).getLong(PRIVATE_STUDY_UID);
        }
    }

    @Override
    public DataResult unmarkPermissionRule(long studyId, String permissionRuleId) throws CatalogException {
        return unmarkPermissionRule(cohortCollection, studyId, permissionRuleId);
    }

    @Override
    public DataResult<Long> count(Query query) throws CatalogDBException {
        return count(null, query);
    }

    private DataResult<Long> count(ClientSession clientSession, Query query) throws CatalogDBException {
        long startTime = startQuery();
        return endQuery(startTime, cohortCollection.count(clientSession, parseQuery(query)));
    }

    @Override
    public DataResult<Long> count(final Query query, final String user, final StudyAclEntry.StudyPermissions studyPermissions)
            throws CatalogDBException, CatalogAuthorizationException {
        return count(null, query, user, studyPermissions);
    }

    private DataResult<Long> count(ClientSession clientSession, final Query query, final String user,
                                   final StudyAclEntry.StudyPermissions studyPermissions)
            throws CatalogDBException, CatalogAuthorizationException {
        filterOutDeleted(query);
        StudyAclEntry.StudyPermissions studyPermission = (studyPermissions == null
                ? StudyAclEntry.StudyPermissions.VIEW_COHORTS : studyPermissions);

        // Get the study document
        Document studyDocument = getStudyDocument(query);

        // Get the document query needed to check the permissions as well
        Document queryForAuthorisedEntries = getQueryForAuthorisedEntries(studyDocument, user, studyPermission.name(),
                studyPermission.getCohortPermission().name(), Entity.COHORT.name());
        Bson bson = parseQuery(query, queryForAuthorisedEntries);
        logger.debug("Cohort count: query : {}", bson.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()));
        return cohortCollection.count(clientSession, bson);
    }

    private void filterOutDeleted(Query query) {
        if (!query.containsKey(QueryParams.STATUS_NAME.key())) {
            query.append(QueryParams.STATUS_NAME.key(), "!=" + Status.DELETED);
        }
    }

    @Override
    public DataResult distinct(Query query, String field) throws CatalogDBException {
        Bson bson = parseQuery(query);
        return cohortCollection.distinct(field, bson);
    }

    @Override
    public DataResult stats(Query query) {
        return null;
    }

    @Override
    public DataResult update(Query query, ObjectMap parameters, QueryOptions queryOptions) throws CatalogDBException {
        return update(query, parameters, Collections.emptyList(), queryOptions);
    }

    @Override
    public DataResult update(Query query, ObjectMap parameters, List<VariableSet> variableSetList, QueryOptions queryOptions)
            throws CatalogDBException {
        long startTime = startQuery();

        if (parameters.containsKey(QueryParams.ID.key())) {
            // We need to check that the update is only performed over 1 single family
            if (count(query).first() != 1) {
                throw new CatalogDBException("Operation not supported: '" + QueryParams.ID.key() + "' can only be updated for one cohort");
            }
        }

        QueryOptions options = new QueryOptions(QueryOptions.INCLUDE,
                Arrays.asList(QueryParams.ID.key(), QueryParams.UID.key(), QueryParams.STUDY_UID.key()));
        DBIterator<Cohort> iterator = iterator(query, options);

        int numMatches = 0;
        int numModified = 0;
        List<String> warnings = new ArrayList<>();

        while (iterator.hasNext()) {
            Cohort cohort = iterator.next();
            numMatches += 1;

            try {
                runTransaction(clientSession -> {
                    long tmpStartTime = startQuery();

                    Query tmpQuery = new Query()
                            .append(QueryParams.STUDY_UID.key(), cohort.getStudyUid())
                            .append(QueryParams.UID.key(), cohort.getUid());

                    updateAnnotationSets(clientSession, cohort.getUid(), parameters, variableSetList, queryOptions, false);

                    Document cohortUpdate = parseAndValidateUpdateParams(clientSession, parameters, tmpQuery, queryOptions)
                            .toFinalUpdateDocument();

                    if (!cohortUpdate.isEmpty()) {
                        Bson finalQuery = parseQuery(tmpQuery);
                        logger.debug("Cohort update: query : {}, update: {}",
                                finalQuery.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()),
                                cohortUpdate.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()));
                        cohortCollection.update(clientSession, finalQuery, cohortUpdate, null);
                    }

                    return endWrite(tmpStartTime, 1, 1, null);
                });

                logger.info("Cohort {} successfully updated", cohort.getId());
                numModified += 1;
            } catch (Exception e) {
                String errorMsg = "Could not update cohort " +  cohort.getId() + ": " + e.getMessage();
                logger.error("{}", errorMsg);
                warnings.add(errorMsg);
            }
        }

        return endWrite(startTime, numMatches, numModified, warnings);
    }

    private UpdateDocument parseAndValidateUpdateParams(ClientSession clientSession, ObjectMap parameters, Query query,
                                                        QueryOptions queryOptions) throws CatalogDBException {
        UpdateDocument document = new UpdateDocument();

        if (parameters.containsKey(CohortDBAdaptor.QueryParams.ID.key())) {
            // That can only be done to one cohort...
            Query tmpQuery = new Query(query);

            DataResult<Cohort> cohortDataResult = get(clientSession, tmpQuery, new QueryOptions());
            if (cohortDataResult.getNumResults() == 0) {
                throw new CatalogDBException("Update cohort: No cohort found to be updated");
            }
            if (cohortDataResult.getNumResults() > 1) {
                throw CatalogDBException.cannotUpdateMultipleEntries(QueryParams.ID.key(), "cohort");
            }

            // Check that the new sample name is still unique
            long studyId = cohortDataResult.first().getStudyUid();

            tmpQuery = new Query()
                    .append(QueryParams.ID.key(), parameters.get(QueryParams.ID.key()))
                    .append(QueryParams.STUDY_UID.key(), studyId);
            DataResult<Long> count = count(clientSession, tmpQuery);
            if (count.getResults().get(0) > 0) {
                throw new CatalogDBException("Cannot update the " + QueryParams.ID.key() + ". Cohort "
                        + parameters.get(QueryParams.ID.key()) + " already exists.");
            }

            document.getSet().put(QueryParams.ID.key(), parameters.get(QueryParams.ID.key()));
        }

        String[] acceptedParams = {QueryParams.NAME.key(), QueryParams.DESCRIPTION.key(), QueryParams.CREATION_DATE.key()};
        filterStringParams(parameters, document.getSet(), acceptedParams);

        Map<String, Class<? extends Enum>> acceptedEnums = Collections.singletonMap(QueryParams.TYPE.key(), Study.Type.class);
        filterEnumParams(parameters, document.getSet(), acceptedEnums);

        Map<String, Object> actionMap = queryOptions.getMap(Constants.ACTIONS, new HashMap<>());
        String operation = (String) actionMap.getOrDefault(QueryParams.SAMPLES.key(), "ADD");
        String[] acceptedObjectParams = new String[]{QueryParams.SAMPLES.key()};
        switch (operation) {
            case "SET":
                filterObjectParams(parameters, document.getSet(), acceptedObjectParams);
                cohortConverter.validateSamplesToUpdate(document.getSet());
                break;
            case "REMOVE":
                filterObjectParams(parameters, document.getPullAll(), acceptedObjectParams);
                cohortConverter.validateSamplesToUpdate(document.getPullAll());
                break;
            case "ADD":
            default:
                filterObjectParams(parameters, document.getAddToSet(), acceptedObjectParams);
                cohortConverter.validateSamplesToUpdate(document.getAddToSet());
                break;
        }

        String[] acceptedMapParams = {QueryParams.ATTRIBUTES.key(), QueryParams.STATS.key()};
        filterMapParams(parameters, document.getSet(), acceptedMapParams);

        if (parameters.containsKey(QueryParams.STATUS_NAME.key())) {
            document.getSet().put(QueryParams.STATUS_NAME.key(), parameters.get(QueryParams.STATUS_NAME.key()));
            document.getSet().put(QueryParams.STATUS_DATE.key(), TimeUtils.getTime());
        }
        if (parameters.containsKey(QueryParams.STATUS_MSG.key())) {
            document.getSet().put(QueryParams.STATUS_MSG.key(), parameters.get(QueryParams.STATUS_MSG.key()));
            document.getSet().put(QueryParams.STATUS_DATE.key(), TimeUtils.getTime());
        }
        if (parameters.containsKey(QueryParams.STATUS.key())) {
            throw new CatalogDBException("Unable to modify cohort. Use parameter '" + QueryParams.STATUS_NAME.key()
                    + "' instead of '" + QueryParams.STATUS.key() + "'");
        }

        if (!document.toFinalUpdateDocument().isEmpty()) {
            // Update modificationDate param
            String time = TimeUtils.getTime();
            Date date = TimeUtils.toDate(time);
            document.getSet().put(QueryParams.MODIFICATION_DATE.key(), time);
            document.getSet().put(PRIVATE_MODIFICATION_DATE, date);
        }

        return document;
    }

    @Override
    public DataResult delete(Cohort cohort) throws CatalogDBException {
        Query query = new Query(QueryParams.UID.key(), cohort.getUid());
        DataResult delete = delete(query);
        if (delete.getNumMatches() == 0) {
            throw new CatalogDBException("Could not delete cohort. " + cohort.getId() + " not found.");
        } else if (delete.getNumUpdated() == 0) {
            throw new CatalogDBException("Could not delete cohort " + cohort.getId());
        }
        return delete;
    }

    @Override
    public DataResult delete(Query query) throws CatalogDBException {
        QueryOptions options = new QueryOptions(QueryOptions.INCLUDE,
                Arrays.asList(QueryParams.ID.key(), QueryParams.UID.key(), QueryParams.STUDY_UID.key(), QueryParams.STATUS.key()));
        DBIterator<Cohort> iterator = iterator(query, options);

        long startTime = startQuery();
        int numMatches = 0;
        int numModified = 0;
        List<String> warnings = new ArrayList<>();

        while (iterator.hasNext()) {
            Cohort cohort = iterator.next();
            numMatches += 1;

            try {
                runTransaction(clientSession -> {
                    long tmpStartTime = startQuery();
                    logger.info("Deleting cohort {} ({})", cohort.getId(), cohort.getUid());

                    checkCohortCanBeDeleted(cohort);

                    String deleteSuffix = INTERNAL_DELIMITER + "DELETED_" + TimeUtils.getTime();

                    Query tmpQuery = new Query()
                            .append(QueryParams.UID.key(), cohort.getUid())
                            .append(QueryParams.STUDY_UID.key(), cohort.getStudyUid());
                    // Mark the cohort as deleted
                    ObjectMap updateParams = new ObjectMap()
                            .append(QueryParams.STATUS_NAME.key(), Status.DELETED)
                            .append(QueryParams.STATUS_DATE.key(), TimeUtils.getTime())
                            .append(QueryParams.ID.key(), cohort.getId() + deleteSuffix);

                    Bson bsonQuery = parseQuery(tmpQuery);
                    Document updateDocument = parseAndValidateUpdateParams(clientSession, updateParams, tmpQuery, QueryOptions.empty())
                            .toFinalUpdateDocument();

                    logger.debug("Delete cohort {} ({}): Query: {}, update: {}", cohort.getId(), cohort.getUid(),
                            bsonQuery.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()),
                            updateDocument.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()));
                    DataResult result = cohortCollection.update(clientSession, bsonQuery, updateDocument, QueryOptions.empty());

                    if (result.getNumUpdated() == 0) {
                        logger.error("Cohort {} could not be deleted", cohort.getId());
                        throw new CatalogDBException("Cohort " + cohort.getId() + " could not be deleted");
                    }

                    logger.debug("Cohort {} successfully deleted", cohort.getId());
                    return endWrite(tmpStartTime, 1, 1, null);
                });

                logger.info("Cohort {} successfully deleted", cohort.getId());
                numModified += 1;
            } catch (Exception e) {
                String errorMsg = "Could not delete cohort " + cohort.getId() + ": " + e.getMessage();
                logger.error("{}", errorMsg);
                warnings.add(errorMsg);
            }
        }

        return endWrite(startTime, numMatches, numModified, warnings);
    }

    private void checkCohortCanBeDeleted(Cohort cohort) throws CatalogDBException {
        // Check if the cohort is different from DEFAULT_COHORT
        if (StudyEntry.DEFAULT_COHORT.equals(cohort.getId())) {
            throw new CatalogDBException("Cohort " + StudyEntry.DEFAULT_COHORT + " cannot be deleted.");
        }

        // Check if the cohort can be deleted
        if (cohort.getStatus() != null && cohort.getStatus().getName() != null
                && !cohort.getStatus().getName().equals(Cohort.CohortStatus.NONE)) {
            throw new CatalogDBException("Cohort in use in storage.");
        }
    }

    DataResult setStatus(long cohortId, String status) throws CatalogDBException {
        return update(cohortId, new ObjectMap(QueryParams.STATUS_NAME.key(), status), QueryOptions.empty());
    }

    DataResult setStatus(Query query, String status) throws CatalogDBException {
        return update(query, new ObjectMap(QueryParams.STATUS_NAME.key(), status), QueryOptions.empty());
    }

    @Override
    public DataResult remove(long id, QueryOptions queryOptions) throws CatalogDBException {
        throw new UnsupportedOperationException("Operation not yet supported.");
    }

    @Override
    public DataResult remove(Query query, QueryOptions queryOptions) throws CatalogDBException {
        throw new UnsupportedOperationException("Operation not yet supported.");
    }

    @Override
    public DataResult restore(long id, QueryOptions queryOptions) throws CatalogDBException {
        checkId(id);
        // Check if the cohort is active
        Query query = new Query(QueryParams.UID.key(), id)
                .append(QueryParams.STATUS_NAME.key(), Status.DELETED);
        if (count(query).first() == 0) {
            throw new CatalogDBException("The cohort {" + id + "} is not deleted");
        }

        // Change the status of the cohort to deleted
        return setStatus(id, Cohort.CohortStatus.NONE);
    }

    @Override
    public DataResult restore(Query query, QueryOptions queryOptions) throws CatalogDBException {
        query.put(QueryParams.STATUS_NAME.key(), Status.DELETED);
        return setStatus(query, Cohort.CohortStatus.NONE);
    }

    @Override
    public DataResult<Cohort> get(long cohortId, QueryOptions options) throws CatalogDBException {
        Query query = new Query()
                .append(QueryParams.UID.key(), cohortId)
                .append(QueryParams.STUDY_UID.key(), getStudyId(cohortId))
                .append(QueryParams.STATUS_NAME.key(), "!=" + Status.DELETED);
        return get(query, options);
    }

    @Override
    public DataResult<Cohort> get(Query query, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        return get(null, query, options, user);
    }

    DataResult<Cohort> get(ClientSession clientSession, Query query, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        long startTime = startQuery();
        List<Cohort> documentList = new ArrayList<>();
        DataResult<Cohort> queryResult;
        try (DBIterator<Cohort> dbIterator = iterator(clientSession, query, options, user)) {
            while (dbIterator.hasNext()) {
                documentList.add(dbIterator.next());
            }
        }
        queryResult = endQuery(startTime, documentList);

        if (options != null && options.getBoolean(QueryOptions.SKIP_COUNT, false)) {
            return queryResult;
        }

        // We only count the total number of results if the actual number of results equals the limit established for performance purposes.
        if (options != null && options.getInt(QueryOptions.LIMIT, 0) == queryResult.getNumResults()) {
            DataResult<Long> count = count(clientSession, query, user, StudyAclEntry.StudyPermissions.VIEW_COHORTS);
            queryResult.setNumTotalResults(count.first());
        }
        return queryResult;
    }

    @Override
    public DataResult<Cohort> get(Query query, QueryOptions options) throws CatalogDBException {
        return get(null, query, options);
    }

    DataResult<Cohort> get(ClientSession clientSession, Query query, QueryOptions options) throws CatalogDBException {
        long startTime = startQuery();
        List<Cohort> documentList = new ArrayList<>();
        try (DBIterator<Cohort> dbIterator = iterator(clientSession, query, options)) {
            while (dbIterator.hasNext()) {
                documentList.add(dbIterator.next());
            }
        }
        DataResult<Cohort> queryResult = endQuery(startTime, documentList);

        if (options != null && options.getBoolean(QueryOptions.SKIP_COUNT, false)) {
            return queryResult;
        }

        // We only count the total number of results if the actual number of results equals the limit established for performance purposes.
        if (options != null && options.getInt(QueryOptions.LIMIT, 0) == queryResult.getNumResults()) {
            DataResult<Long> count = count(clientSession, query);
            queryResult.setNumTotalResults(count.first());
        }
        return queryResult;
    }

    @Override
    public DataResult nativeGet(Query query, QueryOptions options) throws CatalogDBException {
        long startTime = startQuery();
        List<Document> documentList = new ArrayList<>();
        DataResult<Document> queryResult;
        try (DBIterator<Document> dbIterator = nativeIterator(query, options)) {
            while (dbIterator.hasNext()) {
                documentList.add(dbIterator.next());
            }
        }
        queryResult = endQuery(startTime, documentList);

        if (options != null && options.getBoolean(QueryOptions.SKIP_COUNT, false)) {
            return queryResult;
        }

        // We only count the total number of results if the actual number of results equals the limit established for performance purposes.
        if (options != null && options.getInt(QueryOptions.LIMIT, 0) == queryResult.getNumResults()) {
            DataResult<Long> count = count(query);
            queryResult.setNumTotalResults(count.first());
        }
        return queryResult;
    }

    @Override
    public DataResult nativeGet(Query query, QueryOptions options, String user) throws CatalogDBException, CatalogAuthorizationException {
        long startTime = startQuery();
        List<Document> documentList = new ArrayList<>();
        DataResult<Document> queryResult;
        try (DBIterator<Document> dbIterator = nativeIterator(query, options, user)) {
            while (dbIterator.hasNext()) {
                documentList.add(dbIterator.next());
            }
        }
        queryResult = endQuery(startTime, documentList);

        if (options != null && options.getBoolean(QueryOptions.SKIP_COUNT, false)) {
            return queryResult;
        }

        // We only count the total number of results if the actual number of results equals the limit established for performance purposes.
        if (options != null && options.getInt(QueryOptions.LIMIT, 0) == queryResult.getNumResults()) {
            DataResult<Long> count = count(query);
            queryResult.setNumTotalResults(count.first());
        }
        return queryResult;
    }

    @Override
    public DBIterator<Cohort> iterator(Query query, QueryOptions options) throws CatalogDBException {
        return iterator(null, query, options);
    }

    DBIterator<Cohort> iterator(ClientSession clientSession, Query query, QueryOptions options) throws CatalogDBException {
        MongoCursor<Document> mongoCursor = getMongoCursor(clientSession, query, options);
        return new CohortMongoDBIterator(mongoCursor, clientSession, cohortConverter, null, dbAdaptorFactory.getCatalogSampleDBAdaptor(),
                query.getLong(PRIVATE_STUDY_UID), null, options);
    }

    @Override
    public DBIterator<Document> nativeIterator(Query query, QueryOptions options) throws CatalogDBException {
        QueryOptions queryOptions = options != null ? new QueryOptions(options) : new QueryOptions();
        queryOptions.put(NATIVE_QUERY, true);

        MongoCursor<Document> mongoCursor = getMongoCursor(null, query, queryOptions);
        return new CohortMongoDBIterator(mongoCursor, null, null, null, dbAdaptorFactory.getCatalogSampleDBAdaptor(),
                query.getLong(PRIVATE_STUDY_UID), null, options);
    }

    @Override
    public DBIterator<Cohort> iterator(Query query, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        return iterator(null, query, options, user);
    }

    DBIterator<Cohort> iterator(ClientSession clientSession, Query query, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        Document studyDocument = getStudyDocument(query);
        MongoCursor<Document> mongoCursor = getMongoCursor(clientSession, query, options, studyDocument, user);
        Function<Document, Document> iteratorFilter = (d) -> filterAnnotationSets(studyDocument, d, user,
                StudyAclEntry.StudyPermissions.VIEW_COHORT_ANNOTATIONS.name(), CohortAclEntry.CohortPermissions.VIEW_ANNOTATIONS.name());

        return new CohortMongoDBIterator<>(mongoCursor, clientSession, cohortConverter, iteratorFilter,
                dbAdaptorFactory.getCatalogSampleDBAdaptor(), query.getLong(PRIVATE_STUDY_UID), user, options);
    }

    @Override
    public DBIterator nativeIterator(Query query, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        QueryOptions queryOptions = options != null ? new QueryOptions(options) : new QueryOptions();
        queryOptions.put(NATIVE_QUERY, true);

        Document studyDocument = getStudyDocument(query);
        MongoCursor<Document> mongoCursor = getMongoCursor(null, query, queryOptions, studyDocument, user);
        Function<Document, Document> iteratorFilter = (d) -> filterAnnotationSets(studyDocument, d, user,
                StudyAclEntry.StudyPermissions.VIEW_COHORT_ANNOTATIONS.name(), CohortAclEntry.CohortPermissions.VIEW_ANNOTATIONS.name());

        return new CohortMongoDBIterator(mongoCursor, null, null, iteratorFilter, dbAdaptorFactory.getCatalogSampleDBAdaptor(),
                query.getLong(PRIVATE_STUDY_UID), user, options);
    }

    private MongoCursor<Document> getMongoCursor(ClientSession clientSession, Query query, QueryOptions options) throws CatalogDBException {
        MongoCursor<Document> documentMongoCursor;
        try {
            documentMongoCursor = getMongoCursor(clientSession, query, options, null, null);
        } catch (CatalogAuthorizationException e) {
            throw new CatalogDBException(e);
        }
        return documentMongoCursor;
    }

    private MongoCursor<Document> getMongoCursor(ClientSession clientSession, Query query, QueryOptions options, Document studyDocument,
                                                 String user) throws CatalogDBException, CatalogAuthorizationException {
        Document queryForAuthorisedEntries = null;
        if (studyDocument != null && user != null) {
            // Get the document query needed to check the permissions as well
            queryForAuthorisedEntries = getQueryForAuthorisedEntries(studyDocument, user,
                    StudyAclEntry.StudyPermissions.VIEW_COHORTS.name(), CohortAclEntry.CohortPermissions.VIEW.name(), Entity.COHORT.name());
        }

        filterOutDeleted(query);
        Bson bson = parseQuery(query, queryForAuthorisedEntries);

        QueryOptions qOptions;
        if (options != null) {
            qOptions = new QueryOptions(options);
        } else {
            qOptions = new QueryOptions();
        }
        qOptions = removeInnerProjections(qOptions, QueryParams.SAMPLES.key());
        qOptions = removeAnnotationProjectionOptions(qOptions);
        qOptions = filterOptions(qOptions, FILTER_ROUTE_COHORTS);

        logger.debug("Cohort query : {}", bson.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()));
        return cohortCollection.nativeQuery().find(clientSession, bson, qOptions).iterator();
    }

    private Document getStudyDocument(Query query) throws CatalogDBException {
        // Get the study document
        Query studyQuery = new Query(StudyDBAdaptor.QueryParams.UID.key(), query.getLong(QueryParams.STUDY_UID.key()));
        DataResult<Document> queryResult = dbAdaptorFactory.getCatalogStudyDBAdaptor().nativeGet(studyQuery, QueryOptions.empty());
        if (queryResult.getNumResults() == 0) {
            throw new CatalogDBException("Study " + query.getLong(QueryParams.STUDY_UID.key()) + " not found");
        }
        return queryResult.first();
    }

    @Override
    public DataResult rank(Query query, String field, int numResults, boolean asc) throws CatalogDBException {
        filterOutDeleted(query);
        Bson bsonQuery = parseQuery(query);
        return rank(cohortCollection, bsonQuery, field, "name", numResults, asc);
    }

    @Override
    public DataResult groupBy(Query query, String field, QueryOptions options) throws CatalogDBException {
        filterOutDeleted(query);
        Bson bsonQuery = parseQuery(query);
        return groupBy(cohortCollection, bsonQuery, field, "name", options);
    }

    @Override
    public DataResult groupBy(Query query, List<String> fields, QueryOptions options) throws CatalogDBException {
        filterOutDeleted(query);
        Bson bsonQuery = parseQuery(query);
        return groupBy(cohortCollection, bsonQuery, fields, "name", options);
    }

    @Override
    public DataResult groupBy(Query query, String field, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        Document studyDocument = getStudyDocument(query);
        Document queryForAuthorisedEntries;
        if (containsAnnotationQuery(query)) {
            queryForAuthorisedEntries = getQueryForAuthorisedEntries(studyDocument, user,
                    StudyAclEntry.StudyPermissions.VIEW_COHORT_ANNOTATIONS.name(),
                    CohortAclEntry.CohortPermissions.VIEW_ANNOTATIONS.name(), Entity.COHORT.name());
        } else {
            queryForAuthorisedEntries = getQueryForAuthorisedEntries(studyDocument, user,
                    StudyAclEntry.StudyPermissions.VIEW_COHORTS.name(), CohortAclEntry.CohortPermissions.VIEW.name(), Entity.COHORT.name());
        }
        filterOutDeleted(query);
        Bson bsonQuery = parseQuery(query, queryForAuthorisedEntries);
        return groupBy(cohortCollection, bsonQuery, field, QueryParams.ID.key(), options);
    }

    @Override
    public DataResult groupBy(Query query, List<String> fields, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        Document studyDocument = getStudyDocument(query);
        Document queryForAuthorisedEntries;
        if (containsAnnotationQuery(query)) {
            queryForAuthorisedEntries = getQueryForAuthorisedEntries(studyDocument, user,
                    StudyAclEntry.StudyPermissions.VIEW_COHORT_ANNOTATIONS.name(),
                    CohortAclEntry.CohortPermissions.VIEW_ANNOTATIONS.name(), Entity.COHORT.name());
        } else {
            queryForAuthorisedEntries = getQueryForAuthorisedEntries(studyDocument, user,
                    StudyAclEntry.StudyPermissions.VIEW_COHORTS.name(), CohortAclEntry.CohortPermissions.VIEW.name(), Entity.COHORT.name());
        }
        filterOutDeleted(query);
        Bson bsonQuery = parseQuery(query, queryForAuthorisedEntries);
        return groupBy(cohortCollection, bsonQuery, fields, QueryParams.ID.key(), options);
    }

    @Override
    public void forEach(Query query, Consumer<? super Object> action, QueryOptions options) throws CatalogDBException {
        Objects.requireNonNull(action);
        try (DBIterator<Cohort> catalogDBIterator = iterator(query, options)) {
            while (catalogDBIterator.hasNext()) {
                action.accept(catalogDBIterator.next());
            }
        }
    }

    private void checkCohortIdExists(ClientSession clientSession, long studyId, String cohortId) throws CatalogDBException {
        DataResult<Long> count = cohortCollection.count(clientSession, Filters.and(
                Filters.eq(PRIVATE_STUDY_UID, studyId), Filters.eq(QueryParams.ID.key(), cohortId)));
        if (count.getResults().get(0) > 0) {
            throw CatalogDBException.alreadyExists("Cohort", "id", cohortId);
        }
    }

    private Bson parseQuery(Query query) throws CatalogDBException {
        return parseQuery(query, null);
    }

    protected Bson parseQuery(Query query, Document authorisation) throws CatalogDBException {
        List<Bson> andBsonList = new ArrayList<>();
        Document annotationDocument = null;

        fixComplexQueryParam(QueryParams.ATTRIBUTES.key(), query);
        fixComplexQueryParam(QueryParams.BATTRIBUTES.key(), query);
        fixComplexQueryParam(QueryParams.NATTRIBUTES.key(), query);

        for (Map.Entry<String, Object> entry : query.entrySet()) {
            String key = entry.getKey().split("\\.")[0];
            QueryParams queryParam = QueryParams.getParam(entry.getKey()) != null ? QueryParams.getParam(entry.getKey())
                    : QueryParams.getParam(key);
            if (queryParam == null) {
                if (Constants.PRIVATE_ANNOTATION_PARAM_TYPES.equals(entry.getKey())) {
                    continue;
                }
                throw new CatalogDBException("Unexpected parameter " + entry.getKey() + ". The parameter does not exist or cannot be "
                        + "queried for.");
            }
            try {
                switch (queryParam) {
                    case UID:
                        addAutoOrQuery(PRIVATE_UID, queryParam.key(), query, queryParam.type(), andBsonList);
                        break;
                    case STUDY_UID:
                        addAutoOrQuery(PRIVATE_STUDY_UID, queryParam.key(), query, queryParam.type(), andBsonList);
                        break;
                    case ATTRIBUTES:
                        addAutoOrQuery(entry.getKey(), entry.getKey(), query, queryParam.type(), andBsonList);
                        break;
                    case BATTRIBUTES:
                        String mongoKey = entry.getKey().replace(QueryParams.BATTRIBUTES.key(), QueryParams.ATTRIBUTES.key());
                        addAutoOrQuery(mongoKey, entry.getKey(), query, queryParam.type(), andBsonList);
                        break;
                    case NATTRIBUTES:
                        mongoKey = entry.getKey().replace(QueryParams.NATTRIBUTES.key(), QueryParams.ATTRIBUTES.key());
                        addAutoOrQuery(mongoKey, entry.getKey(), query, queryParam.type(), andBsonList);
                        break;
                    case ANNOTATION:
                        if (annotationDocument == null) {
                            annotationDocument = createAnnotationQuery(query.getString(QueryParams.ANNOTATION.key()),
                                    query.get(Constants.PRIVATE_ANNOTATION_PARAM_TYPES, ObjectMap.class));
//                            annotationDocument = createAnnotationQuery(query.getString(QueryParams.ANNOTATION.key()),
//                                    query.getLong(QueryParams.VARIABLE_SET_UID.key()),
//                                    query.getString(QueryParams.ANNOTATION_SET_NAME.key()));
                        }
                        break;
                    case SAMPLE_UIDS:
                        addQueryFilter(queryParam.key(), queryParam.key(), query, queryParam.type(),
                                MongoDBQueryUtils.ComparisonOperator.IN, MongoDBQueryUtils.LogicalOperator.OR, andBsonList);
                        break;
                    case CREATION_DATE:
                        addAutoOrQuery(PRIVATE_CREATION_DATE, queryParam.key(), query, queryParam.type(), andBsonList);
                        break;
                    case MODIFICATION_DATE:
                        addAutoOrQuery(PRIVATE_MODIFICATION_DATE, queryParam.key(), query, queryParam.type(), andBsonList);
                        break;
                    case STATUS_NAME:
                        // Convert the status to a positive status
                        query.put(queryParam.key(),
                                Status.getPositiveStatus(Cohort.CohortStatus.STATUS_LIST, query.getString(queryParam.key())));
                        addAutoOrQuery(queryParam.key(), queryParam.key(), query, queryParam.type(), andBsonList);
                        break;
                    case UUID:
                    case ID:
                    case NAME:
                    case TYPE:
                    case RELEASE:
                    case STATUS_MSG:
                    case STATUS_DATE:
                    case DESCRIPTION:
                    case ANNOTATION_SETS:
//                    case VARIABLE_NAME:
                        addAutoOrQuery(queryParam.key(), queryParam.key(), query, queryParam.type(), andBsonList);
                        break;
                    default:
                        throw new CatalogDBException("Cannot query by parameter " + queryParam.key());
                }
            } catch (Exception e) {
                throw new CatalogDBException(e);
            }
        }

        if (annotationDocument != null && !annotationDocument.isEmpty()) {
            andBsonList.add(annotationDocument);
        }
        if (authorisation != null && authorisation.size() > 0) {
            andBsonList.add(authorisation);
        }
        if (andBsonList.size() > 0) {
            return Filters.and(andBsonList);
        } else {
            return new Document();
        }
    }

    public MongoDBCollection getCohortCollection() {
        return cohortCollection;
    }

    void removeSampleReferences(ClientSession clientSession, long studyUid, long sampleUid) throws CatalogDBException {
        Document bsonQuery = new Document()
                .append(QueryParams.STUDY_UID.key(), studyUid)
                .append(QueryParams.SAMPLE_UIDS.key(), sampleUid);

        // We set the status of all the matching cohorts to INVALID and add the sample to be removed
        ObjectMap params = new ObjectMap()
                .append(QueryParams.STATUS_NAME.key(), Cohort.CohortStatus.INVALID)
                .append(QueryParams.SAMPLES.key(), Collections.singletonList(new Sample().setUid(sampleUid)));
        // Add the the Remove action for the sample provided
        QueryOptions queryOptions = new QueryOptions(Constants.ACTIONS,
                new ObjectMap(QueryParams.SAMPLES.key(), ParamUtils.UpdateAction.REMOVE.name()));

        Bson update = parseAndValidateUpdateParams(clientSession, params, null, queryOptions).toFinalUpdateDocument();

        QueryOptions multi = new QueryOptions(MongoDBCollection.MULTI, true);

        logger.debug("Sample references extraction. Query: {}, update: {}",
                bsonQuery.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()),
                update.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()));
        DataResult result = cohortCollection.update(clientSession, bsonQuery, update, multi);
        logger.debug("Sample uid '" + sampleUid + "' references removed from " + result.getNumUpdated() + " out of "
                + result.getNumMatches() + " cohorts");
    }

}
