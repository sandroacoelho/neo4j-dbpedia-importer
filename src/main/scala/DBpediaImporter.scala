/**
 * Copyright (C) 2014 Kenny Bastani
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD

/**
 * This is a Spark application that processes flat file RDF dumps of DBpedia.org and generates CSV files
 * that are used to generate Neo4j data store files.
 */
object DBpediaImporter {
  //-master "local" --total-executor-cores 8 --driver-memory 14g --driver-java-options "-Dspark.executor.memory=12g"
  val conf = new SparkConf()
    .setAppName("Simple Application")
    .setMaster("local[8]")
    .set("total-executor-cores", "8")
    .set("driver-memory", "12g")
    .set("spark.executor.memory", "12g")
    .set("spark.driver.memory", "12g")

  val sc = new SparkContext(conf)

  def main(args: Array[String]) {

    // Import the page nodes and link graph
    val pageIndex: scala.collection.Map[String, Long] = importPageNodesAndLinks()

    // We need the last unique id, which will be used to offset the id for category nodes
    val lastIndexPointer = pageIndex.toList.sortBy(a => (a._2, a._1)).last._2: Long

    // Load categories file
    val categoriesFile = sc.textFile(Configuration.CATEGORIES_FILE_NAME)

    // Process and prepare the categories for creating the nodes file
    val categoriesMap = processCategories(categoriesFile)

    // Generate a categories and then join it to the pageIndex

    // Step 1: Get a distinct list of categories and generate a node index
    val categoryNodeData = categoriesMap.map(cat => cat._1)
      .zipWithUniqueId()
      .map(a => (a._1, a._2 + lastIndexPointer))

    // Generate the category node rows with property name and id
    val categoryNodeRows = generateCategoryNodes(categoryNodeData)

    // Save the category nodes CSV
    categoryNodeRows.saveAsTextFile(Configuration.HDFS_HOST + "categorynodes")

  }

  def processCategories(categoriesFile: RDD[String]): RDD[(String, Iterable[String])] = {
    val categoriesMap = categoriesFile
      .filter(line => line.contains(Configuration.RDF_CATEGORY_URL))
      .map(e => e.split("(?<=>)\\s(?=<)|\\s\\.$").filter(a => !a.contains(Configuration.RDF_CATEGORY_URL)))
      .map(uri => (uri(1), uri(0)))
      .groupByKey()

    categoriesMap
  }

  def importPageNodesAndLinks(): scala.collection.Map[String, Long] = {
    // Load the text files
    val wikiLinksFile = sc.textFile(Configuration.WIKI_LINKS_FILE_NAME)
    val wikiNamesFile = sc.textFile(Configuration.WIKI_NAMES_FILE_NAME)
    val pageLinksFile = sc.textFile(Configuration.PAGE_LINKS_FILE_NAME)

    // First stage: Join the Wikipedia map file and the names map file into a single RDD
    // Process and prepare the Wikipedia links file to join on the DBpedia key
    val wikiLinksMap = processWikiLinks(wikiLinksFile)

    // Process and prepare the page names to join on the DBpedia key
    val pageNamesMap = processPageNames(wikiNamesFile)

    // Join the Wikipedia map and the names map on the DBpedia key
    val pageNodeData = joinNamesToLinks(wikiLinksMap, pageNamesMap)

    // Take the union of the two datasets and generate a CSV as an RDD
    val pageNodeRows = generatePageNodes(pageNodeData)

    // Second stage: Encode each value in the page links file with the
    // unique node id generated during the last stage

    // Create an in-memory hash table to lookup DBpedia keys and return the
    // encoded unique node id
    val pageNodeIndex = pageNodeData.map(r => {
      r._1
    }).zipWithUniqueId().collectAsMap()

    // Process and prepare the page links file to be encoded on the DBpedia key
    val pageLinkRelationshipData = processPageLinks(pageLinksFile)

    // Encode each DBpedia key with the Neo4j node id located in the pageNodeIndex table
    val pageLinkRelationshipRows = encodePageLinks(pageLinkRelationshipData, pageNodeIndex)

    // Final stage: Save the page nodes and relationship results to HDFS

    // Save the page nodes CSV
    pageNodeRows.saveAsTextFile(Configuration.HDFS_HOST + "pagenodes")

    // Save the page rels CSV
    pageLinkRelationshipRows.saveAsTextFile(Configuration.HDFS_HOST + "pagerels")

    pageNodeIndex
  }

  /**
   * Process Wikipedia Links RDF file
   * @param wikiLinksFile
   * @return Returns an RDD[String] map of filtered lines for import into Neo4j
   */
  def processWikiLinks(wikiLinksFile: RDD[String]): RDD[String] = {
    val wikiLinksMap = wikiLinksFile.filter(line =>
      line.contains(Configuration.PRIMARY_TOPIC_URL) &&
        !line.contains(Configuration.EXCLUDE_FILE_PATTERN))
      .map(e => {
      e.split("(?<=>)\\s(?=<)|\\s\\.$")
        .filter(a => {
        !a.contains(Configuration.PRIMARY_TOPIC_URL)
      })
    })
      .map(uri => {
      (uri(1), uri(0))
    })
      .map(line => {
      line._1 + " " + line._2
    })

    wikiLinksMap
  }

  /**
   *
   * @param wikiNamesFile
   * @return
   */
  def processPageNames(wikiNamesFile: RDD[String]): RDD[String] = {
    val wikiNamesMap = wikiNamesFile.filter(line => line.contains(Configuration.RDF_LABEL_URL))
      .filter(line => !line.contains(Configuration.EXCLUDE_FILE_PATTERN))
      .map(e => {
      e.split("(?<=>)\\s(?=<)|(?<=>)\\s(?=\\\")|@en\\s\\.$")
        .filter(a => { !a.contains(Configuration.RDF_LABEL_URL) })
    })
      .map(uri => { (uri(0), uri(1)) })
      .map(line => { line._1 + " " + line._2 })

    wikiNamesMap
  }

  /**
   *
   * @param wikiLinksMap
   * @param wikiNamesMap
   * @return
   */
  def joinNamesToLinks(wikiLinksMap: RDD[String], wikiNamesMap: RDD[String]): RDD[(String, Iterable[String])] = {
    val joinedList = wikiLinksMap.union(wikiNamesMap).map(line => {
      val items = line.split("^<|>\\s<|\\>\\s\\\"|\\\"$|>$").filter(!_.isEmpty)
      val mapResult = if (items.length >= 2) (items(0), items(1)) else ("N/A", "N/A")
      mapResult
    }).filter(items => items._1 != "N/A").map(a => (a._1, a._2)).groupByKey()

    joinedList
  }

  /**
   *
   * @param pageNodeData
   * @return
   */
  def generatePageNodes(pageNodeData: RDD[(String, Iterable[String])]): RDD[String] = {
    val header = sc.parallelize(Seq(Configuration.PAGE_NODES_CSV_HEADER).toList)
    val rows = pageNodeData.zipWithUniqueId().map(e => {
      e._1._1 + "\t" + e._2 + "\tPage\t" + e._1._2.toList.mkString("\t")
    })

    val result = header.union(rows)

    result
  }

  def generateCategoryNodes(categoryNodeData: RDD[(String, Long)]): RDD[String] = {
    val header = sc.parallelize(Seq(Configuration.CATEGORY_NODES_CSV_HEADER).toList)
    val rows = categoryNodeData.map(line => line._2 + "\tCategory\t" + line._1 )
    val result = header.union(rows)

    result
  }

  /**
   *
   * @param pageLinks
   * @param pageNodeIndex
   * @return
   */
  def encodePageLinks(pageLinks: RDD[(Array[String])], pageNodeIndex: scala.collection.Map[String, Long]): RDD[(Long, Long)] = {
    // Filter out bad links
    val encodedPageLinksResult = pageLinks.filter(uri => {
      !(pageNodeIndex.getOrElse(uri(0), -1) == -1 || pageNodeIndex.getOrElse(uri(1), -1) == -1)
    }).map(uri => {
      (pageNodeIndex(uri(0)), pageNodeIndex(uri(1)))
    })

    encodedPageLinksResult
  }

  /**
   *
   * @param pageLinksFile
   * @return
   */
  def processPageLinks(pageLinksFile: RDD[String]): RDD[(Array[String])] = {
    val pageLinks = pageLinksFile.filter(line =>
      line.contains(Configuration.WIKI_PAGE_LINK_URL) &&
        !line.contains(Configuration.EXCLUDE_FILE_PATTERN) &&
        !line.contains(Configuration.EXCLUDE_CATEGORY_PATTERN))
      .map(e => {
      e.split("^<|>\\s<|\\>\\s\\\"|>\\s\\.$")
        .filter(!_.isEmpty)
        .filter(a => { !a.contains(Configuration.WIKI_PAGE_LINK_URL) })
    })

    pageLinks
  }

  /**
   *
   * @param pageLinkResults
   * @return
   */
  def generatePageLinkRelationships(pageLinkResults: RDD[(String, String)]): RDD[String] = {
    val relHeader = sc.parallelize(Seq(Configuration.PAGE_LINKS_CSV_HEADER).toList)
    val relRows = pageLinkResults.map(line => { line._1 + "\t" + line._2 + "\tHAS_LINK" })
    val relResult = relHeader.union(relRows)

    relResult
  }
}
