package org.opencb.opencga.catalog.db.mongodb.iterators;

import com.mongodb.client.MongoCursor;
import org.bson.Document;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.opencga.catalog.db.api.FileDBAdaptor;
import org.opencb.opencga.catalog.db.api.SampleDBAdaptor;
import org.opencb.opencga.catalog.db.mongodb.converters.AnnotableConverter;
import org.opencb.opencga.catalog.exceptions.CatalogAuthorizationException;
import org.opencb.opencga.catalog.exceptions.CatalogDBException;
import org.opencb.opencga.catalog.utils.Constants;
import org.opencb.opencga.core.models.Annotable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

import static org.opencb.opencga.catalog.db.mongodb.MongoDBAdaptor.NATIVE_QUERY;

public class FileMongoDBIterator<E> extends AnnotableMongoDBIterator<E> {

    private long studyUid;
    private String user;

    private SampleDBAdaptor sampleDBAdaptor;
    private QueryOptions sampleQueryOptions;

    private Queue<Document> fileListBuffer;

    private Logger logger;

    private static final int BUFFER_SIZE = 100;

    public FileMongoDBIterator(MongoCursor mongoCursor,  AnnotableConverter<? extends Annotable> converter,
                               Function<Document, Document> filter, SampleDBAdaptor sampleMongoDBAdaptor,
                               long studyUid, String user, QueryOptions options) {
        super(mongoCursor, converter, filter, options);

        this.user = user;
        this.studyUid = studyUid;

        this.sampleDBAdaptor = sampleMongoDBAdaptor;
        this.sampleQueryOptions = createSampleQueryOptions();

        this.fileListBuffer = new LinkedList<>();
        this.logger = LoggerFactory.getLogger(FileMongoDBIterator.class);
    }

    @Override
    public E next() {
        Document next = fileListBuffer.remove();

        if (filter != null) {
            next = filter.apply(next);
        }

        Object origSampleList = next.get(FileDBAdaptor.QueryParams.SAMPLES.key());
        // If the file contains more than 100 samples, we will only leave the id and version information
        if (origSampleList != null && ((List) origSampleList).size() > 100) {
            List<Document> sampleList = new ArrayList<>();

            for (Document sample : ((List<Document>) origSampleList)) {
                sampleList.add(new Document()
                        .append(SampleDBAdaptor.QueryParams.ID.key(), sample.get(SampleDBAdaptor.QueryParams.ID.key()))
                        .append(SampleDBAdaptor.QueryParams.UID.key(), sample.get(SampleDBAdaptor.QueryParams.UID.key()))
                        .append(SampleDBAdaptor.QueryParams.VERSION.key(), sample.get(SampleDBAdaptor.QueryParams.VERSION.key()))
                );
            }

            next.put(FileDBAdaptor.QueryParams.SAMPLES.key(), sampleList);
        }

        if (converter != null) {
            return (E) converter.convertToDataModelType(next, options);
        } else {
            return (E) next;
        }
    }

    @Override
    public boolean hasNext() {
        if (fileListBuffer.isEmpty()) {
            fetchNextBatch();
        }
        return !fileListBuffer.isEmpty();
    }

    private void fetchNextBatch() {
        Set<Long> sampleSet = new HashSet<>();

        // Get next BUFFER_SIZE documents
        int counter = 0;
        while (mongoCursor.hasNext() && counter < BUFFER_SIZE) {
            Document fileDocument = (Document) mongoCursor.next();

            fileListBuffer.add(fileDocument);
            counter++;

            // Extract all the samples
            Object samples = fileDocument.get(FileDBAdaptor.QueryParams.SAMPLES.key());
            if (samples != null && !options.getBoolean(NATIVE_QUERY)) {
                List<Document> sampleList = (List<Document>) samples;
                if (!sampleList.isEmpty()) {
                    sampleList.forEach(s -> sampleSet.add(s.getLong(SampleDBAdaptor.QueryParams.UID.key())));
                }
            }
        }

        if (!sampleSet.isEmpty()) {
            // Obtain all those samples
            Query query = new Query()
                    .append(SampleDBAdaptor.QueryParams.STUDY_UID.key(), studyUid)
                    .append(SampleDBAdaptor.QueryParams.UID.key(), new ArrayList<>(sampleSet));
            List<Document> sampleList;
            try {
                if (user != null) {
                    sampleList = sampleDBAdaptor.nativeGet(query, sampleQueryOptions, user).getResult();
                } else {
                    sampleList = sampleDBAdaptor.nativeGet(query, sampleQueryOptions).getResult();
                }
            } catch (CatalogDBException | CatalogAuthorizationException e) {
                logger.warn("Could not obtain the samples associated to the files: {}", e.getMessage(), e);
                return;
            }

            // Map each sample uid - version to the sample entry
            Map<Long, Document> sampleMap = new HashMap<>(sampleList.size());
            sampleList.forEach(sample ->
                    sampleMap.put(sample.getLong(SampleDBAdaptor.QueryParams.UID.key()), sample)
            );

            // Add the samples obtained to the corresponding files
            fileListBuffer.forEach(fileDocument -> {
                List<Document> tmpSampleList = new ArrayList<>();
                List<Document> samples = (List<Document>) fileDocument.get(FileDBAdaptor.QueryParams.SAMPLES.key());

                samples.forEach(s -> {
                    long uid = s.getLong(SampleDBAdaptor.QueryParams.UID.key());

                    // If the samples has been returned... (it might have not been fetched due to permissions issues)
                    if (sampleMap.containsKey(uid)) {
                        tmpSampleList.add(sampleMap.get(uid));
                    }
                });

                fileDocument.put(FileDBAdaptor.QueryParams.SAMPLES.key(), tmpSampleList);
            });
        }
    }

    private QueryOptions createSampleQueryOptions() {
        QueryOptions queryOptions = new QueryOptions(NATIVE_QUERY, true);

        if (options.containsKey(QueryOptions.INCLUDE)) {
            List<String> currentIncludeList = options.getAsStringList(QueryOptions.INCLUDE);
            List<String> includeList = new ArrayList<>();
            for (String include : currentIncludeList) {
                if (include.startsWith(FileDBAdaptor.QueryParams.SAMPLES.key() + ".")) {
                    includeList.add(include.replace(FileDBAdaptor.QueryParams.SAMPLES.key() + ".", ""));
                }
            }
            if (!includeList.isEmpty()) {
                includeList.add(SampleDBAdaptor.QueryParams.VERSION.key());
                includeList.add(SampleDBAdaptor.QueryParams.UID.key());
                queryOptions.put(QueryOptions.INCLUDE, includeList);
            }
        }
        if (options.containsKey(QueryOptions.EXCLUDE)) {
            List<String> currentExcludeList = options.getAsStringList(QueryOptions.EXCLUDE);
            List<String> excludeList = new ArrayList<>();
            for (String exclude : currentExcludeList) {
                if (exclude.startsWith(FileDBAdaptor.QueryParams.SAMPLES.key() + ".")) {
                    String replace = exclude.replace(FileDBAdaptor.QueryParams.SAMPLES.key() + ".", "");
                    if (!SampleDBAdaptor.QueryParams.UID.key().equals(replace)
                            && !SampleDBAdaptor.QueryParams.VERSION.key().equals(replace)) {
                        excludeList.add(replace);
                    }
                }
            }
            if (!excludeList.isEmpty()) {
                queryOptions.put(QueryOptions.EXCLUDE, excludeList);
            } else {
                queryOptions.remove(QueryOptions.EXCLUDE);
            }
        }
        if (options.containsKey(Constants.FLATTENED_ANNOTATIONS)) {
            queryOptions.put(Constants.FLATTENED_ANNOTATIONS, options.getBoolean(Constants.FLATTENED_ANNOTATIONS));
        }

        return queryOptions;
    }

}
