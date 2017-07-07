/*
 * Copyright 2015-2016 OpenCB
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

package org.opencb.opencga.storage.core.search.solr;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.params.CursorMarkParams;
import org.opencb.opencga.storage.core.search.VariantSearchModel;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;

/**
 * Created by wasim on 14/11/16.
 */
public class VariantSearchIterator implements Iterator<VariantSearchModel>, AutoCloseable {

    private SolrClient solrClient;
    private String collection;
    private SolrQuery solrQuery;
    private QueryResponse solrResponse;
    private String cursorMark;
    private String nextCursorMark;

    private Iterator<VariantSearchModel> solrIterator;

    private int remaining;

    private static final int BATCH_SIZE = 100;
    private static final int DEFAULT_LIMIT = 100000;

    @Deprecated
    public VariantSearchIterator(Iterator<VariantSearchModel> solrIterator) {
        this.solrIterator = solrIterator;
    }

    public VariantSearchIterator(SolrClient solrClient, String collection, SolrQuery solrQuery) throws IOException, SolrServerException {
        this.solrClient = solrClient;
        this.collection = collection;
        this.solrQuery = solrQuery;

        // Make sure that query is sorted
        this.solrQuery.setSort(SolrQuery.SortClause.asc("id"));

        // This is the limit of the user, or the default limit if it is not passed
        this.remaining = (solrQuery.getRows() == null || solrQuery.getRows() < 0)
                ? DEFAULT_LIMIT
                : solrQuery.getRows();

        // We the set cursor at the beginning
        this.cursorMark = CursorMarkParams.CURSOR_MARK_START;

        // We create an empty iterator, this will return false in the first hasNext call
        this.solrIterator = Collections.emptyIterator();

        // Current Solr iterator (aka cursorMarks) implementation does not support skip.
        // A simple solution is to waste these records and remove the Start from the solrQuery
        if (solrQuery.getStart() != null && solrQuery.getStart() >= 0) {
            // Do not change the order or position of the next two lines of code
            Integer skip = solrQuery.getStart();
            solrQuery.setStart(null);
            for (int i = 0; i < skip && hasNext(); i++) {
                next();
            }
        }
    }

    @Override
    public boolean hasNext() {
        // This is always false the first time with the empty iterator
        if (solrIterator.hasNext()) {
            return true;
        } else {
            // This only happens when there are no more records in Solr
            if (cursorMark.equals(nextCursorMark)) {
                return false;
            }

            // We need to fetch another batch from Solr
            try {
                if (nextCursorMark != null) {
                    cursorMark = nextCursorMark;
                }
                solrQuery.setRows(remaining > BATCH_SIZE ? BATCH_SIZE : remaining);
                solrQuery.set(CursorMarkParams.CURSOR_MARK_PARAM, cursorMark);

                // Execute the query and fetch setRows records, we will iterate over this list
                solrResponse = solrClient.query(collection, solrQuery);
                remaining -= solrResponse.getResults().size();
                nextCursorMark = solrResponse.getNextCursorMark();
                solrIterator = solrResponse.getBeans(VariantSearchModel.class).iterator();
                return solrIterator.hasNext();
            } catch (SolrServerException | IOException e) {
                // TODO do something better than this
                e.printStackTrace();
            }
            return false;
        }
    }

    @Override
    public VariantSearchModel next() {
        return solrIterator.next();
    }

    @Override
    public void close() throws Exception {
        // nothing to do
    }

    public long getNumFound() {
        return solrResponse.getResults().getNumFound();
    }
}
