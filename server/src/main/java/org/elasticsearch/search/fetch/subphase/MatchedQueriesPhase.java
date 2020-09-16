/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.fetch.subphase;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.search.fetch.FetchSubPhase;
import org.elasticsearch.search.fetch.FetchSubPhaseProcessor;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.lookup.SearchLookup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MatchedQueriesPhase implements FetchSubPhase {

    @Override
    public FetchSubPhaseProcessor getProcessor(SearchContext context, SearchLookup lookup) throws IOException {
        if (context.docIdsToLoadSize() == 0 ||
            // in case the request has only suggest, parsed query is null
            context.parsedQuery() == null) {
            return null;
        }
        Map<String, Query> namedQueries = new HashMap<>(context.parsedQuery().namedFilters());
        if (context.parsedPostFilter() != null) {
            namedQueries.putAll(context.parsedPostFilter().namedFilters());
        }
        if (namedQueries.isEmpty()) {
            return null;
        }
        Map<String, Weight> weights = new HashMap<>();
        for (Map.Entry<String, Query> entry : namedQueries.entrySet()) {
            weights.put(entry.getKey(),
                context.searcher().createWeight(context.searcher().rewrite(entry.getValue()), ScoreMode.COMPLETE_NO_SCORES, 1));
        }
        return new FetchSubPhaseProcessor() {

            final Map<String, Bits> matchingIterators = new HashMap<>();

            @Override
            public void setNextReader(LeafReaderContext readerContext) throws IOException {
                matchingIterators.clear();
                for (Map.Entry<String, Weight> entry : weights.entrySet()) {
                    ScorerSupplier ss = entry.getValue().scorerSupplier(readerContext);
                    if (ss != null) {
                        Bits matchingBits = Lucene.asSequentialAccessBits(readerContext.reader().maxDoc(), ss);
                        matchingIterators.put(entry.getKey(), matchingBits);
                    }
                }
            }

            @Override
            public void process(HitContext hitContext) {
                List<String> matches = new ArrayList<>();
                int doc = hitContext.docId();
                for (Map.Entry<String, Bits> iterator : matchingIterators.entrySet()) {
                    if (iterator.getValue().get(doc)) {
                        matches.add(iterator.getKey());
                    }
                }
                hitContext.hit().matchedQueries(matches.toArray(new String[0]));
            }
        };
    }

}
