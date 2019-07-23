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
import com.mongodb.client.TransactionBody;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.result.UpdateResult;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.commons.datastore.core.result.Error;
import org.opencb.commons.datastore.core.result.WriteResult;
import org.opencb.commons.datastore.mongodb.MongoDBCollection;
import org.opencb.opencga.catalog.db.api.*;
import org.opencb.opencga.catalog.db.mongodb.converters.AnnotableConverter;
import org.opencb.opencga.catalog.db.mongodb.converters.SampleConverter;
import org.opencb.opencga.catalog.db.mongodb.iterators.SampleMongoDBIterator;
import org.opencb.opencga.catalog.exceptions.CatalogAuthorizationException;
import org.opencb.opencga.catalog.exceptions.CatalogDBException;
import org.opencb.opencga.catalog.exceptions.CatalogException;
import org.opencb.opencga.catalog.managers.AnnotationSetManager;
import org.opencb.opencga.catalog.utils.Constants;
import org.opencb.opencga.catalog.utils.UUIDUtils;
import org.opencb.opencga.core.common.Entity;
import org.opencb.opencga.core.common.TimeUtils;
import org.opencb.opencga.core.models.*;
import org.opencb.opencga.core.models.acls.permissions.SampleAclEntry;
import org.opencb.opencga.core.models.acls.permissions.StudyAclEntry;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.opencb.opencga.catalog.db.mongodb.AuthorizationMongoDBUtils.filterAnnotationSets;
import static org.opencb.opencga.catalog.db.mongodb.AuthorizationMongoDBUtils.getQueryForAuthorisedEntries;
import static org.opencb.opencga.catalog.db.mongodb.MongoDBUtils.*;

/**
 * Created by hpccoll1 on 14/08/15.
 */
public class SampleMongoDBAdaptor extends AnnotationMongoDBAdaptor<Sample> implements SampleDBAdaptor {

    private final MongoDBCollection sampleCollection;
    private SampleConverter sampleConverter;

    public SampleMongoDBAdaptor(MongoDBCollection sampleCollection, MongoDBAdaptorFactory dbAdaptorFactory) {
        super(LoggerFactory.getLogger(SampleMongoDBAdaptor.class));
        this.dbAdaptorFactory = dbAdaptorFactory;
        this.sampleCollection = sampleCollection;
        this.sampleConverter = new SampleConverter();
    }

    @Override
    protected AnnotableConverter<? extends Annotable> getConverter() {
        return sampleConverter;
    }

    @Override
    protected MongoDBCollection getCollection() {
        return sampleCollection;
    }

    /*
     * Samples methods
     * ***************************
     */

    public boolean exists(ClientSession clientSession, long sampleUid) throws CatalogDBException {
        return count(clientSession, new Query(QueryParams.UID.key(), sampleUid)).first() > 0;
    }

    @Override
    public void nativeInsert(Map<String, Object> sample, String userId) throws CatalogDBException {
        Document sampleDocument = getMongoDBDocument(sample, "sample");
        sampleCollection.insert(sampleDocument, null);
    }

    Sample insert(ClientSession clientSession, long studyId, Sample sample, List<VariableSet> variableSetList) throws CatalogDBException {
        dbAdaptorFactory.getCatalogStudyDBAdaptor().checkId(studyId);

        if (StringUtils.isEmpty(sample.getId())) {
            throw new CatalogDBException("Missing sample id");
        }

        // Check the sample does not exist
        List<Bson> filterList = new ArrayList<>();
        filterList.add(Filters.eq(QueryParams.ID.key(), sample.getId()));
        filterList.add(Filters.eq(PRIVATE_STUDY_UID, studyId));
        filterList.add(Filters.eq(QueryParams.STATUS_NAME.key(), Status.READY));

        Bson bson = Filters.and(filterList);
        QueryResult<Long> count = sampleCollection.count(clientSession, bson);
        if (count.getResult().get(0) > 0) {
            throw new CatalogDBException("Sample { id: '" + sample.getId() + "'} already exists.");
        }

        long sampleId = getNewUid(clientSession);
        sample.setUid(sampleId);
        sample.setStudyUid(studyId);
        sample.setVersion(1);
        if (StringUtils.isEmpty(sample.getUuid())) {
            sample.setUuid(UUIDUtils.generateOpenCGAUUID(UUIDUtils.Entity.SAMPLE));
        }
        if (StringUtils.isEmpty(sample.getCreationDate())) {
            sample.setCreationDate(TimeUtils.getTime());
        }

        Document sampleObject = sampleConverter.convertToStorageType(sample, variableSetList);

        // Versioning private parameters
        sampleObject.put(RELEASE_FROM_VERSION, Arrays.asList(sample.getRelease()));
        sampleObject.put(LAST_OF_VERSION, true);
        sampleObject.put(LAST_OF_RELEASE, true);
        sampleObject.put(PRIVATE_CREATION_DATE, TimeUtils.toDate(sample.getCreationDate()));
        sampleObject.put(PERMISSION_RULES_APPLIED, Collections.emptyList());

        logger.debug("Inserting sample '{}' ({})...", sample.getId(), sample.getUid());
        sampleCollection.insert(clientSession, sampleObject, null);
        logger.debug("Sample '{}' successfully inserted", sample.getId());

        return sample;
    }

    @Override
    public QueryResult<Sample> insert(long studyId, Sample sample, List<VariableSet> variableSetList, QueryOptions options)
            throws CatalogDBException {
        long startQuery = startQuery();

        ClientSession clientSession = getClientSession();
        TransactionBody<WriteResult> txnBody = () -> {
            long tmpStartTime = startQuery();

            logger.debug("Starting sample insert transaction for sample id '{}'", sample.getId());

            try {
                dbAdaptorFactory.getCatalogStudyDBAdaptor().checkId(clientSession, studyId);
                Sample createdSample = insert(clientSession, studyId, sample, variableSetList);
                return endWrite(String.valueOf(createdSample.getUid()), tmpStartTime, 1, 1, null);
            } catch (CatalogDBException e) {
                logger.error("Could not create sample {}: {}", sample.getId(), e.getMessage());
                clientSession.abortTransaction();
                return endWrite(sample.getId(), tmpStartTime, 1, 0,
                        Collections.singletonList(new WriteResult.Fail(sample.getId(), e.getMessage())));
            }
        };

        WriteResult result = commitTransaction(clientSession, txnBody);

        if (result.getNumModified() == 1) {
            Query query = new Query()
                    .append(QueryParams.STUDY_UID.key(), studyId)
                    .append(QueryParams.UID.key(), Long.parseLong(result.getId()));
            return endQuery("createSample", startQuery, get(query, options));
        } else {
            throw new CatalogDBException(result.getFailed().get(0).getMessage());
        }
    }


    @Override
    public QueryResult<Sample> getAllInStudy(long studyId, QueryOptions options) throws CatalogDBException {
        long startTime = startQuery();
        Query query = new Query(QueryParams.STUDY_UID.key(), studyId);
        return endQuery("Get all files", startTime, get(query, options).getResult());
    }

    @Override
    public QueryResult<Sample> update(long id, ObjectMap parameters, QueryOptions queryOptions) throws CatalogDBException {
        return update(id, parameters, Collections.emptyList(), queryOptions);
    }

    @Override
    public QueryResult<AnnotationSet> getAnnotationSet(long id, @Nullable String annotationSetName) throws CatalogDBException {
        QueryOptions queryOptions = new QueryOptions();
        List<String> includeList = new ArrayList<>();

        if (StringUtils.isNotEmpty(annotationSetName)) {
            includeList.add(Constants.ANNOTATION_SET_NAME + "." + annotationSetName);
        } else {
            includeList.add(QueryParams.ANNOTATION_SETS.key());
        }
        queryOptions.put(QueryOptions.INCLUDE, includeList);

        QueryResult<Sample> sampleQueryResult = get(id, queryOptions);
        if (sampleQueryResult.first().getAnnotationSets().isEmpty()) {
            return new QueryResult<>("Get annotation set", sampleQueryResult.getDbTime(), 0, 0, sampleQueryResult.getWarningMsg(),
                    sampleQueryResult.getErrorMsg(), Collections.emptyList());
        } else {
            List<AnnotationSet> annotationSets = sampleQueryResult.first().getAnnotationSets();
            int size = annotationSets.size();
            return new QueryResult<>("Get annotation set", sampleQueryResult.getDbTime(), size, size, sampleQueryResult.getWarningMsg(),
                    sampleQueryResult.getErrorMsg(), annotationSets);
        }
    }

    @Override
    public QueryResult<Sample> update(long id, ObjectMap parameters, List<VariableSet> variableSetList, QueryOptions queryOptions)
            throws CatalogDBException {
        long startTime = startQuery();
        WriteResult update = update(new Query(QueryParams.UID.key(), id), parameters, variableSetList, queryOptions);
        if (update.getNumModified() != 1 && parameters.size() > 0 && !(parameters.size() <= 2
                && (parameters.containsKey(QueryParams.ANNOTATION_SETS.key())
                || parameters.containsKey(AnnotationSetManager.ANNOTATIONS)))) {
            throw new CatalogDBException("Could not update sample with id " + id);
        }
        Query query = new Query()
                .append(QueryParams.UID.key(), id)
                .append(QueryParams.STUDY_UID.key(), getStudyId(id))
                .append(QueryParams.STATUS_NAME.key(), "!=EMPTY");
        return endQuery("Update sample", startTime, get(query, queryOptions));
    }

    @Override
    public WriteResult update(Query query, ObjectMap parameters, QueryOptions queryOptions) throws CatalogDBException {
        return update(query, parameters, Collections.emptyList(), queryOptions);
    }

    @Override
    public WriteResult update(Query query, ObjectMap parameters, List<VariableSet> variableSetList, QueryOptions queryOptions)
            throws CatalogDBException {
        long startTime = startQuery();

        if (parameters.containsKey(QueryParams.ID.key())) {
            // We need to check that the update is only performed over 1 single sample
            if (count(query).first() != 1) {
                throw new CatalogDBException("Operation not supported: '" + QueryParams.ID.key() + "' can only be updated for one sample");
            }
        }

        QueryOptions options = new QueryOptions(QueryOptions.INCLUDE,
                Arrays.asList(QueryParams.ID.key(), QueryParams.UID.key(), QueryParams.VERSION.key(), QueryParams.STUDY_UID.key()));
        DBIterator<Sample> iterator = iterator(query, options);

        int numMatches = 0;
        int numModified = 0;
        List<WriteResult.Fail> failList = new ArrayList<>();

        while (iterator.hasNext()) {
            Sample sample = iterator.next();
            numMatches += 1;

            ClientSession clientSession = getClientSession();
            TransactionBody<WriteResult> txnBody = () -> {
                long tmpStartTime = startQuery();
                try {
                    Query tmpQuery = new Query()
                            .append(QueryParams.STUDY_UID.key(), sample.getStudyUid())
                            .append(QueryParams.UID.key(), sample.getUid());

                    if (queryOptions.getBoolean(Constants.INCREMENT_VERSION)) {
                        createNewVersion(clientSession, sample.getStudyUid(), sample.getUid());
                    }

                    // Perform the update
                    updateAnnotationSets(clientSession, sample.getUid(), parameters, variableSetList, queryOptions, true);

                    Document sampleUpdate = parseAndValidateUpdateParams(clientSession, parameters, tmpQuery).toFinalUpdateDocument();

                    if (!sampleUpdate.isEmpty()) {
                        Bson finalQuery = parseQuery(tmpQuery);

                        logger.debug("Sample update: query : {}, update: {}",
                                finalQuery.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()),
                                sampleUpdate.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()));
                        sampleCollection.update(clientSession, finalQuery, sampleUpdate, new QueryOptions("multi", true)).first();
                    }

                    return endWrite(sample.getId(), tmpStartTime, 1, 1, null);
                } catch (CatalogDBException e) {
                    logger.error("Error updating sample {}({}). {}", sample.getId(), sample.getUid(), e.getMessage(), e);
                    clientSession.abortTransaction();
                    return endWrite(sample.getId(), tmpStartTime, 1, 0,
                            Collections.singletonList(new WriteResult.Fail(sample.getId(), e.getMessage())));
                }
            };

            WriteResult result = commitTransaction(clientSession, txnBody);

            if (result.getNumModified() == 1) {
                logger.info("Sample {} successfully updated", sample.getId());
                numModified += 1;
            } else {
                if (result.getFailed() != null && !result.getFailed().isEmpty()) {
                    logger.error("Could not update sample {}: {}", sample.getId(), result.getFailed().get(0).getMessage());
                    failList.addAll(result.getFailed());
                } else {
                    logger.error("Could not update sample {}", sample.getId());
                }
            }
        }

        Error error = null;
        if (!failList.isEmpty()) {
            error = new Error(-1, "update", (numModified == 0
                    ? "None of the samples could be updated"
                    : "Some of the samples could not be updated"));
        }

        return endWrite("update", startTime, numMatches, numModified, failList, null, error);
    }

    private void createNewVersion(ClientSession clientSession, long studyUid, long sampleUid) throws CatalogDBException {
        Query query = new Query()
                .append(QueryParams.STUDY_UID.key(), studyUid)
                .append(QueryParams.UID.key(), sampleUid);
        QueryResult<Document> queryResult = nativeGet(clientSession, query, new QueryOptions(QueryOptions.EXCLUDE, "_id"));

        if (queryResult.getNumResults() == 0) {
            throw new CatalogDBException("Could not find sample '" + sampleUid + "'");
        }

        createNewVersion(clientSession, sampleCollection, queryResult.first());
    }

    private UpdateDocument parseAndValidateUpdateParams(ClientSession clientSession, ObjectMap parameters, Query query)
            throws CatalogDBException {
        UpdateDocument document = new UpdateDocument();

        final String[] acceptedBooleanParams = {QueryParams.SOMATIC.key()};
        filterBooleanParams(parameters, document.getSet(), acceptedBooleanParams);

        final String[] acceptedParams = {QueryParams.NAME.key(), QueryParams.SOURCE.key(), QueryParams.DESCRIPTION.key(),
                QueryParams.TYPE.key()};
        filterStringParams(parameters, document.getSet(), acceptedParams);

        final String[] acceptedMapParams = {QueryParams.STATS.key(), QueryParams.ATTRIBUTES.key()};
        filterMapParams(parameters, document.getSet(), acceptedMapParams);

        final String[] acceptedObjectParams = {QueryParams.PHENOTYPES.key(), UpdateParams.COLLECTION.key(), UpdateParams.PROCESSING.key()};
        filterObjectParams(parameters, document.getSet(), acceptedObjectParams);

        if (parameters.containsKey(QueryParams.ID.key())) {
            // That can only be done to one sample...

            Query tmpQuery = new Query(query);
            // We take out ALL_VERSION from query just in case we get multiple results...
            tmpQuery.remove(Constants.ALL_VERSIONS);

            QueryResult<Sample> sampleQueryResult = get(clientSession, tmpQuery, new QueryOptions());
            if (sampleQueryResult.getNumResults() == 0) {
                throw new CatalogDBException("Update sample: No sample found to be updated");
            }
            if (sampleQueryResult.getNumResults() > 1) {
                throw new CatalogDBException("Update sample: Cannot update " + QueryParams.ID.key() + " parameter. More than one sample "
                        + "found to be updated.");
            }

            // Check that the new sample name is still unique
            long studyId = sampleQueryResult.first().getStudyUid();

            tmpQuery = new Query()
                    .append(QueryParams.ID.key(), parameters.get(QueryParams.ID.key()))
                    .append(QueryParams.STUDY_UID.key(), studyId);
            QueryResult<Long> count = count(clientSession, tmpQuery);
            if (count.getResult().get(0) > 0) {
                throw new CatalogDBException("Cannot update the " + QueryParams.ID.key() + ". Sample "
                        + parameters.get(QueryParams.ID.key()) + " already exists.");
            }

            document.getSet().put(QueryParams.ID.key(), parameters.get(QueryParams.ID.key()));
        }

        if (parameters.containsKey(QueryParams.STATUS_NAME.key())) {
            document.getSet().put(QueryParams.STATUS_NAME.key(), parameters.get(QueryParams.STATUS_NAME.key()));
            document.getSet().put(QueryParams.STATUS_DATE.key(), TimeUtils.getTime());
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
    public long getStudyId(long sampleId) throws CatalogDBException {
        Bson query = new Document(PRIVATE_UID, sampleId);
        Bson projection = Projections.include(PRIVATE_STUDY_UID);
        QueryResult<Document> queryResult = sampleCollection.find(query, projection, null);

        if (!queryResult.getResult().isEmpty()) {
            Object studyId = queryResult.getResult().get(0).get(PRIVATE_STUDY_UID);
            return studyId instanceof Number ? ((Number) studyId).longValue() : Long.parseLong(studyId.toString());
        } else {
            throw CatalogDBException.uidNotFound("Sample", sampleId);
        }
    }

    @Override
    public void updateProjectRelease(long studyId, int release) throws CatalogDBException {
        Query query = new Query()
                .append(QueryParams.STUDY_UID.key(), studyId)
                .append(QueryParams.SNAPSHOT.key(), release - 1);
        Bson bson = parseQuery(query);

        Document update = new Document()
                .append("$addToSet", new Document(RELEASE_FROM_VERSION, release));

        QueryOptions queryOptions = new QueryOptions("multi", true);

        sampleCollection.update(bson, update, queryOptions);
    }

    @Override
    public void unmarkPermissionRule(long studyId, String permissionRuleId) throws CatalogException {
        unmarkPermissionRule(sampleCollection, studyId, permissionRuleId);
    }

    @Deprecated
    public void checkInUse(long sampleId) throws CatalogDBException {
        long studyId = getStudyId(sampleId);

        Query query = new Query(FileDBAdaptor.QueryParams.SAMPLE_UIDS.key(), sampleId);
        QueryOptions queryOptions = new QueryOptions(MongoDBCollection.INCLUDE, Arrays.asList(FILTER_ROUTE_FILES + FileDBAdaptor
                .QueryParams.UID.key(), FILTER_ROUTE_FILES + FileDBAdaptor.QueryParams.PATH.key()));
        QueryResult<File> fileQueryResult = dbAdaptorFactory.getCatalogFileDBAdaptor().get(query, queryOptions);
        if (fileQueryResult.getNumResults() != 0) {
            String msg = "Can't delete Sample " + sampleId + ", still in use in \"sampleId\" array of files : "
                    + fileQueryResult.getResult().stream()
                    .map(file -> "{ id: " + file.getUid() + ", path: \"" + file.getPath() + "\" }")
                    .collect(Collectors.joining(", ", "[", "]"));
            throw new CatalogDBException(msg);
        }


        queryOptions = new QueryOptions(CohortDBAdaptor.QueryParams.SAMPLES.key(), sampleId)
                .append(MongoDBCollection.INCLUDE, Arrays.asList(FILTER_ROUTE_COHORTS + CohortDBAdaptor.QueryParams.UID.key(),
                        FILTER_ROUTE_COHORTS + CohortDBAdaptor.QueryParams.ID.key()));
        QueryResult<Cohort> cohortQueryResult = dbAdaptorFactory.getCatalogCohortDBAdaptor().getAllInStudy(studyId, queryOptions);
        if (cohortQueryResult.getNumResults() != 0) {
            String msg = "Can't delete Sample " + sampleId + ", still in use in cohorts : "
                    + cohortQueryResult.getResult().stream()
                    .map(cohort -> "{ id: " + cohort.getUid() + ", name: \"" + cohort.getId() + "\" }")
                    .collect(Collectors.joining(", ", "[", "]"));
            throw new CatalogDBException(msg);
        }
    }

    /**
     * To be able to delete a sample, the sample does not have to be part of any cohort.
     *
     * @param sampleId sample id.
     * @throws CatalogDBException if the sampleId is used on any cohort.
     */
    private void checkCanDelete(long sampleId) throws CatalogDBException {
        Query query = new Query(CohortDBAdaptor.QueryParams.SAMPLES.key(), sampleId);
        if (dbAdaptorFactory.getCatalogCohortDBAdaptor().count(query).first() > 0) {
            List<Cohort> cohorts = dbAdaptorFactory.getCatalogCohortDBAdaptor()
                    .get(query, new QueryOptions(QueryOptions.INCLUDE, CohortDBAdaptor.QueryParams.UID.key())).getResult();
            throw new CatalogDBException("The sample {" + sampleId + "} cannot be deleted/removed. It is being used in "
                    + cohorts.size() + " cohorts: [" + cohorts.stream().map(Cohort::getUid).collect(Collectors.toList()).toString() + "]");
        }
    }

    @Override
    public QueryResult<Long> count(Query query) throws CatalogDBException {
        return count(null, query);
    }

    QueryResult<Long> count(ClientSession clientSession, Query query) throws CatalogDBException {
        Bson bson = parseQuery(query);
        return sampleCollection.count(clientSession, bson);
    }


    @Override
    public QueryResult<Long> count(Query query, String user, StudyAclEntry.StudyPermissions studyPermission)
            throws CatalogDBException, CatalogAuthorizationException {
        Query finalQuery = new Query(query);
        filterOutDeleted(finalQuery);

        if (studyPermission == null) {
            studyPermission = StudyAclEntry.StudyPermissions.VIEW_SAMPLES;
        }

        // Get the study document
        Query studyQuery = new Query(StudyDBAdaptor.QueryParams.UID.key(), finalQuery.getLong(QueryParams.STUDY_UID.key()));
        QueryResult queryResult = dbAdaptorFactory.getCatalogStudyDBAdaptor().nativeGet(studyQuery, QueryOptions.empty());
        if (queryResult.getNumResults() == 0) {
            throw new CatalogDBException("Study " + finalQuery.getLong(QueryParams.STUDY_UID.key()) + " not found");
        }

        // Just in case the parameter is in the query object, we attempt to remove it from the query map
        finalQuery.remove(QueryParams.INDIVIDUAL_UID.key());

        // Get the document query needed to check the permissions as well
        Document queryForAuthorisedEntries = getQueryForAuthorisedEntries((Document) queryResult.first(), user,
                studyPermission.name(), studyPermission.getSamplePermission().name(), Entity.SAMPLE.name());
        Bson bson = parseQuery(finalQuery, queryForAuthorisedEntries);

        if (query.containsKey(QueryParams.INDIVIDUAL_UID.key())) {
            // We need to do a left join
            Bson match = Aggregates.match(bson);
            Bson lookup = Aggregates.lookup("individual", QueryParams.UID.key(), IndividualDBAdaptor.QueryParams.SAMPLES.key() + ".uid",
                    "_individual");

            // We create the match for the individual id
            List<Bson> andBsonList = new ArrayList<>();
            addAutoOrQuery("_individual.uid", QueryParams.INDIVIDUAL_UID.key(), query, QueryParams.INDIVIDUAL_UID.type(), andBsonList);
            Bson individualMatch = Aggregates.match(andBsonList.get(0));

            Bson count = Aggregates.count("count");

            logger.debug("Sample count aggregation: {} -> {} -> {} -> {}",
                    match.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()),
                    lookup.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()),
                    individualMatch.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()),
                    count.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()));

            QueryResult<Document> aggregate = sampleCollection.aggregate(Arrays.asList(match, lookup, individualMatch, count),
                    QueryOptions.empty());
            long numResults = aggregate.getNumResults() == 0 ? 0 : ((int) aggregate.first().get("count"));
            return new QueryResult<>(null, aggregate.getDbTime(), 1, 1, null, null, Collections.singletonList(numResults));
        } else {
            logger.debug("Sample count query: {}", bson.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()));
            return sampleCollection.count(bson);
        }
    }

    private void filterOutDeleted(Query query) {
        if (!query.containsKey(QueryParams.STATUS_NAME.key())) {
            query.append(QueryParams.STATUS_NAME.key(), "!=" + Status.DELETED);
        }
    }

    @Override
    public QueryResult distinct(Query query, String field) throws CatalogDBException {
        Bson bson = parseQuery(query);
        return sampleCollection.distinct(field, bson);
    }

    @Override
    public QueryResult stats(Query query) {
        return null;
    }

    @Override
    public WriteResult delete(long id) throws CatalogDBException {
        Query query = new Query(QueryParams.UID.key(), id);
        return delete(query);
    }

    @Override
    public WriteResult delete(Query query) throws CatalogDBException {
        QueryOptions options = new QueryOptions(QueryOptions.INCLUDE,
                Arrays.asList(QueryParams.ID.key(), QueryParams.UID.key(), QueryParams.VERSION.key(), QueryParams.STUDY_UID.key()));
        DBIterator<Sample> iterator = iterator(query, options);

        long startTime = startQuery();
        int numMatches = 0;
        int numModified = 0;
        List<WriteResult.Fail> failList = new ArrayList<>();

        while (iterator.hasNext()) {
            Sample sample = iterator.next();
            numMatches += 1;

            ClientSession clientSession = getClientSession();
            TransactionBody<WriteResult> txnBody = () -> {
                long tmpStartTime = startQuery();
                try {
                    logger.info("Deleting sample {} ({})", sample.getId(), sample.getUid());

                    removeSampleReferences(clientSession, sample.getStudyUid(), sample.getUid());

                    // Look for all the different sample versions
                    Query sampleQuery = new Query()
                            .append(QueryParams.UID.key(), sample.getUid())
                            .append(QueryParams.STUDY_UID.key(), sample.getStudyUid())
                            .append(Constants.ALL_VERSIONS, true);
                    DBIterator<Sample> sampleDBIterator = iterator(sampleQuery, options);

                    String deleteSuffix = INTERNAL_DELIMITER + "DELETED_" + TimeUtils.getTime();

                    while (sampleDBIterator.hasNext()) {
                        Sample tmpSample = sampleDBIterator.next();

                        sampleQuery = new Query()
                                .append(QueryParams.UID.key(), tmpSample.getUid())
                                .append(QueryParams.VERSION.key(), tmpSample.getVersion())
                                .append(QueryParams.STUDY_UID.key(), tmpSample.getStudyUid());
                        // Mark the sample as deleted
                        ObjectMap updateParams = new ObjectMap()
                                .append(QueryParams.STATUS_NAME.key(), Status.DELETED)
                                .append(QueryParams.STATUS_DATE.key(), TimeUtils.getTime())
                                .append(QueryParams.ID.key(), tmpSample.getId() + deleteSuffix);

                        Bson bsonQuery = parseQuery(sampleQuery);
                        Document updateDocument = parseAndValidateUpdateParams(clientSession, updateParams, sampleQuery)
                                .toFinalUpdateDocument();

                        logger.debug("Delete version {} of sample: Query: {}, update: {}", tmpSample.getVersion(),
                                bsonQuery.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()),
                                updateDocument.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()));
                        UpdateResult sampleResult = sampleCollection.update(clientSession, bsonQuery, updateDocument,
                                QueryOptions.empty()).first();
                        if (sampleResult.getModifiedCount() == 1) {
                            logger.debug("Sample version {} successfully deleted", tmpSample.getVersion());
                        } else {
                            logger.error("Sample version {} could not be deleted", tmpSample.getVersion());
                        }
                    }

                    logger.info("Sample {}({}) deleted", sample.getId(), sample.getUid());

                    return endWrite(sample.getId(), tmpStartTime, 1, 1, null);

                } catch (CatalogDBException e) {
                    logger.error("Error deleting sample {}({}). {}", sample.getId(), sample.getUid(), e.getMessage(), e);
                    clientSession.abortTransaction();
                    return endWrite(sample.getId(), tmpStartTime, 1, 0,
                            Collections.singletonList(new WriteResult.Fail(sample.getId(), e.getMessage())));
                }
            };

            WriteResult result = commitTransaction(clientSession, txnBody);

            if (result.getNumModified() == 1) {
                logger.info("Sample {} successfully deleted", sample.getId());
                numModified += 1;
            } else {
                if (result.getFailed() != null && !result.getFailed().isEmpty()) {
                    logger.error("Could not delete sample {}: {}", sample.getId(), result.getFailed().get(0).getMessage());
                    failList.addAll(result.getFailed());
                } else {
                    logger.error("Could not delete sample {}", sample.getId());
                }
            }
        }

        Error error = null;
        if (!failList.isEmpty()) {
            error = new Error(-1, "delete", (numModified == 0
                    ? "None of the samples could be deleted"
                    : "Some of the samples could not be deleted"));
        }

        return endWrite("delete", startTime, numMatches, numModified, failList, null, error);
    }

    private void removeSampleReferences(ClientSession clientSession, long studyUid, long sampleUid) throws CatalogDBException {
        dbAdaptorFactory.getCatalogFileDBAdaptor().removeSampleReferences(clientSession, studyUid, sampleUid);
        dbAdaptorFactory.getCatalogCohortDBAdaptor().removeSampleReferences(clientSession, studyUid, sampleUid);
        dbAdaptorFactory.getCatalogIndividualDBAdaptor().removeSampleReferences(clientSession, studyUid, sampleUid);
    }

    // TODO: Check clean
    public QueryResult<Sample> clean(long id) throws CatalogDBException {
        throw new UnsupportedOperationException("Clean is not yet implemented.");
    }

    @Override
    public QueryResult<Long> restore(Query query, QueryOptions queryOptions) throws CatalogDBException {
        long startTime = startQuery();
        query.put(QueryParams.STATUS_NAME.key(), Status.DELETED);
        return endQuery("Restore samples", startTime, setStatus(query, Status.READY));
    }

    @Override
    public QueryResult<Sample> restore(long id, QueryOptions queryOptions) throws CatalogDBException {
        long startTime = startQuery();

        checkId(id);
        // Check if the sample is active
        Query query = new Query(QueryParams.UID.key(), id)
                .append(QueryParams.STATUS_NAME.key(), Status.DELETED);
        if (count(query).first() == 0) {
            throw new CatalogDBException("The sample {" + id + "} is not deleted");
        }

        // Change the status of the sample to deleted
        setStatus(id, Status.READY);
        query = new Query(QueryParams.UID.key(), id);

        return endQuery("Restore sample", startTime, get(query, null));
    }

    /***
     * Checks whether the sample id corresponds to any Individual and if it is parent of any other individual.
     * @param id Sample id that will be checked.
     * @throws CatalogDBException when the sample is parent of other individual.
     */
    @Deprecated
    public void checkSampleIsParentOfFamily(int id) throws CatalogDBException {
        Sample sample = get(id, new QueryOptions()).first();
        if (sample.getIndividual().getUid() > 0) {
            Query query = new Query(IndividualDBAdaptor.QueryParams.FATHER_UID.key(), sample.getIndividual().getUid())
                    .append(IndividualDBAdaptor.QueryParams.MOTHER_UID.key(), sample.getIndividual().getUid());
            Long count = dbAdaptorFactory.getCatalogIndividualDBAdaptor().count(query).first();
            if (count > 0) {
                throw CatalogDBException.sampleIdIsParentOfOtherIndividual(id);
            }
        }
    }

    @Override
    public QueryResult<Sample> get(long sampleId, QueryOptions options) throws CatalogDBException {
        checkId(sampleId);
        Query query = new Query(QueryParams.UID.key(), sampleId).append(QueryParams.STATUS_NAME.key(), "!=" + Status.DELETED)
                .append(QueryParams.STUDY_UID.key(), getStudyId(sampleId));
        return get(query, options);
    }

    @Override
    public QueryResult<Sample> get(Query query, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        long startTime = startQuery();
        List<Sample> documentList = new ArrayList<>();
        QueryResult<Sample> queryResult;
        try (DBIterator<Sample> dbIterator = iterator(query, options, user)) {
            while (dbIterator.hasNext()) {
                documentList.add(dbIterator.next());
            }
        }
        queryResult = endQuery("Get", startTime, documentList);

        if (options != null && options.getBoolean(QueryOptions.SKIP_COUNT, false)) {
            return queryResult;
        }

        // We only count the total number of results if the actual number of results equals the limit established for performance purposes.
        if (options != null && options.getInt(QueryOptions.LIMIT, 0) == queryResult.getNumResults()) {
            QueryResult<Long> count = count(query, user, StudyAclEntry.StudyPermissions.VIEW_SAMPLES);
            queryResult.setNumTotalResults(count.first());
        }
        return queryResult;
    }

    @Override
    public QueryResult<Sample> get(Query query, QueryOptions options) throws CatalogDBException {
        return get(null, query, options);
    }

    QueryResult<Sample> get(ClientSession clientSession, Query query, QueryOptions options) throws CatalogDBException {
        long startTime = startQuery();
        List<Sample> documentList = new ArrayList<>();
        try (DBIterator<Sample> dbIterator = iterator(clientSession, query, options)) {
            while (dbIterator.hasNext()) {
                documentList.add(dbIterator.next());
            }
        }
        QueryResult<Sample> queryResult = endQuery("Get", startTime, documentList);

        if (options != null && options.getBoolean(QueryOptions.SKIP_COUNT, false)) {
            return queryResult;
        }

        // We only count the total number of results if the actual number of results equals the limit established for performance purposes.
        if (options != null && options.getInt(QueryOptions.LIMIT, 0) == queryResult.getNumResults() && !isQueryingIndividualFields(query)) {
            QueryResult<Long> count = count(clientSession, query);
            queryResult.setNumTotalResults(count.first());
        }
        return queryResult;
    }

    @Override
    public QueryResult<Document> nativeGet(Query query, QueryOptions options) throws CatalogDBException {
        return nativeGet(null, query, options);
    }

    public QueryResult<Document> nativeGet(ClientSession clientSession, Query query, QueryOptions options) throws CatalogDBException {
        long startTime = startQuery();
        List<Document> documentList = new ArrayList<>();
        QueryResult<Document> queryResult;
        try (DBIterator<Document> dbIterator = nativeIterator(clientSession, query, options)) {
            while (dbIterator.hasNext()) {
                documentList.add(dbIterator.next());
            }
        }
        queryResult = endQuery("Native get", startTime, documentList);

        if (options != null && options.getBoolean(QueryOptions.SKIP_COUNT, false)) {
            return queryResult;
        }

        // We only count the total number of results if the actual number of results equals the limit established for performance purposes.
        if (options != null && options.getInt(QueryOptions.LIMIT, 0) == queryResult.getNumResults()) {
            QueryResult<Long> count = count(clientSession, query);
            queryResult.setNumTotalResults(count.first());
        }
        return queryResult;
    }

    @Override
    public QueryResult nativeGet(Query query, QueryOptions options, String user) throws CatalogDBException, CatalogAuthorizationException {
        return nativeGet(null, query, options, user);
    }

    public QueryResult nativeGet(ClientSession clientSession, Query query, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        long startTime = startQuery();
        List<Document> documentList = new ArrayList<>();
        QueryResult<Document> queryResult;
        try (DBIterator<Document> dbIterator = nativeIterator(clientSession, query, options, user)) {
            while (dbIterator.hasNext()) {
                documentList.add(dbIterator.next());
            }
        }
        queryResult = endQuery("Native get", startTime, documentList);

        if (options != null && options.getBoolean(QueryOptions.SKIP_COUNT, false)) {
            return queryResult;
        }

        // We only count the total number of results if the actual number of results equals the limit established for performance purposes.
        if (options != null && options.getInt(QueryOptions.LIMIT, 0) == queryResult.getNumResults()) {
            QueryResult<Long> count = count(clientSession, query);
            queryResult.setNumTotalResults(count.first());
        }
        return queryResult;
    }

    @Override
    public DBIterator<Sample> iterator(Query query, QueryOptions options) throws CatalogDBException {
        return iterator(null, query, options);
    }

    DBIterator<Sample> iterator(ClientSession clientSession, Query query, QueryOptions options) throws CatalogDBException {
        MongoCursor<Document> mongoCursor = getMongoCursor(clientSession, query, options);
        return new SampleMongoDBIterator<>(mongoCursor, clientSession, sampleConverter, null,
                dbAdaptorFactory.getCatalogIndividualDBAdaptor(), query.getLong(PRIVATE_STUDY_UID), null, options);
    }

    @Override
    public DBIterator nativeIterator(Query query, QueryOptions options) throws CatalogDBException {
        return nativeIterator(null, query, options);
    }

    DBIterator nativeIterator(ClientSession clientSession, Query query, QueryOptions options) throws CatalogDBException {
        QueryOptions queryOptions = options != null ? new QueryOptions(options) : new QueryOptions();
        queryOptions.put(NATIVE_QUERY, true);
        MongoCursor<Document> mongoCursor = getMongoCursor(clientSession, query, queryOptions);
        return new SampleMongoDBIterator(mongoCursor, clientSession, null, null, dbAdaptorFactory.getCatalogIndividualDBAdaptor(),
                query.getLong(PRIVATE_STUDY_UID), null, options);
    }

    @Override
    public DBIterator<Sample> iterator(Query query, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        Document studyDocument = getStudyDocument(query);
        MongoCursor<Document> mongoCursor = getMongoCursor(null, query, options, studyDocument, user);
        Function<Document, Document> iteratorFilter = (d) ->  filterAnnotationSets(studyDocument, d, user,
                StudyAclEntry.StudyPermissions.VIEW_SAMPLE_ANNOTATIONS.name(),
                SampleAclEntry.SamplePermissions.VIEW_ANNOTATIONS.name());
        return new SampleMongoDBIterator<>(mongoCursor, null, sampleConverter, iteratorFilter,
                dbAdaptorFactory.getCatalogIndividualDBAdaptor(), query.getLong(PRIVATE_STUDY_UID), user, options);
    }

    @Override
    public DBIterator nativeIterator(Query query, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        return nativeIterator(null, query, options, user);
    }

    DBIterator nativeIterator(ClientSession clientSession, Query query, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        QueryOptions queryOptions = options != null ? new QueryOptions(options) : new QueryOptions();
        queryOptions.put(NATIVE_QUERY, true);

        Document studyDocument = getStudyDocument(clientSession, query);
        MongoCursor<Document> mongoCursor = getMongoCursor(clientSession, query, queryOptions, studyDocument, user);
        Function<Document, Document> iteratorFilter = (d) ->  filterAnnotationSets(studyDocument, d, user,
                StudyAclEntry.StudyPermissions.VIEW_SAMPLE_ANNOTATIONS.name(),
                SampleAclEntry.SamplePermissions.VIEW_ANNOTATIONS.name());
        return new SampleMongoDBIterator<>(mongoCursor, clientSession, null, iteratorFilter,
                dbAdaptorFactory.getCatalogIndividualDBAdaptor(), query.getLong(PRIVATE_STUDY_UID), user, options);
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
                    StudyAclEntry.StudyPermissions.VIEW_SAMPLES.name(), SampleAclEntry.SamplePermissions.VIEW.name(), Entity.SAMPLE.name());
        }

        Query finalQuery = new Query(query);
        filterOutDeleted(finalQuery);

        QueryOptions qOptions;
        if (options != null) {
            qOptions = new QueryOptions(options);
        } else {
            qOptions = new QueryOptions();
        }
        qOptions = removeAnnotationProjectionOptions(qOptions);
        qOptions = filterOptions(qOptions, FILTER_ROUTE_SAMPLES);

        if (isQueryingIndividualFields(finalQuery)) {
            QueryResult<Individual> individualQueryResult;
            if (StringUtils.isEmpty(user)) {
                individualQueryResult = dbAdaptorFactory.getCatalogIndividualDBAdaptor().get(clientSession,
                        getIndividualQueryFields(finalQuery),
                        new QueryOptions(QueryOptions.INCLUDE, IndividualDBAdaptor.QueryParams.SAMPLE_UIDS.key()));
            } else {
                individualQueryResult = dbAdaptorFactory.getCatalogIndividualDBAdaptor().get(clientSession,
                        getIndividualQueryFields(finalQuery),
                        new QueryOptions(QueryOptions.INCLUDE, IndividualDBAdaptor.QueryParams.SAMPLE_UIDS.key()), user);
            }
            finalQuery = getSampleQueryFields(finalQuery);

            // Process the whole list of sampleUids recovered from the individuals
            Set<Long> sampleUids = new HashSet<>();
            individualQueryResult.getResult().forEach(individual ->
                sampleUids.addAll(individual.getSamples().stream().map(Sample::getUid).collect(Collectors.toSet()))
            );

            if (sampleUids.isEmpty()) {
                // We want not to get any result
                finalQuery.append(QueryParams.UID.key(), -1);
            } else {
                finalQuery.append(QueryParams.UID.key(), sampleUids);
            }
        }

        Bson bson = parseQuery(finalQuery, queryForAuthorisedEntries);
        logger.debug("Sample query: {}", bson.toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()));
        return sampleCollection.nativeQuery().find(clientSession, bson, qOptions).iterator();
    }

    private boolean isQueryingIndividualFields(Query query) {
        for (String s : query.keySet()) {
            if (s.startsWith("individual")) {
                return true;
            }
        }
        return false;
    }

    private Query getSampleQueryFields(Query query) {
        Query retQuery = new Query();
        for (Map.Entry<String, Object> entry : query.entrySet()) {
            if (!entry.getKey().startsWith("individual")) {
                retQuery.append(entry.getKey(), entry.getValue());
            }
        }
        return retQuery;
    }

    private Query getIndividualQueryFields(Query query) {
        Query retQuery = new Query();
        for (Map.Entry<String, Object> entry : query.entrySet()) {
            if (entry.getKey().startsWith("individual.")) {
                retQuery.append(entry.getKey().replace("individual.", ""), entry.getValue());
            } else if (entry.getKey().startsWith("individual")) {
                retQuery.append(entry.getKey().replace("individual", IndividualDBAdaptor.QueryParams.ID.key()), entry.getValue());
            } else if (QueryParams.STUDY_UID.key().equals(entry.getKey())) {
                retQuery.append(entry.getKey(), entry.getValue());
            }
        }
        return retQuery;
    }

    private Document getStudyDocument(Query query) throws CatalogDBException {
        // Get the study document
        Query studyQuery = new Query(StudyDBAdaptor.QueryParams.UID.key(), query.getLong(QueryParams.STUDY_UID.key()));
        QueryResult<Document> queryResult = dbAdaptorFactory.getCatalogStudyDBAdaptor().nativeGet(studyQuery, QueryOptions.empty());
        if (queryResult.getNumResults() == 0) {
            throw new CatalogDBException("Study " + query.getLong(QueryParams.STUDY_UID.key()) + " not found");
        }
        return queryResult.first();
    }

    @Override
    public QueryResult rank(Query query, String field, int numResults, boolean asc) throws CatalogDBException {
        filterOutDeleted(query);
        Bson bsonQuery = parseQuery(query);
        return rank(sampleCollection, bsonQuery, field, "name", numResults, asc);
    }

    @Override
    public QueryResult groupBy(Query query, String field, QueryOptions options) throws CatalogDBException {
        filterOutDeleted(query);
        Bson bsonQuery = parseQuery(query);
        return groupBy(sampleCollection, bsonQuery, field, "name", options);
    }

    @Override
    public QueryResult groupBy(Query query, List<String> fields, QueryOptions options) throws CatalogDBException {
        filterOutDeleted(query);
        Bson bsonQuery = parseQuery(query);
        return groupBy(sampleCollection, bsonQuery, fields, "name", options);
    }

    @Override
    public QueryResult groupBy(Query query, String field, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        Document studyDocument = getStudyDocument(query);
        Document queryForAuthorisedEntries;
        if (containsAnnotationQuery(query)) {
            queryForAuthorisedEntries = getQueryForAuthorisedEntries(studyDocument, user,
                    StudyAclEntry.StudyPermissions.VIEW_SAMPLE_ANNOTATIONS.name(),
                    SampleAclEntry.SamplePermissions.VIEW_ANNOTATIONS.name(), Entity.SAMPLE.name());
        } else {
            queryForAuthorisedEntries = getQueryForAuthorisedEntries(studyDocument, user,
                    StudyAclEntry.StudyPermissions.VIEW_SAMPLES.name(), SampleAclEntry.SamplePermissions.VIEW.name(), Entity.SAMPLE.name());
        }
        filterOutDeleted(query);
        Bson bsonQuery = parseQuery(query, queryForAuthorisedEntries);
        return groupBy(sampleCollection, bsonQuery, field, QueryParams.ID.key(), options);
    }

    @Override
    public QueryResult groupBy(Query query, List<String> fields, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        Document studyDocument = getStudyDocument(query);
        Document queryForAuthorisedEntries;
        if (containsAnnotationQuery(query)) {
            queryForAuthorisedEntries = getQueryForAuthorisedEntries(studyDocument, user,
                    StudyAclEntry.StudyPermissions.VIEW_SAMPLE_ANNOTATIONS.name(),
                    SampleAclEntry.SamplePermissions.VIEW_ANNOTATIONS.name(), Entity.SAMPLE.name());
        } else {
            queryForAuthorisedEntries = getQueryForAuthorisedEntries(studyDocument, user,
                    StudyAclEntry.StudyPermissions.VIEW_SAMPLES.name(), SampleAclEntry.SamplePermissions.VIEW.name(), Entity.SAMPLE.name());
        }
        filterOutDeleted(query);
        Bson bsonQuery = parseQuery(query, queryForAuthorisedEntries);
        return groupBy(sampleCollection, bsonQuery, fields, QueryParams.ID.key(), options);
    }

    @Override
    public void forEach(Query query, Consumer<? super Object> action, QueryOptions options) throws CatalogDBException {
        Objects.requireNonNull(action);
        try (DBIterator<Sample> catalogDBIterator = iterator(query, options)) {
            while (catalogDBIterator.hasNext()) {
                action.accept(catalogDBIterator.next());
            }
        }
    }

    private Bson parseQuery(Query query) throws CatalogDBException {
        return parseQuery(query, null);
    }

    protected Bson parseQuery(Query query, Document authorisation) throws CatalogDBException {
        List<Bson> andBsonList = new ArrayList<>();
        Document annotationDocument = null;

        Query queryCopy = new Query(query);

        fixComplexQueryParam(QueryParams.ATTRIBUTES.key(), queryCopy);
        fixComplexQueryParam(QueryParams.BATTRIBUTES.key(), queryCopy);
        fixComplexQueryParam(QueryParams.NATTRIBUTES.key(), queryCopy);

        boolean uidVersionQueryFlag = generateUidVersionQuery(queryCopy, andBsonList);

        for (Map.Entry<String, Object> entry : queryCopy.entrySet()) {
            String key = entry.getKey().split("\\.")[0];
            QueryParams queryParam =  QueryParams.getParam(entry.getKey()) != null ? QueryParams.getParam(entry.getKey())
                    : QueryParams.getParam(key);
            if (queryParam == null) {
                if (Constants.ALL_VERSIONS.equals(entry.getKey()) || Constants.PRIVATE_ANNOTATION_PARAM_TYPES.equals(entry.getKey())) {
                    continue;
                }
                throw new CatalogDBException("Unexpected parameter " + entry.getKey() + ". The parameter does not exist or cannot be "
                        + "queried for.");
            }
            try {
                switch (queryParam) {
                    case UID:
                        addAutoOrQuery(PRIVATE_UID, queryParam.key(), queryCopy, queryParam.type(), andBsonList);
                        break;
                    case STUDY_UID:
                        addAutoOrQuery(PRIVATE_STUDY_UID, queryParam.key(), queryCopy, queryParam.type(), andBsonList);
                        break;
                    case ATTRIBUTES:
                        addAutoOrQuery(entry.getKey(), entry.getKey(), queryCopy, queryParam.type(), andBsonList);
                        break;
                    case BATTRIBUTES:
                        String mongoKey = entry.getKey().replace(QueryParams.BATTRIBUTES.key(), QueryParams.ATTRIBUTES.key());
                        addAutoOrQuery(mongoKey, entry.getKey(), queryCopy, queryParam.type(), andBsonList);
                        break;
                    case NATTRIBUTES:
                        mongoKey = entry.getKey().replace(QueryParams.NATTRIBUTES.key(), QueryParams.ATTRIBUTES.key());
                        addAutoOrQuery(mongoKey, entry.getKey(), queryCopy, queryParam.type(), andBsonList);
                        break;
                    case PHENOTYPES:
                        addOntologyQueryFilter(queryParam.key(), queryParam.key(), queryCopy, andBsonList);
                        break;
                    case ANNOTATION:
                        if (annotationDocument == null) {
                            annotationDocument = createAnnotationQuery(queryCopy.getString(QueryParams.ANNOTATION.key()),
                                    queryCopy.get(Constants.PRIVATE_ANNOTATION_PARAM_TYPES, ObjectMap.class));
                        }
                        break;
                    case SNAPSHOT:
                        addAutoOrQuery(RELEASE_FROM_VERSION, queryParam.key(), queryCopy, queryParam.type(), andBsonList);
                        break;
                    case CREATION_DATE:
                        addAutoOrQuery(PRIVATE_CREATION_DATE, queryParam.key(), queryCopy, queryParam.type(), andBsonList);
                        break;
                    case MODIFICATION_DATE:
                        addAutoOrQuery(PRIVATE_MODIFICATION_DATE, queryParam.key(), query, queryParam.type(), andBsonList);
                        break;
                    case STATUS_NAME:
                        // Convert the status to a positive status
                        query.put(queryParam.key(),
                                Status.getPositiveStatus(Status.STATUS_LIST, query.getString(queryParam.key())));
                        addAutoOrQuery(queryParam.key(), queryParam.key(), query, queryParam.type(), andBsonList);
                        break;
                    case ID:
                    case UUID:
                    case NAME:
                    case RELEASE:
                    case VERSION:
                    case SOURCE:
                    case DESCRIPTION:
                    case STATUS_MSG:
                    case STATUS_DATE:
                    case SOMATIC:
                    case TYPE:
                    case PHENOTYPES_ID:
                    case PHENOTYPES_NAME:
                    case PHENOTYPES_SOURCE:
//                    case ANNOTATION_SETS:
                        addAutoOrQuery(queryParam.key(), queryParam.key(), queryCopy, queryParam.type(), andBsonList);
                        break;
                    default:
                        throw new CatalogDBException("Cannot query by parameter " + queryParam.key());
                }
            } catch (Exception e) {
                if (e instanceof CatalogDBException) {
                    throw e;
                } else {
                    throw new CatalogDBException("Error parsing query : " + queryCopy.toJson(), e);
                }
            }
        }

        // If the user doesn't look for a concrete version...
        if (!uidVersionQueryFlag && !queryCopy.getBoolean(Constants.ALL_VERSIONS) && !queryCopy.containsKey(QueryParams.VERSION.key())) {
            if (queryCopy.containsKey(QueryParams.SNAPSHOT.key())) {
                // If the user looks for anything from some release, we will try to find the latest from the release (snapshot)
                andBsonList.add(Filters.eq(LAST_OF_RELEASE, true));
            } else {
                // Otherwise, we will always look for the latest version
                andBsonList.add(Filters.eq(LAST_OF_VERSION, true));
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

    public MongoDBCollection getSampleCollection() {
        return sampleCollection;
    }

    QueryResult<Sample> setStatus(long sampleId, String status) throws CatalogDBException {
        return update(sampleId, new ObjectMap(QueryParams.STATUS_NAME.key(), status), QueryOptions.empty());
    }

    QueryResult<Long> setStatus(Query query, String status) throws CatalogDBException {
        WriteResult update = update(query, new ObjectMap(QueryParams.STATUS_NAME.key(), status), QueryOptions.empty());
        return new QueryResult<>(update.getId(), update.getDbTime(), (int) update.getNumMatches(), update.getNumMatches(), "",
                "", Collections.singletonList(update.getNumModified()));
    }

}
