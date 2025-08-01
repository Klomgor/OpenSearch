/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.index.mapper;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexOrDocValuesQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.IndexSortSortedNumericDocValuesRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.opensearch.Version;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.time.DateFormatter;
import org.opensearch.common.time.DateFormatters;
import org.opensearch.common.time.DateMathParser;
import org.opensearch.common.util.BigArrays;
import org.opensearch.common.util.io.IOUtils;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.fielddata.IndexNumericFieldData;
import org.opensearch.index.fielddata.LeafNumericFieldData;
import org.opensearch.index.fielddata.plain.SortedNumericIndexFieldData;
import org.opensearch.index.mapper.DateFieldMapper.DateFieldType;
import org.opensearch.index.mapper.DateFieldMapper.Resolution;
import org.opensearch.index.mapper.MappedFieldType.Relation;
import org.opensearch.index.mapper.ParseContext.Document;
import org.opensearch.index.query.BaseQueryRewriteContext;
import org.opensearch.index.query.DateRangeIncludingNowQuery;
import org.opensearch.index.query.QueryRewriteContext;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.search.approximate.ApproximatePointRangeQuery;
import org.opensearch.search.approximate.ApproximateScoreQuery;
import org.opensearch.test.TestSearchContext;
import org.joda.time.DateTimeZone;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.apache.lucene.document.LongPoint.pack;

public class DateFieldTypeTests extends FieldTypeTestCase {

    private static final long nowInMillis = 0;

    public void testIsFieldWithinRangeEmptyReader() throws IOException {
        QueryRewriteContext context = new BaseQueryRewriteContext(xContentRegistry(), writableRegistry(), null, () -> nowInMillis);
        IndexReader reader = new MultiReader();
        DateFieldType ft = new DateFieldType("my_date");
        assertEquals(
            Relation.DISJOINT,
            ft.isFieldWithinQuery(reader, "2015-10-12", "2016-04-03", randomBoolean(), randomBoolean(), null, null, context)
        );
    }

    public void testIsFieldWithinQueryDateMillis() throws IOException {
        DateFieldType ft = new DateFieldType("my_date", Resolution.MILLISECONDS);
        isFieldWithinRangeTestCase(ft);
    }

    public void testIsFieldWithinQueryDateNanos() throws IOException {
        DateFieldType ft = new DateFieldType("my_date", Resolution.NANOSECONDS);
        isFieldWithinRangeTestCase(ft);
    }

    public void isFieldWithinRangeTestCase(DateFieldType ft) throws IOException {

        Directory dir = newDirectory();
        IndexWriter w = new IndexWriter(dir, new IndexWriterConfig(null));
        Document doc = new Document();
        LongPoint field = new LongPoint("my_date", ft.parse("2015-10-12"));
        doc.add(field);
        w.addDocument(doc);
        field.setLongValue(ft.parse("2016-04-03"));
        w.addDocument(doc);
        DirectoryReader reader = DirectoryReader.open(w);

        DateMathParser alternateFormat = DateFieldMapper.getDefaultDateTimeFormatter().toDateMathParser();
        doTestIsFieldWithinQuery(ft, reader, null, null);
        doTestIsFieldWithinQuery(ft, reader, null, alternateFormat);
        doTestIsFieldWithinQuery(ft, reader, DateTimeZone.UTC, null);
        doTestIsFieldWithinQuery(ft, reader, DateTimeZone.UTC, alternateFormat);

        QueryRewriteContext context = new BaseQueryRewriteContext(xContentRegistry(), writableRegistry(), null, () -> nowInMillis);

        // Fields with no value indexed.
        DateFieldType ft2 = new DateFieldType("my_date2");

        assertEquals(Relation.DISJOINT, ft2.isFieldWithinQuery(reader, "2015-10-09", "2016-01-02", false, false, null, null, context));

        IOUtils.close(reader, w, dir);
    }

    private void doTestIsFieldWithinQuery(DateFieldType ft, DirectoryReader reader, DateTimeZone zone, DateMathParser alternateFormat)
        throws IOException {
        QueryRewriteContext context = new BaseQueryRewriteContext(xContentRegistry(), writableRegistry(), null, () -> nowInMillis);
        assertEquals(
            Relation.INTERSECTS,
            ft.isFieldWithinQuery(reader, "2015-10-09", "2016-01-02", randomBoolean(), randomBoolean(), null, null, context)
        );
        assertEquals(
            Relation.INTERSECTS,
            ft.isFieldWithinQuery(reader, "2016-01-02", "2016-06-20", randomBoolean(), randomBoolean(), null, null, context)
        );
        assertEquals(
            Relation.INTERSECTS,
            ft.isFieldWithinQuery(reader, "2016-01-02", "2016-02-12", randomBoolean(), randomBoolean(), null, null, context)
        );
        assertEquals(
            Relation.DISJOINT,
            ft.isFieldWithinQuery(reader, "2014-01-02", "2015-02-12", randomBoolean(), randomBoolean(), null, null, context)
        );
        assertEquals(
            Relation.DISJOINT,
            ft.isFieldWithinQuery(reader, "2016-05-11", "2016-08-30", randomBoolean(), randomBoolean(), null, null, context)
        );
        assertEquals(
            Relation.WITHIN,
            ft.isFieldWithinQuery(reader, "2015-09-25", "2016-05-29", randomBoolean(), randomBoolean(), null, null, context)
        );
        assertEquals(Relation.WITHIN, ft.isFieldWithinQuery(reader, "2015-10-12", "2016-04-03", true, true, null, null, context));
        assertEquals(Relation.INTERSECTS, ft.isFieldWithinQuery(reader, "2015-10-12", "2016-04-03", false, false, null, null, context));
        assertEquals(Relation.INTERSECTS, ft.isFieldWithinQuery(reader, "2015-10-12", "2016-04-03", false, true, null, null, context));
        assertEquals(Relation.INTERSECTS, ft.isFieldWithinQuery(reader, "2015-10-12", "2016-04-03", true, false, null, null, context));
    }

    public void testValueFormat() {
        MappedFieldType ft = new DateFieldType("field");
        long instant = DateFormatters.from(DateFieldMapper.getDefaultDateTimeFormatter().parse("2015-10-12T14:10:55"))
            .toInstant()
            .toEpochMilli();

        assertEquals("2015-10-12T14:10:55.000Z", ft.docValueFormat(null, ZoneOffset.UTC).format(instant));
        assertEquals("2015-10-12T15:10:55.000+01:00", ft.docValueFormat(null, ZoneOffset.ofHours(1)).format(instant));
        assertEquals("2015", new DateFieldType("field").docValueFormat("YYYY", ZoneOffset.UTC).format(instant));
        assertEquals(instant, ft.docValueFormat(null, ZoneOffset.UTC).parseLong("2015-10-12T14:10:55", false, null));
        assertEquals(instant + 999, ft.docValueFormat(null, ZoneOffset.UTC).parseLong("2015-10-12T14:10:55", true, null));
        long i = DateFormatters.from(DateFieldMapper.getDefaultDateTimeFormatter().parse("2015-10-13")).toInstant().toEpochMilli();
        assertEquals(i - 1, ft.docValueFormat(null, ZoneOffset.UTC).parseLong("2015-10-12||/d", true, null));
    }

    public void testValueForSearch() {
        MappedFieldType ft = new DateFieldType("field");
        String date = "2015-10-12T12:09:55.000Z";
        long instant = DateFieldMapper.getDefaultDateTimeFormatter().parseMillis(date);
        assertEquals(date, ft.valueForDisplay(instant));
    }

    public void testTermQuery() {
        Settings indexSettings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
            .build();
        QueryShardContext context = new QueryShardContext(
            0,
            new IndexSettings(IndexMetadata.builder("foo").settings(indexSettings).build(), indexSettings),
            BigArrays.NON_RECYCLING_INSTANCE,
            null,
            null,
            null,
            null,
            null,
            xContentRegistry(),
            writableRegistry(),
            null,
            null,
            () -> nowInMillis,
            null,
            null,
            () -> true,
            null
        );
        MappedFieldType ft = new DateFieldType("field");
        String date = "2015-10-12T14:10:55";
        long instant = DateFormatters.from(DateFieldMapper.getDefaultDateTimeFormatter().parse(date)).toInstant().toEpochMilli();
        Query expected = new ApproximateScoreQuery(
            new IndexOrDocValuesQuery(
                LongPoint.newRangeQuery("field", instant, instant + 999),
                SortedNumericDocValuesField.newSlowRangeQuery("field", instant, instant + 999)
            ),
            new ApproximatePointRangeQuery(
                "field",
                pack(new long[] { instant }).bytes,
                pack(new long[] { instant + 999 }).bytes,
                new long[] { instant }.length,
                ApproximatePointRangeQuery.LONG_FORMAT
            )
        );
        assertEquals(expected, ft.termQuery(date, context));

        MappedFieldType unsearchable = new DateFieldType(
            "field",
            false,
            false,
            false,
            DateFieldMapper.getDefaultDateTimeFormatter(),
            Resolution.MILLISECONDS,
            null,
            Collections.emptyMap()
        );
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> unsearchable.termQuery(date, context));
        assertEquals("Cannot search on field [field] since it is both not indexed, and does not have doc_values enabled.", e.getMessage());
    }

    public void testRangeQuery() throws IOException {
        Settings indexSettings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
            .build();
        QueryShardContext context = new QueryShardContext(
            0,
            new IndexSettings(IndexMetadata.builder("foo").settings(indexSettings).build(), indexSettings),
            BigArrays.NON_RECYCLING_INSTANCE,
            null,
            null,
            null,
            null,
            null,
            xContentRegistry(),
            writableRegistry(),
            null,
            null,
            () -> nowInMillis,
            null,
            null,
            () -> true,
            null
        );
        MappedFieldType ft = new DateFieldType("field");
        String date1 = "2015-10-12T14:10:55";
        String date2 = "2016-04-28T11:33:52";
        long instant1 = DateFormatters.from(DateFieldMapper.getDefaultDateTimeFormatter().parse(date1)).toInstant().toEpochMilli();
        long instant2 = DateFormatters.from(DateFieldMapper.getDefaultDateTimeFormatter().parse(date2)).toInstant().toEpochMilli() + 999;
        ApproximatePointRangeQuery approximatePointRangeQuery = new ApproximatePointRangeQuery(
            "field",
            pack(new long[] { instant1 }).bytes,
            pack(new long[] { instant2 }).bytes,
            new long[] { instant1 }.length,
            ApproximatePointRangeQuery.LONG_FORMAT
        );
        Query expected = new ApproximateScoreQuery(
            new IndexOrDocValuesQuery(
                LongPoint.newRangeQuery("field", instant1, instant2),
                SortedNumericDocValuesField.newSlowRangeQuery("field", instant1, instant2)
            ),
            approximatePointRangeQuery
        );
        Query rangeQuery = ft.rangeQuery(date1, date2, true, true, null, null, null, context);
        assertTrue(rangeQuery instanceof ApproximateScoreQuery);
        ((ApproximateScoreQuery) rangeQuery).setContext(new TestSearchContext(context));
        assertEquals(expected, rangeQuery.rewrite(new IndexSearcher(new MultiReader())));

        instant1 = nowInMillis;
        instant2 = instant1 + 100;
        expected = new ApproximateScoreQuery(
            new DateRangeIncludingNowQuery(
                new IndexOrDocValuesQuery(
                    LongPoint.newRangeQuery("field", instant1, instant2),
                    SortedNumericDocValuesField.newSlowRangeQuery("field", instant1, instant2)
                )
            ),
            new ApproximatePointRangeQuery(
                "field",
                pack(new long[] { instant1 }).bytes,
                pack(new long[] { instant2 }).bytes,
                new long[] { instant1 }.length,
                ApproximatePointRangeQuery.LONG_FORMAT

            )
        );
        assertEquals(expected, ft.rangeQuery("now", instant2, true, true, null, null, null, context));

        MappedFieldType unsearchable = new DateFieldType(
            "field",
            false,
            false,
            false,
            DateFieldMapper.getDefaultDateTimeFormatter(),
            Resolution.MILLISECONDS,
            null,
            Collections.emptyMap()
        );
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> unsearchable.rangeQuery(date1, date2, true, true, null, null, null, context)
        );
        assertEquals("Cannot search on field [field] since it is both not indexed, and does not have doc_values enabled.", e.getMessage());
    }

    public void testRangeQueryWithIndexSort() {
        Settings settings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
            .put("index.sort.field", "field")
            .build();

        IndexMetadata indexMetadata = new IndexMetadata.Builder("index").settings(settings).build();
        IndexSettings indexSettings = new IndexSettings(indexMetadata, settings);

        QueryShardContext context = new QueryShardContext(
            0,
            indexSettings,
            BigArrays.NON_RECYCLING_INSTANCE,
            null,
            null,
            null,
            null,
            null,
            xContentRegistry(),
            writableRegistry(),
            null,
            null,
            () -> 0L,
            null,
            null,
            () -> true,
            null
        );

        MappedFieldType ft = new DateFieldType("field");
        String date1 = "2015-10-12T14:10:55";
        String date2 = "2016-04-28T11:33:52";
        long instant1 = DateFormatters.from(DateFieldMapper.getDefaultDateTimeFormatter().parse(date1)).toInstant().toEpochMilli();
        long instant2 = DateFormatters.from(DateFieldMapper.getDefaultDateTimeFormatter().parse(date2)).toInstant().toEpochMilli() + 999;

        Query dvQuery = SortedNumericDocValuesField.newSlowRangeQuery("field", instant1, instant2);
        Query expected = new ApproximateScoreQuery(
            new IndexSortSortedNumericDocValuesRangeQuery(
                "field",
                instant1,
                instant2,
                new IndexOrDocValuesQuery(LongPoint.newRangeQuery("field", instant1, instant2), dvQuery)
            ),
            new ApproximatePointRangeQuery(
                "field",
                pack(new long[] { instant1 }).bytes,
                pack(new long[] { instant2 }).bytes,
                new long[] { instant1 }.length,
                ApproximatePointRangeQuery.LONG_FORMAT
            )
        );
        assertEquals(expected, ft.rangeQuery(date1, date2, true, true, null, null, null, context));
    }

    public void testDateNanoDocValues() throws IOException {
        // Create an index with some docValues
        Directory dir = newDirectory();
        IndexWriter w = new IndexWriter(dir, new IndexWriterConfig(null));
        Document doc = new Document();
        NumericDocValuesField docValuesField = new NumericDocValuesField("my_date", 1444608000000L);
        doc.add(docValuesField);
        w.addDocument(doc);
        docValuesField.setLongValue(1459641600000L);
        w.addDocument(doc);
        // Create the doc values reader
        SortedNumericIndexFieldData fieldData = new SortedNumericIndexFieldData(
            "my_date",
            IndexNumericFieldData.NumericType.DATE_NANOSECONDS
        );
        // Read index and check the doc values
        DirectoryReader reader = DirectoryReader.open(w);
        assertTrue(reader.leaves().size() > 0);
        LeafNumericFieldData a = fieldData.load(reader.leaves().get(0).reader().getContext());
        SortedNumericDocValues docValues = a.getLongValues();
        assertEquals(0, docValues.nextDoc());
        assertEquals(1, docValues.nextDoc());
        assertEquals(DocIdSetIterator.NO_MORE_DOCS, docValues.nextDoc());
        reader.close();
        w.close();
        dir.close();
    }

    private static DateFieldType fieldType(Resolution resolution, String format, String nullValue) {
        DateFormatter formatter = DateFormatter.forPattern(format);
        return new DateFieldType("field", true, false, true, formatter, resolution, nullValue, Collections.emptyMap());
    }

    public void testFetchSourceValue() throws IOException {
        MappedFieldType fieldType = new DateFieldType("field", Resolution.MILLISECONDS);
        String date = "2020-05-15T21:33:02.000Z";
        assertEquals(Collections.singletonList(date), fetchSourceValue(fieldType, date));
        assertEquals(Collections.singletonList(date), fetchSourceValue(fieldType, 1589578382000L));

        MappedFieldType fieldWithFormat = fieldType(Resolution.MILLISECONDS, "yyyy/MM/dd||epoch_millis", null);
        String dateInFormat = "1990/12/29";
        assertEquals(Collections.singletonList(dateInFormat), fetchSourceValue(fieldWithFormat, dateInFormat));
        assertEquals(Collections.singletonList(dateInFormat), fetchSourceValue(fieldWithFormat, 662428800000L));

        MappedFieldType millis = fieldType(Resolution.MILLISECONDS, "epoch_millis", null);
        String dateInMillis = "662428800000";
        assertEquals(Collections.singletonList(dateInMillis), fetchSourceValue(millis, dateInMillis));
        assertEquals(Collections.singletonList(dateInMillis), fetchSourceValue(millis, 662428800000L));

        String nullValueDate = "2020-05-15T21:33:02.000Z";
        MappedFieldType nullFieldType = fieldType(Resolution.MILLISECONDS, "strict_date_time", nullValueDate);
        assertEquals(Collections.singletonList(nullValueDate), fetchSourceValue(nullFieldType, null));
    }

    public void testParseSourceValueWithFormat() throws IOException {
        MappedFieldType mapper = fieldType(Resolution.NANOSECONDS, "strict_date_time", "1970-12-29T00:00:00.000Z");
        String date = "1990-12-29T00:00:00.000Z";
        assertEquals(Collections.singletonList("1990/12/29"), fetchSourceValue(mapper, date, "yyyy/MM/dd"));
        assertEquals(Collections.singletonList("662428800000"), fetchSourceValue(mapper, date, "epoch_millis"));
        assertEquals(Collections.singletonList("1970/12/29"), fetchSourceValue(mapper, null, "yyyy/MM/dd"));
    }

    public void testParseSourceValueNanos() throws IOException {
        MappedFieldType mapper = fieldType(Resolution.NANOSECONDS, "strict_date_time||epoch_millis", null);
        String date = "2020-05-15T21:33:02.123456789Z";
        assertEquals(Collections.singletonList("2020-05-15T21:33:02.123456789Z"), fetchSourceValue(mapper, date));
        assertEquals(Collections.singletonList("2020-05-15T21:33:02.123Z"), fetchSourceValue(mapper, 1589578382123L));

        String nullValueDate = "2020-05-15T21:33:02.123456789Z";
        MappedFieldType nullValueMapper = fieldType(Resolution.NANOSECONDS, "strict_date_time||epoch_millis", nullValueDate);
        assertEquals(Collections.singletonList(nullValueDate), fetchSourceValue(nullValueMapper, null));
    }

    public void testDateResolutionForOverflow() throws IOException {
        Directory dir = newDirectory();
        IndexWriter w = new IndexWriter(dir, new IndexWriterConfig(null));

        DateFieldType ft = new DateFieldType(
            "test_date",
            true,
            true,
            true,
            DateFormatter.forPattern("yyyy-MM-dd HH:mm:ss||yyyy-MM-dd||epoch_millis||strict_date_optional_time"),
            Resolution.MILLISECONDS,
            null,
            Collections.emptyMap()
        );

        List<String> dates = Arrays.asList(
            null,
            "2020-01-01T00:00:00Z",
            null,
            "2021-01-01T00:00:00Z",
            "+292278994-08-17T07:12:55.807Z",
            null,
            "-292275055-05-16T16:47:04.192Z"
        );

        int numNullDates = 0;
        long minDateValue = Long.MAX_VALUE;
        long maxDateValue = Long.MIN_VALUE;

        for (int i = 0; i < dates.size(); i++) {
            ParseContext.Document doc = new ParseContext.Document();
            String dateStr = dates.get(i);

            if (dateStr != null) {
                long timestamp = Resolution.MILLISECONDS.convert(DateFormatters.from(ft.dateTimeFormatter().parse(dateStr)).toInstant());
                doc.add(new LongPoint(ft.name(), timestamp));
                doc.add(new SortedNumericDocValuesField(ft.name(), timestamp));
                doc.add(new StoredField(ft.name(), timestamp));
                doc.add(new StoredField("id", i));
                minDateValue = Math.min(minDateValue, timestamp);
                maxDateValue = Math.max(maxDateValue, timestamp);
            } else {
                numNullDates++;
                doc.add(new StoredField("id", i));
            }
            w.addDocument(doc);
        }

        DirectoryReader reader = DirectoryReader.open(w);
        IndexSearcher searcher = new IndexSearcher(reader);

        Settings indexSettings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
            .build();
        QueryShardContext context = new QueryShardContext(
            0,
            new IndexSettings(IndexMetadata.builder("foo").settings(indexSettings).build(), indexSettings),
            BigArrays.NON_RECYCLING_INSTANCE,
            null,
            null,
            null,
            null,
            null,
            xContentRegistry(),
            writableRegistry(),
            null,
            null,
            () -> nowInMillis,
            null,
            null,
            () -> true,
            null
        );

        Query rangeQuery = ft.rangeQuery(
            "-292275055-05-16T16:47:04.192Z",
            "+292278994-08-17T07:12:55.807Z",
            true,
            true,
            null,
            null,
            null,
            context
        );

        TopDocs topDocs = searcher.search(rangeQuery, dates.size());
        assertEquals("Number of non-null date documents", dates.size() - numNullDates, topDocs.totalHits.value());

        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            org.apache.lucene.document.Document doc = reader.storedFields().document(scoreDoc.doc);
            IndexableField dateField = doc.getField(ft.name());
            if (dateField != null) {
                long dateValue = dateField.numericValue().longValue();
                assertTrue(
                    "Date value " + dateValue + " should be within valid range",
                    dateValue >= minDateValue && dateValue <= maxDateValue
                );
            }
        }

        DateFieldType ftWithNullValue = new DateFieldType(
            "test_date",
            true,
            true,
            true,
            DateFormatter.forPattern("yyyy-MM-dd HH:mm:ss||yyyy-MM-dd||epoch_millis||strict_date_optional_time"),
            Resolution.MILLISECONDS,
            "2020-01-01T00:00:00Z",
            Collections.emptyMap()
        );

        Query nullValueQuery = ftWithNullValue.termQuery("2020-01-01T00:00:00Z", context);
        topDocs = searcher.search(nullValueQuery, dates.size());
        assertEquals("Documents matching the 2020-01-01 date", 1, topDocs.totalHits.value());

        IOUtils.close(reader, w, dir);
    }

    public void testDateFieldTypeWithNulls() throws IOException {
        DateFieldType ft = new DateFieldType(
            "domainAttributes.dueDate",
            true,
            true,
            true,
            DateFormatter.forPattern("yyyy-MM-dd HH:mm:ss||yyyy-MM-dd||epoch_millis||date_optional_time"),
            Resolution.MILLISECONDS,
            null,
            Collections.emptyMap()
        );

        Directory dir = newDirectory();
        IndexWriter w = new IndexWriter(dir, new IndexWriterConfig(null));

        int nullDocs = 3500;
        int datedDocs = 50;

        for (int i = 0; i < nullDocs; i++) {
            ParseContext.Document doc = new ParseContext.Document();
            doc.add(new StringField("domainAttributes.firmId", "12345678910111213", Field.Store.YES));
            w.addDocument(doc);
        }

        for (int i = 1; i <= datedDocs; i++) {
            ParseContext.Document doc = new ParseContext.Document();
            String dateStr = String.format(Locale.ROOT, "2022-03-%02dT15:40:58.324", (i % 30) + 1);
            long timestamp = Resolution.MILLISECONDS.convert(DateFormatters.from(ft.dateTimeFormatter().parse(dateStr)).toInstant());
            doc.add(new StringField("domainAttributes.firmId", "12345678910111213", Field.Store.YES));
            doc.add(new LongPoint(ft.name(), timestamp));
            doc.add(new SortedNumericDocValuesField(ft.name(), timestamp));
            doc.add(new StoredField(ft.name(), timestamp));
            w.addDocument(doc);
        }

        DirectoryReader reader = DirectoryReader.open(w);
        IndexSearcher searcher = new IndexSearcher(reader);

        BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();
        queryBuilder.add(new TermQuery(new Term("domainAttributes.firmId", "12345678910111213")), BooleanClause.Occur.MUST);

        Sort sort = new Sort(new SortField(ft.name(), SortField.Type.DOC, false));

        for (int i = 0; i < 100; i++) {
            TopDocs topDocs = searcher.search(queryBuilder.build(), nullDocs + datedDocs, sort);
            assertEquals("Total hits should match total documents", nullDocs + datedDocs, topDocs.totalHits.value());
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                org.apache.lucene.document.Document doc = reader.storedFields().document(scoreDoc.doc);
                IndexableField dateField = doc.getField(ft.name());
                if (dateField != null) {
                    long dateValue = dateField.numericValue().longValue();
                    Instant dateInstant = Instant.ofEpochMilli(dateValue);
                    assertTrue(
                        "Date should be in March 2022",
                        dateInstant.isAfter(Instant.parse("2022-03-01T00:00:00Z"))
                            && dateInstant.isBefore(Instant.parse("2022-04-01T00:00:00Z"))
                    );
                }
            }
        }
        IOUtils.close(reader, w, dir);
    }
}
