/*
 * Copyright (2020) The Hyperspace Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.hyperspace.index

import org.apache.hadoop.fs.Path
import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.DataFrame

import com.microsoft.hyperspace.{Hyperspace, HyperspaceException, SampleData, SparkInvolvedSuite}
import com.microsoft.hyperspace.util.FileUtils

class CreateIndexTests extends SparkFunSuite with SparkInvolvedSuite {

  private val sampleData = SampleData.testData
  private val sampleParquetDataLocation = "src/test/resources/sampleparquet"
  private val indexSystemPath = "src/test/resources/indexLocation"
  private val indexConfig1 = IndexConfig("index1", Seq("RGUID"), Seq("Date"))
  private val indexConfig2 = IndexConfig("index2", Seq("Query"), Seq("imprs"))
  private var df: DataFrame = _
  private var hyperspace: Hyperspace = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    val sparkSession = spark
    import sparkSession.implicits._
    hyperspace = new Hyperspace(sparkSession)
    FileUtils.delete(new Path(indexSystemPath))
    FileUtils.delete(new Path(sampleParquetDataLocation))

    val dfFromSample = sampleData.toDF("Date", "RGUID", "Query", "imprs", "clicks")
    dfFromSample.write.parquet(sampleParquetDataLocation)

    df = spark.read.parquet(sampleParquetDataLocation)
    spark.conf.set(IndexConstants.INDEX_SYSTEM_PATH, indexSystemPath)
  }

  override def afterAll(): Unit = {
    FileUtils.delete(new Path(sampleParquetDataLocation))
    super.afterAll()
  }

  after {
    FileUtils.delete(new Path(indexSystemPath))
  }

  test("Creating one index.") {
    hyperspace.createIndex(df, indexConfig1)
    val count = hyperspace.indexes.where(s"""name = "${indexConfig1.indexName}" """).count
    assert(count == 1)
  }

  test("Creating index with existing index name fails.") {
    hyperspace.createIndex(df, indexConfig1)
    val exception = intercept[HyperspaceException] {
      hyperspace.createIndex(df, indexConfig2.copy(indexName = "index1"))
    }
    assert(exception.getMessage.contains("Another Index with name index1 already exists"))
  }

  test("Creating index with existing index name (case-insensitive) fails.") {
    hyperspace.createIndex(df, indexConfig1)
    val exception = intercept[HyperspaceException] {
      hyperspace.createIndex(df, indexConfig1.copy(indexName = "INDEX1"))
    }
    assert(exception.getMessage.contains("Another Index with name INDEX1 already exists"))
  }

  test("Index creation fails since indexConfig does not satisfy the table schema.") {
    val exception = intercept[HyperspaceException] {
      hyperspace.createIndex(df, IndexConfig("index1", Seq("IllegalColA"), Seq("IllegalColB")))
    }
    assert(exception.getMessage.contains("Index config is not applicable to dataframe schema"))
  }

  test("Index creation fails since the dataframe has a filter node.") {
    val dfWithFilter = df.filter("Query='facebook'")
    val exception = intercept[HyperspaceException] {
      hyperspace.createIndex(dfWithFilter, indexConfig1)
    }
    assert(
      exception.getMessage.contains(
        "Only creating index over HDFS file based scan nodes is supported."))
  }

  test("Index creation fails since the dataframe has a projection node.") {
    val dfWithSelect = df.select("Query")
    val exception = intercept[HyperspaceException] {
      hyperspace.createIndex(dfWithSelect, indexConfig1)
    }
    assert(
      exception.getMessage.contains(
        "Only creating index over HDFS file based scan nodes is supported."))
  }

  test("Index creation fails since the dataframe has a join node.") {
    val dfJoin = df
      .join(df, df("Query") === df("Query"))
      .select(df("RGUID"), df("Query"), df("imprs"))
    val exception = intercept[HyperspaceException] {
      hyperspace.createIndex(dfJoin, indexConfig1)
    }
    assert(
      exception.getMessage.contains(
        "Only creating index over HDFS file based scan nodes is supported."))
  }
}
