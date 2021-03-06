package com.knoldus.elasticsearch.api

import java.io.{PrintWriter, StringWriter}

import com.knoldus.common._
import com.knoldus.common.services.{BulkUpsertResponse, DeleteResponse, GetResponse, UpsertResponse}
import com.knoldus.common.utils.ResourceCompanion
import org.slf4j.Logger
import play.api.libs.json.{JsObject, Reads, Writes}

import scala.concurrent.{ExecutionContext, Future}

object ElasticsearchClient {

  import scala.language.implicitConversions

  implicit class ElasticsearchIndex(name: String) {
    override def toString: String = name
  }

  implicit def elasticsearchIndexToString(esi: ElasticsearchIndex): String = esi.toString
}

trait ElasticsearchClient extends AutoCloseable {

  def logger: Logger

  implicit val ec: ExecutionContext

  /**
   * Asynchronous Get to elasticsearch, returning a JsObject
   */
  def getJson(typ: ResourceCompanion[_], idVal: String): Future[GetResponse[JsObject]]

  /**
   * Asynchronous Get to elasticsearch, return an object of type T
   */
  def get[A](typ: ResourceCompanion[A], idVal: String)(implicit tjs: Reads[A]): Future[GetResponse[A]] = {
    getJson(typ, idVal) map { response =>
      response.value.fold(ifEmpty = GetResponse[A](value = None)) { json =>
        json.validate[A].fold(
          invalid = errors => throw new RuntimeException("Invalid JSON: " + errors.toString),
          valid = obj => GetResponse[A](Some(obj), version = response.version)
        )
      }
    } recover {
      case err: Exception => throw clientException(err)
    }
  }

  /**
   * Create a new index and put mappings
   */
  // def createIndexWithMappings(index: String, typs: GenIterable[ResourceCompanion[_]]): Future[Boolean]

  protected def clientException(err: Throwable): Throwable = err match {
    case e => getDefaultESError(e)
  }

  protected def getDefaultESError(err: Throwable): ESException = {
    val sw = new StringWriter()
    err.printStackTrace(new PrintWriter(sw))
    logger.error("Failed to execute against Elasticsearch - " + err.getClass + ": " + err.getMessage + "\n" + sw.toString)
    ESException("Unknown error connecting to Elasticsearch - " + err.getClass + ": " + err.getMessage + "\n" + sw.toString)
  }

  /**
   * Asynchronous Insert/Update to elasticsearch
   */
  def upsert[A](typ: ResourceCompanion[A], obj: A)(implicit tjs: Writes[A]): Future[UpsertResponse[A]]

  /**
   * Asynchronous Bulk upsert to elasticsearch
   */
  def bulkUpsert[A](typ: ResourceCompanion[A], objs: Vector[A],
                    indexOverride: Option[String] = None)(implicit tjs: Writes[A]): Future[BulkUpsertResponse]

  /**
   * Asynchronous Delete to elasticsearch
   */
  def delete(typ: ResourceCompanion[_], idStr: String): Future[DeleteResponse]

  /**
   * Health of the elasticsearch cluster: green, yellow, or red
   */
  def clusterHealthColor: Future[String]

  /**
   * Is the cluster healthy?  I.e. is the cluster not *Red*?
   */
  def isClusterHealthy: Future[Boolean] = clusterHealthColor.map(_ != "red")

  /**
   * Gets a map of indices to the list of aliases that they have
   */
  def getAliasesByIndex: Future[Map[String, List[String]]]

  /**
   * Gets a map of indices to the list of aliases that they have
   */
  def getIndicesByAlias: Future[Map[String, List[String]]] = getAliasesByIndex.map(CommonConversion.invertStringMap)

  /**
   * Get any indices associated with the given alias.
   *
   * @return a Future with a sequence of index names that are associated with the given alias.
   */
  def getIndicesForAlias(aliasName: String): Future[Seq[String]]

  /**
   * Get all indices
   */
  def getAllIndices(): Future[Seq[String]]

  /**
   * Get all indices matching the given string, e.g. "oculus_*"
   */
  def getMatchingIndices(matching: String): Future[Seq[String]]

  /**
   * Update an alias, removing it from a list of old indices and adding it to a list of new indices.
   */
  def updateAlias(aliasName: String, addToIndices: Seq[String], removeFromIndices: Seq[String]): Future[Boolean]

  /**
   * Returns Future with boolean value - does the index exist?
   */
  def indexExists(idx: String): Future[Boolean]

  /**
   * Delete the given index
   */
  def deleteIndex(index: String): Future[Boolean]

  /**
   * Returns Future with true if the index was created, false if it already existed
   */
  def createIndex(index: String): Future[Boolean]

}
