package io.prediction.data.storage.hbase

import io.prediction.data.storage.Event
import io.prediction.data.storage.Events
import io.prediction.data.storage.EventJson4sSupport
import io.prediction.data.storage.DataMap
import io.prediction.data.storage.StorageError

import grizzled.slf4j.Logging

import org.json4s.DefaultFormats
import org.json4s.native.Serialization.{ read, write }
//import org.json4s.ext.JodaTimeSerializers

import org.joda.time.DateTime
import org.joda.time.DateTimeZone

import org.apache.hadoop.hbase.NamespaceDescriptor
import org.apache.hadoop.hbase.NamespaceExistException
import org.apache.hadoop.hbase.HTableDescriptor
import org.apache.hadoop.hbase.HColumnDescriptor
import org.apache.hadoop.hbase.TableName
import org.apache.hadoop.hbase.client.HTable
import org.apache.hadoop.hbase.client.Put
import org.apache.hadoop.hbase.client.Get
import org.apache.hadoop.hbase.client.Delete
import org.apache.hadoop.hbase.client.Result
import org.apache.hadoop.hbase.client.Scan
import org.apache.hadoop.hbase.util.Bytes

import scala.collection.JavaConversions._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext

import java.util.UUID

class HBEvents(client: HBClient, namespace: String) extends Events with Logging {

  val nameDesc = NamespaceDescriptor.create(namespace).build()

  try {
    client.admin.createNamespace(nameDesc)
  } catch {
    case e: NamespaceExistException => ()
    case e: Exception => throw new RuntimeException(e)
  }

  implicit val formats = DefaultFormats + new EventJson4sSupport.DBSerializer
  //implicit val formats = DefaultFormats.lossless ++ JodaTimeSerializers.all

  val tableName = TableName.valueOf(namespace, "events")
  val table = new HTable(client.conf, tableName)

  // create table if not exist
  if (!client.admin.tableExists(tableName)) {
    val tableDesc = new HTableDescriptor(tableName)
    tableDesc.addFamily(new HColumnDescriptor("e")) // e:tid
    tableDesc.addFamily(new HColumnDescriptor("p"))
    tableDesc.addFamily(new HColumnDescriptor("tag")) // tag
    tableDesc.addFamily(new HColumnDescriptor("o")) // others
    client.admin.createTable(tableDesc)
  }

  private def eventToRowKey(event: Event): String = {
    // TODO: could be bad since writing to same region for same appId?
    // TODO: hash entityId and event to avoid arbitaray string length
    // and conflict with delimiter
    val uuid: Long = UUID.randomUUID().getLeastSignificantBits
    event.appId + "-" + event.eventTime.getMillis + "-" +
      event.event + "-" + event.entityId + "-" + uuid
  }

  private def startStopRowKey(appId: Int, startTime: Option[DateTime],
    untilTime: Option[DateTime]) = {

    (appId, startTime, untilTime) match {
      case (x, None, None) => (x + "-", (x+1) + "-")
      case (x, Some(start), None) => (x + "-" + start.getMillis + "-",
        (x+1) + "-")
      case (x, None, Some(end)) => (x + "-", x + "-" + end.getMillis + "-")
      case (x, Some(start), Some(end)) =>
        (x + "-" + start.getMillis + "-", x + "-" + end.getMillis + "-")
    }
  }

  private def rowKeyToPartialEvent(rowKey: String): Event = {
    val data = rowKey.split("-")

    // incomplete info:
    // targetEntityId, properties, tags and predictionKey
    Event(
      entityId = data(3),
      event = data(2),
      eventTime = new DateTime(data(1).toLong, DateTimeZone.UTC),
      appId = data(0).toInt
    )
  }

  private def rowKeyToEventId(rowKey: String): String = rowKey

  private def eventIdToRowKey(eventId: String): String = eventId

  override
  def futureInsert(event: Event)(implicit ec: ExecutionContext):
    Future[Either[StorageError, String]] = {
    Future {
      val table = new HTable(client.conf, tableName)
      val rowKey = eventToRowKey(event)
      val put = new Put(Bytes.toBytes(rowKey))
      if (event.targetEntityId != None) {
        put.add(Bytes.toBytes("e"), Bytes.toBytes("tid"),
          Bytes.toBytes(event.targetEntityId.get))
      }
      // TODO: better way to handle event.properties?
      // serialize whole properties as string for now..
      /*put.add(Bytes.toBytes("p"), Bytes.toBytes("p"),
        Bytes.toBytes(write(JObject(event.properties.toList))))*/
      put.add(Bytes.toBytes("p"), Bytes.toBytes("p"),
        Bytes.toBytes(write(event.properties)))

      event.tags.foreach { tag =>
        put.add(Bytes.toBytes("tag"), Bytes.toBytes(tag), Bytes.toBytes(true))
      }
      if (event.predictionKey != None) {
        put.add(Bytes.toBytes("o"), Bytes.toBytes("pk"),
          Bytes.toBytes(event.predictionKey.get))
      }
      table.put(put)
      table.flushCommits()
      table.close()
      Right(rowKeyToEventId(rowKey))
    }
  }

  private def resultToEvent(result: Result): Event = {
    val rowKey = Bytes.toString(result.getRow())

    val e = result.getFamilyMap(Bytes.toBytes("e"))
    val p = result.getFamilyMap(Bytes.toBytes("p"))
    val tag = result.getFamilyMap(Bytes.toBytes("tag"))
    val o = result.getFamilyMap(Bytes.toBytes("o"))

    val targetEntityId = if (e != null) {
      val tid = e.get(Bytes.toBytes("tid"))
      if (tid != null) Some(Bytes.toString(tid)) else None
    } else None

    //val properties: Map[String, JValue] =
    //  read[JObject](Bytes.toString(p.get(Bytes.toBytes("p")))).obj.toMap

    val properties: DataMap =
      read[DataMap](Bytes.toString(p.get(Bytes.toBytes("p"))))

    val tags = if (tag != null)
      tag.keySet.toSeq.map(Bytes.toString(_))
    else Seq()

    val predictionKey = if (o != null) {
      val pk = o.get(Bytes.toBytes("pk"))
      if (pk != null) Some(Bytes.toString(pk)) else None
    } else None

    val partialEvent = rowKeyToPartialEvent(rowKey)
    val event = partialEvent.copy(
      targetEntityId = targetEntityId,
      properties = properties,
      tags = tags,
      predictionKey = predictionKey
    )
    event
  }

  override
  def futureGet(eventId: String)(implicit ec: ExecutionContext):
    Future[Either[StorageError, Option[Event]]] = {
      Future {
        val get = new Get(Bytes.toBytes(eventId))

        val result = table.get(get)

        if (!result.isEmpty()) {
          val event = resultToEvent(result)
          Right(Some(event))
        } else {
          Right(None)
        }
      }
    }

  override
  def futureDelete(eventId: String)(implicit ec: ExecutionContext):
    Future[Either[StorageError, Boolean]] = {
    Future {
      val rowKeyBytes = Bytes.toBytes(eventIdToRowKey(eventId))
      val exists = table.exists(new Get(rowKeyBytes))
      table.delete(new Delete(rowKeyBytes))
      Right(exists)
    }
  }

  override
  def futureGetByAppId(appId: Int)(implicit ec: ExecutionContext):
    Future[Either[StorageError, Iterator[Event]]] = {
      Future {
        val (start, stop) = startStopRowKey(appId, None, None)
        val scan = new Scan(Bytes.toBytes(start), Bytes.toBytes(stop))
        val scanner = table.getScanner(scan)
        Right(scanner.iterator().map { resultToEvent(_) })
      }
    }

  override
  def futureGetByAppIdAndTime(appId: Int, startTime: Option[DateTime],
    untilTime: Option[DateTime])(implicit ec: ExecutionContext):
    Future[Either[StorageError, Iterator[Event]]] = {
      Future {
        val (start, stop) = startStopRowKey(appId, startTime, untilTime)
        println(start, stop)
        val scan = new Scan(Bytes.toBytes(start), Bytes.toBytes(stop))
        val scanner = table.getScanner(scan)
        Right(scanner.iterator().map { resultToEvent(_) })
      }
  }

  override
  def futureDeleteByAppId(appId: Int)(implicit ec: ExecutionContext):
    Future[Either[StorageError, Unit]] = {
    Future {
      // TODO: better way to handle range delete
      val (start, stop) = startStopRowKey(appId, None, None)
      val scan = new Scan(Bytes.toBytes(start), Bytes.toBytes(stop))
      val scanner = table.getScanner(scan)
      val it = scanner.iterator()
      while (it.hasNext()) {
        val result = it.next()
        table.delete(new Delete(result.getRow()))
      }
      scanner.close()
      Right(())
    }
  }

}
