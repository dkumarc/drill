/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.apache.drill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.types.TypeProtos;
import org.apache.drill.exec.HyperVectorValueIterator;
import org.apache.drill.exec.exception.SchemaChangeException;
import org.apache.drill.exec.memory.BufferAllocator;
import org.apache.drill.exec.proto.UserBitShared;
import org.apache.drill.exec.proto.UserBitShared.QueryType;
import org.apache.drill.exec.record.BatchSchema;
import org.apache.drill.exec.record.HyperVectorWrapper;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.drill.exec.record.RecordBatchLoader;
import org.apache.drill.exec.record.VectorWrapper;
import org.apache.drill.exec.rpc.user.QueryDataBatch;
import org.apache.drill.exec.util.Text;
import org.apache.drill.exec.vector.ValueVector;

/**
 * An object to encapsulate the options for a Drill unit test, as well as the execution methods to perform the tests and
 * validation of results.
 *
 * To construct an instance easily, look at the TestBuilder class. From an implementation of
 * the BaseTestQuery class, and instance of the builder is accessible through the testBuilder() method.
 */
public class DrillTestWrapper {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(BaseTestQuery.class);

  // TODO - when in JSON, read baseline in all text mode to avoid precision loss for decimal values

  // This flag will enable all of the values that are validated to be logged. For large validations this is time consuming
  // so this is not exposed in a way that it can be enabled for an individual test. It can be changed here while debugging
  // a test to see all of the output, but as this framework is doing full validation, there is no reason to keep it on as
  // it will only make the test slower.
  private static boolean VERBOSE_DEBUG = false;

  // Unit test doesn't expect any specific batch count
  public static final int EXPECTED_BATCH_COUNT_NOT_SET = -1;

  // The motivation behind the TestBuilder was to provide a clean API for test writers. The model is mostly designed to
  // prepare all of the components necessary for running the tests, before the TestWrapper is initialized. There is however
  // one case where the setup for the baseline is driven by the test query results, and this is implicit type enforcement
  // for the baseline data. In this case there needs to be a call back into the TestBuilder once we know the type information
  // from the test query.
  private TestBuilder testBuilder;
  // test query to run
  private String query;
  // The type of query provided
  private UserBitShared.QueryType queryType;
  // The type of query provided for the baseline
  private UserBitShared.QueryType baselineQueryType;
  // should ordering be enforced in the baseline check
  private boolean ordered;
  // TODO - implement this
  private boolean approximateEquality;
  private BufferAllocator allocator;
  // queries to run before the baseline or test queries, can be used to set options
  private String baselineOptionSettingQueries;
  private String testOptionSettingQueries;
  // two different methods are available for comparing ordered results, the default reads all of the records
  // into giant lists of objects, like one giant on-heap batch of 'vectors'
  // this flag enables the other approach which iterates through a hyper batch for the test query results and baseline
  // while this does work faster and use less memory, it can be harder to debug as all of the elements are not in a
  // single list
  private boolean highPerformanceComparison;
  // if the baseline is a single option test writers can provide the baseline values and columns
  // without creating a file, these are provided to the builder in the baselineValues() and baselineColumns() methods
  // and translated into a map in the builder
  private List<Map> baselineRecords;

  private int expectedNumBatches;

  public DrillTestWrapper(TestBuilder testBuilder, BufferAllocator allocator, String query, QueryType queryType,
                          String baselineOptionSettingQueries, String testOptionSettingQueries,
                          QueryType baselineQueryType, boolean ordered, boolean approximateEquality,
                          boolean highPerformanceComparison, List<Map> baselineRecords, int expectedNumBatches) {
    this.testBuilder = testBuilder;
    this.allocator = allocator;
    this.query = query;
    this.queryType = queryType;
    this.baselineQueryType = baselineQueryType;
    this.ordered = ordered;
    this.approximateEquality = approximateEquality;
    this.baselineOptionSettingQueries = baselineOptionSettingQueries;
    this.testOptionSettingQueries = testOptionSettingQueries;
    this.highPerformanceComparison = highPerformanceComparison;
    this.baselineRecords = baselineRecords;
    this.expectedNumBatches = expectedNumBatches;
  }

  public void run() throws Exception {
    if (testBuilder.getExpectedSchema() != null) {
      compareSchemaOnly();
    } else {
      if (ordered) {
        compareOrderedResults();
      } else {
        compareUnorderedResults();
      }
    }
  }

  private BufferAllocator getAllocator() {
    return allocator;
  }

  private void compareHyperVectors(Map<String, HyperVectorValueIterator> expectedRecords,
                                         Map<String, HyperVectorValueIterator> actualRecords) throws Exception {
    for (String s : expectedRecords.keySet()) {
      assertNotNull("Expected column '" + s + "' not found.", actualRecords.get(s));
      assertEquals(expectedRecords.get(s).getTotalRecords(), actualRecords.get(s).getTotalRecords());
      HyperVectorValueIterator expectedValues = expectedRecords.get(s);
      HyperVectorValueIterator actualValues = actualRecords.get(s);
      int i = 0;
      while (expectedValues.hasNext()) {
        compareValuesErrorOnMismatch(expectedValues.next(), actualValues.next(), i, s);
        i++;
      }
    }
    for (HyperVectorValueIterator hvi : expectedRecords.values()) {
      for (ValueVector vv : hvi.getHyperVector().getValueVectors()) {
        vv.clear();
      }
    }
    for (HyperVectorValueIterator hvi : actualRecords.values()) {
      for (ValueVector vv : hvi.getHyperVector().getValueVectors()) {
        vv.clear();
      }
    }
  }

  private void compareMergedVectors(Map<String, List> expectedRecords, Map<String, List> actualRecords) throws Exception {

    for (String s : actualRecords.keySet()) {
      assertNotNull("Unexpected extra column " + s + " returned by query.", expectedRecords.get(s));
      assertEquals("Incorrect number of rows returned by query.", expectedRecords.get(s).size(), actualRecords.get(s).size());
      List expectedValues = expectedRecords.get(s);
      List actualValues = actualRecords.get(s);
      assertEquals("Different number of records returned", expectedValues.size(), actualValues.size());

      for (int i = 0; i < expectedValues.size(); i++) {
        try {
          compareValuesErrorOnMismatch(expectedValues.get(i), actualValues.get(i), i, s);
        } catch (Exception ex) {
          throw new Exception(ex.getMessage() + "\n\n" + printNearbyRecords(expectedRecords, actualRecords, i), ex);
        }
      }
    }
    if (actualRecords.size() < expectedRecords.size()) {
      throw new Exception(findMissingColumns(expectedRecords.keySet(), actualRecords.keySet()));
    }
  }

  private String printNearbyRecords(Map<String, List> expectedRecords, Map<String, List> actualRecords, int offset) {
    StringBuilder expected = new StringBuilder();
    StringBuilder actual = new StringBuilder();
    expected.append("Expected Records near verification failure:\n");
    actual.append("Actual Records near verification failure:\n");
    int firstRecordToPrint = Math.max(0, offset - 5);
    List<Object> expectedValuesInFirstColumn = expectedRecords.get(expectedRecords.keySet().iterator().next());
    List<Object> actualValuesInFirstColumn = expectedRecords.get(expectedRecords.keySet().iterator().next());
    int numberOfRecordsToPrint = Math.min(Math.min(10, expectedValuesInFirstColumn.size()), actualValuesInFirstColumn.size());
    for (int i = firstRecordToPrint; i < numberOfRecordsToPrint; i++) {
      expected.append("Record Number: ").append(i).append(" { ");
      actual.append("Record Number: ").append(i).append(" { ");
      for (String s : actualRecords.keySet()) {
        List actualValues = actualRecords.get(s);
        actual.append(s).append(" : ").append(actualValues.get(i)).append(",");
      }
      for (String s : expectedRecords.keySet()) {
        List expectedValues = expectedRecords.get(s);
        expected.append(s).append(" : ").append(expectedValues.get(i)).append(",");
      }
      expected.append(" }\n");
      actual.append(" }\n");
    }

    return expected.append("\n\n").append(actual).toString();

  }

  private Map<String, HyperVectorValueIterator> addToHyperVectorMap(List<QueryDataBatch> records, RecordBatchLoader loader,
                                                                      BatchSchema schema) throws SchemaChangeException, UnsupportedEncodingException {
    // TODO - this does not handle schema changes
    Map<String, HyperVectorValueIterator> combinedVectors = new HashMap();

    long totalRecords = 0;
    QueryDataBatch batch;
    int size = records.size();
    for (int i = 0; i < size; i++) {
      batch = records.get(i);
      loader = new RecordBatchLoader(getAllocator());
      loader.load(batch.getHeader().getDef(), batch.getData());
      logger.debug("reading batch with " + loader.getRecordCount() + " rows, total read so far " + totalRecords);
      totalRecords += loader.getRecordCount();
      for (VectorWrapper w : loader) {
        String field = SchemaPath.getSimplePath(w.getField().getPath()).toExpr();
        if (!combinedVectors.containsKey(field)) {
          MaterializedField mf = w.getField();
          ValueVector[] vvList = (ValueVector[]) Array.newInstance(mf.getValueClass(), 1);
          vvList[0] = w.getValueVector();
          combinedVectors.put(SchemaPath.getSimplePath(mf.getPath()).toExpr(), new HyperVectorValueIterator(mf, new HyperVectorWrapper(mf,
              vvList)));
        } else {
          combinedVectors.get(field).getHyperVector().addVector(w.getValueVector());
        }

      }
    }
    for (HyperVectorValueIterator hvi : combinedVectors.values()) {
      hvi.determineTotalSize();
    }
    return combinedVectors;
  }

  /**
   * Only use this method if absolutely needed. There are utility methods to compare results of single queries.
   * The current use case for exposing this is setting session or system options between the test and verification
   * queries.
   *
   * TODO - evaluate adding an interface to allow setting session and system options before running queries
   * @param records
   * @param loader
   * @param schema
   * @return
   * @throws SchemaChangeException
   * @throws UnsupportedEncodingException
   */
   private Map<String, List> addToCombinedVectorResults(List<QueryDataBatch> records, RecordBatchLoader loader,
                                                         BatchSchema schema) throws SchemaChangeException, UnsupportedEncodingException {
    // TODO - this does not handle schema changes
    Map<String, List> combinedVectors = new HashMap();

    long totalRecords = 0;
    QueryDataBatch batch;
    int size = records.size();
    for (int i = 0; i < size; i++) {
      batch = records.get(0);
      loader.load(batch.getHeader().getDef(), batch.getData());
      // TODO:  Clean:  DRILL-2933:  That load(...) no longer throws
      // SchemaChangeException, so check/clean throws clause above.
      if (schema == null) {
        schema = loader.getSchema();
        for (MaterializedField mf : schema) {
          combinedVectors.put(SchemaPath.getSimplePath(mf.getPath()).toExpr(), new ArrayList());
        }
      }
      logger.debug("reading batch with " + loader.getRecordCount() + " rows, total read so far " + totalRecords);
      totalRecords += loader.getRecordCount();
      for (VectorWrapper w : loader) {
        String field = SchemaPath.getSimplePath(w.getField().getPath()).toExpr();
        for (int j = 0; j < loader.getRecordCount(); j++) {
          Object obj = w.getValueVector().getAccessor().getObject(j);
          if (obj != null) {
            if (obj instanceof Text) {
              obj = obj.toString();
            }
          }
          combinedVectors.get(field).add(obj);
        }
      }
      records.remove(0);
      batch.release();
      loader.clear();
    }
    return combinedVectors;
  }

  protected void compareSchemaOnly() throws Exception {
    RecordBatchLoader loader = new RecordBatchLoader(getAllocator());
    List<QueryDataBatch> actual = Collections.EMPTY_LIST;


    QueryDataBatch batch = null;
    try {
      BaseTestQuery.test(testOptionSettingQueries);
      actual = BaseTestQuery.testRunAndReturn(queryType, query);
      batch = actual.get(0);
      loader.load(batch.getHeader().getDef(), batch.getData());

      final BatchSchema schema = loader.getSchema();
      if(schema.getFieldCount() != testBuilder.getExpectedSchema().size()) {
        throw new Exception("The column numbers for actual schema and expected schema do not match");
      }

      for(int i = 0; i < schema.getFieldCount(); ++i) {
        final String actualSchemaPath = schema.getColumn(i).getPath();
        final TypeProtos.MajorType actualMajorType = schema.getColumn(i).getType();

        final String expectedSchemaPath = schema.getColumn(i).getPath();
        final TypeProtos.MajorType expectedlMajorType = schema.getColumn(i).getType();

        if(!actualSchemaPath.equalsIgnoreCase(expectedSchemaPath)
            || !actualMajorType.equals(expectedlMajorType)) {
          throw new Exception("The type of the " + i + "-th column is '" + actualSchemaPath + "' mismatched, expected: '"
              + expectedlMajorType + "'");
        }
      }

    }  finally {
      if(batch != null) {
        batch.release();
      }
      loader.clear();
    }
  }

  /**
   * Use this method only if necessary to validate one query against another. If you are just validating against a
   * baseline file use one of the simpler interfaces that will write the validation query for you.
   *
   * @throws Exception
   */
  protected void compareUnorderedResults() throws Exception {
    RecordBatchLoader loader = new RecordBatchLoader(getAllocator());
    BatchSchema schema = null;

    List<QueryDataBatch> actual = Collections.EMPTY_LIST;
    List<QueryDataBatch> expected = Collections.EMPTY_LIST;
    List<Map> expectedRecords = new ArrayList<>();
    List<Map> actualRecords = new ArrayList<>();

    try {
      BaseTestQuery.test(testOptionSettingQueries);
      actual = BaseTestQuery.testRunAndReturn(queryType, query);

      checkNumBatches(actual);

      addTypeInfoIfMissing(actual.get(0), testBuilder);
      addToMaterializedResults(actualRecords, actual, loader, schema);

      // If baseline data was not provided to the test builder directly, we must run a query for the baseline, this includes
      // the cases where the baseline is stored in a file.
      if (baselineRecords == null) {
        BaseTestQuery.test(baselineOptionSettingQueries);
        expected = BaseTestQuery.testRunAndReturn(baselineQueryType, testBuilder.getValidationQuery());
        addToMaterializedResults(expectedRecords, expected, loader, schema);
      } else {
        expectedRecords = baselineRecords;
      }

      compareResults(expectedRecords, actualRecords);
    } finally {
      cleanupBatches(actual, expected);
    }
  }

  /**
   * Use this method only if necessary to validate one query against another. If you are just validating against a
   * baseline file use one of the simpler interfaces that will write the validation query for you.
   *
   * @throws Exception
   */
  protected void compareOrderedResults() throws Exception {
    if (highPerformanceComparison) {
      if (baselineQueryType == null) {
        throw new Exception("Cannot do a high performance comparison without using a baseline file");
      }
      compareResultsHyperVector();
    } else {
      compareMergedOnHeapVectors();
    }
  }

  public void compareMergedOnHeapVectors() throws Exception {
    RecordBatchLoader loader = new RecordBatchLoader(getAllocator());
    BatchSchema schema = null;

    List<QueryDataBatch> actual = Collections.EMPTY_LIST;;
    List<QueryDataBatch> expected = Collections.EMPTY_LIST;
    Map<String, List> actualSuperVectors;
    Map<String, List> expectedSuperVectors;

    try {
    BaseTestQuery.test(testOptionSettingQueries);
    actual = BaseTestQuery.testRunAndReturn(queryType, query);

    checkNumBatches(actual);

    // To avoid extra work for test writers, types can optionally be inferred from the test query
    addTypeInfoIfMissing(actual.get(0), testBuilder);

    actualSuperVectors = addToCombinedVectorResults(actual, loader, schema);

    // If baseline data was not provided to the test builder directly, we must run a query for the baseline, this includes
    // the cases where the baseline is stored in a file.
    if (baselineRecords == null) {
      BaseTestQuery.test(baselineOptionSettingQueries);
      expected = BaseTestQuery.testRunAndReturn(baselineQueryType, testBuilder.getValidationQuery());
      expectedSuperVectors = addToCombinedVectorResults(expected, loader, schema);
    } else {
      // data is built in the TestBuilder in a row major format as it is provided by the user
      // translate it here to vectorized, the representation expected by the ordered comparison
      expectedSuperVectors = new HashMap();
      expected = new ArrayList();
      for (String s : ((Map<String, Object>)baselineRecords.get(0)).keySet()) {
        expectedSuperVectors.put(s, new ArrayList());
      }
      for (Map<String, Object> m : baselineRecords) {
        for (String s : m.keySet()) {
          expectedSuperVectors.get(s).add(m.get(s));
        }
      }
    }

    compareMergedVectors(expectedSuperVectors, actualSuperVectors);
    } catch (Exception e) {
      throw new Exception(e.getMessage() + "\nFor query: " + query , e);
    } finally {
      cleanupBatches(expected, actual);
    }
  }

  public void compareResultsHyperVector() throws Exception {
    RecordBatchLoader loader = new RecordBatchLoader(getAllocator());
    BatchSchema schema = null;

    BaseTestQuery.test(testOptionSettingQueries);
    List<QueryDataBatch> results = BaseTestQuery.testRunAndReturn(queryType, query);

    checkNumBatches(results);

    // To avoid extra work for test writers, types can optionally be inferred from the test query
    addTypeInfoIfMissing(results.get(0), testBuilder);

    Map<String, HyperVectorValueIterator> actualSuperVectors = addToHyperVectorMap(results, loader, schema);

    BaseTestQuery.test(baselineOptionSettingQueries);
    List<QueryDataBatch> expected = BaseTestQuery.testRunAndReturn(baselineQueryType, testBuilder.getValidationQuery());

    Map<String, HyperVectorValueIterator> expectedSuperVectors = addToHyperVectorMap(expected, loader, schema);

    compareHyperVectors(expectedSuperVectors, actualSuperVectors);
    cleanupBatches(results, expected);
  }

  private void checkNumBatches(final List<QueryDataBatch> results) {
    if (expectedNumBatches != EXPECTED_BATCH_COUNT_NOT_SET) {
      final int actualNumBatches = results.size();
      assertEquals(String.format("Expected %d batches but query returned %d non empty batch(es)%n", expectedNumBatches,
          actualNumBatches), expectedNumBatches, actualNumBatches);
    }
  }

  private void addTypeInfoIfMissing(QueryDataBatch batch, TestBuilder testBuilder) {
    if (! testBuilder.typeInfoSet()) {
      Map<SchemaPath, TypeProtos.MajorType> typeMap = getTypeMapFromBatch(batch);
      testBuilder.baselineTypes(typeMap);
    }

  }

  private Map<SchemaPath, TypeProtos.MajorType> getTypeMapFromBatch(QueryDataBatch batch) {
    Map<SchemaPath, TypeProtos.MajorType> typeMap = new HashMap();
    for (int i = 0; i < batch.getHeader().getDef().getFieldCount(); i++) {
      typeMap.put(SchemaPath.getSimplePath(MaterializedField.create(batch.getHeader().getDef().getField(i)).getPath()),
          batch.getHeader().getDef().getField(i).getMajorType());
    }
    return typeMap;
  }

  private void cleanupBatches(List<QueryDataBatch>... results) {
    for (List<QueryDataBatch> resultList : results ) {
      for (QueryDataBatch result : resultList) {
        result.release();
      }
    }
  }

  protected void addToMaterializedResults(List<Map> materializedRecords,  List<QueryDataBatch> records, RecordBatchLoader loader,
                                          BatchSchema schema) throws SchemaChangeException, UnsupportedEncodingException {
    long totalRecords = 0;
    QueryDataBatch batch;
    int size = records.size();
    for (int i = 0; i < size; i++) {
      batch = records.get(0);
      loader.load(batch.getHeader().getDef(), batch.getData());
      // TODO:  Clean:  DRILL-2933:  That load(...) no longer throws
      // SchemaChangeException, so check/clean throws clause above.
      if (schema == null) {
        schema = loader.getSchema();
      }
      logger.debug("reading batch with " + loader.getRecordCount() + " rows, total read so far " + totalRecords);
      totalRecords += loader.getRecordCount();
      for (int j = 0; j < loader.getRecordCount(); j++) {
        HashMap<String, Object> record = new HashMap<>();
        for (VectorWrapper w : loader) {
          Object obj = w.getValueVector().getAccessor().getObject(j);
          if (obj != null) {
            if (obj instanceof Text) {
              obj = obj.toString();
            }
            else if (obj instanceof byte[]) {
              obj = new String((byte[]) obj, "UTF-8");
            }
            record.put(SchemaPath.getSimplePath(w.getField().getPath()).toExpr(), obj);
          }
          record.put(SchemaPath.getSimplePath(w.getField().getPath()).toExpr(), obj);
        }
        materializedRecords.add(record);
      }
      records.remove(0);
      batch.release();
      loader.clear();
    }
  }

  public boolean compareValuesErrorOnMismatch(Object expected, Object actual, int counter, String column) throws Exception {

    if (compareValues(expected, actual, counter, column)) {
      return true;
    }
    if (expected == null) {
      throw new Exception("at position " + counter + " column '" + column + "' mismatched values, expected: null " +
          "but received " + actual + "(" + actual.getClass().getSimpleName() + ")");
    }
    if (actual == null) {
      throw new Exception("unexpected null at position " + counter + " column '" + column + "' should have been:  " + expected);
    }
    if (actual instanceof byte[]) {
      throw new Exception("at position " + counter + " column '" + column + "' mismatched values, expected: "
          + new String((byte[])expected, "UTF-8") + " but received " + new String((byte[])actual, "UTF-8"));
    }
    if (!expected.equals(actual)) {
      throw new Exception("at position " + counter + " column '" + column + "' mismatched values, expected: "
          + expected + "(" + expected.getClass().getSimpleName() + ") but received " + actual + "(" + actual.getClass().getSimpleName() + ")");
    }
    return true;
  }

  public boolean compareValues(Object expected, Object actual, int counter, String column) throws Exception {
    if (expected == null) {
      if (actual == null) {
        if (VERBOSE_DEBUG) {
          logger.debug("(1) at position " + counter + " column '" + column + "' matched value:  " + expected );
        }
        return true;
      } else {
        return false;
      }
    }
    if (actual == null) {
      return false;
    }
    if (actual instanceof byte[]) {
      if ( ! Arrays.equals((byte[]) expected, (byte[]) actual)) {
        return false;
      } else {
        if (VERBOSE_DEBUG) {
          logger.debug("at position " + counter + " column '" + column + "' matched value " + new String((byte[])expected, "UTF-8"));
        }
        return true;
      }
    }
    if (!expected.equals(actual)) {
      return false;
    } else {
      if (VERBOSE_DEBUG) {
        logger.debug("at position " + counter + " column '" + column + "' matched value:  " + expected );
      }
    }
    return true;
  }

  /**
   * Compare two result sets, ignoring ordering.
   *
   * @param expectedRecords - list of records from baseline
   * @param actualRecords - list of records from test query, WARNING - this list is destroyed in this method
   * @throws Exception
   */
  private void compareResults(List<Map> expectedRecords, List<Map> actualRecords) throws Exception {

    assertEquals("Different number of records returned", expectedRecords.size(), actualRecords.size());

    int i = 0;
    int counter = 0;
    boolean found;
    for (Map<String, Object> expectedRecord : expectedRecords) {
      i = 0;
      found = false;
      findMatch:
      for (Map<String, Object> actualRecord : actualRecords) {
        for (String s : actualRecord.keySet()) {
          if (!expectedRecord.containsKey(s)) {
            throw new Exception("Unexpected column '" + s + "' returned by query.");
          }
          if ( ! compareValues(expectedRecord.get(s), actualRecord.get(s), counter, s)) {
            i++;
            continue findMatch;
          }
        }
        if (actualRecord.size() < expectedRecord.size()) {
          throw new Exception(findMissingColumns(expectedRecord.keySet(), actualRecord.keySet()));
        }
        found = true;
        break;
      }
      if (!found) {
        StringBuilder sb = new StringBuilder();
        for (int expectedRecordDisplayCount = 0;
             expectedRecordDisplayCount < 10 && expectedRecordDisplayCount < expectedRecords.size();
             expectedRecordDisplayCount++) {
          sb.append(printRecord(expectedRecords.get(expectedRecordDisplayCount)));
        }
        String expectedRecordExamples = sb.toString();
        sb.setLength(0);
        for (int actualRecordDisplayCount = 0;
             actualRecordDisplayCount < 10 && actualRecordDisplayCount < actualRecords.size();
             actualRecordDisplayCount++) {
          sb.append(printRecord(actualRecords.get(actualRecordDisplayCount)));
        }
        String actualRecordExamples = sb.toString();
        throw new Exception(String.format("After matching %d records, did not find expected record in result set: %s\n\n" +
            "Some examples of expected records:%s\n\n Some examples of records returned by the test query:%s",
            counter, printRecord(expectedRecord), expectedRecordExamples, actualRecordExamples));
      } else {
        actualRecords.remove(i);
        counter++;
      }
    }
    assertEquals(0, actualRecords.size());
  }

  private String findMissingColumns(Set<String> expected, Set<String> actual) {
    String missingCols = "";
    for (String colName : expected) {
      if (!actual.contains(colName)) {
        missingCols += colName + ", ";
      }
    }
    return "Expected column(s) " + missingCols + " not found in result set: " + actual + ".";
  }

  private String printRecord(Map<String, Object> record) {
    String ret = "";
    for (String s : record.keySet()) {
      ret += s + " : "  + record.get(s) + ", ";
    }
    return ret + "\n";
  }

}
